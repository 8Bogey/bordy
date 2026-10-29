import { Env } from '../types';
import { checkRateLimit } from '../rate-limit';
import { errorResponse, isValidDeviceId, jsonResponse, parseBoundedJson } from '../utils';
import { hashPrivateIdentifier, normalizeUsername, verifyPassword } from './crypto';
import { requireAccountAuth, requireAccountReadAuth } from './auth';
import { AccountDeviceRow, AccountRow } from './types';

interface TransferRow {
  id: string;
  account_id: string;
  source_device_id: string;
  target_device_id: string;
  status: string;
  snapshot_cursor: number | null;
  expires_at: number;
}

interface CursorInput { cursor?: number }

interface WipeAckInput {
  username?: string;
  password?: string;
  device_id?: string;
  install_id?: string;
  local_data_wiped?: boolean;
}

export async function handleGetCurrentTransfer(request: Request, env: Env): Promise<Response> {
  const auth = await requireAccountReadAuth(request, env);
  if (auth instanceof Response) return auth;
  const transfer = await env.DB.prepare(
    `SELECT id, account_id, source_device_id, target_device_id, status, snapshot_cursor, expires_at
     FROM device_transfers
     WHERE account_id = ? AND (source_device_id = ? OR target_device_id = ?)
       AND status NOT IN ('complete','rejected','cancelled','expired')
     ORDER BY requested_at DESC LIMIT 1`,
  ).bind(auth.account.id, auth.device.id, auth.device.id).first<TransferRow>();
  return jsonResponse({ ok: true, transfer });
}

export async function handleSourceFrozen(
  request: Request,
  env: Env,
  transferId: string,
): Promise<Response> {
  const auth = await requireAccountAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await parseBoundedJson<CursorInput>(request, 8 * 1024);
  const cursor = Number(body?.cursor);
  if (!Number.isSafeInteger(cursor) || cursor < 0) {
    return errorResponse('مؤشر المزامنة غير صالح', 400, 'VALIDATION_FAILED');
  }
  const transfer = await env.DB.prepare(
    `SELECT id, account_id, source_device_id, target_device_id, status, snapshot_cursor, expires_at
     FROM device_transfers WHERE id = ? AND account_id = ?`,
  ).bind(transferId, auth.account.id).first<TransferRow>();
  if (!transfer) return errorResponse('طلب النقل غير موجود', 404, 'TRANSFER_NOT_FOUND');
  if (transfer.source_device_id !== auth.device.id) return errorResponse('هذا ليس الجهاز المصدر', 403, 'TRANSFER_FORBIDDEN');
  if (transfer.status !== 'approved') return errorResponse('طلب النقل ليس بمرحلة التجميد', 409, 'TRANSFER_STATE_CHANGED');
  if (transfer.expires_at <= Date.now()) return errorResponse('انتهت صلاحية طلب النقل', 410, 'TRANSFER_EXPIRED');

  const now = Date.now();
  const assertionId = `transfer:${transfer.id}:freeze`;
  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO sync_assertions(id, ok)
         SELECT ?, CASE WHEN EXISTS(
           SELECT 1 FROM device_transfers t
           JOIN accounts a ON a.id = t.account_id
           JOIN account_devices s ON s.id = t.source_device_id
           JOIN account_devices d ON d.id = t.target_device_id
           WHERE t.id = ? AND t.account_id = ? AND t.status = 'approved'
             AND a.active_device_id = ? AND a.latest_change_id = ?
             AND s.status = 'active' AND d.status = 'pending'
         ) THEN 1 ELSE 0 END`,
      ).bind(assertionId, transfer.id, auth.account.id, auth.device.id, cursor),
      env.DB.prepare(
        `UPDATE account_devices SET status = 'frozen', device_epoch = device_epoch + 1, updated_at = ?
         WHERE id = ? AND status = 'active'`,
      ).bind(now, transfer.source_device_id),
      env.DB.prepare(
        `UPDATE account_devices SET status = 'restoring', device_epoch = device_epoch + 1, updated_at = ?
         WHERE id = ? AND status = 'pending'`,
      ).bind(now, transfer.target_device_id),
      env.DB.prepare(
        `UPDATE accounts SET active_device_id = ?, account_epoch = account_epoch + 1, updated_at = ?
         WHERE id = ? AND active_device_id = ?`,
      ).bind(transfer.target_device_id, now, auth.account.id, transfer.source_device_id),
      env.DB.prepare(
        `UPDATE device_transfers
         SET status = 'target_restoring', frozen_at = ?, snapshot_cursor = ?
         WHERE id = ? AND status = 'approved'`,
      ).bind(now, cursor, transfer.id),
      env.DB.prepare('UPDATE refresh_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE account_id = ?')
        .bind(now, auth.account.id),
      env.DB.prepare(
        `INSERT INTO account_audit_events
         (account_id, actor_type, actor_id, action, target_type, target_id, metadata_json, created_at)
         VALUES (?, 'device', ?, 'SOURCE_FROZEN', 'transfer', ?, ?, ?)`,
      ).bind(auth.account.id, auth.device.id, transfer.id, JSON.stringify({ cursor }), now),
      env.DB.prepare('DELETE FROM sync_assertions WHERE id = ?').bind(assertionId),
    ]);
  } catch {
    return errorResponse('تغيّرت بيانات الحساب؛ أعد المزامنة وحاول مجددًا', 409, 'TRANSFER_STATE_CHANGED');
  }
  return jsonResponse({ ok: true, status: 'target_restoring', snapshot_cursor: cursor });
}

export async function handleTargetConfirmed(
  request: Request,
  env: Env,
  transferId: string,
): Promise<Response> {
  const auth = await requireAccountReadAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await parseBoundedJson<CursorInput>(request, 8 * 1024);
  const cursor = Number(body?.cursor);
  if (!Number.isSafeInteger(cursor) || cursor < 0) {
    return errorResponse('مؤشر الاستعادة غير صالح', 400, 'VALIDATION_FAILED');
  }
  const transfer = await env.DB.prepare(
    `SELECT id, account_id, source_device_id, target_device_id, status, snapshot_cursor, expires_at
     FROM device_transfers WHERE id = ? AND account_id = ?`,
  ).bind(transferId, auth.account.id).first<TransferRow>();
  if (!transfer) return errorResponse('طلب النقل غير موجود', 404, 'TRANSFER_NOT_FOUND');
  if (transfer.target_device_id !== auth.device.id) return errorResponse('هذا ليس الجهاز الهدف', 403, 'TRANSFER_FORBIDDEN');
  if (transfer.status !== 'target_restoring') return errorResponse('طلب النقل ليس بمرحلة الاستعادة', 409, 'TRANSFER_STATE_CHANGED');
  if (cursor < (transfer.snapshot_cursor || 0)) return errorResponse('الاستعادة غير مكتملة', 409, 'RESTORE_INCOMPLETE');

  const now = Date.now();
  const assertionId = `transfer:${transfer.id}:confirm`;
  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO sync_assertions(id, ok)
         SELECT ?, CASE WHEN EXISTS(
           SELECT 1 FROM device_transfers t
           JOIN accounts a ON a.id = t.account_id
           JOIN account_devices d ON d.id = t.target_device_id
           WHERE t.id = ? AND t.status = 'target_restoring' AND t.target_device_id = ?
             AND a.active_device_id = ? AND a.latest_change_id = ? AND d.status = 'restoring'
         ) THEN 1 ELSE 0 END`,
      ).bind(assertionId, transfer.id, auth.device.id, auth.device.id, cursor),
      env.DB.prepare(
        `UPDATE account_devices SET status = 'active', updated_at = ?
         WHERE id = ? AND status = 'restoring'`,
      ).bind(now, transfer.target_device_id),
      env.DB.prepare(
        `UPDATE account_devices SET status = 'wipe_pending', device_epoch = device_epoch + 1, updated_at = ?
         WHERE id = ? AND status = 'frozen'`,
      ).bind(now, transfer.source_device_id),
      env.DB.prepare(
        `UPDATE device_transfers
         SET status = 'source_wipe_pending', restored_at = ?, activated_at = ?
         WHERE id = ? AND status = 'target_restoring'`,
      ).bind(now, now, transfer.id),
      env.DB.prepare(
        `INSERT INTO account_audit_events
         (account_id, actor_type, actor_id, action, target_type, target_id, metadata_json, created_at)
         VALUES (?, 'device', ?, 'TARGET_CONFIRMED', 'transfer', ?, ?, ?)`,
      ).bind(auth.account.id, auth.device.id, transfer.id, JSON.stringify({ cursor }), now),
      env.DB.prepare('DELETE FROM sync_assertions WHERE id = ?').bind(assertionId),
    ]);
  } catch {
    return errorResponse('وصلت تغييرات جديدة؛ أكمل المزامنة ثم أكد الاستعادة', 409, 'RESTORE_INCOMPLETE');
  }
  return jsonResponse({ ok: true, status: 'source_wipe_pending' });
}

export async function handleSourceWiped(
  request: Request,
  env: Env,
  transferId: string,
): Promise<Response> {
  const body = await parseBoundedJson<WipeAckInput>(request, 16 * 1024);
  if (!body || body.local_data_wiped !== true || !isValidDeviceId(String(body.device_id || ''))
    || !body.install_id || body.install_id.length > 200) {
    return errorResponse('تأكيد المسح غير صالح', 400, 'VALIDATION_FAILED');
  }
  const normalized = normalizeUsername(String(body.username || ''));
  const key = await hashPrivateIdentifier(`${normalized}:${request.headers.get('CF-Connecting-IP') || 'unknown'}`, env.PASSWORD_PEPPER);
  if (!await checkRateLimit(env.DB, `transfer_wipe:${key}`, 10)) {
    return errorResponse('محاولات كثيرة، حاول لاحقًا', 429, 'RATE_LIMITED');
  }
  const account = await env.DB.prepare('SELECT * FROM accounts WHERE username_norm = ?')
    .bind(normalized).first<AccountRow>();
  const valid = account && await verifyPassword(
    String(body.password || ''), env.PASSWORD_PEPPER, account.password_salt,
    account.password_hash, account.password_iterations,
  );
  if (!account || !valid) return errorResponse('بيانات الدخول غير صحيحة', 401, 'INVALID_CREDENTIALS');
  const installHash = await hashPrivateIdentifier(body.install_id, env.PASSWORD_PEPPER);
  const fingerprintHash = await hashPrivateIdentifier(String(body.device_id), env.PASSWORD_PEPPER);
  const device = await env.DB.prepare(
    `SELECT * FROM account_devices
     WHERE account_id = ? AND install_id_hash = ? AND fingerprint_hash = ?`,
  ).bind(account.id, installHash, fingerprintHash).first<AccountDeviceRow>();
  if (!device) return errorResponse('الجهاز غير معروف', 403, 'TRANSFER_FORBIDDEN');
  const transfer = await env.DB.prepare(
    `SELECT id, account_id, source_device_id, target_device_id, status, snapshot_cursor, expires_at
     FROM device_transfers WHERE id = ? AND account_id = ?`,
  ).bind(transferId, account.id).first<TransferRow>();
  if (!transfer || transfer.source_device_id !== device.id) {
    return errorResponse('طلب النقل غير موجود', 404, 'TRANSFER_NOT_FOUND');
  }
  if (transfer.status === 'complete' && device.status === 'wiped') {
    return jsonResponse({ ok: true, status: 'complete' });
  }
  if (transfer.status !== 'source_wipe_pending' || device.status !== 'wipe_pending') {
    return errorResponse('طلب النقل ليس بمرحلة المسح', 409, 'TRANSFER_STATE_CHANGED');
  }

  const now = Date.now();
  const assertionId = `transfer:${transfer.id}:wipe`;
  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO sync_assertions(id, ok)
         SELECT ?, CASE WHEN EXISTS(
           SELECT 1 FROM device_transfers t JOIN account_devices d ON d.id = t.source_device_id
           WHERE t.id = ? AND t.status = 'source_wipe_pending' AND d.id = ? AND d.status = 'wipe_pending'
         ) THEN 1 ELSE 0 END`,
      ).bind(assertionId, transfer.id, device.id),
      env.DB.prepare("UPDATE account_devices SET status = 'wiped', updated_at = ? WHERE id = ? AND status = 'wipe_pending'")
        .bind(now, device.id),
      env.DB.prepare(
        `UPDATE device_transfers SET status = 'complete', wipe_ack_at = ?
         WHERE id = ? AND status = 'source_wipe_pending'`,
      ).bind(now, transfer.id),
      env.DB.prepare(
        `INSERT INTO account_audit_events
         (account_id, actor_type, actor_id, action, target_type, target_id, created_at)
         VALUES (?, 'device', ?, 'SOURCE_WIPED', 'transfer', ?, ?)`,
      ).bind(account.id, device.id, transfer.id, now),
      env.DB.prepare('DELETE FROM sync_assertions WHERE id = ?').bind(assertionId),
    ]);
  } catch {
    return errorResponse('تغيّرت حالة النقل', 409, 'TRANSFER_STATE_CHANGED');
  }
  return jsonResponse({ ok: true, status: 'complete' });
}
