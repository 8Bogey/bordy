import { Env, CodeRow } from '../types';
import { getDevice, getCode, getCodeDeviceUsage, logEvent, getSettings } from '../db';
import { isValidDeviceId, normalizeCode, jsonResponse, errorResponse, parseJsonBody, timingSafeEqualString } from '../utils';
import { checkRateLimit } from '../rate-limit';
import { checkCodeBodySchema, redeemBodySchema } from '../validate';
import { getPrivateKey, signToken } from '../crypto';
import { buildTokenPayload } from './activate';

/**
 * POST /v1/codes/check — Validate a code without reserving it.
 */
export async function handleCheckCode(request: Request, env: Env): Promise<Response> {
  const parsed = checkCodeBodySchema.safeParse(await parseJsonBody(request));
  if (!parsed.success) {
    return errorResponse('Missing required fields');
  }
  const body = parsed.data;

  if (!isValidDeviceId(body.did)) {
    return errorResponse('Invalid device ID');
  }

  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const settings = await getSettings(env.DB, ['code_attempts_per_hour_device', 'code_attempts_per_hour_ip']);

  const deviceLimit = parseInt(settings.code_attempts_per_hour_device || '10');
  const ipLimit = parseInt(settings.code_attempts_per_hour_ip || '30');

  const [deviceOk, ipOk] = await Promise.all([
    checkRateLimit(env.DB, `code:${body.did}`, deviceLimit),
    checkRateLimit(env.DB, `code:${ip}`, ipLimit),
  ]);

  if (!deviceOk || !ipOk) {
    return errorResponse('محاولات كثيرة، حاول بعد قليل', 429, 'RATE_LIMITED');
  }

  const rawCode = body.code.trim();

  // The owner/lifetime code is not a promo code; recognize it so the app can
  // validate it before handing it to /v1/activate (which grants lifetime).
  if (await timingSafeEqualString(rawCode, env.ADMIN_SECRET)) {
    return jsonResponse({ ok: true, code: 'OWNER', kind: 'OWNER', value: 0, plans: '*' });
  }

  const normalized = normalizeCode(body.code);
  const result = await validateCode(env.DB, normalized, body.did, body.plan_id);

  if (!result.valid) {
    return errorResponse('كود غير صالح أو منتهي الصلاحية', 400, 'CODE_INVALID');
  }

  return jsonResponse({
    ok: true,
    code: normalized,
    kind: result.code!.kind,
    value: result.code!.value,
    plans: result.code!.plans,
  });
}

/**
 * POST /v1/redeem — Redeem a FREE_DAYS code directly.
 */
export async function handleRedeem(request: Request, env: Env): Promise<Response> {
  const parsed = redeemBodySchema.safeParse(await parseJsonBody(request));
  if (!parsed.success) {
    return errorResponse('Missing required fields');
  }
  const body = parsed.data;

  if (!isValidDeviceId(body.did)) {
    return errorResponse('Invalid device ID');
  }

  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const settings = await getSettings(env.DB, ['code_attempts_per_hour_device', 'code_attempts_per_hour_ip']);

  const deviceLimit = parseInt(settings.code_attempts_per_hour_device || '10');
  const ipLimit = parseInt(settings.code_attempts_per_hour_ip || '30');

  const [deviceOk, ipOk] = await Promise.all([
    checkRateLimit(env.DB, `redeem:${body.did}`, deviceLimit),
    checkRateLimit(env.DB, `redeem:${ip}`, ipLimit),
  ]);

  if (!deviceOk || !ipOk) {
    return errorResponse('محاولات كثيرة، حاول بعد قليل', 429, 'RATE_LIMITED');
  }

  const normalized = normalizeCode(body.code);
  const result = await validateCode(env.DB, normalized, body.did);

  if (!result.valid || !result.code) {
    return errorResponse('كود غير صالح أو منتهي الصلاحية', 400, 'CODE_INVALID');
  }

  if (result.code.kind !== 'FREE_DAYS') {
    return errorResponse('هذا الكود يُستخدم مع طلب شراء من داخل التطبيق', 400, 'CODE_KIND');
  }

  const device = await getDevice(env.DB, body.did);
  if (!device) {
    return errorResponse('الجهاز غير مسجّل، أعد فتح التطبيق أولاً', 404, 'DEVICE_NOT_REGISTERED');
  }

  if (device.blocked) {
    return errorResponse('هذا الجهاز محظور', 403, 'DEVICE_BLOCKED');
  }

  // Apply free days directly. Claim a use atomically so concurrent redemptions
  // cannot exceed max_uses between validation and the write.
  const now = Date.now();
  const base = Math.max(now, device.paid_until);
  const newPaidUntil = base + result.code.value * 86400000;

  const claim = await env.DB.prepare(
    'UPDATE codes SET used_count = used_count + 1 WHERE code = ? AND (max_uses IS NULL OR used_count + reserved_count < max_uses)'
  ).bind(normalized).run();

  if (!claim.meta.changes) {
    return errorResponse('انتهت مرات استخدام هذا الكود', 409, 'CODE_UNAVAILABLE');
  }

  try {
    await env.DB.batch([
      env.DB.prepare('UPDATE devices SET paid_until = ? WHERE device_id = ?').bind(newPaidUntil, body.did),
      env.DB.prepare(
        `INSERT INTO code_redemptions (code, device_id, order_id, state, created_at) VALUES (?, ?, NULL, 'CONSUMED', ?)`
      ).bind(normalized, body.did, now),
    ]);
  } catch (_) {
    // Already redeemed on this device (unique index) — give the claimed use back.
    await env.DB.prepare('UPDATE codes SET used_count = MAX(0, used_count - 1) WHERE code = ?').bind(normalized).run();
    return errorResponse('لقد استخدمت هذا الكود مسبقاً على هذا الجهاز', 409, 'CODE_USED');
  }

  await logEvent(env.DB, body.did, 'CODE_REDEEM', `Code ${normalized}: +${result.code.value} days, paid_until=${newPaidUntil}`);

  let token: string | null = null;
  try {
    const updatedDevice = await getDevice(env.DB, body.did);
    if (updatedDevice) {
      const settings = await getSettings(env.DB, ['paid_token_ttl_days', 'grace_days', 'min_app_version', 'tos_version']);
      const paidTtlDays = parseInt(settings.paid_token_ttl_days || '7');
      const graceDays = parseInt(settings.grace_days || '2');
      const minAppVersion = parseInt(settings.min_app_version || '1');
      const tosVersion = parseInt(settings.tos_version || '1');
      const nonce = crypto.randomUUID().replace(/-/g, '').substring(0, 16);
      const tokenPayload = buildTokenPayload(updatedDevice, now, nonce, paidTtlDays, graceDays, minAppVersion, tosVersion);
      const privateKey = await getPrivateKey(env.LICENSE_PRIVATE_KEY);
      token = await signToken(tokenPayload, privateKey);
    }
  } catch (_) {
    // Non-fatal: fallback to app sync
  }

  return jsonResponse({
    ok: true,
    token,
    days_added: result.code.value,
    paid_until: newPaidUntil,
  });
}

/**
 * Validate a code for a device. Returns validity info.
 */
export async function validateCode(
  db: D1Database,
  code: string,
  deviceId: string,
  planId?: string
): Promise<{ valid: boolean; error?: string; code?: CodeRow }> {
  const codeRow = await getCode(db, code);

  if (!codeRow) {
    return { valid: false, error: 'Code not found' };
  }

  if (!codeRow.active) {
    return { valid: false, error: 'Code is inactive' };
  }

  const now = Date.now();
  if (now < codeRow.starts_at || now >= codeRow.ends_at) {
    return { valid: false, error: 'Code is expired or not yet active' };
  }

  // Check usage limits
  if (codeRow.max_uses !== null && (codeRow.used_count + codeRow.reserved_count) >= codeRow.max_uses) {
    return { valid: false, error: 'Code usage limit reached' };
  }

  // Check per-device limit
  const deviceUsage = await getCodeDeviceUsage(db, code, deviceId);
  if (deviceUsage >= codeRow.per_device_limit) {
    return { valid: false, error: 'You already used this code' };
  }

  // Check plan restriction
  if (planId && codeRow.plans !== '*') {
    const allowedPlans = codeRow.plans.split(',').map(p => p.trim());
    if (!allowedPlans.includes(planId)) {
      return { valid: false, error: 'Code not valid for this plan' };
    }
  }

  return { valid: true, code: codeRow };
}
