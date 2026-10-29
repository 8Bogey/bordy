import { Env } from '../types';
import { parseBoundedJson } from '../utils';
import { derivePasswordHash, normalizeUsername, randomId, validateCredentials } from '../account/crypto';
import { isEntityType } from '../account/schema';
import { applySyncOperation } from '../account/sync';
import { SyncOperationInput } from '../account/types';

interface AdminMutationBody {
  operation_id?: string;
  mutations?: SyncOperationInput['mutations'];
}

interface AdminCreateBody {
  username?: string;
  password?: string;
  trial_days?: number;
  lifetime?: boolean;
}

interface AdminLicenseBody {
  status?: string;
  trial_days?: number;
  paid_days?: number;
  lifetime?: boolean;
}

async function accountExists(env: Env, accountId: string): Promise<boolean> {
  const row = await env.DB.prepare('SELECT 1 AS found FROM accounts WHERE id = ?')
    .bind(accountId).first<{ found: number }>();
  return Boolean(row);
}

export async function handleAdminAccountsApi(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const parts = url.pathname.split('/');
  const accountId = parts[4] ? decodeURIComponent(parts[4]) : '';
  const action = parts[5] || '';

  if (!accountId) {
    if (request.method === 'GET') return listAccounts(url, env);
    if (request.method === 'POST') return createAccount(request, env);
    return Response.json({ ok: false, error: 'طريقة غير مسموحة' }, { status: 405 });
  }

  if (!await accountExists(env, accountId)) {
    return Response.json({ ok: false, error: 'الحساب غير موجود' }, { status: 404 });
  }

  if (!action && request.method === 'GET') return getAccount(accountId, env);
  if (action === 'transfers' && parts[6] && request.method === 'POST') {
    return updateTransfer(request, accountId, decodeURIComponent(parts[6]), env);
  }
  if (action === 'records' && request.method === 'GET') return listRecords(url, accountId, env);
  if (action === 'records' && request.method === 'POST') return mutateRecords(request, accountId, env);
  if (action === 'export' && request.method === 'GET') return exportAccount(accountId, env);
  if (action === 'license' && request.method === 'POST') return updateLicense(request, accountId, env);
  if (action === 'state' && request.method === 'POST') return updateAccountState(request, accountId, env);

  return Response.json({ ok: false, error: 'المسار غير موجود' }, { status: 404 });
}

/** إنشاء حساب بلا جهاز: أول جهاز يسجّل الدخول بكلمة المرور الصحيحة يصبح جهازه النشط. */
async function createAccount(request: Request, env: Env): Promise<Response> {
  const body = await parseBoundedJson<AdminCreateBody>(request, 8 * 1024);
  if (!body) return Response.json({ ok: false, error: 'طلب غير صالح' }, { status: 400 });
  const username = String(body.username || '').trim();
  const password = String(body.password || '');
  const invalid = validateCredentials(username, password);
  if (invalid) return Response.json({ ok: false, error: invalid }, { status: 400 });

  const normalized = normalizeUsername(username);
  const exists = await env.DB.prepare('SELECT id FROM accounts WHERE username_norm = ?')
    .bind(normalized).first();
  if (exists) return Response.json({ ok: false, error: 'اسم المستخدم مستخدم مسبقًا' }, { status: 409 });

  const lifetime = body.lifetime === true;
  const trialDays = Math.max(0, Math.min(3650, Number(body.trial_days || 0)));
  const licenseStatus = lifetime ? 'owner' : trialDays > 0 ? 'trial' : 'expired';
  const now = Date.now();
  const accountId = randomId();
  const passwordData = await derivePasswordHash(password, env.PASSWORD_PEPPER);

  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO accounts
         (id, username, username_norm, password_hash, password_salt, password_iterations, status,
          account_epoch, active_device_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, 'active', 1, NULL, ?, ?)`,
      ).bind(accountId, username, normalized, passwordData.hash, passwordData.salt, passwordData.iterations, now, now),
      env.DB.prepare(
        `INSERT INTO account_licenses
         (account_id, status, trial_ends_at, paid_until, lifetime, source, updated_at)
         VALUES (?, ?, ?, 0, ?, 'admin', ?)`,
      ).bind(accountId, licenseStatus, trialDays > 0 ? now + trialDays * 86_400_000 : 0, lifetime ? 1 : 0, now),
      env.DB.prepare(
        `INSERT INTO account_audit_events
         (account_id, actor_type, actor_id, action, metadata_json, created_at)
         VALUES (?, 'admin', NULL, 'ACCOUNT_CREATED', ?, ?)`,
      ).bind(accountId, JSON.stringify({ license: licenseStatus, trial_days: trialDays }), now),
    ]);
  } catch {
    return Response.json({ ok: false, error: 'تعذر إنشاء الحساب أو أن الاسم أصبح مستخدمًا' }, { status: 409 });
  }

  return Response.json({ ok: true, account: { id: accountId, username, license: licenseStatus } });
}

async function listAccounts(url: URL, env: Env): Promise<Response> {  const q = (url.searchParams.get('q') || '').trim().slice(0, 100);
  const status = url.searchParams.get('status') || '';
  const where: string[] = [];
  const params: unknown[] = [];

  if (q) {
    where.push('(a.username LIKE ? OR a.id LIKE ? OR d.name LIKE ?)');
    params.push(`%${q}%`, `%${q}%`, `%${q}%`);
  }
  if (['pending', 'active', 'locked', 'disabled'].includes(status)) {
    where.push('a.status = ?');
    params.push(status);
  }

  const rows = await env.DB.prepare(
    `SELECT a.id, a.username, a.status, a.account_epoch, a.active_device_id,
            a.latest_change_id, a.data_schema_version, a.created_at, a.updated_at,
            l.status AS license_status, l.trial_ends_at, l.paid_until, l.lifetime,
            d.name AS device_name, d.status AS device_status, d.last_seen_at,
            (SELECT COUNT(*) FROM sync_records r WHERE r.account_id = a.id AND r.deleted_at IS NULL) AS record_count,
            (SELECT COUNT(*) FROM sync_conflicts c WHERE c.account_id = a.id AND c.status = 'open') AS conflict_count,
            (SELECT COUNT(*) FROM device_transfers t WHERE t.account_id = a.id AND t.status NOT IN ('complete','rejected','cancelled','expired')) AS transfer_count
     FROM accounts a
     LEFT JOIN account_licenses l ON l.account_id = a.id
     LEFT JOIN account_devices d ON d.id = a.active_device_id
     ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
     ORDER BY a.updated_at DESC LIMIT 100`,
  ).bind(...params).all<Record<string, unknown>>();

  return Response.json({ ok: true, accounts: rows.results });
}

async function getAccount(accountId: string, env: Env): Promise<Response> {
  const account = await env.DB.prepare(
    `SELECT a.id, a.username, a.status, a.account_epoch, a.active_device_id,
            a.latest_change_id, a.data_schema_version, a.created_at, a.updated_at,
            l.plan_id, l.status AS license_status, l.trial_ends_at, l.paid_until,
            l.lifetime, l.source, l.legacy_device_id, l.updated_at AS license_updated_at
     FROM accounts a LEFT JOIN account_licenses l ON l.account_id = a.id WHERE a.id = ?`,
  ).bind(accountId).first<Record<string, unknown>>();
  const [devices, counts, transfers] = await Promise.all([
    env.DB.prepare(
      `SELECT id, name, app_version, status, device_epoch, last_seen_at, last_sync_at, created_at, updated_at
       FROM account_devices WHERE account_id = ? ORDER BY created_at DESC`,
    ).bind(accountId).all<Record<string, unknown>>(),
    env.DB.prepare(
      `SELECT
         (SELECT COUNT(*) FROM sync_records WHERE account_id = ? AND deleted_at IS NULL) AS records,
         (SELECT COUNT(*) FROM sync_conflicts WHERE account_id = ? AND status = 'open') AS conflicts,
         (SELECT COUNT(*) FROM sync_changes WHERE account_id = ?) AS changes`,
    ).bind(accountId, accountId, accountId).first<Record<string, number>>(),
    env.DB.prepare(
      `SELECT id, source_device_id, target_device_id, status, requested_at, approved_at,
              frozen_at, snapshot_cursor, restored_at, activated_at, wipe_ack_at,
              approved_by, forced_without_source_ack, expires_at
       FROM device_transfers WHERE account_id = ? ORDER BY requested_at DESC LIMIT 20`,
    ).bind(accountId).all<Record<string, unknown>>(),
  ]);

  return Response.json({ ok: true, account, devices: devices.results, counts, transfers: transfers.results });
}

async function listRecords(url: URL, accountId: string, env: Env): Promise<Response> {
  const entityType = url.searchParams.get('entity_type') || '';
  if (entityType && !isEntityType(entityType)) {
    return Response.json({ ok: false, error: 'نوع البيانات غير صالح' }, { status: 400 });
  }
  const includeDeleted = url.searchParams.get('include_deleted') === '1';
  const params: unknown[] = [accountId];
  let where = 'account_id = ?';
  if (entityType) {
    where += ' AND entity_type = ?';
    params.push(entityType);
  }
  if (!includeDeleted) where += ' AND deleted_at IS NULL';
  params.push(500);

  const rows = await env.DB.prepare(
    `SELECT entity_type, entity_id, revision, schema_version, payload_json, deleted_at,
            updated_at, updated_by_type, updated_by_id, operation_id
     FROM sync_records WHERE ${where}
     ORDER BY entity_type ASC, entity_id ASC LIMIT ?`,
  ).bind(...params).all<{
    entity_type: string; entity_id: string; revision: number; schema_version: number;
    payload_json: string | null; deleted_at: number | null; updated_at: number;
    updated_by_type: string; updated_by_id: string; operation_id: string;
  }>();

  return Response.json({
    ok: true,
    records: rows.results.map(row => ({
      ...row,
      payload: row.payload_json ? JSON.parse(row.payload_json) : null,
      payload_json: undefined,
    })),
  });
}

async function mutateRecords(request: Request, accountId: string, env: Env): Promise<Response> {
  const body = await parseBoundedJson<AdminMutationBody>(request, 256 * 1024);
  if (!body || !Array.isArray(body.mutations) || body.mutations.length < 1 || body.mutations.length > 50) {
    return Response.json({ ok: false, error: 'عملية التعديل غير صالحة' }, { status: 400 });
  }
  const operationId = body.operation_id || `admin_${randomId()}`;
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(operationId)) {
    return Response.json({ ok: false, error: 'معرّف العملية غير صالح' }, { status: 400 });
  }

  const operation: SyncOperationInput = { operation_id: operationId, mutations: body.mutations };
  const result = await applySyncOperation(env, accountId, { type: 'admin', id: 'admin-panel' }, operation);
  if (result.status === 'accepted') {
    await env.DB.prepare(
      `INSERT INTO account_audit_events
       (account_id, actor_type, actor_id, action, target_type, target_id, metadata_json, created_at)
       VALUES (?, 'admin', 'admin-panel', 'SYNC_MUTATION', 'account', ?, ?, ?)`,
    ).bind(accountId, accountId, JSON.stringify({ operation_id: operationId }), Date.now()).run();
  }
  return Response.json({ ok: result.status === 'accepted', result }, { status: result.status === 'accepted' ? 200 : 409 });
}

async function updateTransfer(
  request: Request,
  accountId: string,
  transferId: string,
  env: Env,
): Promise<Response> {
  const body = await parseBoundedJson<{ action?: string }>(request, 8 * 1024);
  const action = body?.action || '';
  if (action !== 'approve' && action !== 'reject') {
    return Response.json({ ok: false, error: 'إجراء النقل غير صالح' }, { status: 400 });
  }
  const transfer = await env.DB.prepare(
    `SELECT id, source_device_id, target_device_id, status, expires_at
     FROM device_transfers WHERE id = ? AND account_id = ?`,
  ).bind(transferId, accountId).first<{
    id: string; source_device_id: string; target_device_id: string; status: string; expires_at: number;
  }>();
  if (!transfer) return Response.json({ ok: false, error: 'طلب النقل غير موجود' }, { status: 404 });
  if (transfer.status !== 'requested') {
    return Response.json({ ok: false, error: 'تغيّرت حالة طلب النقل' }, { status: 409 });
  }
  const now = Date.now();
  if (transfer.expires_at <= now) {
    await env.DB.prepare("UPDATE device_transfers SET status = 'expired' WHERE id = ? AND status = 'requested'")
      .bind(transfer.id).run();
    return Response.json({ ok: false, error: 'انتهت صلاحية طلب النقل' }, { status: 410 });
  }

  const assertionId = `admin-transfer:${transfer.id}`;
  try {
    if (action === 'approve') {
      await env.DB.batch([
        env.DB.prepare(
          `INSERT INTO sync_assertions(id, ok)
           SELECT ?, CASE WHEN EXISTS(
             SELECT 1 FROM device_transfers t
             JOIN accounts a ON a.id = t.account_id
             JOIN account_devices s ON s.id = t.source_device_id
             JOIN account_devices d ON d.id = t.target_device_id
             WHERE t.id = ? AND t.account_id = ? AND t.status = 'requested'
               AND t.expires_at > ? AND a.active_device_id = s.id
               AND s.status = 'active' AND d.status = 'pending'
           ) THEN 1 ELSE 0 END`,
        ).bind(assertionId, transfer.id, accountId, now),
        env.DB.prepare(
          `UPDATE device_transfers SET status = 'approved', approved_at = ?, approved_by = 'admin-panel'
           WHERE id = ? AND status = 'requested'`,
        ).bind(now, transfer.id),
        env.DB.prepare(
          `INSERT INTO account_audit_events
           (account_id, actor_type, actor_id, action, target_type, target_id, created_at)
           VALUES (?, 'admin', 'admin-panel', 'TRANSFER_APPROVED', 'transfer', ?, ?)`,
        ).bind(accountId, transfer.id, now),
        env.DB.prepare('DELETE FROM sync_assertions WHERE id = ?').bind(assertionId),
      ]);
    } else {
      await env.DB.batch([
        env.DB.prepare("UPDATE device_transfers SET status = 'rejected', approved_by = 'admin-panel' WHERE id = ? AND status = 'requested'")
          .bind(transfer.id),
        env.DB.prepare("UPDATE account_devices SET status = 'revoked', device_epoch = device_epoch + 1, updated_at = ? WHERE id = ? AND status = 'pending'")
          .bind(now, transfer.target_device_id),
        env.DB.prepare(
          `INSERT INTO account_audit_events
           (account_id, actor_type, actor_id, action, target_type, target_id, created_at)
           VALUES (?, 'admin', 'admin-panel', 'TRANSFER_REJECTED', 'transfer', ?, ?)`,
        ).bind(accountId, transfer.id, now),
      ]);
    }
  } catch {
    return Response.json({ ok: false, error: 'تغيّرت حالة الحساب أو الجهاز' }, { status: 409 });
  }
  return Response.json({ ok: true, status: action === 'approve' ? 'approved' : 'rejected' });
}

async function updateAccountState(request: Request, accountId: string, env: Env): Promise<Response> {
  const body = await parseBoundedJson<{ status?: string }>(request, 16 * 1024);
  const status = body?.status || '';
  if (!['active', 'locked', 'disabled'].includes(status)) {
    return Response.json({ ok: false, error: 'حالة الحساب غير صالحة' }, { status: 400 });
  }
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('UPDATE accounts SET status = ?, account_epoch = account_epoch + 1, updated_at = ? WHERE id = ?')
      .bind(status, now, accountId),
    env.DB.prepare('UPDATE refresh_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE account_id = ?')
      .bind(now, accountId),
    env.DB.prepare(
      `INSERT INTO account_audit_events
       (account_id, actor_type, actor_id, action, target_type, target_id, metadata_json, created_at)
       VALUES (?, 'admin', 'admin-panel', 'ACCOUNT_STATE_CHANGED', 'account', ?, ?, ?)`,
    ).bind(accountId, accountId, JSON.stringify({ status }), now),
  ]);
  return Response.json({ ok: true, status });
}

/** تمديد الاشتراك من الحساب: التجربة والمدفوع يمتدان من الأبعد بين الآن والنهاية الحالية. */
async function updateLicense(request: Request, accountId: string, env: Env): Promise<Response> {
  const body = await parseBoundedJson<AdminLicenseBody>(request, 8 * 1024);
  const status = String(body?.status || '');
  if (!['trial', 'paid', 'owner', 'expired'].includes(status)) {
    return Response.json({ ok: false, error: 'حالة الترخيص غير صالحة' }, { status: 400 });
  }
  const now = Date.now();
  const trialDays = Math.max(0, Math.min(3650, Number(body?.trial_days || 0)));
  const paidDays = Math.max(0, Math.min(3650, Number(body?.paid_days || 0)));
  const current = await env.DB.prepare(
    'SELECT trial_ends_at, paid_until, lifetime FROM account_licenses WHERE account_id = ?',
  ).bind(accountId).first<{ trial_ends_at: number; paid_until: number; lifetime: number }>();

  let trialEnds = current?.trial_ends_at || 0;
  let paidUntil = current?.paid_until || 0;
  let lifetime = current?.lifetime || 0;
  let licenseStatus = status;

  if (status === 'trial') {
    trialEnds = Math.max(now, trialEnds) + trialDays * 86_400_000;
    paidUntil = 0;
    lifetime = 0;
  } else if (status === 'paid') {
    paidUntil = Math.max(now, paidUntil) + paidDays * 86_400_000;
    lifetime = 0;
  } else if (status === 'owner') {
    lifetime = 1;
    paidUntil = 0;
  } else if (status === 'expired') {
    trialEnds = 0;
    paidUntil = 0;
    lifetime = 0;
    licenseStatus = 'expired';
  }

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO account_licenses
       (account_id, status, trial_ends_at, paid_until, lifetime, source, updated_at)
       VALUES (?, ?, ?, ?, ?, 'admin', ?)
       ON CONFLICT(account_id) DO UPDATE SET
         status = excluded.status, trial_ends_at = excluded.trial_ends_at,
         paid_until = excluded.paid_until, lifetime = excluded.lifetime,
         source = excluded.source, updated_at = excluded.updated_at`,
    ).bind(accountId, licenseStatus, trialEnds, paidUntil, lifetime, now),
    env.DB.prepare(
      `INSERT INTO account_audit_events
       (account_id, actor_type, actor_id, action, target_type, target_id, metadata_json, created_at)
       VALUES (?, 'admin', 'admin-panel', 'LICENSE_UPDATED', 'account', ?, ?, ?)`,
    ).bind(accountId, accountId, JSON.stringify({ status: licenseStatus, trial_ends_at: trialEnds, paid_until: paidUntil, lifetime }), now),
  ]);
  return Response.json({ ok: true, license: { status: licenseStatus, trial_ends_at: trialEnds, paid_until: paidUntil, lifetime } });
}

async function exportAccount(accountId: string, env: Env): Promise<Response> {  const account = await env.DB.prepare(
    `SELECT a.id, a.username, a.status, a.account_epoch, a.latest_change_id,
            a.data_schema_version, a.created_at, a.updated_at,
            l.plan_id, l.status AS license_status, l.trial_ends_at, l.paid_until,
            l.lifetime, l.source, l.updated_at AS license_updated_at
     FROM accounts a LEFT JOIN account_licenses l ON l.account_id = a.id WHERE a.id = ?`,
  ).bind(accountId).first<Record<string, unknown>>();
  const records = await env.DB.prepare(
    `SELECT entity_type, entity_id, revision, schema_version, payload_json, deleted_at,
            updated_at, updated_by_type, operation_id
     FROM sync_records WHERE account_id = ? ORDER BY entity_type, entity_id`,
  ).bind(accountId).all<{
    entity_type: string; entity_id: string; revision: number; schema_version: number;
    payload_json: string | null; deleted_at: number | null; updated_at: number;
    updated_by_type: string; operation_id: string;
  }>();
  return Response.json({
    ok: true,
    exported_at: Date.now(),
    account,
    records: records.results.map(row => ({
      ...row,
      payload: row.payload_json ? JSON.parse(row.payload_json) : null,
      payload_json: undefined,
    })),
  }, { headers: { 'Cache-Control': 'private, no-store' } });
}
