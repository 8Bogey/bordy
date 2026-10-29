import { Env, OrderRow, CodeRow } from '../types';
import {
  getDevice, getPlan, getOrder, getActiveOrderForDevice,
  getAutoApplyCode, reserveCode, consumeCode, releaseCode,
  logEvent, getSettings,
} from '../db';
import { validateCode } from './codes';
import { checkRateLimit } from '../rate-limit';
import { sendTelegram } from '../telegram';
import { getPrivateKey, signToken } from '../crypto';
import { buildTokenPayload } from './activate';
import {
  isValidDeviceId, normalizeCode, roundPrice, generateOrderId,
  jsonResponse, errorResponse, parseJsonBody, formatBaghdadTime,
  parsePaymentMethods,
} from '../utils';
import { cancelOrderBodySchema, createOrderBodySchema, submitProofBodySchema } from '../validate';

const MAX_PROOF_BYTES = 2 * 1024 * 1024;
const ALLOWED_PROOF_MIMES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif']);

/** Trust the bytes, not the client's Content-Type. */
export function sniffImageMime(bytes: Uint8Array): string | null {
  if (bytes.length >= 3 && bytes[0] === 0xFF && bytes[1] === 0xD8 && bytes[2] === 0xFF) return 'image/jpeg';
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E && bytes[3] === 0x47) return 'image/png';
  if (
    bytes.length >= 12 &&
    bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46 &&
    bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50
  ) return 'image/webp';
  if (bytes.length >= 12) {
    const box = String.fromCharCode(bytes[4], bytes[5], bytes[6], bytes[7]);
    const brand = String.fromCharCode(bytes[8], bytes[9], bytes[10], bytes[11]).toLowerCase();
    if (box === 'ftyp' && ['heic', 'heix', 'hevc', 'hevx', 'mif1', 'msf1'].includes(brand)) {
      return brand === 'mif1' || brand === 'msf1' ? 'image/heif' : 'image/heic';
    }
  }
  return null;
}

// ── Order helpers ────────────────────────────────────────────────────────────

export function isExpired(order: OrderRow, now: number): boolean {
  return (order.status === 'PENDING' || order.status === 'SUBMITTED') && order.expires_at <= now;
}

function orderPublic(order: OrderRow) {
  return {
    id: order.id,
    plan_id: order.plan_id,
    base_price: order.base_price,
    discount: order.discount,
    final_price: order.final_price,
    days_granted: order.days_granted,
    code: order.code,
    status: order.status,
    method: order.method,
    txn_ref: order.txn_ref,
    created_at: order.created_at,
    submitted_at: order.submitted_at,
    expires_at: order.expires_at,
  };
}

async function expireOrder(env: Env, order: OrderRow): Promise<void> {
  const result = await env.DB.prepare(
    `UPDATE orders SET status = 'EXPIRED', decided_at = ? WHERE id = ? AND status IN ('PENDING','SUBMITTED')`
  ).bind(Date.now(), order.id).run();

  if (result.meta.changes) {
    if (order.code) await releaseCode(env.DB, order.code, order.id);
    await logEvent(env.DB, order.device_id, 'ORDER_EXPIRE', `Order ${order.id} expired`);
  }
}

/** Expire this device's stale orders so they do not block a new order. */
async function expireStaleOrdersForDevice(env: Env, deviceId: string, now: number): Promise<void> {
  const stale = await env.DB.prepare(
    `SELECT id, code FROM orders WHERE device_id = ? AND status IN ('PENDING','SUBMITTED') AND expires_at <= ?`
  ).bind(deviceId, now).all<{ id: string; code: string | null }>();

  if (!stale.results.length) return;

  await env.DB.prepare(
    `UPDATE orders SET status = 'EXPIRED', decided_at = ? WHERE device_id = ? AND status IN ('PENDING','SUBMITTED') AND expires_at <= ?`
  ).bind(now, deviceId, now).run();

  for (const row of stale.results) {
    if (row.code) await releaseCode(env.DB, row.code, row.id);
  }
  await logEvent(env.DB, deviceId, 'ORDER_EXPIRE', `${stale.results.length} stale order(s) expired`);
}

async function getPaymentInfo(env: Env) {
  const settings = await getSettings(env.DB, ['payment_methods', 'support_whatsapp', 'support_telegram']);
  return {
    methods: parsePaymentMethods(settings.payment_methods),
    support: {
      whatsapp: settings.support_whatsapp || '',
      telegram: settings.support_telegram || '',
    },
  };
}

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * Tell the owner that an order was created. The app's flow is "transfer then send the
 * receipt on WhatsApp", so the order can be approved from this message directly.
 */
async function notifyNewOrder(env: Env, order: OrderRow): Promise<void> {
  try {
    const [device, plan] = await Promise.all([
      getDevice(env.DB, order.device_id),
      getPlan(env.DB, order.plan_id),
    ]);

    const msg = `🧾 <b>طلب اشتراك جديد</b>\n\n`
      + `📋 الطلب: <code>${order.id}</code>\n`
      + `📱 الجهاز: <code>${order.device_id.substring(0, 8)}...</code>\n`
      + `${device?.name ? `👤 الاسم: ${escapeHtml(device.name)}\n` : ''}`
      + `${device?.phone ? `📞 الهاتف: ${escapeHtml(device.phone)}\n` : ''}`
      + `💰 المبلغ: ${order.final_price} د.ع\n`
      + `📦 الخطة: ${plan?.title || order.plan_id} (${order.days_granted} يوم)\n`
      + `${order.code ? `🏷️ كود: ${order.code}\n` : ''}`
      + `💳 طريقة الدفع: ${order.method || 'لم تُحدد بعد (يصل الإيصال عبر واتساب)'}\n`
      + `${order.txn_ref ? `🔖 رقم العملية: ${order.txn_ref}\n` : ''}`
      + `⏰ الوقت: ${formatBaghdadTime(order.created_at)}\n\n`
      + `ينتهي الطلب: ${formatBaghdadTime(order.expires_at)}`;

    const inlineKeyboard = {
      inline_keyboard: [
        [
          { text: '✅ قبول', callback_data: `approve:${order.id}` },
          { text: '❌ رفض', callback_data: `reject:${order.id}` },
        ],
      ],
    };

    await sendTelegram(env.TG_BOT_TOKEN, env.OWNER_CHAT_ID, msg, inlineKeyboard);
  } catch (_) {
    // Notification failure must never break the purchase flow.
  }
}

// ── Handlers ─────────────────────────────────────────────────────────────────

/**
 * POST /v1/orders — Create a new order.
 */
export async function handleCreateOrder(request: Request, env: Env): Promise<Response> {
  const parsed = createOrderBodySchema.safeParse(await parseJsonBody(request));
  if (!parsed.success) {
    return errorResponse('بيانات الطلب غير مكتملة', 400, 'MISSING_FIELDS');
  }
  const body = parsed.data;

  if (!isValidDeviceId(body.did)) {
    return errorResponse('معرّف الجهاز غير صالح', 400, 'INVALID_DEVICE');
  }

  const device = await getDevice(env.DB, body.did);
  if (!device) {
    return errorResponse('الجهاز غير مسجّل، أعد فتح التطبيق أولاً', 404, 'DEVICE_NOT_REGISTERED');
  }
  if (device.blocked) {
    return errorResponse('هذا الجهاز محظور', 403, 'DEVICE_BLOCKED');
  }

  // A real buyer creates one order per purchase; a runaway loop must not flood the owner.
  if (!await checkRateLimit(env.DB, `order:${body.did}`, 20)) {
    return errorResponse('طلبات كثيرة خلال وقت قصير، حاول لاحقاً', 429, 'RATE_LIMITED');
  }

  const now = Date.now();
  await expireStaleOrdersForDevice(env, body.did, now);

  // Only one live order per device at a time.
  const existingOrder = await getActiveOrderForDevice(env.DB, body.did);
  if (existingOrder) {
    return jsonResponse({
      ok: false,
      code: 'ACTIVE_ORDER',
      error: 'لديك طلب دفع قيد المعالجة',
      order: orderPublic(existingOrder),
    }, 409);
  }

  const plan = await getPlan(env.DB, body.plan_id);
  if (!plan || !plan.active) {
    return errorResponse('الباقة غير متاحة حالياً', 400, 'PLAN_INVALID');
  }

  const settings = await getSettings(env.DB, ['order_ttl_hours']);
  const orderTtlHours = parseInt(settings.order_ttl_hours || '48');

  let codeRow: CodeRow | null = null;

  // The user's own code must be valid, otherwise the UI would promise a price we won't honor.
  if (body.code && body.code.trim()) {
    const normalized = normalizeCode(body.code);
    const validation = await validateCode(env.DB, normalized, body.did, body.plan_id);
    if (!validation.valid || !validation.code) {
      if (validation.error === 'Code not valid for this plan') {
        return errorResponse('هذا الكود لا ينطبق على الباقة المختارة', 400, 'CODE_PLAN_MISMATCH');
      }
      return errorResponse('كود الخصم غير صالح أو منتهي الصلاحية', 400, 'CODE_INVALID');
    }
    codeRow = validation.code;
  }

  // A code the buyer typed is an explicit choice — honor it exactly as the app previewed.
  // Automatic campaigns only fill in when the buyer did not apply any code.
  if (!codeRow) {
    const autoCode = await getAutoApplyCode(env.DB, now);
    if (autoCode) {
      const autoValid = await validateCode(env.DB, autoCode.code, body.did, body.plan_id);
      if (autoValid.valid && autoValid.code) {
        codeRow = autoValid.code;
      }
    }
  }

  let discount = 0;
  let finalPrice = plan.price;
  let daysGranted = plan.days;

  if (codeRow) {
    const discountResult = applyDiscount(codeRow, plan.price, plan.days);
    discount = discountResult.discount;
    finalPrice = discountResult.finalPrice;
    daysGranted = discountResult.daysGranted;
  }

  const orderId = generateOrderId();
  const expiresAt = now + orderTtlHours * 3600000;
  const isFreeDeal = finalPrice <= 0;

  if (codeRow) {
    const reserved = await reserveCode(env.DB, codeRow.code, body.did, orderId);
    if (!reserved) {
      return jsonResponse({
        ok: false,
        code: 'CODE_UNAVAILABLE',
        error: 'هذا الكود لم يعد متاحاً، أزل الكود أو جرّب كوداً آخر',
      }, 409);
    }
  }

  try {
    await env.DB.prepare(
      `INSERT INTO orders (id, device_id, plan_id, base_price, discount, final_price, code, days_granted, method, payer_name, payer_phone, status, provider, created_at, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 'manual', ?, ?)`
    ).bind(
      orderId, body.did, body.plan_id, plan.price, discount,
      Math.max(0, finalPrice), codeRow?.code ?? null, daysGranted,
      body.method || null, body.payer_name || null, body.payer_phone || null,
      now, expiresAt
    ).run();
  } catch (e) {
    if (codeRow) await releaseCode(env.DB, codeRow.code, orderId);
    console.error('Order insert failed:', e);
    return errorResponse('تعذر إنشاء الطلب، حاول مرة أخرى', 500, 'ORDER_CREATE_FAILED');
  }

  await logEvent(env.DB, body.did, 'ORDER_CREATE',
    `Order ${orderId}: plan=${body.plan_id} price=${finalPrice} code=${codeRow?.code || 'none'}`);

  // Free orders (FULL / FREE_DAYS) are approved immediately and get a token right away.
  // They must start as PENDING so approveOrder grants the days and consumes the reserved code.
  let status = 'PENDING';
  let approvedToken: string | null = null;
  if (isFreeDeal) {
    const approved = await approveOrder(env, orderId, 'system');
    if (approved) {
      status = 'APPROVED';
      try {
        const freshDevice = await getDevice(env.DB, body.did);
        const freshSettings = await getSettings(env.DB, ['paid_token_ttl_days', 'grace_days', 'min_app_version', 'tos_version']);
        if (freshDevice && !freshDevice.blocked) {
          const paidTtlDays = parseInt(freshSettings.paid_token_ttl_days || '7');
          const graceDays = parseInt(freshSettings.grace_days || '2');
          const minAppVersion = parseInt(freshSettings.min_app_version || '1');
          const tosVersion = parseInt(freshSettings.tos_version || '1');
          const nonce = crypto.randomUUID().replace(/-/g, '').substring(0, 16);
          const tokenPayload = buildTokenPayload(freshDevice, now, nonce, paidTtlDays, graceDays, minAppVersion, tosVersion);
          const privateKey = await getPrivateKey(env.LICENSE_PRIVATE_KEY);
          approvedToken = await signToken(tokenPayload, privateKey);
        }
      } catch (_) {
        // Non-fatal: app refreshes via /v1/refresh on next open.
      }
    }
  }

  const createdOrder = await getOrder(env.DB, orderId);
  if (!isFreeDeal && createdOrder) {
    await notifyNewOrder(env, createdOrder);
  }

  const paymentInfo = await getPaymentInfo(env);

  return jsonResponse({
    ok: true,
    token: approvedToken,
    order: {
      id: orderId,
      plan_id: body.plan_id,
      base_price: plan.price,
      discount,
      final_price: Math.max(0, finalPrice),
      days_granted: daysGranted,
      code: codeRow?.code ?? null,
      status,
      method: body.method || null,
      expires_at: expiresAt,
    },
    payment_methods: paymentInfo.methods,
    support: paymentInfo.support,
  });
}

/**
 * POST /v1/orders/:id/proof — Submit payment proof.
 */
export async function handleSubmitProof(request: Request, env: Env, orderId: string): Promise<Response> {
  const parsed = submitProofBodySchema.safeParse(await parseJsonBody(request));
  if (!parsed.success) {
    return errorResponse('بيانات الإيصال غير مكتملة', 400, 'MISSING_FIELDS');
  }
  const body = parsed.data;

  const order = await getOrder(env.DB, orderId);
  if (!order) {
    return errorResponse('الطلب غير موجود', 404, 'ORDER_NOT_FOUND');
  }
  if (order.device_id !== body.did) {
    return errorResponse('هذا الطلب لا يخص هذا الجهاز', 403, 'FORBIDDEN');
  }

  const now = Date.now();
  if (isExpired(order, now)) {
    await expireOrder(env, order);
    return errorResponse('انتهت صلاحية هذا الطلب، أنشئ طلباً جديداً', 409, 'ORDER_EXPIRED');
  }

  if (order.status !== 'PENDING' && order.status !== 'SUBMITTED') {
    return errorResponse('لا يمكن إرسال إيصال لهذا الطلب في حالته الحالية', 409, 'ORDER_STATE');
  }

  if (!await checkRateLimit(env.DB, `proof:${body.did}`, 30)) {
    return errorResponse('محاولات إرسال كثيرة، حاول بعد قليل', 429, 'RATE_LIMITED');
  }

  const method = (body.method || '').trim();
  if (!method) {
    return errorResponse('يرجى اختيار طريقة الدفع', 400, 'METHOD_REQUIRED');
  }

  const settings = await getSettings(env.DB, ['payment_methods']);
  const configuredMethods = parsePaymentMethods(settings.payment_methods);
  if (configuredMethods.length > 0 && !configuredMethods.some(m => m.name === method)) {
    return errorResponse('طريقة الدفع غير متاحة', 400, 'METHOD_INVALID');
  }

  let txnRef = (body.txn_ref || '').replace(/\s+/g, ' ').trim();
  if (txnRef.length > 64) txnRef = txnRef.substring(0, 64);

  // The same transfer reference must not back two different orders.
  if (txnRef) {
    const duplicate = await env.DB.prepare(
      'SELECT id FROM orders WHERE method = ? AND txn_ref = ? AND id <> ?'
    ).bind(method, txnRef, orderId).first<{ id: string }>();
    if (duplicate) {
      return errorResponse('رقم العملية مستخدم في طلب آخر، راجع رقم العملية أو تواصل مع الدعم', 409, 'TXN_DUP');
    }
  }

  const stmts: D1PreparedStatement[] = [];

  stmts.push(
    env.DB.prepare(
      `UPDATE orders SET status = 'SUBMITTED', method = ?, txn_ref = ?, payer_name = ?, payer_phone = ?, submitted_at = ?
       WHERE id = ? AND status IN ('PENDING','SUBMITTED')`
    ).bind(method, txnRef || null, body.payer_name || null, body.payer_phone || null, now, orderId)
  );

  // Store the proof image when the client sends one (the current app sends receipts on WhatsApp).
  const imageData = body.proof_data || body.image;
  let hasImage = false;
  if (imageData) {
    if (typeof imageData !== 'string' || imageData.length > 3_500_000) {
      return errorResponse('حجم صورة الإيصال كبير جداً (الحد الأقصى 2 ميغابايت)', 400, 'PROOF_TOO_LARGE');
    }
    const base64 = imageData.includes(',') ? imageData.substring(imageData.indexOf(',') + 1) : imageData;
    let binaryStr: string;
    try {
      binaryStr = atob(base64);
    } catch (_) {
      return errorResponse('صورة الإيصال غير صالحة', 400, 'INVALID_IMAGE');
    }
    if (binaryStr.length > MAX_PROOF_BYTES) {
      return errorResponse('حجم صورة الإيصال كبير جداً', 400, 'PROOF_TOO_LARGE');
    }

    const bytes = new Uint8Array(binaryStr.length);
    for (let i = 0; i < binaryStr.length; i++) {
      bytes[i] = binaryStr.charCodeAt(i);
    }

    // Only real raster images are accepted; the stored MIME comes from the file header.
    const mime = sniffImageMime(bytes);
    if (!mime || !ALLOWED_PROOF_MIMES.has(mime)) {
      return errorResponse('صورة الإيصال غير مدعومة، استخدم JPG أو PNG', 400, 'INVALID_IMAGE');
    }

    stmts.push(
      env.DB.prepare(
        'INSERT OR REPLACE INTO proofs (order_id, mime, data, created_at) VALUES (?, ?, ?, ?)'
      ).bind(orderId, mime, bytes, now)
    );
    hasImage = true;
  }

  try {
    await env.DB.batch(stmts);
  } catch (e) {
    console.error('Proof submit failed:', e);
    return errorResponse('تعذر حفظ الإيصال، حاول مرة أخرى', 500, 'PROOF_SAVE_FAILED');
  }

  await logEvent(env.DB, order.device_id, 'ORDER_SUBMIT',
    `Order ${orderId} submitted, method=${method}, txn=${txnRef || 'none'}, image=${hasImage}`);

  // Notify the owner with approve/reject buttons.
  const freshOrder = (await getOrder(env.DB, orderId)) || order;
  const device = await getDevice(env.DB, order.device_id);
  const msg = `🧾 <b>إيصال دفع جديد</b>\n\n`
    + `📋 الطلب: <code>${orderId}</code>\n`
    + `📱 الجهاز: <code>${order.device_id.substring(0, 8)}...</code>\n`
    + `${device?.name ? `👤 الاسم: ${escapeHtml(device.name)}\n` : ''}`
    + `💰 المبلغ: ${freshOrder.final_price} د.ع\n`
    + `📦 الخطة: ${freshOrder.plan_id} (${freshOrder.days_granted} يوم)\n`
    + `💳 طريقة الدفع: ${method}\n`
    + `${txnRef ? `🔖 رقم العملية: ${txnRef}\n` : ''}`
    + `${body.payer_name ? `👤 اسم الدافع: ${escapeHtml(body.payer_name)}\n` : ''}`
    + `${body.payer_phone ? `📞 رقم الدافع: ${escapeHtml(body.payer_phone)}\n` : ''}`
    + `${hasImage ? '🖼️ الصورة مرفقة في اللوحة\n' : 'ℹ️ لا توجد صورة مرفقة — الإيصال يصل عبر واتساب\n'}`
    + `⏰ الوقت: ${formatBaghdadTime(now)}`;

  const inlineKeyboard = {
    inline_keyboard: [
      [
        { text: '✅ قبول', callback_data: `approve:${orderId}` },
        { text: '❌ رفض', callback_data: `reject:${orderId}` },
      ],
    ],
  };

  await sendTelegram(env.TG_BOT_TOKEN, env.OWNER_CHAT_ID, msg, inlineKeyboard);

  return jsonResponse({
    ok: true,
    status: 'SUBMITTED',
    order: orderPublic(freshOrder),
  });
}

/**
 * GET /v1/orders/:id?did=... — Get order status.
 * If APPROVED, also returns a fresh signed token for the owning device.
 */
export async function handleGetOrder(request: Request, env: Env, orderId: string): Promise<Response> {
  const did = new URL(request.url).searchParams.get('did') || '';
  if (!isValidDeviceId(did)) {
    return errorResponse('معرّف الجهاز مطلوب', 403, 'FORBIDDEN');
  }

  const order = await getOrder(env.DB, orderId);
  if (!order) {
    return errorResponse('الطلب غير موجود', 404, 'ORDER_NOT_FOUND');
  }
  if (order.device_id !== did) {
    return errorResponse('هذا الطلب لا يخص هذا الجهاز', 403, 'FORBIDDEN');
  }

  const now = Date.now();
  if (isExpired(order, now)) {
    await expireOrder(env, order);
    order.status = 'EXPIRED';
    order.decided_at = now;
  }

  let token: string | null = null;

  if (order.status === 'APPROVED') {
    try {
      const settings = await getSettings(env.DB, ['paid_token_ttl_days', 'grace_days', 'min_app_version', 'tos_version']);
      const device = await getDevice(env.DB, order.device_id);

      if (device && !device.blocked) {
        const paidTtlDays = parseInt(settings.paid_token_ttl_days || '7');
        const graceDays = parseInt(settings.grace_days || '2');
        const minAppVersion = parseInt(settings.min_app_version || '1');
        const tosVersion = parseInt(settings.tos_version || '1');
        const nonce = crypto.randomUUID().replace(/-/g, '').substring(0, 16);

        const tokenPayload = buildTokenPayload(device, now, nonce, paidTtlDays, graceDays, minAppVersion, tosVersion);
        const privateKey = await getPrivateKey(env.LICENSE_PRIVATE_KEY);
        token = await signToken(tokenPayload, privateKey);
      }
    } catch (_) {
      // Token generation failure is non-fatal; app will refresh via /v1/refresh.
    }
  }

  const paymentInfo = await getPaymentInfo(env);

  return jsonResponse({
    ok: true,
    token,
    order: {
      ...orderPublic(order),
      reject_reason: order.reject_reason,
      decided_at: order.decided_at,
    },
    payment_methods: paymentInfo.methods,
    support: paymentInfo.support,
  });
}

/**
 * POST /v1/orders/:id/cancel — Cancel a pending order.
 */
export async function handleCancelOrder(request: Request, env: Env, orderId: string): Promise<Response> {
  const parsed = cancelOrderBodySchema.safeParse(await parseJsonBody(request));
  if (!parsed.success) {
    return errorResponse('معرّف الجهاز مطلوب', 403, 'FORBIDDEN');
  }
  const body = parsed.data;

  const order = await getOrder(env.DB, orderId);
  if (!order) {
    return errorResponse('الطلب غير موجود', 404, 'ORDER_NOT_FOUND');
  }
  if (order.device_id !== body.did) {
    return errorResponse('هذا الطلب لا يخص هذا الجهاز', 403, 'FORBIDDEN');
  }

  const now = Date.now();
  if (isExpired(order, now)) {
    await expireOrder(env, order);
    return jsonResponse({ ok: true, status: 'EXPIRED' });
  }

  if (order.status !== 'PENDING' && order.status !== 'SUBMITTED') {
    return jsonResponse({ ok: false, code: 'ORDER_STATE', status: order.status }, 409);
  }

  const result = await env.DB.prepare(
    `UPDATE orders SET status = 'CANCELLED', decided_at = ? WHERE id = ? AND status IN ('PENDING','SUBMITTED')`
  ).bind(now, orderId).run();

  if (!result.meta.changes) {
    return jsonResponse({ ok: false, code: 'ORDER_STATE', status: 'UNCHANGED' }, 409);
  }

  if (order.code) {
    await releaseCode(env.DB, order.code, orderId);
  }

  await logEvent(env.DB, order.device_id, 'ORDER_CANCEL', `Order ${orderId} cancelled by device`);

  return jsonResponse({ ok: true, status: 'CANCELLED' });
}

/**
 * Approve an order: grant subscription days.
 */
export async function approveOrder(env: Env, orderId: string, actor: string): Promise<boolean> {
  const order = await getOrder(env.DB, orderId);
  if (!order || (order.status !== 'PENDING' && order.status !== 'SUBMITTED')) {
    return false;
  }

  const now = Date.now();

  const result = await env.DB.prepare(
    `UPDATE orders SET status = 'APPROVED', decided_at = ?, decided_by = ? WHERE id = ? AND status IN ('PENDING','SUBMITTED')`
  ).bind(now, actor, orderId).run();

  if (!result.meta.changes || result.meta.changes === 0) {
    return false;
  }

  const device = await getDevice(env.DB, order.device_id);
  if (!device) return false;

  const base = Math.max(now, device.paid_until);
  const newPaidUntil = base + order.days_granted * 86400000;

  await env.DB.prepare(
    'UPDATE devices SET paid_until = MAX(paid_until, ?) + ? WHERE device_id = ?'
  ).bind(now, order.days_granted * 86400000, order.device_id).run();

  if (order.code) {
    await consumeCode(env.DB, order.code, orderId);
  }

  await logEvent(env.DB, order.device_id, 'ORDER_APPROVE',
    `Order ${orderId} approved by ${actor}. paid_until=${newPaidUntil} (+${order.days_granted}d)`);

  // Free activations deserve an audit trail in the owner's chat.
  if (order.final_price <= 0) {
    await sendTelegram(env.TG_BOT_TOKEN, env.OWNER_CHAT_ID,
      `🎁 <b>تفعيل مجاني</b>\n`
      + `📋 الطلب: <code>${orderId}</code>\n`
      + `📱 الجهاز: <code>${order.device_id.substring(0, 8)}...</code>\n`
      + `${order.code ? `🏷️ الكود: ${order.code}\n` : ''}`
      + `📅 حتى: ${formatBaghdadTime(newPaidUntil)}`);
  }

  return true;
}

/**
 * Reject an order.
 */
export async function rejectOrder(env: Env, orderId: string, reason: string, actor: string): Promise<boolean> {
  const order = await getOrder(env.DB, orderId);
  if (!order || (order.status !== 'PENDING' && order.status !== 'SUBMITTED')) {
    return false;
  }

  const result = await env.DB.prepare(
    `UPDATE orders SET status = 'REJECTED', reject_reason = ?, decided_at = ?, decided_by = ? WHERE id = ? AND status IN ('PENDING','SUBMITTED')`
  ).bind(reason, Date.now(), actor, orderId).run();

  // Lost the race to another decision (e.g. approve and reject tapped at once) — report honestly.
  if (!result.meta.changes) return false;

  if (order.code) {
    await releaseCode(env.DB, order.code, orderId);
  }

  await logEvent(env.DB, order.device_id, 'ORDER_REJECT',
    `Order ${orderId} rejected by ${actor}: ${reason}`);

  return true;
}

/**
 * Apply a discount code and return final price and days.
 */
export function applyDiscount(code: CodeRow, basePrice: number, baseDays: number): {
  discount: number;
  finalPrice: number;
  daysGranted: number;
} {
  switch (code.kind) {
    case 'PERCENT': {
      const disc = roundPrice(basePrice * code.value / 100);
      return { discount: disc, finalPrice: roundPrice(basePrice - disc), daysGranted: baseDays };
    }
    case 'FIXED': {
      const disc = Math.min(code.value, basePrice);
      return { discount: disc, finalPrice: roundPrice(basePrice - disc), daysGranted: baseDays };
    }
    case 'FULL':
      return { discount: basePrice, finalPrice: 0, daysGranted: baseDays };
    case 'FREE_DAYS':
      return { discount: 0, finalPrice: 0, daysGranted: baseDays + code.value };
    default:
      return { discount: 0, finalPrice: basePrice, daysGranted: baseDays };
  }
}
