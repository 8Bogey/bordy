import { Env, TokenPayload, DeviceRow } from '../types';
import { getDevice, insertDevice, touchDevice, logEvent, getSettings } from '../db';
import { getPrivateKey, signToken } from '../crypto';
import { isValidDeviceId, normalizeCode, jsonResponse, errorResponse, parseJsonBody, timingSafeEqualString } from '../utils';
import { checkRateLimit } from '../rate-limit';
import { validateCode } from './codes';
import { clampTrialSeconds } from './config';
import { activateBodySchema } from '../validate';

export async function handleActivate(request: Request, env: Env): Promise<Response> {
  const parsed = activateBodySchema.safeParse(await parseJsonBody(request));
  if (!parsed.success) {
    return errorResponse('Missing required fields');
  }
  const body = parsed.data;

  if (!isValidDeviceId(body.did)) {
    return errorResponse('Invalid device ID');
  }

  // Rate limit by IP
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const allowed = await checkRateLimit(env.DB, `activate:${ip}`, 20);
  if (!allowed) {
    return errorResponse('Rate limited', 429);
  }

  // Check maintenance mode
  const settings = await getSettings(env.DB, [
    'trial_days', 'trial_seconds', 'paid_token_ttl_days', 'grace_days',
    'min_app_version', 'maintenance', 'tos_text', 'privacy_text', 'tos_version'
  ]);

  if (settings.maintenance === 'on') {
    return jsonResponse({ ok: false, error: 'Service under maintenance' }, 503);
  }

  const now = Date.now();
  const trialSeconds = clampTrialSeconds(settings.trial_seconds, settings.trial_days);
  const paidTtlDays = parseInt(settings.paid_token_ttl_days || '7');
  const graceDays = parseInt(settings.grace_days || '2');
  const minAppVersion = parseInt(settings.min_app_version || '1');
  const tosVersion = parseInt(settings.tos_version || '1');

  let device = await getDevice(env.DB, body.did);
  let isNew = false;

  if (!device) {
    // New device → start trial
    const trialEnd = now + trialSeconds * 1000;
    await insertDevice(env.DB, {
      device_id: body.did,
      first_seen: now,
      last_seen: now,
      trial_started_at: now,
      trial_ends_at: trialEnd,
      app_ver: body.ver || 1,
      model: body.model || '',
      sdk: body.sdk || 0,
      cert_hash: body.cert_hash || '',
    });
    await logEvent(env.DB, body.did, 'TRIAL_START', `Trial ends: ${new Date(trialEnd).toISOString()}`);
    device = await getDevice(env.DB, body.did);
    isNew = true;
  } else {
    // Existing device → update metadata (throttled to once per 24h)
    await touchDevice(env.DB, body.did, {
      app_ver: body.ver || device.app_ver || 1,
      model: body.model || device.model || '',
      sdk: body.sdk || device.sdk || 0,
      cert_hash: body.cert_hash || device.cert_hash || '',
    }, now);
  }

  if (!device) {
    return errorResponse('Device creation failed', 500);
  }

  // Check if blocked
  if (device.blocked) {
    return jsonResponse({
      ok: false,
      status: 'BLOCKED',
      reason: device.block_reason || 'Account blocked',
    });
  }

  // Handle owner_code / promo code passed at activation
  if (body.owner_code && body.owner_code.trim()) {
    const rawCode = body.owner_code.trim();
    const isOwnerMatch = await timingSafeEqualString(rawCode, env.ADMIN_SECRET);
    if (isOwnerMatch) {
      // Direct lifetime owner authorization
      await env.DB.prepare('UPDATE devices SET lifetime = 1 WHERE device_id = ?').bind(body.did).run();
      device.lifetime = 1;
      await logEvent(env.DB, body.did, 'OWNER_ACTIVATED', 'Activated lifetime owner via owner_code');
    } else {
      // Check if it's a promotional FREE_DAYS code
      const normalized = normalizeCode(rawCode);
      const validation = await validateCode(env.DB, normalized, body.did);
      if (validation.valid && validation.code && validation.code.kind === 'FREE_DAYS') {
        // Claim a use atomically so two concurrent activations cannot exceed max_uses.
        const claimed = await env.DB.prepare(
          `UPDATE codes SET used_count = used_count + 1
           WHERE code = ? AND active = 1 AND (max_uses IS NULL OR used_count + reserved_count < max_uses)`
        ).bind(normalized).run();

        if (claimed.meta.changes) {
          const base = Math.max(now, device.paid_until);
          const newPaidUntil = base + validation.code.value * 86400000;
          try {
            await env.DB.batch([
              env.DB.prepare('UPDATE devices SET paid_until = ? WHERE device_id = ?').bind(newPaidUntil, body.did),
              env.DB.prepare(
                `INSERT INTO code_redemptions (code, device_id, order_id, state, created_at) VALUES (?, ?, NULL, 'CONSUMED', ?)`
              ).bind(normalized, body.did, now),
            ]);
            device.paid_until = newPaidUntil;
            await logEvent(env.DB, body.did, 'CODE_REDEEM', `Activated with code ${normalized}: +${validation.code.value} days`);
          } catch (_) {
            // Already redeemed on this device (unique index) — give the use back.
            await env.DB.prepare('UPDATE codes SET used_count = MAX(0, used_count - 1) WHERE code = ?').bind(normalized).run();
          }
        }
      }
    }
  }

  // Determine status and build token
  const tokenPayload = buildTokenPayload(device, now, body.nonce, paidTtlDays, graceDays, minAppVersion, tosVersion);

  const privateKey = await getPrivateKey(env.LICENSE_PRIVATE_KEY);
  const token = await signToken(tokenPayload, privateKey);

  return jsonResponse({
    ok: true,
    token,
    status: tokenPayload.st,
    is_new: isNew,
    tos: {
      version: tosVersion,
      text: settings.tos_text || '',
      privacy: settings.privacy_text || '',
    },
  });
}

export function buildTokenPayload(
  device: DeviceRow,
  now: number,
  nonce: string,
  paidTtlDays: number,
  graceDays: number,
  minAppVersion: number,
  tosVersion: number
): TokenPayload {
  let st: 'trial' | 'paid' | 'owner';
  let expBase: number;

  if (device.lifetime) {
    st = 'owner';
    // Lifetime must not inherit the short paid-token TTL: an offline device would
    // otherwise lock itself out once the token expired.
    expBase = now + 100 * 365 * 86400000;
  } else if (device.paid_until >= now) {
    st = 'paid';
    expBase = Math.min(device.paid_until + graceDays * 86400000, now + paidTtlDays * 86400000);
  } else {
    st = 'trial';
    expBase = Math.min((device.trial_ends_at || 0) + graceDays * 86400000, now + paidTtlDays * 86400000);
  }

  return {
    v: 1,
    kid: 'k1',
    did: device.device_id,
    st,
    tre: device.trial_ends_at || 0,
    pu: device.paid_until,
    iat: now,
    exp: expBase,
    nonce,
    minv: minAppVersion,
    tos_ver: tosVersion,
  };
}
