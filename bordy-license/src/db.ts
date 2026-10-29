import { DeviceRow, OrderRow, CodeRow, SettingRow, PlanRow, EventRow } from './types';

/**
 * Get a single setting value by key.
 */
export async function getSetting(db: D1Database, key: string): Promise<string | null> {
  const row = await db.prepare('SELECT v FROM settings WHERE k = ?').bind(key).first<SettingRow>();
  return row ? row.v : null;
}

/**
 * Get multiple settings at once.
 */
export async function getSettings(db: D1Database, keys: string[]): Promise<Record<string, string>> {
  const result: Record<string, string> = {};
  const placeholders = keys.map(() => '?').join(',');
  const rows = await db.prepare(`SELECT k, v FROM settings WHERE k IN (${placeholders})`).bind(...keys).all<SettingRow>();
  for (const row of rows.results) {
    result[row.k] = row.v;
  }
  return result;
}

/**
 * Update a setting value.
 */
export async function setSetting(db: D1Database, key: string, value: string): Promise<void> {
  await db.prepare('INSERT OR REPLACE INTO settings (k, v) VALUES (?, ?)').bind(key, value).run();
}

/**
 * Get a device by device_id.
 */
export async function getDevice(db: D1Database, deviceId: string): Promise<DeviceRow | null> {
  return db.prepare('SELECT * FROM devices WHERE device_id = ?').bind(deviceId).first<DeviceRow>();
}

/**
 * Insert a new device.
 */
export async function insertDevice(db: D1Database, device: {
  device_id: string;
  first_seen: number;
  last_seen: number;
  trial_started_at: number;
  trial_ends_at: number;
  app_ver: number;
  model: string;
  sdk: number;
  cert_hash: string;
}): Promise<void> {
  await db.prepare(
    `INSERT INTO devices (device_id, first_seen, last_seen, trial_started_at, trial_ends_at, app_ver, model, sdk, cert_hash)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    device.device_id, device.first_seen, device.last_seen,
    device.trial_started_at, device.trial_ends_at,
    device.app_ver, device.model, device.sdk, device.cert_hash
  ).run();
}

/**
 * Update device metadata (only if >24h since last update to save writes).
 */
export async function touchDevice(db: D1Database, deviceId: string, data: {
  app_ver: number;
  model: string;
  sdk: number;
  cert_hash: string;
}, now: number): Promise<void> {
  await db.prepare(
    `UPDATE devices SET last_seen = ?, app_ver = ?, model = ?, sdk = ?, cert_hash = ?
     WHERE device_id = ? AND (last_seen IS NULL OR last_seen < ?)`
  ).bind(now, data.app_ver, data.model, data.sdk, data.cert_hash, deviceId, now - 86400000).run();
}

/**
 * Log an event.
 */
export async function logEvent(db: D1Database, deviceId: string | null, type: string, detail?: string, actor?: string): Promise<void> {
  await db.prepare(
    'INSERT INTO events (ts, device_id, type, detail, actor) VALUES (?, ?, ?, ?, ?)'
  ).bind(Date.now(), deviceId, type, detail || null, actor || null).run();
}

/**
 * Get active plans sorted by sort order.
 */
export async function getActivePlans(db: D1Database): Promise<PlanRow[]> {
  const result = await db.prepare('SELECT * FROM plans WHERE active = 1 ORDER BY sort ASC').all<PlanRow>();
  return result.results;
}

/**
 * Get a plan by ID.
 */
export async function getPlan(db: D1Database, planId: string): Promise<PlanRow | null> {
  return db.prepare('SELECT * FROM plans WHERE id = ?').bind(planId).first<PlanRow>();
}

/**
 * Get an order by ID.
 */
export async function getOrder(db: D1Database, orderId: string): Promise<OrderRow | null> {
  return db.prepare('SELECT * FROM orders WHERE id = ?').bind(orderId).first<OrderRow>();
}

/**
 * Get a code by code string.
 */
export async function getCode(db: D1Database, code: string): Promise<CodeRow | null> {
  return db.prepare('SELECT * FROM codes WHERE code = ?').bind(code).first<CodeRow>();
}

/**
 * Check how many times a device used a specific code.
 */
export async function getCodeDeviceUsage(db: D1Database, code: string, deviceId: string): Promise<number> {
  const row = await db.prepare(
    `SELECT COUNT(*) as cnt FROM code_redemptions WHERE code = ? AND device_id = ? AND state IN ('RESERVED','CONSUMED')`
  ).bind(code, deviceId).first<{ cnt: number }>();
  return row?.cnt ?? 0;
}

/**
 * Reserve a code for an order.
 * The usage limit is enforced inside the UPDATE so two concurrent orders cannot both
 * take the last available use. Returns false when the code is no longer available.
 */
export async function reserveCode(db: D1Database, code: string, deviceId: string, orderId: string): Promise<boolean> {
  const update = await db.prepare(
    `UPDATE codes SET reserved_count = reserved_count + 1
     WHERE code = ? AND active = 1 AND (max_uses IS NULL OR used_count + reserved_count < max_uses)`
  ).bind(code).run();

  if (!update.meta.changes) return false;

  try {
    await db.prepare(
      'INSERT INTO code_redemptions (code, device_id, order_id, state, created_at) VALUES (?, ?, ?, ?, ?)'
    ).bind(code, deviceId, orderId, 'RESERVED', Date.now()).run();
  } catch (_) {
    // Reservation row already exists for this device (unique index) — undo the counter.
    await db.prepare('UPDATE codes SET reserved_count = MAX(0, reserved_count - 1) WHERE code = ?').bind(code).run();
    return false;
  }

  return true;
}

/**
 * Consume a reserved code (mark as used).
 */
export async function consumeCode(db: D1Database, code: string, orderId: string): Promise<void> {
  await db.batch([
    db.prepare('UPDATE codes SET used_count = used_count + 1, reserved_count = MAX(0, reserved_count - 1) WHERE code = ?').bind(code),
    db.prepare(`UPDATE code_redemptions SET state = 'CONSUMED' WHERE code = ? AND order_id = ? AND state = 'RESERVED'`).bind(code, orderId),
  ]);
}

/**
 * Release a reserved code (e.g. on order cancellation/expiry).
 */
export async function releaseCode(db: D1Database, code: string, orderId: string): Promise<void> {
  await db.batch([
    db.prepare('UPDATE codes SET reserved_count = MAX(0, reserved_count - 1) WHERE code = ?').bind(code),
    db.prepare(`UPDATE code_redemptions SET state = 'RELEASED' WHERE code = ? AND order_id = ? AND state = 'RESERVED'`).bind(code, orderId),
  ]);
}

/**
 * Find auto-apply code that is currently active.
 */
export async function getAutoApplyCode(db: D1Database, now: number): Promise<CodeRow | null> {
  return db.prepare(
    `SELECT * FROM codes WHERE auto_apply = 1 AND active = 1 AND starts_at <= ? AND ends_at > ?
     AND (max_uses IS NULL OR (used_count + reserved_count) < max_uses)
     ORDER BY value DESC LIMIT 1`
  ).bind(now, now).first<CodeRow>();
}

/**
 * Get pending/submitted orders that have not passed their TTL yet.
 * Expired-but-unprocessed orders must not block the device from starting a new one.
 */
export async function getActiveOrderForDevice(db: D1Database, deviceId: string): Promise<OrderRow | null> {
  return db.prepare(
    `SELECT * FROM orders WHERE device_id = ? AND status IN ('PENDING','SUBMITTED') AND expires_at > ? LIMIT 1`
  ).bind(deviceId, Date.now()).first<OrderRow>();
}

/**
 * Get recent events for a device.
 */
export async function getDeviceEvents(db: D1Database, deviceId: string, limit = 20): Promise<EventRow[]> {
  const result = await db.prepare(
    'SELECT * FROM events WHERE device_id = ? ORDER BY ts DESC LIMIT ?'
  ).bind(deviceId, limit).all<EventRow>();
  return result.results;
}
