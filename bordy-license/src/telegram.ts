import { Env, DeviceRow, CodeRow } from './types';
import { getDevice, getOrder, logEvent, getSetting, setSetting, getSettings, getActivePlans, getPlan } from './db';
import { approveOrder, rejectOrder } from './handlers/orders';
import { formatBaghdadTime, jsonResponse } from './utils';

/**
 * Send a message via Telegram Bot API.
 */
export async function sendTelegram(
  botToken: string,
  chatId: string,
  text: string,
  replyMarkup?: unknown,
  parseMode: string = 'HTML'
): Promise<boolean> {
  try {
    const body: Record<string, unknown> = {
      chat_id: chatId,
      text,
      parse_mode: parseMode,
    };
    if (replyMarkup) {
      body.reply_markup = replyMarkup;
    }

    const resp = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    return resp.ok;
  } catch {
    return false;
  }
}

/**
 * Send a file (document) via Telegram Bot API.
 */
export async function sendTelegramDocument(
  botToken: string,
  chatId: string,
  filename: string,
  content: string,
  caption?: string
): Promise<boolean> {
  try {
    const form = new FormData();
    form.append('chat_id', chatId);
    form.append('document', new Blob([content], { type: 'text/csv' }), filename);
    if (caption) form.append('caption', caption);

    const resp = await fetch(`https://api.telegram.org/bot${botToken}/sendDocument`, {
      method: 'POST',
      body: form,
    });
    return resp.ok;
  } catch {
    return false;
  }
}

/**
 * Answer a callback query.
 */
async function answerCallback(botToken: string, callbackId: string, text?: string): Promise<void> {
  await fetch(`https://api.telegram.org/bot${botToken}/answerCallbackQuery`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ callback_query_id: callbackId, text: text || '' }),
  });
}

// NOTE: Cloudflare Workers are stateless per-request, so pendingRejects cannot be
// stored in-memory reliably across separate webhook invocations (each request may hit
// a different isolate). We work around this by encoding the orderId in the
// reject callback_data itself (reject_reason_ask:<orderId>) so the flow is:
// 1. Admin taps ❌ Reject  → bot asks for reason, stores orderId in D1 settings
// 2. Admin sends reason text → bot reads orderId from D1, calls rejectOrder
//
// For backwards compatibility, the Map is kept as a same-request fallback only.
const pendingRejects = new Map<string, string>(); // chatId → orderId (same-invocation only)

/**
 * Persist the pending reject orderId in D1 so it survives across Worker invocations.
 */
async function savePendingReject(db: D1Database, chatId: string, orderId: string): Promise<void> {
  await db.prepare("INSERT OR REPLACE INTO settings (k, v) VALUES (?, ?)")
    .bind(`tg_pending_reject_${chatId}`, orderId).run();
}

async function loadPendingReject(db: D1Database, chatId: string): Promise<string | null> {
  const row = await db.prepare("SELECT v FROM settings WHERE k = ?")
    .bind(`tg_pending_reject_${chatId}`).first<{ v: string }>();
  return row?.v ?? null;
}

async function clearPendingReject(db: D1Database, chatId: string): Promise<void> {
  await db.prepare("DELETE FROM settings WHERE k = ?")
    .bind(`tg_pending_reject_${chatId}`).run();
}

/**
 * Handle incoming Telegram webhook update.
 */
export async function handleTelegramWebhook(request: Request, env: Env): Promise<Response> {
  const update = await request.json() as any;

  // Handle callback queries (inline buttons)
  if (update.callback_query) {
    return handleCallback(update.callback_query, env);
  }

  // Handle messages
  if (update.message?.text) {
    const msg = update.message;
    const chatId = String(msg.chat.id);

    // Only owner can use bot commands
    if (chatId !== env.OWNER_CHAT_ID) {
      await sendTelegram(env.TG_BOT_TOKEN, chatId, '⛔ غير مصرح');
      return new Response('OK');
    }

    const text = msg.text.trim();

    // Check if we're waiting for reject reason (check D1 first, then in-memory fallback)
    const persistedOrderId = await loadPendingReject(env.DB, chatId);
    const inMemoryOrderId = pendingRejects.get(chatId);
    const pendingOrderId = persistedOrderId || inMemoryOrderId;

    if (pendingOrderId && !text.startsWith('/')) {
      // Clear both stores
      await clearPendingReject(env.DB, chatId);
      pendingRejects.delete(chatId);

      const success = await rejectOrder(env, pendingOrderId, text, 'telegram');
      if (success) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, `❌ تم رفض الطلب <code>${pendingOrderId}</code>\nالسبب: ${text}`);
      } else {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, `⚠️ فشل في رفض الطلب ${pendingOrderId} (قد يكون تمت معالجته)`);
      }
      return new Response('OK');
    }

    // Parse command
    if (text.startsWith('/')) {
      await handleCommand(chatId, text, env);
    }

    return new Response('OK');
  }

  return new Response('OK');
}

/**
 * Handle callback queries from inline buttons.
 */
async function handleCallback(query: any, env: Env): Promise<Response> {
  const chatId = String(query.message?.chat?.id || '');
  const data = query.data || '';
  const callbackId = query.id;

  if (chatId !== env.OWNER_CHAT_ID) {
    await answerCallback(env.TG_BOT_TOKEN, callbackId, 'غير مصرح');
    return new Response('OK');
  }

  if (data.startsWith('approve:')) {
    const orderId = data.split(':')[1];
    // Show confirmation
    await answerCallback(env.TG_BOT_TOKEN, callbackId);
    await sendTelegram(env.TG_BOT_TOKEN, chatId, `هل تريد تأكيد قبول الطلب <code>${orderId}</code>؟`, {
      inline_keyboard: [
        [
          { text: '✅ تأكيد القبول', callback_data: `approve_confirm:${orderId}` },
          { text: '↩️ إلغاء', callback_data: 'dismiss' },
        ],
      ],
    });
  } else if (data.startsWith('approve_confirm:')) {
    const orderId = data.split(':')[1];
    const success = await approveOrder(env, orderId, 'telegram');
    if (success) {
      await answerCallback(env.TG_BOT_TOKEN, callbackId, 'تمت الموافقة ✅');
      await sendTelegram(env.TG_BOT_TOKEN, chatId, `✅ تم قبول الطلب <code>${orderId}</code> وتفعيل الاشتراك`);
    } else {
      await answerCallback(env.TG_BOT_TOKEN, callbackId, 'فشل في القبول');
      await sendTelegram(env.TG_BOT_TOKEN, chatId, `⚠️ فشل في قبول الطلب ${orderId} (قد يكون تمت معالجته مسبقاً)`);
    }
  } else if (data.startsWith('reject:')) {
    const orderId = data.split(':')[1];
    await answerCallback(env.TG_BOT_TOKEN, callbackId);
    // Persist in D1 so the next webhook invocation (a new Worker isolate) can read it
    pendingRejects.set(chatId, orderId);
    await savePendingReject(env.DB, chatId, orderId);
    await sendTelegram(env.TG_BOT_TOKEN, chatId, `❓ أرسل سبب رفض الطلب <code>${orderId}</code>:\n(يمكنك كتابة "لا يوجد سبب" للرفض بدون سبب محدد)`);
  } else if (data === 'dismiss') {
    await answerCallback(env.TG_BOT_TOKEN, callbackId, 'تم الإلغاء');
  }

  return new Response('OK');
}

/**
 * Handle bot commands.
 */
async function handleCommand(chatId: string, text: string, env: Env): Promise<void> {
  const parts = text.split(/\s+/);
  const cmd = parts[0].toLowerCase().replace(/@.*$/, '');

  switch (cmd) {
    case '/start':
    case '/help': {
      await sendTelegram(env.TG_BOT_TOKEN, chatId, `🤖 <b>بوت إدارة بوردي (Bordy)</b>\n\n`
        + `/stats — إحصائيات عامة\n`
        + `/pending — الطلبات المعلقة\n`
        + `/expiring N — المشتركين الذين ينتهي اشتراكهم خلال N يوم\n`
        + `/find query — بحث عن جهاز\n`
        + `/grant did plan — منح اشتراك\n`
        + `/extend did days — تمديد اشتراك\n`
        + `/revoke did — إلغاء اشتراك\n`
        + `/block did reason — حظر جهاز\n`
        + `/unblock did — رفع الحظر\n`
        + `/note did text — إضافة ملاحظة\n`
        + `/code new|off|stats — إدارة الأكواد\n`
        + `/codes — قائمة الأكواد\n`
        + `/price plan price — تعديل سعر\n`
        + `/report — تقرير يومي\n`
        + `/export — تصدير البيانات\n`
        + `/maint on|off — وضع الصيانة\n`
        + `/tos set|privacy|bump — إدارة الشروط`);
      break;
    }

    case '/stats': {
      const now = Date.now();
      const results = await env.DB.batch([
        env.DB.prepare('SELECT COUNT(*) as c FROM devices'),
        env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE paid_until > ? OR lifetime = 1').bind(now),
        env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE trial_ends_at > ? AND paid_until < ?').bind(now, now),
        env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE blocked = 1'),
        env.DB.prepare(`SELECT COUNT(*) as c FROM orders WHERE status IN ('PENDING','SUBMITTED')`),
        env.DB.prepare(`SELECT COALESCE(SUM(final_price), 0) as c FROM orders WHERE status = 'APPROVED' AND decided_at > ?`).bind(now - 30 * 86400000),
      ]);

      const total = (results[0].results[0] as any)?.c || 0;
      const active = (results[1].results[0] as any)?.c || 0;
      const trials = (results[2].results[0] as any)?.c || 0;
      const blocked = (results[3].results[0] as any)?.c || 0;
      const pending = (results[4].results[0] as any)?.c || 0;
      const revenue = (results[5].results[0] as any)?.c || 0;

      await sendTelegram(env.TG_BOT_TOKEN, chatId, `📊 <b>إحصائيات</b>\n\n`
        + `📱 إجمالي الأجهزة: ${total}\n`
        + `✅ مشتركين فعالين: ${active}\n`
        + `🆓 تجريبي نشط: ${trials}\n`
        + `🚫 محظورين: ${blocked}\n`
        + `📋 طلبات معلقة: ${pending}\n`
        + `💰 إيرادات 30 يوم: ${revenue.toLocaleString()} د.ع`);
      break;
    }

    case '/pending': {
      const orders = await env.DB.prepare(
        `SELECT * FROM orders WHERE status IN ('PENDING','SUBMITTED') ORDER BY created_at DESC LIMIT 20`
      ).all();

      if (orders.results.length === 0) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '✅ لا توجد طلبات معلقة');
        break;
      }

      let msg = `📋 <b>الطلبات المعلقة (${orders.results.length})</b>\n\n`;
      for (const o of orders.results as any[]) {
        msg += `<code>${o.id}</code> | ${o.status} | ${o.final_price} د.ع | ${o.plan_id}\n`;
        msg += `  📱 ${o.device_id.substring(0, 8)}... | ⏰ ${formatBaghdadTime(o.created_at)}\n\n`;
      }

      await sendTelegram(env.TG_BOT_TOKEN, chatId, msg);
      break;
    }

    case '/expiring': {
      const days = parseInt(parts[1] || '3');
      const now = Date.now();
      const cutoff = now + days * 86400000;

      const devices = await env.DB.prepare(
        'SELECT * FROM devices WHERE paid_until > ? AND paid_until < ? AND lifetime = 0 ORDER BY paid_until ASC LIMIT 20'
      ).bind(now, cutoff).all<DeviceRow>();

      if (devices.results.length === 0) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, `✅ لا يوجد مشتركين ينتهون خلال ${days} أيام`);
        break;
      }

      let msg = `⏰ <b>ينتهون خلال ${days} أيام (${devices.results.length})</b>\n\n`;
      for (const d of devices.results) {
        const remaining = Math.ceil((d.paid_until - now) / 86400000);
        msg += `<code>${d.device_id.substring(0, 12)}...</code> | ${remaining} يوم\n`;
        if (d.name) msg += `  👤 ${d.name}\n`;
      }

      await sendTelegram(env.TG_BOT_TOKEN, chatId, msg);
      break;
    }

    case '/find': {
      const query = parts.slice(1).join(' ');
      if (!query) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ أدخل معرف الجهاز أو رقم الهاتف');
        break;
      }

      const devices = await env.DB.prepare(
        `SELECT * FROM devices WHERE device_id LIKE ? OR phone LIKE ? OR name LIKE ? LIMIT 5`
      ).bind(`%${query}%`, `%${query}%`, `%${query}%`).all<DeviceRow>();

      if (devices.results.length === 0) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '❌ لم يتم العثور على نتائج');
        break;
      }

      const now = Date.now();
      for (const d of devices.results) {
        let status = '❌ منتهي';
        if (d.blocked) status = '🚫 محظور';
        else if (d.lifetime) status = '👑 دائم';
        else if (d.paid_until > now) status = '✅ مشترك';
        else if ((d.trial_ends_at || 0) > now) status = '🆓 تجريبي';

        let msg = `📱 <b>جهاز</b>\n\n`
          + `🆔 <code>${d.device_id}</code>\n`
          + `📊 الحالة: ${status}\n`;
        if (d.name) msg += `👤 الاسم: ${d.name}\n`;
        if (d.phone) msg += `📞 الهاتف: ${d.phone}\n`;
        if (d.model) msg += `📱 الموديل: ${d.model}\n`;
        msg += `📅 أول ظهور: ${formatBaghdadTime(d.first_seen)}\n`;
        if (d.paid_until > 0) msg += `💳 مدفوع حتى: ${formatBaghdadTime(d.paid_until)}\n`;
        if (d.trial_ends_at) msg += `🆓 تجريبي حتى: ${formatBaghdadTime(d.trial_ends_at)}\n`;
        if (d.notes) msg += `📝 ملاحظات: ${d.notes}\n`;
        msg += `🔄 التحويلات: ${d.transfers_used}\n`;

        await sendTelegram(env.TG_BOT_TOKEN, chatId, msg);
      }
      break;
    }

    case '/grant': {
      const did = parts[1];
      const planId = parts[2];
      if (!did || !planId) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /grant device_id plan_id');
        break;
      }

      const plan = await getPlan(env.DB, planId);
      if (!plan) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '❌ الخطة غير موجودة');
        break;
      }

      const device = await getDevice(env.DB, did);
      if (!device) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '❌ الجهاز غير مسجل');
        break;
      }

      const now = Date.now();
      const base = Math.max(now, device.paid_until);
      const newPaidUntil = base + plan.days * 86400000;

      await env.DB.prepare('UPDATE devices SET paid_until = ? WHERE device_id = ?').bind(newPaidUntil, did).run();
      await logEvent(env.DB, did, 'ADMIN_GRANT', `Granted ${planId} (${plan.days}d) via Telegram`);

      await sendTelegram(env.TG_BOT_TOKEN, chatId,
        `✅ تم منح <code>${did.substring(0, 12)}...</code> اشتراك ${plan.title} (${plan.days} يوم)\n`
        + `💳 مدفوع حتى: ${formatBaghdadTime(newPaidUntil)}`);
      break;
    }

    case '/extend': {
      const did = parts[1];
      const days = parseInt(parts[2] || '0');
      if (!did || !days) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /extend device_id days');
        break;
      }

      const device = await getDevice(env.DB, did);
      if (!device) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '❌ الجهاز غير مسجل');
        break;
      }

      const now = Date.now();
      const base = Math.max(now, device.paid_until);
      const newPaidUntil = base + days * 86400000;

      await env.DB.prepare('UPDATE devices SET paid_until = ? WHERE device_id = ?').bind(newPaidUntil, did).run();
      await logEvent(env.DB, did, 'ADMIN_EXTEND', `Extended ${days}d via Telegram`);

      await sendTelegram(env.TG_BOT_TOKEN, chatId,
        `✅ تم تمديد <code>${did.substring(0, 12)}...</code> بـ ${days} يوم\n`
        + `💳 مدفوع حتى: ${formatBaghdadTime(newPaidUntil)}`);
      break;
    }

    case '/revoke': {
      const did = parts[1];
      if (!did) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /revoke device_id');
        break;
      }

      await env.DB.prepare('UPDATE devices SET paid_until = 0, lifetime = 0 WHERE device_id = ?').bind(did).run();
      await logEvent(env.DB, did, 'ADMIN_REVOKE', 'Subscription revoked via Telegram');

      await sendTelegram(env.TG_BOT_TOKEN, chatId, `✅ تم إلغاء اشتراك <code>${did.substring(0, 12)}...</code>`);
      break;
    }

    case '/block': {
      const did = parts[1];
      const reason = parts.slice(2).join(' ') || 'Blocked by admin';
      if (!did) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /block device_id reason');
        break;
      }

      await env.DB.prepare('UPDATE devices SET blocked = 1, block_reason = ? WHERE device_id = ?').bind(reason, did).run();
      await logEvent(env.DB, did, 'ADMIN_BLOCK', reason);

      await sendTelegram(env.TG_BOT_TOKEN, chatId, `🚫 تم حظر <code>${did.substring(0, 12)}...</code>\nالسبب: ${reason}`);
      break;
    }

    case '/unblock': {
      const did = parts[1];
      if (!did) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /unblock device_id');
        break;
      }

      await env.DB.prepare('UPDATE devices SET blocked = 0, block_reason = NULL WHERE device_id = ?').bind(did).run();
      await logEvent(env.DB, did, 'ADMIN_UNBLOCK', 'Unblocked via Telegram');

      await sendTelegram(env.TG_BOT_TOKEN, chatId, `✅ تم رفع الحظر عن <code>${did.substring(0, 12)}...</code>`);
      break;
    }

    case '/note': {
      const did = parts[1];
      const note = parts.slice(2).join(' ');
      if (!did || !note) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /note device_id text');
        break;
      }

      await env.DB.prepare('UPDATE devices SET notes = ? WHERE device_id = ?').bind(note, did).run();
      await sendTelegram(env.TG_BOT_TOKEN, chatId, `📝 تم تحديث ملاحظة <code>${did.substring(0, 12)}...</code>`);
      break;
    }

    case '/code': {
      const action = parts[1];
      if (action === 'new') {
        // /code new CODE KIND VALUE DAYS [plans] [max_uses] [per_device]
        // Example: /code new SALE50 PERCENT 50 30 * 100 1
        const code = parts[2];
        const kind = parts[3];
        const value = parseInt(parts[4] || '0');
        const daysValid = parseInt(parts[5] || '30');
        const plans = parts[6] || '*';
        const maxUses = parts[7] ? parseInt(parts[7]) : null;
        const perDevice = parseInt(parts[8] || '1');

        if (!code || !kind || !value) {
          await sendTelegram(env.TG_BOT_TOKEN, chatId,
            '⚠️ الاستخدام: /code new CODE KIND VALUE DAYS [plans] [max_uses] [per_device]\n'
            + 'KIND: PERCENT, FIXED, FREE_DAYS, FULL');
          break;
        }

        const now = Date.now();
        await env.DB.prepare(
          `INSERT OR REPLACE INTO codes (code, kind, value, plans, starts_at, ends_at, max_uses, per_device_limit, created_at, active)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)`
        ).bind(code.toUpperCase(), kind.toUpperCase(), value, plans, now, now + daysValid * 86400000, maxUses, perDevice, now).run();

        await sendTelegram(env.TG_BOT_TOKEN, chatId,
          `✅ تم إنشاء الكود <code>${code.toUpperCase()}</code>\n`
          + `النوع: ${kind} | القيمة: ${value} | صالح لـ ${daysValid} يوم`);
      } else if (action === 'off') {
        const code = parts[2];
        if (!code) {
          await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /code off CODE');
          break;
        }
        await env.DB.prepare('UPDATE codes SET active = 0 WHERE code = ?').bind(code.toUpperCase()).run();
        await sendTelegram(env.TG_BOT_TOKEN, chatId, `✅ تم تعطيل الكود <code>${code.toUpperCase()}</code>`);
      } else if (action === 'stats') {
        const code = parts[2];
        if (!code) {
          await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /code stats CODE');
          break;
        }
        const codeRow = await env.DB.prepare('SELECT * FROM codes WHERE code = ?').bind(code.toUpperCase()).first<CodeRow>();
        if (!codeRow) {
          await sendTelegram(env.TG_BOT_TOKEN, chatId, '❌ الكود غير موجود');
          break;
        }
        await sendTelegram(env.TG_BOT_TOKEN, chatId,
          `📊 <b>كود ${codeRow.code}</b>\n\n`
          + `النوع: ${codeRow.kind} | القيمة: ${codeRow.value}\n`
          + `الحالة: ${codeRow.active ? '✅ نشط' : '❌ معطل'}\n`
          + `الاستخدام: ${codeRow.used_count}/${codeRow.max_uses ?? '∞'}\n`
          + `محجوز: ${codeRow.reserved_count}\n`
          + `الخطط: ${codeRow.plans}\n`
          + `ينتهي: ${formatBaghdadTime(codeRow.ends_at)}`);
      } else {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /code new|off|stats ...');
      }
      break;
    }

    case '/codes': {
      const codes = await env.DB.prepare('SELECT * FROM codes WHERE active = 1 ORDER BY created_at DESC LIMIT 20').all<CodeRow>();
      if (codes.results.length === 0) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '📭 لا توجد أكواد نشطة');
        break;
      }

      let msg = `🏷️ <b>الأكواد النشطة (${codes.results.length})</b>\n\n`;
      for (const c of codes.results) {
        msg += `<code>${c.code}</code> | ${c.kind} ${c.value} | ${c.used_count}/${c.max_uses ?? '∞'}\n`;
      }
      await sendTelegram(env.TG_BOT_TOKEN, chatId, msg);
      break;
    }

    case '/price': {
      const planId = parts[1];
      const newPrice = parseInt(parts[2] || '0');
      if (!planId || !newPrice) {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /price plan_id new_price');
        break;
      }

      await env.DB.prepare('UPDATE plans SET price = ? WHERE id = ?').bind(newPrice, planId).run();
      await sendTelegram(env.TG_BOT_TOKEN, chatId, `✅ تم تحديث سعر ${planId} إلى ${newPrice.toLocaleString()} د.ع`);
      break;
    }

    case '/report': {
      await generateDailyReport(env);
      break;
    }

    case '/export': {
      await exportData(env);
      break;
    }

    case '/maint': {
      const mode = parts[1];
      if (mode !== 'on' && mode !== 'off') {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /maint on|off');
        break;
      }
      await setSetting(env.DB, 'maintenance', mode);
      await sendTelegram(env.TG_BOT_TOKEN, chatId,
        mode === 'on' ? '🔧 تم تفعيل وضع الصيانة' : '✅ تم إيقاف وضع الصيانة');
      break;
    }

    case '/tos': {
      const action = parts[1];
      if (action === 'set') {
        const newText = parts.slice(2).join(' ');
        if (!newText) {
          await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /tos set <new text>');
          break;
        }
        await setSetting(env.DB, 'tos_text', newText);
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '✅ تم تحديث شروط الخدمة');
      } else if (action === 'privacy') {
        const newText = parts.slice(2).join(' ');
        if (!newText) {
          await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /tos privacy <new text>');
          break;
        }
        await setSetting(env.DB, 'privacy_text', newText);
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '✅ تم تحديث سياسة الخصوصية');
      } else if (action === 'bump') {
        const current = parseInt(await getSetting(env.DB, 'tos_version') || '1');
        await setSetting(env.DB, 'tos_version', String(current + 1));
        await sendTelegram(env.TG_BOT_TOKEN, chatId, `✅ تم رفع نسخة الشروط إلى ${current + 1}`);
      } else {
        await sendTelegram(env.TG_BOT_TOKEN, chatId, '⚠️ الاستخدام: /tos set|privacy|bump ...');
      }
      break;
    }

    default:
      await sendTelegram(env.TG_BOT_TOKEN, chatId, '❓ أمر غير معروف. أرسل /help للمساعدة');
  }
}

/**
 * Generate and send daily report.
 */
export async function generateDailyReport(env: Env): Promise<void> {
  const now = Date.now();
  const yesterday = now - 86400000;
  const threeDays = now + 3 * 86400000;

  const results = await env.DB.batch([
    // New trials yesterday
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE first_seen > ? AND first_seen < ?').bind(yesterday, now),
    // Approved orders + revenue yesterday
    env.DB.prepare(`SELECT COUNT(*) as c, COALESCE(SUM(final_price), 0) as rev FROM orders WHERE status = 'APPROVED' AND decided_at > ? AND decided_at < ?`).bind(yesterday, now),
    // Expiring in 3 days
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE paid_until > ? AND paid_until < ? AND lifetime = 0').bind(now, threeDays),
    // Active subscribers
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE (paid_until > ? OR lifetime = 1)').bind(now),
    // Monthly revenue
    env.DB.prepare(`SELECT COALESCE(SUM(final_price), 0) as rev FROM orders WHERE status = 'APPROVED' AND decided_at > ?`).bind(now - 30 * 86400000),
  ]);

  const newTrials = (results[0].results[0] as any)?.c || 0;
  const approvedCount = (results[1].results[0] as any)?.c || 0;
  const dailyRevenue = (results[1].results[0] as any)?.rev || 0;
  const expiringCount = (results[2].results[0] as any)?.c || 0;
  const activeCount = (results[3].results[0] as any)?.c || 0;
  const monthlyRevenue = (results[4].results[0] as any)?.rev || 0;

  const msg = `📊 <b>التقرير اليومي</b> — ${formatBaghdadTime(now)}\n\n`
    + `🆕 تجريبيات جديدة: ${newTrials}\n`
    + `✅ طلبات مقبولة: ${approvedCount}\n`
    + `💰 إيرادات اليوم: ${dailyRevenue.toLocaleString()} د.ع\n`
    + `⏰ ينتهون خلال 3 أيام: ${expiringCount}\n`
    + `📱 مشتركين فعالين: ${activeCount}\n`
    + `💵 إيرادات الشهر: ${monthlyRevenue.toLocaleString()} د.ع`;

  await sendTelegram(env.TG_BOT_TOKEN, env.OWNER_CHAT_ID, msg);
}

/**
 * Export key tables as CSV and send via Telegram.
 */
export async function exportData(env: Env): Promise<void> {
  // Export devices
  const devices = await env.DB.prepare('SELECT * FROM devices ORDER BY first_seen DESC').all<DeviceRow>();
  let csv = 'device_id,first_seen,paid_until,lifetime,blocked,name,phone,model,transfers_used\n';
  for (const d of devices.results) {
    csv += `${d.device_id},${d.first_seen},${d.paid_until},${d.lifetime},${d.blocked},"${d.name || ''}","${d.phone || ''}","${d.model || ''}",${d.transfers_used}\n`;
  }

  const date = formatBaghdadTime(Date.now()).split(' ')[0];
  await sendTelegramDocument(env.TG_BOT_TOKEN, env.OWNER_CHAT_ID, `devices_${date}.csv`, csv, '📋 تصدير الأجهزة');

  // Export orders
  const orders = await env.DB.prepare('SELECT * FROM orders ORDER BY created_at DESC LIMIT 500').all();
  let orderCsv = 'id,device_id,plan_id,final_price,status,code,method,created_at\n';
  for (const o of orders.results as any[]) {
    orderCsv += `${o.id},${o.device_id},${o.plan_id},${o.final_price},${o.status},"${o.code || ''}","${o.method || ''}",${o.created_at}\n`;
  }

  await sendTelegramDocument(env.TG_BOT_TOKEN, env.OWNER_CHAT_ID, `orders_${date}.csv`, orderCsv, '📋 تصدير الطلبات');
}
