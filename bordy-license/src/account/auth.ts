import { Env } from '../types';
import { checkRateLimit } from '../rate-limit';
import { errorResponse, isValidDeviceId, jsonResponse, parseBoundedJson } from '../utils';
import {
  ACCESS_TOKEN_TTL_MS,
  REFRESH_TOKEN_TTL_MS,
  derivePasswordHash,
  hashPrivateIdentifier,
  normalizeUsername,
  randomId,
  randomSecret,
  sha256Base64url,
  signAccessToken,
  validateCredentials,
  verifyAccessToken,
  verifyPassword,
} from './crypto';
import { verifyLegacyLicenseToken } from './license-proof';
import { AccessClaims, AccountDeviceRow, AccountRow, AuthContext, RefreshSessionRow } from './types';

interface ClaimInput {
  username: string;
  password: string;
  device_id: string;
  install_id: string;
  license_token: string;
  device_name?: string;
  app_version?: number;
}

interface LoginInput {
  username: string;
  password: string;
  device_id: string;
  install_id: string;
  device_name?: string;
  app_version?: number;
}

interface RefreshInput { refresh_token: string }

interface SessionMaterial {
  sessionId: string;
  familyId: string;
  refreshHash: string;
  now: number;
  response: {
    access_token: string;
    refresh_token: string;
    access_expires_at: number;
    refresh_expires_at: number;
  };
}

function clientIp(request: Request): string {
  return request.headers.get('CF-Connecting-IP') || 'unknown';
}

async function audit(
  env: Env,
  accountId: string | null,
  actorType: 'device' | 'admin' | 'system',
  actorId: string | null,
  action: string,
  request?: Request,
  metadata?: Record<string, unknown>,
): Promise<void> {
  const ipHash = request ? await hashPrivateIdentifier(clientIp(request), env.PASSWORD_PEPPER) : null;
  await env.DB.prepare(
    `INSERT INTO account_audit_events
     (account_id, actor_type, actor_id, action, metadata_json, ip_hash, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    accountId,
    actorType,
    actorId,
    action,
    metadata ? JSON.stringify(metadata) : null,
    ipHash,
    Date.now(),
  ).run();
}

async function buildSession(
  env: Env,
  account: AccountRow,
  device: AccountDeviceRow,
  familyId?: string,
): Promise<SessionMaterial> {
  const now = Date.now();
  const sessionId = randomId();
  const family = familyId || randomId();
  const refreshToken = randomSecret(32);
  const refreshHash = await sha256Base64url(refreshToken);
  const accessExpires = now + ACCESS_TOKEN_TTL_MS;
  const refreshExpires = now + REFRESH_TOKEN_TTL_MS;
  const claims: AccessClaims = {
    v: 1,
    aud: 'bordy-account',
    sid: sessionId,
    aid: account.id,
    did: device.id,
    ae: account.account_epoch,
    de: device.device_epoch,
    iat: now,
    exp: accessExpires,
  };
  return {
    sessionId,
    familyId: family,
    refreshHash,
    now,
    response: {
      access_token: await signAccessToken(claims, env.ACCOUNT_TOKEN_SECRET),
      refresh_token: refreshToken,
      access_expires_at: accessExpires,
      refresh_expires_at: refreshExpires,
    },
  };
}

function insertSessionStatement(
  env: Env,
  account: AccountRow,
  device: AccountDeviceRow,
  session: SessionMaterial,
): D1PreparedStatement {
  return env.DB.prepare(
    `INSERT INTO refresh_sessions
     (id, family_id, account_id, device_id, token_hash, account_epoch, device_epoch, expires_at, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    session.sessionId, session.familyId, account.id, device.id, session.refreshHash,
    account.account_epoch, device.device_epoch, session.response.refresh_expires_at, session.now,
  );
}

async function issueSession(
  env: Env,
  account: AccountRow,
  device: AccountDeviceRow,
  familyId?: string,
): Promise<SessionMaterial['response']> {
  const session = await buildSession(env, account, device, familyId);
  await env.DB.batch([
    insertSessionStatement(env, account, device, session),
    env.DB.prepare('UPDATE account_devices SET last_seen_at = ?, updated_at = ? WHERE id = ?')
      .bind(session.now, session.now, device.id),
  ]);
  return session.response;
}

async function authenticateAccountToken(request: Request, env: Env): Promise<AuthContext | Response> {
  const header = request.headers.get('Authorization') || '';
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) return errorResponse('يلزم تسجيل الدخول', 401, 'AUTH_REQUIRED');
  const claims = await verifyAccessToken(match[1], env.ACCOUNT_TOKEN_SECRET);
  if (!claims) return errorResponse('انتهت جلسة الدخول', 401, 'AUTH_REQUIRED');

  const [account, device] = await Promise.all([
    env.DB.prepare('SELECT * FROM accounts WHERE id = ?').bind(claims.aid).first<AccountRow>(),
    env.DB.prepare('SELECT * FROM account_devices WHERE id = ? AND account_id = ?')
      .bind(claims.did, claims.aid).first<AccountDeviceRow>(),
  ]);
  if (!account || !device) return errorResponse('الجلسة غير صالحة', 401, 'AUTH_REQUIRED');
  if (account.status !== 'active') return errorResponse('الحساب موقوف', 403, 'ACCOUNT_LOCKED');
  if (account.account_epoch !== claims.ae) return errorResponse('تغيّرت صلاحية الحساب', 401, 'ACCOUNT_EPOCH_CHANGED');
  if (device.device_epoch !== claims.de) return errorResponse('تغيّرت صلاحية الجهاز', 401, 'DEVICE_EPOCH_CHANGED');
  if (device.status === 'wipe_pending') return errorResponse('يجب مسح بيانات هذا الجهاز', 409, 'WIPE_REQUIRED');
  if (account.active_device_id !== device.id) return errorResponse('هذا الجهاز غير مخوّل بالمزامنة', 403, 'DEVICE_NOT_ACTIVE');
  return { account, device, claims };
}

export async function requireAccountAuth(request: Request, env: Env): Promise<AuthContext | Response> {
  const auth = await authenticateAccountToken(request, env);
  if (auth instanceof Response) return auth;
  if (auth.device.status !== 'active') return errorResponse('هذا الجهاز غير نشط', 403, 'DEVICE_NOT_ACTIVE');
  return auth;
}

export async function requireAccountReadAuth(request: Request, env: Env): Promise<AuthContext | Response> {
  const auth = await authenticateAccountToken(request, env);
  if (auth instanceof Response) return auth;
  if (auth.device.status !== 'active' && auth.device.status !== 'restoring') {
    return errorResponse('هذا الجهاز غير مخوّل بالقراءة', 403, 'DEVICE_NOT_ACTIVE');
  }
  return auth;
}

export async function handleClaimLegacy(request: Request, env: Env): Promise<Response> {
  const body = await parseBoundedJson<ClaimInput>(request, 32 * 1024);
  if (!body) return errorResponse('طلب غير صالح', 400, 'VALIDATION_FAILED');
  const username = String(body.username || '').trim();
  const password = String(body.password || '');
  const validation = validateCredentials(username, password);
  if (validation) return errorResponse(validation, 400, 'VALIDATION_FAILED');
  if (!isValidDeviceId(body.device_id || '') || !body.install_id || body.install_id.length > 200) {
    return errorResponse('هوية الجهاز غير صالحة', 400, 'VALIDATION_FAILED');
  }

  const ip = clientIp(request);
  if (!await checkRateLimit(env.DB, `account_claim:${ip}`, 10)) {
    return errorResponse('محاولات كثيرة، حاول لاحقًا', 429, 'RATE_LIMITED');
  }
  const proof = await verifyLegacyLicenseToken(body.license_token || '', body.device_id, env.LICENSE_PRIVATE_KEY);
  if (!proof) return errorResponse('تعذر إثبات ملكية هذا الترخيص', 403, 'LICENSE_PROOF_INVALID');

  const legacyDevice = await env.DB.prepare('SELECT * FROM devices WHERE device_id = ?')
    .bind(body.device_id).first<{
      device_id: string; account_id: string | null; trial_ends_at: number | null;
      paid_until: number; lifetime: number; blocked: number;
    }>();
  if (!legacyDevice || legacyDevice.blocked) return errorResponse('الجهاز غير مؤهل للمطالبة', 403, 'LICENSE_NOT_ELIGIBLE');
  if (legacyDevice.account_id) return errorResponse('هذا الترخيص مربوط بحساب مسبقًا', 409, 'ALREADY_CLAIMED');

  const normalized = normalizeUsername(username);
  const exists = await env.DB.prepare('SELECT id FROM accounts WHERE username_norm = ?').bind(normalized).first();
  if (exists) return errorResponse('اسم المستخدم مستخدم مسبقًا', 409, 'USERNAME_TAKEN');

  const passwordData = await derivePasswordHash(password, env.PASSWORD_PEPPER);
  const now = Date.now();
  const accountId = randomId();
  const accountDeviceId = randomId();
  const fingerprintHash = await hashPrivateIdentifier(body.device_id, env.PASSWORD_PEPPER);
  const installHash = await hashPrivateIdentifier(body.install_id, env.PASSWORD_PEPPER);
  const licenseStatus = legacyDevice.lifetime ? 'owner'
    : legacyDevice.paid_until > now ? 'paid'
    : (legacyDevice.trial_ends_at || 0) > now ? 'trial' : 'expired';

  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO accounts
         (id, username, username_norm, password_hash, password_salt, password_iterations, status,
          account_epoch, active_device_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, 'active', 1, ?, ?, ?)`,
      ).bind(accountId, username, normalized, passwordData.hash, passwordData.salt, passwordData.iterations, accountDeviceId, now, now),
      env.DB.prepare(
        `INSERT INTO account_devices
         (id, account_id, fingerprint_hash, install_id_hash, name, app_version, status, device_epoch, last_seen_at, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, 'active', 1, ?, ?, ?)`,
      ).bind(accountDeviceId, accountId, fingerprintHash, installHash, String(body.device_name || '').slice(0, 100), Number(body.app_version || 0), now, now, now),
      env.DB.prepare(
        `INSERT INTO account_licenses
         (account_id, status, trial_ends_at, paid_until, lifetime, source, legacy_device_id, updated_at)
         VALUES (?, ?, ?, ?, ?, 'legacy_claim', ?, ?)`,
      ).bind(accountId, licenseStatus, legacyDevice.trial_ends_at || 0, legacyDevice.paid_until, legacyDevice.lifetime, body.device_id, now),
      env.DB.prepare('UPDATE devices SET account_id = ? WHERE device_id = ? AND account_id IS NULL')
        .bind(accountId, body.device_id),
      env.DB.prepare('UPDATE orders SET account_id = ? WHERE device_id = ? AND account_id IS NULL')
        .bind(accountId, body.device_id),
    ]);
  } catch {
    return errorResponse('تعذر إنشاء الحساب أو أن الاسم أصبح مستخدمًا', 409, 'CLAIM_FAILED');
  }

  const account = await env.DB.prepare('SELECT * FROM accounts WHERE id = ?').bind(accountId).first<AccountRow>();
  const device = await env.DB.prepare('SELECT * FROM account_devices WHERE id = ?').bind(accountDeviceId).first<AccountDeviceRow>();
  if (!account || !device) return errorResponse('تعذر إكمال الحساب', 500, 'CLAIM_FAILED');
  const session = await issueSession(env, account, device);
  await audit(env, accountId, 'device', accountDeviceId, 'ACCOUNT_CLAIMED', request, { legacy_device_id: body.device_id });
  return jsonResponse({ ok: true, account: publicAccount(account, device), ...session });
}

export async function handleAccountLogin(request: Request, env: Env): Promise<Response> {
  const body = await parseBoundedJson<LoginInput>(request, 32 * 1024);
  if (!body) return errorResponse('طلب غير صالح', 400, 'VALIDATION_FAILED');
  const normalized = normalizeUsername(String(body.username || ''));
  const password = String(body.password || '');
  const ip = clientIp(request);
  const key = await hashPrivateIdentifier(`${normalized}:${ip}`, env.PASSWORD_PEPPER);
  if (!await checkRateLimit(env.DB, `account_login:${key}`, 8)) {
    return errorResponse('محاولات كثيرة، حاول لاحقًا', 429, 'RATE_LIMITED');
  }

  const account = await env.DB.prepare('SELECT * FROM accounts WHERE username_norm = ?')
    .bind(normalized).first<AccountRow>();
  const valid = account && await verifyPassword(
    password,
    env.PASSWORD_PEPPER,
    account.password_salt,
    account.password_hash,
    account.password_iterations,
  );
  if (!account || !valid) return errorResponse('اسم المستخدم أو كلمة المرور غير صحيحة', 401, 'INVALID_CREDENTIALS');
  if (account.status !== 'active') return errorResponse('الحساب موقوف', 403, 'ACCOUNT_LOCKED');
  if (!isValidDeviceId(body.device_id || '') || !body.install_id || body.install_id.length > 200) {
    return errorResponse('هوية الجهاز غير صالحة', 400, 'VALIDATION_FAILED');
  }

  const installHash = await hashPrivateIdentifier(body.install_id, env.PASSWORD_PEPPER);
  let device = await env.DB.prepare('SELECT * FROM account_devices WHERE account_id = ? AND install_id_hash = ?')
    .bind(account.id, installHash).first<AccountDeviceRow>();
  if (!device) {
    const now = Date.now();
    const targetId = randomId();
    const fingerprintHash = await hashPrivateIdentifier(body.device_id, env.PASSWORD_PEPPER);
    await env.DB.prepare(
      `INSERT INTO account_devices
       (id, account_id, fingerprint_hash, install_id_hash, name, app_version, status, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)`,
    ).bind(targetId, account.id, fingerprintHash, installHash, String(body.device_name || '').slice(0, 100), Number(body.app_version || 0), now, now).run();
    device = await env.DB.prepare('SELECT * FROM account_devices WHERE id = ?').bind(targetId).first<AccountDeviceRow>();
  }
  if (!device) return errorResponse('تعذر تسجيل الجهاز', 500, 'DEVICE_CREATE_FAILED');
  if (device.status === 'revoked' || device.status === 'wiped') {
    const now = Date.now();
    await env.DB.prepare(
      `UPDATE account_devices
       SET status = 'pending', device_epoch = device_epoch + 1, name = ?, app_version = ?, updated_at = ?
       WHERE id = ? AND status IN ('revoked','wiped')`,
    ).bind(String(body.device_name || '').slice(0, 100), Number(body.app_version || 0), now, device.id).run();
    device = await env.DB.prepare('SELECT * FROM account_devices WHERE id = ?').bind(device.id).first<AccountDeviceRow>();
    if (!device) return errorResponse('تعذر إعادة تسجيل الجهاز', 500, 'DEVICE_CREATE_FAILED');
  }

  if (device.status === 'restoring' && account.active_device_id === device.id) {
    const transfer = await env.DB.prepare(
      `SELECT id, status, snapshot_cursor FROM device_transfers
       WHERE account_id = ? AND target_device_id = ? AND status = 'target_restoring'
       ORDER BY requested_at DESC LIMIT 1`,
    ).bind(account.id, device.id).first<{ id: string; status: string; snapshot_cursor: number | null }>();
    if (!transfer) return errorResponse('حالة الاستعادة غير مكتملة', 409, 'TRANSFER_STATE_CHANGED');
    const session = await issueSession(env, account, device);
    await audit(env, account.id, 'device', device.id, 'RESTORE_LOGIN_SUCCEEDED', request);
    return jsonResponse({
      ok: true,
      restore_required: true,
      transfer_id: transfer.id,
      snapshot_cursor: transfer.snapshot_cursor || 0,
      account: publicAccount(account, device),
      ...session,
    });
  }

  if (['freeze_pending', 'frozen', 'wipe_pending'].includes(device.status)) {
    const sourceTransfer = await env.DB.prepare(
      `SELECT id, status FROM device_transfers
       WHERE account_id = ? AND source_device_id = ?
         AND status NOT IN ('complete','rejected','cancelled','expired')
       ORDER BY requested_at DESC LIMIT 1`,
    ).bind(account.id, device.id).first<{ id: string; status: string }>();
    if (device.status === 'wipe_pending') {
      return jsonResponse({
        ok: false,
        code: 'WIPE_REQUIRED',
        error: 'اكتملت الاستعادة على الجهاز الجديد ويجب مسح بيانات هذا الجهاز',
        transfer_id: sourceTransfer?.id || null,
        transfer_status: sourceTransfer?.status || 'source_wipe_pending',
      }, 409);
    }
    return jsonResponse({
      ok: false,
      code: 'TRANSFER_IN_PROGRESS',
      error: 'نقل الحساب إلى الجهاز الجديد قيد التنفيذ',
      transfer_id: sourceTransfer?.id || null,
      transfer_status: sourceTransfer?.status || null,
    }, 409);
  }

  // An account created without an active device (admin provisioning) adopts the
  // first device that authenticates with the right password.
  if (!account.active_device_id) {
    const now = Date.now();
    await env.DB.batch([
      env.DB.prepare('UPDATE accounts SET active_device_id = ?, updated_at = ? WHERE id = ? AND active_device_id IS NULL')
        .bind(device.id, now, account.id),
      env.DB.prepare("UPDATE account_devices SET status = 'active', last_seen_at = ?, updated_at = ? WHERE id = ? AND status = 'pending'")
        .bind(now, now, device.id),
    ]);
    account.active_device_id = device.id;
    device.status = 'active';
    await audit(env, account.id, 'device', device.id, 'DEVICE_ADOPTED', request);
  }

  if (account.active_device_id !== device.id || device.status !== 'active') {
    const existingTransfer = account.active_device_id
      ? await env.DB.prepare(
          `SELECT id, status FROM device_transfers
           WHERE account_id = ? AND target_device_id = ?
           AND status IN ('requested','approved','source_frozen','target_restoring','target_confirmed')
           ORDER BY requested_at DESC LIMIT 1`,
        ).bind(account.id, device.id).first<{ id: string; status: string }>()
      : null;
    let transfer = existingTransfer;
    if (!transfer && account.active_device_id) {
      const transferId = randomId();
      const now = Date.now();
      await env.DB.prepare(
        `INSERT INTO device_transfers
         (id, account_id, source_device_id, target_device_id, status, requested_at, expires_at)
         VALUES (?, ?, ?, ?, 'requested', ?, ?)`,
      ).bind(transferId, account.id, account.active_device_id, device.id, now, now + 7 * 24 * 60 * 60 * 1000).run();
      transfer = { id: transferId, status: 'requested' };
      await audit(env, account.id, 'device', device.id, 'TRANSFER_REQUESTED', request);
    }
    return jsonResponse({
      ok: false,
      code: 'TRANSFER_REQUIRED',
      error: 'الحساب مربوط بجهاز آخر ويحتاج موافقة النقل',
      transfer_id: transfer?.id || null,
      transfer_status: transfer?.status || null,
    }, 409);
  }

  const session = await issueSession(env, account, device);
  await audit(env, account.id, 'device', device.id, 'LOGIN_SUCCEEDED', request);
  return jsonResponse({ ok: true, account: publicAccount(account, device), ...session });
}

export async function handleAccountRefresh(request: Request, env: Env): Promise<Response> {
  const body = await parseBoundedJson<RefreshInput>(request, 16 * 1024);
  const raw = String(body?.refresh_token || '');
  if (!raw) return errorResponse('رمز التجديد مطلوب', 401, 'AUTH_REQUIRED');
  const hash = await sha256Base64url(raw);
  const session = await env.DB.prepare('SELECT * FROM refresh_sessions WHERE token_hash = ?')
    .bind(hash).first<RefreshSessionRow>();
  if (!session) return errorResponse('جلسة غير صالحة', 401, 'AUTH_REQUIRED');

  if (session.used_at || session.revoked_at) {
    await env.DB.prepare('UPDATE refresh_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE family_id = ?')
      .bind(Date.now(), session.family_id).run();
    return errorResponse('اكتُشف استخدام مكرر للجلسة، سجّل الدخول من جديد', 401, 'TOKEN_REPLAYED');
  }
  if (session.expires_at <= Date.now()) return errorResponse('انتهت جلسة الدخول', 401, 'AUTH_REQUIRED');

  const [account, device] = await Promise.all([
    env.DB.prepare('SELECT * FROM accounts WHERE id = ?').bind(session.account_id).first<AccountRow>(),
    env.DB.prepare('SELECT * FROM account_devices WHERE id = ?').bind(session.device_id).first<AccountDeviceRow>(),
  ]);
  if (!account || !device || account.status !== 'active') return errorResponse('الحساب غير متاح', 403, 'ACCOUNT_LOCKED');
  if (session.account_epoch !== account.account_epoch || session.device_epoch !== device.device_epoch) {
    return errorResponse('تغيّرت صلاحية الجلسة', 401, 'DEVICE_EPOCH_CHANGED');
  }
  if ((device.status !== 'active' && device.status !== 'restoring') || account.active_device_id !== device.id) {
    return errorResponse(device.status === 'wipe_pending' ? 'يجب مسح بيانات هذا الجهاز' : 'الجهاز غير نشط', 403,
      device.status === 'wipe_pending' ? 'WIPE_REQUIRED' : 'DEVICE_NOT_ACTIVE');
  }

  const next = await buildSession(env, account, device, session.family_id);
  const assertionId = `refresh:${session.id}`;
  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO sync_assertions(id, ok)
         SELECT ?, CASE WHEN EXISTS(
           SELECT 1 FROM refresh_sessions WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL
         ) THEN 1 ELSE 0 END`,
      ).bind(assertionId, session.id),
      insertSessionStatement(env, account, device, next),
      env.DB.prepare(
        'UPDATE refresh_sessions SET used_at = ?, replaced_by = ? WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL',
      ).bind(next.now, next.sessionId, session.id),
      env.DB.prepare('UPDATE account_devices SET last_seen_at = ?, updated_at = ? WHERE id = ?')
        .bind(next.now, next.now, device.id),
      env.DB.prepare('DELETE FROM sync_assertions WHERE id = ?').bind(assertionId),
    ]);
  } catch {
    await env.DB.prepare('UPDATE refresh_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE family_id = ?')
      .bind(Date.now(), session.family_id).run();
    return errorResponse('اكتُشف استخدام مكرر للجلسة، سجّل الدخول من جديد', 401, 'TOKEN_REPLAYED');
  }
  return jsonResponse({ ok: true, account: publicAccount(account, device), ...next.response });
}

export async function handleAccountLogout(request: Request, env: Env): Promise<Response> {
  const body = await parseBoundedJson<RefreshInput>(request, 16 * 1024);
  const raw = String(body?.refresh_token || '');
  if (raw) {
    const hash = await sha256Base64url(raw);
    const session = await env.DB.prepare('SELECT family_id FROM refresh_sessions WHERE token_hash = ?')
      .bind(hash).first<{ family_id: string }>();
    if (session) await env.DB.prepare('UPDATE refresh_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE family_id = ?')
      .bind(Date.now(), session.family_id).run();
  }
  return jsonResponse({ ok: true });
}

export async function handleGetAccount(request: Request, env: Env): Promise<Response> {
  const auth = await requireAccountAuth(request, env);
  if (auth instanceof Response) return auth;
  const license = await env.DB.prepare('SELECT * FROM account_licenses WHERE account_id = ?')
    .bind(auth.account.id).first();
  return jsonResponse({ ok: true, account: publicAccount(auth.account, auth.device), license });
}

function publicAccount(account: AccountRow, device: AccountDeviceRow): Record<string, unknown> {
  return {
    id: account.id,
    username: account.username,
    status: account.status,
    account_epoch: account.account_epoch,
    latest_change_id: account.latest_change_id,
    data_schema_version: account.data_schema_version,
    device: {
      id: device.id,
      status: device.status,
      device_epoch: device.device_epoch,
      name: device.name,
      last_sync_at: device.last_sync_at,
    },
  };
}
