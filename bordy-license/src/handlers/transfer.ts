import { Env } from '../types';
import { getDevice, logEvent, getSettings } from '../db';
import { isValidDeviceId, jsonResponse, errorResponse, parseJsonBody, timingSafeEqualString } from '../utils';

interface TransferInput {
  from_did: string;
  to_did: string;
  admin_secret?: string;
}

export async function handleTransferRequest(request: Request, env: Env): Promise<Response> {
  const body = await parseJsonBody<TransferInput>(request);
  if (!body || !body.from_did || !body.to_did) {
    return errorResponse('بيانات التحويل غير مكتملة', 400, 'MISSING_FIELDS');
  }

  // Moving a subscription is an owner decision: without proof of ownership of the
  // source device this endpoint would let anyone drain another user's license.
  const isAdmin = body.admin_secret ? await timingSafeEqualString(body.admin_secret, env.ADMIN_SECRET) : false;
  if (!isAdmin) {
    return errorResponse('تحويل الاشتراك يتم عبر الدعم فقط', 403, 'TRANSFER_FORBIDDEN');
  }

  if (!isValidDeviceId(body.from_did) || !isValidDeviceId(body.to_did)) {
    return errorResponse('معرّف الجهاز غير صالح', 400, 'INVALID_DEVICE');
  }

  if (body.from_did === body.to_did) {
    return errorResponse('لا يمكن التحويل إلى نفس الجهاز', 400, 'SAME_DEVICE');
  }

  const settings = await getSettings(env.DB, ['transfers_per_year', 'transfer_cooldown_h']);
  const maxTransfers = parseInt(settings.transfers_per_year || '2');
  const cooldownHours = parseInt(settings.transfer_cooldown_h || '24');

  const fromDevice = await getDevice(env.DB, body.from_did);
  if (!fromDevice) {
    return errorResponse('الجهاز المصدر غير موجود', 404, 'DEVICE_NOT_FOUND');
  }

  if (fromDevice.blocked) {
    return errorResponse('الجهاز المصدر محظور', 403, 'DEVICE_BLOCKED');
  }

  if (fromDevice.paid_until < Date.now() && !fromDevice.lifetime) {
    return errorResponse('لا يوجد اشتراك فعّال على الجهاز المصدر', 400, 'NO_SUBSCRIPTION');
  }

  const now = Date.now();

  // Limit is per rolling year, not per device lifetime.
  const yearlyTransfers = await env.DB.prepare(
    `SELECT COUNT(*) as c FROM events WHERE device_id = ? AND type = 'TRANSFER_OUT' AND ts > ?`
  ).bind(body.from_did, now - 365 * 86400000).first<{ c: number }>();

  if ((yearlyTransfers?.c ?? 0) >= maxTransfers) {
    return errorResponse(`تم بلوغ حد التحويلات لهذا العام (${maxTransfers})`, 429, 'TRANSFER_LIMIT');
  }

  // Check cooldown: look for recent transfer event
  const lastTransfer = await env.DB.prepare(
    `SELECT ts FROM events WHERE device_id = ? AND type = 'TRANSFER_OUT' ORDER BY ts DESC LIMIT 1`
  ).bind(body.from_did).first<{ ts: number }>();

  if (lastTransfer) {
    const cooldownMs = cooldownHours * 3600000;
    if (Date.now() - lastTransfer.ts < cooldownMs) {
      const waitHours = Math.ceil((cooldownMs - (Date.now() - lastTransfer.ts)) / 3600000);
      return errorResponse(`يرجى الانتظار ${waitHours} ساعة قبل التحويل التالي`, 429, 'TRANSFER_COOLDOWN');
    }
  }

  // Get or create target device
  let toDevice = await getDevice(env.DB, body.to_did);
  if (!toDevice) {
    // Create target device with no trial
    await env.DB.prepare(
      `INSERT INTO devices (device_id, first_seen, last_seen, trial_started_at, trial_ends_at, paid_until)
       VALUES (?, ?, ?, NULL, NULL, 0)`
    ).bind(body.to_did, now, now).run();
    toDevice = await getDevice(env.DB, body.to_did);
  }

  if (!toDevice) {
    return errorResponse('تعذر إنشاء الجهاز الهدف', 500, 'DEVICE_CREATE_FAILED');
  }

  if (toDevice.blocked) {
    return errorResponse('الجهاز الهدف محظور', 403, 'DEVICE_BLOCKED');
  }

  // Calculate new target paid_until so user does not lose existing days on either device
  const targetBase = Math.max(now, toDevice.paid_until);
  const sourceRemaining = Math.max(0, fromDevice.paid_until - now);
  const newPaidUntil = fromDevice.lifetime ? targetBase : (targetBase + sourceRemaining);
  const newLifetime = (fromDevice.lifetime || toDevice.lifetime) ? 1 : 0;

  // Transfer subscription
  await env.DB.batch([
    // Remove from source
    env.DB.prepare(
      `UPDATE devices SET paid_until = 0, lifetime = 0, transfers_used = transfers_used + 1 WHERE device_id = ?`
    ).bind(body.from_did),
    // Add to target
    env.DB.prepare(
      `UPDATE devices SET paid_until = ?, lifetime = ? WHERE device_id = ?`
    ).bind(newPaidUntil, newLifetime, body.to_did),
  ]);

  await logEvent(env.DB, body.from_did, 'TRANSFER_OUT', `Transferred to ${body.to_did.substring(0, 8)}...`);
  await logEvent(env.DB, body.to_did, 'TRANSFER_IN', `Received from ${body.from_did.substring(0, 8)}... (new_paid_until=${newPaidUntil})`);

  return jsonResponse({
    ok: true,
    message: 'تم تحويل الاشتراك بنجاح',
    new_paid_until: newPaidUntil,
  });
}
