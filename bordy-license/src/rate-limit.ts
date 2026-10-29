/**
 * Check and increment a rate limit counter.
 * Returns true if the request is allowed, false if rate-limited.
 * Uses a sliding window of 1 hour.
 */
export async function checkRateLimit(db: D1Database, key: string, maxPerHour: number): Promise<boolean> {
  const now = Date.now();
  const windowMs = 3600000; // 1 hour

  const row = await db.prepare(
    'SELECT window_start, n FROM rate_limits WHERE k = ?'
  ).bind(key).first<{ window_start: number; n: number }>();

  if (!row || (now - row.window_start) >= windowMs) {
    // New window or expired: reset
    await db.prepare(
      'INSERT OR REPLACE INTO rate_limits (k, window_start, n) VALUES (?, ?, 1)'
    ).bind(key, now).run();
    return true;
  }

  if (row.n >= maxPerHour) {
    return false;
  }

  // Increment
  await db.prepare(
    'UPDATE rate_limits SET n = n + 1 WHERE k = ?'
  ).bind(key).run();
  return true;
}
