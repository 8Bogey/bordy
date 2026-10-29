import { Env } from './types';
import { releaseCode, logEvent } from './db';
import { generateDailyReport, exportData, sendTelegram } from './telegram';
import { formatBaghdadTime } from './utils';

/**
 * Handle scheduled cron event (daily at 05:00 UTC / 08:00 Baghdad).
 */
export async function handleScheduled(env: Env): Promise<void> {
  const now = Date.now();

  try {
    // 1. Expire old orders
    const expiredOrders = await env.DB.prepare(
      `SELECT id, code FROM orders WHERE status IN ('PENDING','SUBMITTED') AND expires_at < ?`
    ).bind(now).all<{ id: string; code: string | null }>();

    if (expiredOrders.results.length > 0) {
      // Update all expired orders
      await env.DB.prepare(
        `UPDATE orders SET status = 'EXPIRED' WHERE status IN ('PENDING','SUBMITTED') AND expires_at < ?`
      ).bind(now).run();

      // Release code reservations for expired orders
      for (const order of expiredOrders.results) {
        if (order.code) {
          await releaseCode(env.DB, order.code, order.id);
        }
      }

      await logEvent(env.DB, null, 'CRON_EXPIRE_ORDERS', `Expired ${expiredOrders.results.length} orders`);
    }

    // 2. Clean old proofs (older than 90 days)
    const cleanupCutoff = now - 90 * 86400000;
    await env.DB.prepare('DELETE FROM proofs WHERE created_at < ?').bind(cleanupCutoff).run();

    // 3. Clean old rate limit entries
    await env.DB.prepare('DELETE FROM rate_limits WHERE window_start < ?').bind(now - 7200000).run();

    // 4. Generate daily report
    await generateDailyReport(env);

    // 5. Export data backup
    await exportData(env);

    await logEvent(env.DB, null, 'CRON_DAILY', `Daily cron completed at ${formatBaghdadTime(now)}`);
  } catch (e) {
    const error = e instanceof Error ? e.message : String(e);
    await logEvent(env.DB, null, 'CRON_ERROR', error);
    await sendTelegram(env.TG_BOT_TOKEN, env.OWNER_CHAT_ID, `⚠️ خطأ في المهمة المجدولة:\n<code>${error}</code>`);
  }
}
