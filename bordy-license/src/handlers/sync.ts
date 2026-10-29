import { Env, TokenPayload } from '../types';
import { getDevice, getSettings } from '../db';
import { getPrivateKey, signToken } from '../crypto';
import { buildTokenPayload } from './activate';
import { loadRemoteConfig } from './config';
import { base64urlDecode, errorResponse, isValidDeviceId, jsonResponse } from '../utils';

/** A device polling faster than this is either buggy or abusive — answer cheaply. */
const MIN_SYNC_GAP_MS = 3_000;
/** Re-sign when the current token has less than this left, so the app never sits on an expiring one. */
const TOKEN_REISSUE_MS = 6 * 60 * 60 * 1000;
/** Only the most recent order matters to the app; older ones are history. */
const RECENT_ORDER_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

const lastSyncAt = new Map<string, number>();

function throttleRemainingMs(did: string, now: number): number {
  const last = lastSyncAt.get(did) || 0;
  const elapsed = now - last;
  if (elapsed < MIN_SYNC_GAP_MS) return MIN_SYNC_GAP_MS - elapsed;
  if (lastSyncAt.size > 5_000) lastSyncAt.clear(); // isolate kept alive long enough to grow
  lastSyncAt.set(did, now);
  return 0;
}

function decodeClaims(tok: string): TokenPayload | null {
  try {
    const payload = tok.split('.')[0];
    if (!payload) return null;
    const claims = JSON.parse(new TextDecoder().decode(base64urlDecode(payload))) as TokenPayload;
    return claims && typeof claims === 'object' ? claims : null;
  } catch {
    return null;
  }
}

/** The app only needs a new signed token when its rights actually changed. */
function needsFreshToken(prev: TokenPayload | null, next: TokenPayload, now: number): boolean {
  if (!prev) return true;
  if (prev.did !== next.did) return true;
  if (prev.st !== next.st) return true;
  if (prev.tre !== next.tre) return true;
  if (prev.pu !== next.pu) return true;
  if (prev.minv !== next.minv) return true;
  if (prev.tos_ver !== next.tos_ver) return true;
  if (!Number.isFinite(prev.exp) || prev.exp - now < TOKEN_REISSUE_MS) return true;
  return false;
}

/**
 * GET /v1/sync?did=...&tok=...&pid=...
 *
 * One round-trip for everything the app keeps in sync: license state, plans,
 * the automatic campaign, wallet numbers, support handles, the polling cadence
 * chosen in the admin panel, and the device's most recent order.
 */
export async function handleSync(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const did = url.searchParams.get('did') || '';
  const tok = url.searchParams.get('tok') || '';

  if (!isValidDeviceId(did)) {
    return errorResponse('معرّف الجهاز غير صالح', 403, 'INVALID_DEVICE');
  }

  const now = Date.now();
  const retryAfterMs = throttleRemainingMs(did, now);
  if (retryAfterMs > 0) {
    return jsonResponse({ ok: false, code: 'RATE_LIMITED', retry_after_ms: retryAfterMs }, 429);
  }

  const [device, settings, config] = await Promise.all([
    getDevice(env.DB, did),
    getSettings(env.DB, [
      'paid_token_ttl_days', 'grace_days', 'min_app_version',
      'maintenance', 'tos_version',
    ]),
    loadRemoteConfig(env, now),
  ]);

  if (settings.maintenance === 'on') {
    return jsonResponse({ ok: false, code: 'MAINTENANCE', error: 'الخدمة قيد الصيانة' }, 503);
  }

  if (!device) {
    return errorResponse('الجهاز غير مسجّل، أعد فتح التطبيق أولاً', 404, 'DEVICE_NOT_REGISTERED');
  }

  if (device.blocked) {
    return jsonResponse({
      ok: false,
      status: 'BLOCKED',
      reason: device.block_reason || 'تم حظر هذا الجهاز',
      config_version: config.version,
      sync_interval_seconds: config.syncIntervalSeconds,
      foreground_sync_seconds: config.foregroundSyncSeconds,
    });
  }

  const paidTtlDays = parseInt(settings.paid_token_ttl_days || '7');
  const graceDays = parseInt(settings.grace_days || '2');
  const minAppVersion = parseInt(settings.min_app_version || '1');
  const tosVersion = parseInt(settings.tos_version || '1');

  const nextClaims = buildTokenPayload(
    device, now, 'sync', paidTtlDays, graceDays, minAppVersion, tosVersion
  );

  let token: string | null = null;
  if (needsFreshToken(tok ? decodeClaims(tok) : null, nextClaims, now)) {
    const privateKey = await getPrivateKey(env.LICENSE_PRIVATE_KEY);
    token = await signToken(nextClaims, privateKey);
  }

  const recentOrder = await env.DB.prepare(
    `SELECT id, status, plan_id, final_price, method, txn_ref, reject_reason,
            created_at, submitted_at, decided_at, expires_at
     FROM orders WHERE device_id = ? AND created_at >= ?
     ORDER BY created_at DESC LIMIT 1`
  ).bind(did, now - RECENT_ORDER_WINDOW_MS).first<{
    id: string;
    status: string;
    plan_id: string;
    final_price: number;
    method: string | null;
    txn_ref: string | null;
    reject_reason: string | null;
    created_at: number;
    submitted_at: number | null;
    decided_at: number | null;
    expires_at: number;
  }>();

  return jsonResponse({
    ok: true,
    status: nextClaims.st,
    token,
    order: recentOrder ? {
      id: recentOrder.id,
      status: recentOrder.status,
      plan_id: recentOrder.plan_id,
      final_price: recentOrder.final_price,
      method: recentOrder.method,
      txn_ref: recentOrder.txn_ref,
      reject_reason: recentOrder.reject_reason,
      created_at: recentOrder.created_at,
      submitted_at: recentOrder.submitted_at,
      decided_at: recentOrder.decided_at,
      expires_at: recentOrder.expires_at,
    } : null,
    plans: config.plans,
    auto_discount: config.autoDiscount,
    payment_methods: config.paymentMethods,
    support: config.support,
    config_version: config.version,
    sync_interval_seconds: config.syncIntervalSeconds,
    foreground_sync_seconds: config.foregroundSyncSeconds,
  });
}
