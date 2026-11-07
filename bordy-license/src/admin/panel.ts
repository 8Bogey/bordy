import { Env, DeviceRow, OrderRow, CodeRow, EventRow, PaymentMethod } from '../types';
import { attemptLogin, validateSession, validateCsrf, getCsrfToken, createSessionCookie, clearSessionCookie } from './auth';
import { getSettings, setSetting, logEvent } from '../db';
import { approveOrder, rejectOrder } from '../handlers/orders';
import { SYNC_INTERVAL_MAX, SYNC_INTERVAL_MIN, clampSyncInterval, clampForegroundSync, clampTrialSeconds, FOREGROUND_SYNC_MIN, FOREGROUND_SYNC_MAX, TRIAL_MIN_SECONDS, TRIAL_MAX_SECONDS, clampExpiryNoticeUnit, clampExpiryNoticeValue, expiryNoticeEnabled, EXPIRY_NOTICE_UNITS } from '../handlers/config';
import { formatBaghdadTime, normalizeCode, normalizeBorderColor, parseJsonBody, parsePaymentMethods, paymentMethodId } from '../utils';
import { normalizeVersionInput, canonicalVersion, isValidVersion } from '../version';
import { handleAdminAccountsApi } from './accounts';
import {
  apiUpdateStatus, apiUploadRelease, apiDeleteRelease, apiUpdateReleaseSettings,
} from './releases';
import { apiSavePaymentLogo } from '../handlers/payment-logo';

/**
 * Handle all /admin/* routes.
 */
export async function handleAdminPanel(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;

  // Login page and login action
  if (path === '/admin/login' && request.method === 'POST') {
    return handleLogin(request, env);
  }

  if (path === '/admin/logout') {
    return new Response(null, {
      status: 302,
      headers: {
        'Location': '/admin',
        'Set-Cookie': clearSessionCookie(),
      },
    });
  }

  // Check authentication for all other admin routes
  const cookie = request.headers.get('Cookie');
  if (!await validateSession(cookie, env)) {
    return renderLoginPage();
  }

  if (path.startsWith('/admin/api/') && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method)) {
    if (!await validateCsrf(request, env)) {
      return Response.json({ ok: false, error: 'انتهت حماية الجلسة، أعد تحميل الصفحة' }, { status: 403 });
    }
  }

  // Authenticated routes
  if (path === '/admin' || path === '/admin/') {
    const csrfToken = await getCsrfToken(cookie, env);
    return renderDashboard(env, csrfToken || '');
  }

  if (path === '/admin/api/accounts' || path.startsWith('/admin/api/accounts/')) {
    return handleAdminAccountsApi(request, env);
  }

  // Used by the admin Android app: the session cookie itself is not enough for writes.
  if (path === '/admin/api/csrf' && request.method === 'GET') {
    const token = await getCsrfToken(cookie, env);
    return Response.json({ ok: true, csrf: token || '' });
  }

  if (path === '/admin/api/stats' && request.method === 'GET') {
    return apiStats(env);
  }

  if (path === '/admin/api/settings' && request.method === 'GET') {
    return apiGetSettings(env);
  }

  if (path === '/admin/api/devices') {
    return apiDevices(request, env);
  }

  if (path === '/admin/api/orders') {
    return apiOrders(request, env);
  }

  if (path === '/admin/api/codes') {
    if (request.method === 'POST') return apiCreateCode(request, env);
    return apiCodes(request, env);
  }

  if (path === '/admin/api/events') {
    return apiEvents(request, env);
  }

  if (path === '/admin/api/events/delete' && request.method === 'POST') {
    return apiDeleteEvents(request, env);
  }

  if (path === '/admin/api/rate-limits/reset' && request.method === 'POST') {
    return apiResetRateLimits(env);
  }

  if (path.startsWith('/admin/api/codes/') && request.method === 'POST') {
    return apiCodeAction(request, decodeURIComponent(path.split('/')[4]), env);
  }

  if (path.startsWith('/admin/api/order/')) {
    const parts = path.split('/');
    const orderId = parts[4];
    const action = parts[5];
    // State-changing actions must be POST so a link or image tag cannot trigger them.
    if (action === 'approve' && request.method === 'POST') return apiApproveOrder(orderId, env);
    if (action === 'reject' && request.method === 'POST') return apiRejectOrder(request, orderId, env);
    if (action === 'delete' && request.method === 'POST') return apiDeleteOrder(request, orderId, env);
    if (action === 'proof' && request.method === 'GET') return apiGetProof(orderId, env);
  }

  if (path.startsWith('/admin/api/device/')) {
    const did = path.split('/')[4];
    if (request.method === 'GET') return apiGetDevice(did, env);
    if (request.method === 'POST') return apiUpdateDevice(request, did, env);
  }

  if (path === '/admin/api/settings' && request.method === 'POST') {
    return apiUpdateSettings(request, env);
  }

  if (path === '/admin/api/payment-logo' && request.method === 'POST') {
    return apiSavePaymentLogo(request, env);
  }

  if (path === '/admin/api/update' && request.method === 'GET') {
    return apiUpdateStatus(request, env);
  }

  if (path === '/admin/api/update/settings' && request.method === 'POST') {
    return apiUpdateReleaseSettings(request, env);
  }

  if (path === '/admin/api/releases' && request.method === 'POST') {
    return apiUploadRelease(request, env);
  }

  if (path === '/admin/api/releases/delete' && request.method === 'POST') {
    return apiDeleteRelease(request, env);
  }

  if (path === '/admin/api/export') {
    return apiExport(env);
  }

  return new Response('Not found', { status: 404 });
}

async function handleLogin(request: Request, env: Env): Promise<Response> {
  let secret = '';
  let totp = '';
  try {
    const form = await request.formData();
    secret = (form.get('secret') as string) || '';
    totp = (form.get('totp') as string) || '';
  } catch {
    return renderLoginPage('بيانات الطلب غير صالحة');
  }
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';

  const sessionId = await attemptLogin(env, secret, totp, ip);
  if (!sessionId) {
    return renderLoginPage('بيانات الدخول غير صحيحة أو الحساب مقفل');
  }

  return new Response(null, {
    status: 302,
    headers: {
      'Location': '/admin',
      'Set-Cookie': createSessionCookie(sessionId),
    },
  });
}

function renderLoginPage(error?: string): Response {
  const html = `<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>بوردي - لوحة الإدارة</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',Tahoma,sans-serif;background:#0f172a;color:#e2e8f0;display:flex;justify-content:center;align-items:center;min-height:100vh}
.login-box{background:#1e293b;padding:2rem;border-radius:12px;width:100%;max-width:400px;box-shadow:0 4px 20px rgba(0,0,0,.3)}
h1{text-align:center;margin-bottom:1.5rem;color:#60a5fa}
input{width:100%;padding:.75rem;margin:.5rem 0;border:1px solid #334155;border-radius:8px;background:#0f172a;color:#e2e8f0;font-size:1rem}
button{width:100%;padding:.75rem;margin-top:1rem;border:none;border-radius:8px;background:#3b82f6;color:#fff;font-size:1rem;cursor:pointer;font-weight:bold}
button:hover{background:#2563eb}
.error{color:#f87171;text-align:center;margin-top:.5rem;font-size:.9rem}
</style>
</head>
<body>
<div class="login-box">
  <h1>🔐 لوحة الإدارة</h1>
  <form method="POST" action="/admin/login">
    <input type="password" name="secret" placeholder="كلمة السر" required autofocus>
    <input type="text" name="totp" placeholder="رمز التحقق (TOTP)" required maxlength="6" pattern="[0-9]{6}" inputmode="numeric">
    <button type="submit">دخول</button>
    ${error ? `<p class="error">${error}</p>` : ''}
  </form>
</div>
</body>
</html>`;
  return new Response(html, {
    status: error ? 401 : 200,
    headers: { 'Content-Type': 'text/html;charset=UTF-8' },
  });
}

async function renderDashboard(env: Env, csrfToken: string): Promise<Response> {
  const now = Date.now();
  const sevenDays = now + 7 * 86400000;
  const thirtyDays = now - 30 * 86400000;

  const results = await env.DB.batch([
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE paid_until > ? OR lifetime = 1').bind(now),
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE trial_ends_at > ? AND paid_until < ? AND lifetime = 0').bind(now, now),
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE paid_until < ? AND (trial_ends_at IS NULL OR trial_ends_at < ?) AND lifetime = 0').bind(now, now),
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE paid_until > ? AND paid_until < ? AND lifetime = 0').bind(now, sevenDays),
    env.DB.prepare(`SELECT COALESCE(SUM(final_price), 0) as c FROM orders WHERE status = 'APPROVED' AND decided_at > ?`).bind(thirtyDays),
    env.DB.prepare(`SELECT COUNT(*) as c FROM orders WHERE status IN ('PENDING','SUBMITTED')`),
  ]);

  const activeSubs = (results[0].results[0] as any)?.c || 0;
  const trials = (results[1].results[0] as any)?.c || 0;
  const expired = (results[2].results[0] as any)?.c || 0;
  const expiringIn7 = (results[3].results[0] as any)?.c || 0;
  const monthlyRevenue = (results[4].results[0] as any)?.c || 0;
  const pendingOrders = (results[5].results[0] as any)?.c || 0;

  const settings = await getSettings(env.DB, [
    'tos_text', 'privacy_text', 'tos_version', 'payment_methods', 'maintenance',
    'support_whatsapp', 'support_telegram', 'sync_interval_seconds',
    'foreground_sync_seconds', 'trial_seconds', 'trial_days',
    'paid_token_ttl_days', 'grace_days', 'min_app_version',
    'bot_notifications_enabled', 'expiry_notice_enabled', 'expiry_notice_unit', 'expiry_notice_value',
  ]);

  const trialSeconds = clampTrialSeconds(settings.trial_seconds, settings.trial_days);
  const trialUnit = trialSeconds % 86400 === 0 ? 'days'
    : trialSeconds % 3600 === 0 ? 'hours'
    : trialSeconds % 60 === 0 ? 'minutes'
    : 'seconds';
  const trialValue = trialUnit === 'days' ? trialSeconds / 86400
    : trialUnit === 'hours' ? trialSeconds / 3600
    : trialUnit === 'minutes' ? trialSeconds / 60
    : trialSeconds;
  const foregroundSync = clampForegroundSync(settings.foreground_sync_seconds);
  const botEnabled = expiryNoticeEnabled(settings.bot_notifications_enabled);
  const noticeEnabled = expiryNoticeEnabled(settings.expiry_notice_enabled);
  const noticeUnit = clampExpiryNoticeUnit(settings.expiry_notice_unit);
  const noticeValue = clampExpiryNoticeValue(settings.expiry_notice_value, noticeUnit);

  const html = `<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>بوردي - لوحة الإدارة</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',Tahoma,sans-serif;background:#0f172a;color:#e2e8f0;min-height:100vh}
.header{background:#1e293b;padding:1rem 2rem;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #334155}
.header h1{color:#60a5fa;font-size:1.3rem}
.header a{color:#f87171;text-decoration:none}
.container{max-width:1400px;margin:0 auto;padding:1.5rem}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:1rem;margin-bottom:2rem}
.stat-card{background:#1e293b;border-radius:12px;padding:1.5rem;text-align:center}
.stat-card .num{font-size:2rem;font-weight:bold;color:#60a5fa}
.stat-card .label{color:#94a3b8;margin-top:.5rem}
.stat-card.warning .num{color:#fbbf24}
.stat-card.danger .num{color:#f87171}
.tabs{display:flex;gap:.5rem;margin-bottom:1.5rem;flex-wrap:wrap}
.tab{padding:.5rem 1.5rem;border-radius:8px;background:#1e293b;border:1px solid #334155;cursor:pointer;color:#94a3b8}
.tab.active{background:#3b82f6;color:#fff;border-color:#3b82f6}
.panel{display:none;background:#1e293b;border-radius:12px;padding:1.5rem}
.panel.active{display:block}
table{width:100%;border-collapse:collapse;font-size:.9rem}
th,td{padding:.75rem;text-align:right;border-bottom:1px solid #334155}
th{background:#0f172a;color:#94a3b8;font-weight:600;position:sticky;top:0}
tr:hover{background:#334155}
.badge{padding:2px 8px;border-radius:4px;font-size:.8rem;font-weight:600}
.badge.green{background:#065f46;color:#6ee7b7}
.badge.yellow{background:#78350f;color:#fcd34d}
.badge.red{background:#7f1d1d;color:#fca5a5}
.badge.blue{background:#1e3a5f;color:#93c5fd}
input[type=text],input[type=number],input[type=search],select,textarea{background:#0f172a;border:1px solid #334155;color:#e2e8f0;padding:.5rem;border-radius:6px;width:100%}
textarea{min-height:150px;font-family:inherit}
button,.btn{padding:.5rem 1rem;border:none;border-radius:6px;cursor:pointer;font-size:.85rem;font-weight:600}
.btn-primary{background:#3b82f6;color:#fff}
.btn-primary:hover{background:#2563eb}
.btn-success{background:#059669;color:#fff}
.btn-danger{background:#dc2626;color:#fff}
.btn-warning{background:#d97706;color:#fff}
.search-bar{margin-bottom:1rem}
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:1rem}
.form-group{margin-bottom:1rem}
.form-group label{display:block;margin-bottom:.3rem;color:#94a3b8;font-size:.85rem}
.table-wrap{overflow-x:auto;max-height:500px;overflow-y:auto}
.modal{display:none;position:fixed;inset:0;background:rgba(0,0,0,.7);z-index:100;justify-content:center;align-items:center}
.modal.show{display:flex}
.modal-content{background:#1e293b;border-radius:12px;padding:2rem;max-width:600px;width:90%;max-height:80vh;overflow-y:auto}
.modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:1rem}
.close{cursor:pointer;font-size:1.5rem;color:#94a3b8}
.mt-1{margin-top:.5rem}
.mb-1{margin-bottom:.5rem}
.flex{display:flex;gap:.5rem;align-items:center}
.switch-row{display:flex;justify-content:space-between;align-items:center;gap:1rem;background:#0f172a;border:1px solid #334155;border-radius:10px;padding:.9rem 1rem}
.switch-title{font-weight:600;color:#e2e8f0}
.switch-desc{color:#94a3b8;font-size:.8rem;margin-top:.2rem;max-width:560px}
.switch{position:relative;display:inline-block;width:52px;height:28px;flex:0 0 auto}
.switch input{opacity:0;width:0;height:0}
.slider{position:absolute;cursor:pointer;inset:0;background:#475569;border-radius:999px;transition:.2s}
.slider:before{content:"";position:absolute;height:22px;width:22px;right:3px;top:3px;background:#fff;border-radius:50%;transition:.2s}
.switch input:checked+.slider{background:#059669}
.switch input:checked+.slider:before{transform:translateX(-24px)}
.notice-preview{background:#78350f;color:#fde68a;border-radius:8px;padding:.6rem .8rem;font-weight:600;font-size:.85rem}
.section-num{display:inline-block;background:#1e3a5f;color:#93c5fd;border-radius:6px;padding:0 .4rem;margin-left:.35rem;font-size:.75rem;vertical-align:middle}
#loading{display:none;text-align:center;padding:2rem;color:#94a3b8}
</style>
</head>
<body>
<div class="header">
  <h1>📱 بوردي (Bordy) — لوحة الإدارة ${settings.maintenance === 'on' ? '🔧' : ''}</h1>
  <a href="/admin/logout">🚪 خروج</a>
</div>
<div class="container">
  <div class="stats">
    <div class="stat-card"><div class="num">${activeSubs}</div><div class="label">مشترك فعال</div></div>
    <div class="stat-card"><div class="num">${trials}</div><div class="label">تجريبي نشط</div></div>
    <div class="stat-card warning"><div class="num">${expiringIn7}</div><div class="label">ينتهون خلال 7 أيام</div></div>
    <div class="stat-card danger"><div class="num">${expired}</div><div class="label">منتهي</div></div>
    <div class="stat-card"><div class="num">${monthlyRevenue.toLocaleString()}</div><div class="label">إيرادات الشهر (د.ع)</div></div>
    <div class="stat-card warning"><div class="num">${pendingOrders}</div><div class="label">طلبات معلقة</div></div>
  </div>

  <div class="tabs">
    <div class="tab active" data-tab="devices">📱 المشتركين</div>
    <div class="tab" data-tab="accounts">الحسابات السحابية</div>
    <div class="tab" data-tab="orders">📋 الطلبات</div>
    <div class="tab" data-tab="codes">🏷️ الأكواد</div>
    <div class="tab" data-tab="events">📜 السجل</div>
    <div class="tab" data-tab="settings">⚙️ الإعدادات</div>
    <div class="tab" data-tab="update">🚀 التحديثات</div>
  </div>

  <!-- Devices Panel -->
  <div class="panel active" id="tab-devices">
    <div class="flex search-bar">
      <input type="search" id="device-search" placeholder="بحث بالمعرف أو الاسم أو الرقم..." style="flex:1">
      <select id="device-status" onchange="searchDevices()" style="max-width:170px">
        <option value="">كل الحالات</option>
        <option value="active">مشترك</option>
        <option value="trial">تجريبي</option>
        <option value="expired">منتهي</option>
        <option value="blocked">محظور</option>
      </select>
      <button class="btn btn-primary" onclick="searchDevices()">بحث</button>
      <button class="btn btn-warning" onclick="exportCSV()">📥 تصدير CSV</button>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>المعرف</th><th>الحالة</th><th>الاسم</th><th>الهاتف</th><th>الموديل</th><th>مدفوع حتى</th><th>نهاية التجربة</th><th>آخر ظهور</th><th>إجراءات</th></tr></thead>
        <tbody id="devices-tbody"></tbody>
      </table>
    </div>
    <div id="loading">جاري التحميل...</div>
  </div>

  <div class="panel" id="tab-accounts">
    <div class="flex search-bar">
      <input type="search" id="account-search" placeholder="بحث باسم المستخدم أو معرف الحساب أو اسم الجهاز..." style="flex:1">
      <select id="account-status" onchange="searchAccounts()" style="max-width:170px">
        <option value="">كل الحالات</option>
        <option value="active">فعال</option>
        <option value="locked">مقفل</option>
        <option value="disabled">معطل</option>
        <option value="pending">قيد الإعداد</option>
      </select>
      <button class="btn btn-primary" onclick="searchAccounts()">بحث</button>
      <button class="btn btn-success" onclick="showModal('account-create-modal')">➕ حساب جديد</button>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>اسم المستخدم</th><th>الحالة</th><th>الترخيص</th><th>الجهاز النشط</th><th>السجلات</th><th>التعارضات</th><th>آخر ظهور</th><th>إجراءات</th></tr></thead>
        <tbody id="accounts-tbody"></tbody>
      </table>
    </div>
  </div>

  <!-- Orders Panel -->
  <div class="panel" id="tab-orders">
    <div class="table-wrap">
      <table>
        <thead><tr><th>الرقم</th><th>الجهاز</th><th>الخطة</th><th>المبلغ</th><th>الحالة</th><th>الدفع / العملية</th><th>التاريخ</th><th>إجراءات</th></tr></thead>
        <tbody id="orders-tbody"></tbody>
      </table>
    </div>
  </div>

  <!-- Codes Panel -->
  <div class="panel" id="tab-codes">
    <div class="mb-1">
      <button class="btn btn-primary" onclick="showModal('code-modal')">➕ إضافة كود</button>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>الكود</th><th>النوع</th><th>القيمة</th><th>الاستخدام</th><th>الحالة</th><th>ينتهي</th><th>إجراءات</th></tr></thead>
        <tbody id="codes-tbody"></tbody>
      </table>
    </div>
  </div>

  <!-- Events Panel -->
  <div class="panel" id="tab-events">
    <div class="flex search-bar">
      <input type="search" id="event-search" placeholder="بحث بمعرف الجهاز..." style="flex:1">
      <button class="btn btn-primary" onclick="searchEvents()">بحث</button>
      <button class="btn btn-warning" onclick="purgeEvents()">🧹 حذف الأقدم من (أيام)</button>
      <button class="btn btn-danger" onclick="wipeEvents()">🗑️ مسح كل السجل</button>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>الوقت</th><th>الجهاز</th><th>النوع</th><th>التفاصيل</th><th></th></tr></thead>
        <tbody id="events-tbody"></tbody>
      </table>
    </div>
  </div>

  <!-- Settings Panel -->
  <div class="panel" id="tab-settings">
    <form id="settings-form" onsubmit="saveSettings(event)">
      <h3 style="margin-bottom:1rem;color:#60a5fa">🔔 التنبيهات والبوت <span class="section-num">1</span></h3>
      <div class="switch-row">
        <div>
          <div class="switch-title">إشعارات بوت تيليجرام</div>
          <div class="switch-desc">إشعارات الطلبات، إثباتات الدفع، التقرير اليومي والتصدير. الأوامر وتنبيهات الأمان تبقى تعمل دائماً.</div>
        </div>
        <label class="switch">
          <input type="checkbox" id="set-bot" ${botEnabled ? 'checked' : ''}>
          <span class="slider"></span>
        </label>
      </div>
      <div class="switch-row" style="margin-top:.75rem">
        <div>
          <div class="switch-title">تنبيه قرب انتهاء الاشتراك في تطبيق بوردي</div>
          <div class="switch-desc">يظهر للمستخدم «باقي على انتهاء اشتراكك …» ضمن المدة المحددة أدناه. الاشتراك مدى الحياة لا يعرض أي تنبيه.</div>
        </div>
        <label class="switch">
          <input type="checkbox" id="set-notice-enabled" ${noticeEnabled ? 'checked' : ''}>
          <span class="slider"></span>
        </label>
      </div>
      <div class="form-grid" style="margin-top:.75rem">
        <div class="form-group">
          <label>المدة قبل الانتهاء التي يبدأ عندها التنبيه</label>
          <div style="display:flex;gap:.5rem">
            <input type="number" id="set-notice-value" min="1" step="1" value="${noticeValue}">
            <select id="set-notice-unit" style="max-width:130px">
              <option value="days" ${noticeUnit === 'days' ? 'selected' : ''}>يوم</option>
              <option value="hours" ${noticeUnit === 'hours' ? 'selected' : ''}>ساعة</option>
              <option value="minutes" ${noticeUnit === 'minutes' ? 'selected' : ''}>دقيقة</option>
            </select>
          </div>
          <small style="color:#94a3b8">مثال: 3 أيام = يظهر التنبيه في آخر 72 ساعة. القيمة تُطبَّق على كل الأجهزة عند المزامنة.</small>
        </div>
        <div class="form-group">
          <label>المعاينة في تطبيق بوردي</label>
          <div class="notice-preview" id="notice-preview">باقي على انتهاء اشتراكك ${noticeValue} ${noticeUnit === 'days' ? 'يوم' : noticeUnit === 'hours' ? 'ساعة' : 'دقيقة'}</div>
          <small style="color:#94a3b8">يُحسب الوقت الفعلي المتبقي ويُعرض بنفس الوحدة المختارة.</small>
        </div>
      </div>
      <h3 style="margin:1.5rem 0 1rem;color:#60a5fa">⏱️ التوقيت والترخيص <span class="section-num">2</span></h3>
      <div class="form-grid">
        <div class="form-group">
          <label>مدة النسخة التجريبية</label>
          <div style="display:flex;gap:.5rem">
            <input type="number" id="set-trial-value" min="0" step="any" value="${trialValue}">
            <select id="set-trial-unit" style="max-width:130px">
              <option value="days" ${trialUnit === 'days' ? 'selected' : ''}>يوم</option>
              <option value="hours" ${trialUnit === 'hours' ? 'selected' : ''}>ساعة</option>
              <option value="minutes" ${trialUnit === 'minutes' ? 'selected' : ''}>دقيقة</option>
              <option value="seconds" ${trialUnit === 'seconds' ? 'selected' : ''}>ثانية</option>
            </select>
          </div>
          <small style="color:#94a3b8">تُطبَّق على الأجهزة الجديدة فقط — الحالي: ${trialSeconds > 0 ? `${trialSeconds} ثانية (${(trialSeconds / 86400).toFixed(2)} يوم)` : 'بلا تجربة مجانية (الجهاز الجديد يبدأ مقفلاً حتى الاشتراك)'}. القيمة 0 تُلغي التجربة المجانية.</small>
        </div>
        <div class="form-group">
          <label>المزامنة أثناء فتح التطبيق (ثانية) — شبه آنية</label>
          <input type="number" id="set-fg-sync" min="${FOREGROUND_SYNC_MIN}" max="${FOREGROUND_SYNC_MAX}" value="${foregroundSync}">
          <small style="color:#94a3b8">التطبيق يسأل السيرفر كل هذه المدة وهو مفتوح (${FOREGROUND_SYNC_MIN}–${FOREGROUND_SYNC_MAX} ثانية).</small>
        </div>
        <div class="form-group">
          <label>المزامنة في الخلفية (ثانية)</label>
          <input type="number" id="set-sync" min="${SYNC_INTERVAL_MIN}" max="${SYNC_INTERVAL_MAX}" value="${clampSyncInterval(settings.sync_interval_seconds)}">
          <small style="color:#94a3b8">حد النظام 15 دقيقة فعلياً؛ القيمة هنا تحدّد الجدولة (${SYNC_INTERVAL_MIN}–${SYNC_INTERVAL_MAX} ثانية).</small>
        </div>
        <div class="form-group">
          <label>مدة صلاحية رمز الاشتراك (أيام)</label>
          <input type="number" id="set-ttl" min="1" max="365" value="${parseInt(settings.paid_token_ttl_days || '7', 10)}">
        </div>
        <div class="form-group">
          <label>أيام السماح بعد الانتهاء</label>
          <input type="number" id="set-grace" min="0" max="60" value="${parseInt(settings.grace_days || '2', 10)}">
          <small style="color:#94a3b8">فترة يبقى فيها التطبيق يعمل بعد انتهاء الاشتراك أو التجربة. 0 = يتوقف فوراً عند الانتهاء بلا فترة سماح.</small>
        </div>
      </div>
      <h3 style="margin:1.5rem 0 1rem;color:#60a5fa">💳 الدفع والدعم <span class="section-num">3</span></h3>
      <div class="form-group">
        <label>طرق الدفع</label>
        <div id="pm-list" style="display:flex;flex-direction:column;gap:.5rem"></div>
        <button type="button" class="btn btn-secondary" onclick="openPaymentForm(-1)" style="margin-top:.5rem">＋ إضافة طريقة دفع</button>
        <div id="pm-form" style="display:none;margin-top:.75rem;padding:.75rem;border:1px solid #334155;border-radius:10px">
          <div class="form-grid">
            <div class="form-group">
              <label>اسم طريقة الدفع</label>
              <input type="text" id="pm-name" placeholder="مثال: زين كاش">
            </div>
            <div class="form-group">
              <label>رقم أو حساب التحويل</label>
              <input type="text" id="pm-account" placeholder="07XXXXXXXXX">
            </div>
          </div>
          <div class="form-group">
            <label>ملاحظة (اختياري)</label>
            <input type="text" id="pm-note" placeholder="تظهر للمشترك أسفل رقم التحويل">
          </div>
          <div class="form-group">
            <label>صورة الطريقة (تظهر كإيقونة كبيرة في تطبيق المشترك)</label>
            <div style="display:flex;align-items:center;gap:.75rem">
              <img id="pm-logo-preview" alt="شعار الطريقة" style="display:none;width:64px;height:64px;object-fit:contain;background:#0f172a;border:1px solid #334155;border-radius:10px;padding:4px">
              <div style="display:flex;flex-direction:column;gap:.4rem">
                <input type="file" id="pm-logo-file" accept="image/*" onchange="pmPickLogo(this)" style="font-size:.8rem">
                <button type="button" class="btn btn-secondary" onclick="pmRemoveLogo()" style="font-size:.72rem;align-self:flex-start">إزالة الصورة</button>
              </div>
            </div>
            <small style="color:#94a3b8">تُصغَّر الصورة تلقائياً إلى 256 بكسل وتُرفع عند حفظ الطريقة.</small>
          </div>
          <div class="form-group">
            <label>لون إطار الطريقة في التطبيق</label>
            <div style="display:flex;align-items:center;gap:.5rem">
              <input type="color" id="pm-color" value="#22c55e" style="width:52px;height:34px;padding:2px;background:#0f172a;border:1px solid #334155;border-radius:8px">
              <small style="color:#94a3b8">يُلوَّن بها إطار بطاقة التحويل الخاصة بهذه الطريقة.</small>
            </div>
          </div>
          <div id="pm-error" style="color:#fca5a5;font-size:.8rem;min-height:1rem"></div>
          <div class="flex" style="gap:.5rem">
            <button type="button" class="btn btn-primary" onclick="savePaymentForm()">حفظ الطريقة</button>
            <button type="button" class="btn btn-secondary" onclick="closePaymentForm()">إلغاء</button>
          </div>
        </div>
        <small style="color:#94a3b8;display:block;margin-top:.5rem">يظهر للمشترك صورة الطريقة (إيقونة) واسمها ورقم التحويل والملاحظة ولون الإطار — لا حاجة لأي صيغة تقنية.</small>
      </div>
      <script type="application/json" id="pm-initial">${JSON.stringify(parsePaymentMethods(settings.payment_methods)).replace(/</g, '\\u003c')}</script>
      <script>
        let paymentMethodsData = [];
        try {
          const rawPm = JSON.parse(document.getElementById('pm-initial').textContent || '[]');
          paymentMethodsData = Array.isArray(rawPm) ? rawPm : [];
        } catch (err) { paymentMethodsData = []; }
        let editingPaymentIndex = -1;
        // null = untouched, 'remove' = delete on save, {dataUrl} = upload on save.
        let pmLogoPending = null;

        function escPm(value) {
          return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
        }

        function pmError(message) {
          document.getElementById('pm-error').textContent = message;
        }

        function genPmId() {
          let s = 'pm';
          const hex = '0123456789abcdef';
          for (let i = 0; i < 10; i++) s += hex[Math.floor(Math.random() * 16)];
          return s;
        }

        function renderPaymentMethods() {
          const box = document.getElementById('pm-list');
          if (!box) return;
          if (!paymentMethodsData.length) {
            box.innerHTML = '<div style="color:#94a3b8;font-size:.85rem">لا توجد طرق دفع بعد — أضف واحدة ليظهر زر الدفع في تطبيق بوردي.</div>';
            return;
          }
          box.innerHTML = paymentMethodsData.map(function (m, i) {
            const logo = m.has_logo && m.id
              ? '<img src="/v1/payment-logo?id=' + encodeURIComponent(m.id) + '&t=' + Date.now() + '" alt="" style="width:44px;height:44px;object-fit:contain;background:#0f172a;border:1px solid #334155;border-radius:10px;padding:3px">'
              : '<div style="width:44px;height:44px;display:flex;align-items:center;justify-content:center;background:#0f172a;border:1px solid #334155;border-radius:10px;font-weight:700;color:#94a3b8">' + escPm((m.name || '?').charAt(0)) + '</div>';
            return '<div style="display:flex;align-items:center;gap:.75rem;padding:.6rem .75rem;border:1px solid ' + escPm(m.border_color || '#334155') + ';border-radius:10px">'
              + logo
              + '<div style="flex:1;min-width:0">'
              + '<div style="font-weight:600">' + escPm(m.name) + '</div>'
              + '<div style="font-family:monospace;font-size:.85rem" dir="ltr">' + escPm(m.account) + '</div>'
              + (m.note ? '<div style="color:#94a3b8;font-size:.8rem">' + escPm(m.note) + '</div>' : '')
              + '</div>'
              + '<button type="button" class="btn btn-secondary" onclick="openPaymentForm(' + i + ')" style="font-size:.75rem">تعديل</button>'
              + '<button type="button" class="btn btn-danger" onclick="deletePaymentMethod(' + i + ')" style="font-size:.75rem">حذف</button>'
              + '</div>';
          }).join('');
        }

        function openPaymentForm(index) {
          editingPaymentIndex = typeof index === 'number' ? index : -1;
          const current = editingPaymentIndex >= 0 ? paymentMethodsData[editingPaymentIndex] : null;
          pmLogoPending = null;
          document.getElementById('pm-logo-file').value = '';
          document.getElementById('pm-name').value = current ? current.name : '';
          document.getElementById('pm-account').value = current ? current.account : '';
          document.getElementById('pm-note').value = current && current.note ? current.note : '';
          document.getElementById('pm-color').value = current && current.border_color ? current.border_color : '#22c55e';
          const preview = document.getElementById('pm-logo-preview');
          if (current && current.has_logo && current.id) {
            preview.src = '/v1/payment-logo?id=' + encodeURIComponent(current.id) + '&t=' + Date.now();
            preview.style.display = 'block';
          } else {
            preview.removeAttribute('src');
            preview.style.display = 'none';
          }
          pmError('');
          document.getElementById('pm-form').style.display = 'block';
          document.getElementById('pm-name').focus();
        }

        function closePaymentForm() {
          editingPaymentIndex = -1;
          pmLogoPending = null;
          document.getElementById('pm-form').style.display = 'none';
        }

        function pmPickLogo(input) {
          const file = input.files && input.files[0];
          input.value = '';
          if (!file) return;
          if (!/^image\//.test(file.type || '')) { pmError('اختر ملف صورة (PNG أو JPG)'); return; }
          const img = new Image();
          img.onload = function () {
            URL.revokeObjectURL(img.src);
            const max = 256;
            let w = img.naturalWidth || max, h = img.naturalHeight || max;
            if (w > max || h > max) {
              const scale = Math.min(max / w, max / h);
              w = Math.max(1, Math.round(w * scale));
              h = Math.max(1, Math.round(h * scale));
            }
            const canvas = document.createElement('canvas');
            canvas.width = w;
            canvas.height = h;
            canvas.getContext('2d').drawImage(img, 0, 0, w, h);
            pmLogoPending = { dataUrl: canvas.toDataURL('image/png') };
            const preview = document.getElementById('pm-logo-preview');
            preview.src = pmLogoPending.dataUrl;
            preview.style.display = 'block';
            pmError('');
          };
          img.onerror = function () { URL.revokeObjectURL(img.src); pmError('تعذر قراءة الصورة'); };
          img.src = URL.createObjectURL(file);
        }

        function pmRemoveLogo() {
          pmLogoPending = 'remove';
          const preview = document.getElementById('pm-logo-preview');
          preview.removeAttribute('src');
          preview.style.display = 'none';
        }

        async function savePaymentForm() {
          const name = document.getElementById('pm-name').value.trim();
          const account = document.getElementById('pm-account').value.trim();
          const note = document.getElementById('pm-note').value.trim();
          const color = document.getElementById('pm-color').value;
          if (!name || !account) {
            pmError('اسم الطريقة ورقم التحويل مطلوبان.');
            return;
          }
          let entry;
          if (editingPaymentIndex >= 0) {
            entry = Object.assign({}, paymentMethodsData[editingPaymentIndex], { name: name, account: account, note: note, border_color: color });
          } else {
            entry = { id: genPmId(), name: name, account: account, note: note, border_color: color, has_logo: false };
          }
          const saveBtn = document.querySelector('#pm-form .btn-primary');
          if (saveBtn) saveBtn.disabled = true;
          try {
            if (pmLogoPending === 'remove') {
              const res = await api('payment-logo', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: entry.id, logo_data: '' })
              });
              if (!res.ok) { pmError(res.error || 'تعذر حذف الصورة'); return; }
              entry.has_logo = false;
            } else if (pmLogoPending && pmLogoPending.dataUrl) {
              const res = await api('payment-logo', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: entry.id, logo_data: pmLogoPending.dataUrl })
              });
              if (!res.ok) { pmError(res.error || 'تعذر رفع الصورة'); return; }
              entry.has_logo = true;
            }
            if (editingPaymentIndex >= 0) paymentMethodsData[editingPaymentIndex] = entry;
            else paymentMethodsData.push(entry);
            closePaymentForm();
            renderPaymentMethods();
          } finally {
            if (saveBtn) saveBtn.disabled = false;
          }
        }

        async function deletePaymentMethod(index) {
          const m = paymentMethodsData[index];
          if (!confirm('حذف طريقة الدفع «' + m.name + '»؟')) return;
          paymentMethodsData.splice(index, 1);
          renderPaymentMethods();
          if (m.id && m.has_logo) {
            const res = await api('payment-logo', {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ id: m.id, logo_data: '' })
            });
            if (!res.ok) alert(res.error || 'تعذر حذف صورة الطريقة المحذوفة');
          }
        }

        renderPaymentMethods();
      </script>
      <div class="form-grid">
        <div class="form-group">
          <label>واتساب الدعم (رابط أو رقم)</label>
          <input type="text" id="set-wa" value="${escapeHtml(settings.support_whatsapp || '')}" placeholder="https://wa.me/9647XXXXXXXXX">
        </div>
        <div class="form-group">
          <label>تيليجرام الدعم</label>
          <input type="text" id="set-tg" value="${escapeHtml(settings.support_telegram || '')}" placeholder="https://t.me/username">
        </div>
      </div>
      <p style="color:#94a3b8;font-size:.8rem;margin-bottom:1rem">⚠️ لا تترك رقم الدعم بقيم XXXXXXXX — زر التأكيد عبر واتساب يعتمد عليه.</p>
      <h3 style="margin:1.5rem 0 1rem;color:#60a5fa">📄 الشروط والخصوصية <span class="section-num">4</span></h3>
      <div class="form-grid">
        <div class="form-group">
          <label>شروط الخدمة</label>
          <textarea id="set-tos">${escapeHtml(settings.tos_text || '')}</textarea>
        </div>
        <div class="form-group">
          <label>سياسة الخصوصية</label>
          <textarea id="set-privacy">${escapeHtml(settings.privacy_text || '')}</textarea>
        </div>
      </div>
      <div class="form-grid">
        <div class="form-group">
          <label>نسخة الشروط (رفعها يعيد عرضها على الجميع)</label>
          <input type="number" id="set-tos-ver" value="${settings.tos_version || '1'}">
        </div>
        <div class="form-group">
          <label>وضع الصيانة</label>
          <select id="set-maint"><option value="off" ${settings.maintenance !== 'on' ? 'selected' : ''}>إيقاف</option><option value="on" ${settings.maintenance === 'on' ? 'selected' : ''}>تشغيل</option></select>
        </div>
      </div>
      <button type="submit" class="btn btn-primary">💾 حفظ الإعدادات</button>
    </form>
    <hr style="border-color:#334155;margin:2rem 0">
    <h3 style="margin-bottom:1rem;color:#f87171">⚠️ منطقة الخطر <span class="section-num">5</span></h3>
    <div class="flex" style="gap:.5rem;flex-wrap:wrap">
      <button type="button" class="btn btn-danger" onclick="wipeEvents()">🗑️ مسح كل سجل الأحداث</button>
      <button type="button" class="btn btn-warning" onclick="resetRateLimits()">♻️ تصفير حدود المعدل</button>
    </div>
    <p style="color:#94a3b8;font-size:.8rem;margin-top:.75rem">حذف مشترك معيّن متاح من تبويب «المشتركين» — يحذف بياناته وطلباته وسجله ليعود كجهاز جديد.</p>
  </div>
</div>

<!-- Update Panel -->
<div class="panel" id="tab-update">
  <h3 style="margin-bottom:1rem;color:#60a5fa">🚀 إصدار التطبيق والتحديثات الإجبارية</h3>
  <div class="form-grid">
    <div class="form-group">
      <label>أقل إصدار مسموح به</label>
      <input type="text" inputmode="decimal" id="upd-minv" placeholder="1.0">
      <small style="color:#94a3b8">المقترح حالياً: <b>1.0</b> — أي جهاز بنسخة أقدم يتوقف فوراً ويطلب التحديث. يقبل 1.0 و 2.1 والأرقام العربية ١.٠. اجعله مثلاً 1.1 لإجبار كل من هو على 1.0 بالتحديث.</small>
    </div>
    <div class="form-group">
      <label>أحدث إصدار (الذي يُثبَّت على الأجهزة)</label>
      <input type="text" inputmode="decimal" id="upd-latest" placeholder="1.0">
      <small style="color:#94a3b8">المقترح: يطابق أقل إصدار مسموح. إذا جعلته <b>أقدم</b> من نسخة بعض المستخدمين (مثلاً رجّعته إلى 1.0 بعد مشكلة في 1.1) فسيُطلب منهم الرجوع للنسخة الأقدم — بياناتهم محفوظة سحابياً ويستعيدونها بعد إعادة التثبيت.</small>
    </div>
    <div class="form-group">
      <label>فترة التحقق من التحديثات</label>
      <div class="flex" style="gap:.5rem">
        <input type="number" id="upd-check-value" min="5" step="1" style="flex:1">
        <select id="upd-check-unit" style="max-width:130px">
          <option value="hours">ساعة</option>
          <option value="minutes">دقيقة</option>
          <option value="seconds">ثانية</option>
        </select>
      </div>
      <small style="color:#94a3b8">المقترح: <b>12 ساعة</b> — يكتشف التحديث خلال ساعات باستهلاك شبه معدوم. الحد الأدنى <b>5</b>. الدقائق والثوانٍ القليلة للاختبار فقط: الفحص المتكرر يستهلك البطارية وباقة البيانات، وأندرويد قد يؤجّل الفحص في وضع توفير الطاقة.</small>
    </div>
    <div class="form-group">
      <label>التنزيل التلقائي في الخلفية</label>
      <div class="switch-row">
        <div>
          <div class="switch-title">التنزيل عبر Wi‑Fi فقط</div>
          <div class="switch-desc">عند الإجبار بتحديث، يُحمَّل ملف التثبيت في الخلفية بدون استهلاك باقة الإنترنت.</div>
        </div>
        <label class="switch">
          <input type="checkbox" id="upd-wifi" checked>
          <span class="slider"></span>
        </label>
      </div>
      <small style="color:#94a3b8">المقترح: <b>مفعّل</b> — يحمي باقة المشترك. عطّله مؤقتاً فقط لو أردت وصول التحديث الإجباري لمن لا يملك Wi‑Fi (سيستهلك من باقتهم).</small>
    </div>
  </div>
  <div class="form-group">
    <label>ملاحظات تظهر للمشترك في شاشة التحديث (اختياري)</label>
    <input type="text" id="upd-notes" maxlength="300" placeholder="مثال: تحديث مهم يصلح مشكلة الفواتير">
    <small style="color:#94a3b8">المقترح: جملة قصيرة واحدة توضّح سبب التحديث — تزيد نسبة التثبيت الفوري.</small>
  </div>
  <button type="button" class="btn btn-primary" onclick="saveUpdateSettings()">💾 حفظ إعدادات التحديث</button>

  <hr style="border-color:#334155;margin:2rem 0">
  <h3 style="margin-bottom:.5rem;color:#fbbf24">📣 تنبيه فوري بالتحديث (Trigger)</h3>
  <p style="color:#94a3b8;font-size:.85rem;margin-bottom:1rem">
    زرّ واحد يحفّز المشتركين على التحديث فوراً بدل انتظار الفحص الدوري: من يترك التطبيق مفتوحاً يراه خلال ثوانٍ، ومن يفتحه بعد ذلك يراه لحظة فتحه.
    <b>لا يُلغي هذا الزر الفحص الدوري المجدول ولا يُغيّر فترته</b> — يبقى يعمل كخطة احتياطية للأجهزة المغلقة.
  </p>
  <div class="form-grid">
    <div class="form-group">
      <label>نوع التنبيه عند الإطلاق</label>
      <select id="upd-trig-mode">
        <option value="optional">اختياري — نافذة «تحديث الآن / لاحقاً» لا تمنع العمل</option>
        <option value="forced">إجباري — شاشة حاجزة حتى يتم التحديث</option>
      </select>
      <small style="color:#94a3b8">المقترح عادةً: <b>اختياري</b> — يرفع نسبة التحديث بلا توتير. اجعله <b>إجباري</b> عند خطأ خطير أو ثغرة. لن يظهر أي تنبيه لمن هو على أحدث إصدار بالفعل، ولا لمن هو محجوب أصلاً بـ «أقل إصدار مسموح» (ذاك شاشة تحديث إجباري دائمة).</small>
    </div>
    <div class="form-group">
      <label>رسالة التنبيه (اختياري)</label>
      <input type="text" id="upd-trig-msg" maxlength="200" placeholder="مثال: تحديث يهم دقة حساباتك — ثبّته الآن">
      <small style="color:#94a3b8">جملة قصيرة تُعرض فوق زر التحديث مباشرة داخل التطبيق.</small>
    </div>
  </div>
  <div class="flex" style="gap:.5rem;flex-wrap:wrap">
    <button type="button" class="btn btn-warning" onclick="fireUpdateTrigger()">📣 أطلق التنبيه الآن</button>
    <button type="button" class="btn btn-secondary" onclick="stopUpdateTrigger()">⏹ إيقاف التنبيه</button>
  </div>
  <p id="upd-trig-state" style="margin-top:.75rem;font-size:.85rem;color:#94a3b8"></p>
  <p style="color:#94a3b8;font-size:.8rem;margin-top:.5rem">ملاحظة: لا يوجد إشعار push يصل الجهاز وهو مغلق — ذاك يحتاج Firebase (خارج نطاق الاستضافة المجانية الحالية). التنبيه يصل كل جهاز عند أول اتصال له بالخادم: فوراً إن كان التطبيق مفتوحاً، أو لحظة فتحه.</p>

  <hr style="border-color:#334155;margin:2rem 0">
  <h3 style="margin-bottom:.5rem;color:#60a5fa">📦 رفع نسخة جديدة (APK)</h3>
  <p style="color:#94a3b8;font-size:.85rem;margin-bottom:1rem">ارفع ملف APK هنا — يُخزَّن مجاناً (GitHub Releases افتراضياً، أو R2 إن فعّلته) ويصبح هو «أحدث إصدار» تلقائياً. لا تنسَ رفع versionCode أعلى في كل نسخة جديدة.</p>
  <div class="form-grid">
    <div class="form-group">
      <label>ملف APK (بحد أقصى 100MB)</label>
      <input type="file" id="upd-file" accept=".apk,application/vnd.android.package-archive" style="background:#0f172a;border:1px solid #334155;color:#e2e8f0;padding:.5rem;border-radius:6px;width:100%">
    </div>
    <div class="form-group">
      <label>رقم إصدار هذه النسخة</label>
      <input type="text" inputmode="decimal" id="upd-file-ver" placeholder="مثال: 1.1">
      <small style="color:#94a3b8">يجب أن يطابق versionName داخل الـ APK، وأن يكون أعلى من أقل إصدار مسموح ليُقبل التثبيت.</small>
    </div>
  </div>
  <button type="button" class="btn btn-success" onclick="uploadRelease()">⬆️ رفع وتعيين كأحدث إصدار</button>
  <p id="upd-msg" style="margin-top:.75rem;font-size:.85rem;min-height:1.2rem"></p>

  <div class="form-group" style="margin-top:1rem">
    <label>رابط تنزيل يدوي (بديل للرفع — اختياري)</label>
    <input type="text" id="upd-url" dir="ltr" placeholder="https://github.com/.../bordy-1.1.apk">
    <small style="color:#94a3b8">إن رفعت الملف يدوياً إلى GitHub Releases أو أي استضافة، الصق الرابط المباشر هنا ثم اضبط «أحدث إصدار» أعلاه. يُحفظ مع زر «حفظ إعدادات التحديث».</small>
  </div>

  <hr style="border-color:#334155;margin:2rem 0">
  <h3 style="margin-bottom:.5rem;color:#60a5fa">🗂️ النسخ المرفوعة</h3>
  <div id="upd-releases" style="margin-top:.75rem"></div>
</div>

<!-- Code Modal -->
<div class="modal" id="code-modal">
  <div class="modal-content">
    <div class="modal-header"><h3>إضافة كود جديد</h3><span class="close" onclick="hideModal('code-modal')">&times;</span></div>
    <form onsubmit="createCode(event)">
      <div class="form-grid">
        <div class="form-group"><label>الكود</label><input type="text" id="nc-code" required></div>
        <div class="form-group"><label>النوع</label><select id="nc-kind"><option>PERCENT</option><option>FIXED</option><option>FREE_DAYS</option><option>FULL</option></select></div>
        <div class="form-group"><label>القيمة</label><input type="number" id="nc-value" value="0" required></div>
        <div class="form-group"><label>صالح لـ (أيام)</label><input type="number" id="nc-days" value="30" required></div>
        <div class="form-group"><label>الخطط (* = الكل)</label><input type="text" id="nc-plans" value="*"></div>
        <div class="form-group"><label>أقصى استخدام</label><input type="number" id="nc-max" placeholder="غير محدود"></div>
        <div class="form-group"><label>لكل جهاز</label><input type="number" id="nc-per-device" value="1"></div>
        <div class="form-group"><label>تطبيق تلقائي</label><select id="nc-auto"><option value="0">لا</option><option value="1">نعم</option></select></div>
      </div>
      <button type="submit" class="btn btn-primary mt-1">إنشاء</button>
    </form>
  </div>
</div>

<!-- Reject Modal -->
<div class="modal" id="reject-modal">
  <div class="modal-content">
    <div class="modal-header"><h3>سبب الرفض</h3><span class="close" onclick="hideModal('reject-modal')">&times;</span></div>
    <form onsubmit="confirmReject(event)">
      <input type="hidden" id="reject-order-id">
      <div class="form-group"><label>السبب</label><input type="text" id="reject-reason" required></div>
      <button type="submit" class="btn btn-danger mt-1">رفض الطلب</button>
    </form>
  </div>
</div>

<!-- Device Modal -->
<div class="modal" id="device-modal">
  <div class="modal-content" style="max-width:640px">
    <div class="modal-header"><h3>تعديل المشترك</h3><span class="close" onclick="hideModal('device-modal')">&times;</span></div>
    <input type="hidden" id="dv-id">
    <div id="dv-summary" style="font-size:.8rem;color:#94a3b8;margin-bottom:.75rem"></div>
    <div class="form-grid">
      <div class="form-group"><label>الاسم</label><input type="text" id="dv-name" placeholder="اسم المشترك"></div>
      <div class="form-group"><label>الهاتف</label><input type="text" id="dv-phone" placeholder="07XXXXXXXXX"></div>
    </div>
    <div class="form-group"><label>ملاحظات</label><input type="text" id="dv-notes"></div>
    <div class="form-group">
      <label>الاشتراك المدفوع — مدفوع حتى (بغداد)</label>
      <div style="display:flex;gap:.5rem;align-items:center">
        <input type="datetime-local" id="dv-paid-until" style="flex:1">
        <button type="button" class="btn btn-secondary" onclick="zeroPaid()" style="font-size:.75rem;white-space:nowrap">تصفير</button>
      </div>
      <small id="dv-paid-hint" style="color:#94a3b8;font-size:.75rem"></small>
    </div>
    <div class="form-group">
      <label>التجربة المجانية — تنتهي في (بغداد)</label>
      <div style="display:flex;gap:.5rem;align-items:center">
        <input type="datetime-local" id="dv-trial-ends" style="flex:1">
        <button type="button" class="btn btn-secondary" onclick="zeroTrial()" style="font-size:.75rem;white-space:nowrap">تصفير</button>
      </div>
      <small style="color:#94a3b8;font-size:.75rem">آخر لحظة تُحسب فيها التجربة المجانية؛ بعدها يتوقف التطبيق إن لم يوجد اشتراك مدفوع. العدّ يستمر في الخلفية حتى أثناء مدى الحياة. التصفير = لا تجربة متبقية.</small>
    </div>
    <div class="flex" style="gap:.5rem;margin:.25rem 0 .5rem;flex-wrap:wrap">
      <button type="button" class="btn btn-success" onclick="quickExtend(30)" style="font-size:.8rem">+30 يوم</button>
      <button type="button" class="btn btn-success" onclick="quickExtend(90)" style="font-size:.8rem">+90 يوم</button>
      <button type="button" class="btn btn-success" onclick="quickExtend(365)" style="font-size:.8rem">+365 يوم</button>
      <button type="button" class="btn btn-warning" onclick="setLifetime(true)" style="font-size:.8rem">مدى الحياة</button>
    </div>
    <div class="flex" style="gap:.5rem;margin:.25rem 0 1rem;flex-wrap:wrap">
      <label style="font-size:.8rem;color:#94a3b8;align-self:center">إعادة التجربة:</label>
      <input type="number" id="dv-trial-value" value="7" min="0" step="any" style="max-width:100px">
      <select id="dv-trial-unit" style="max-width:110px">
        <option value="days" selected>يوم</option>
        <option value="hours">ساعة</option>
        <option value="minutes">دقيقة</option>
        <option value="seconds">ثانية</option>
      </select>
      <button type="button" class="btn btn-warning" onclick="resetTrial()" style="font-size:.8rem">إعادة التعيين</button>
    </div>
    <div class="form-grid">
      <div class="form-group">
        <label>حظر الجهاز</label>
        <select id="dv-blocked"><option value="0">غير محظور</option><option value="1">محظور</option></select>
      </div>
      <div class="form-group"><label>سبب الحظر</label><input type="text" id="dv-block-reason" placeholder="يظهر داخل التطبيق"></div>
    </div>
    <label style="display:flex;align-items:center;gap:.5rem;margin:.5rem 0 .25rem;font-size:.9rem"><input type="checkbox" id="dv-lifetime" style="width:auto" onchange="toggleLifetime(this.checked)"> اشتراك مدى الحياة</label>
    <small style="display:block;color:#94a3b8;font-size:.75rem;margin-bottom:1rem">يُطبَّق فوراً عند التحديد أو الإلغاء؛ إلغاؤه يعيد العرض إلى ما تبقّى فعلاً من الاشتراك أو التجربة.</small>
    <div class="flex" style="gap:.5rem">
      <button type="button" class="btn btn-primary" onclick="saveDevice()" style="flex:1">💾 حفظ التعديلات</button>
      <button type="button" class="btn btn-danger" onclick="deleteDevice()">🗑️ حذف المشترك بالكامل</button>
      <button type="button" class="btn btn-secondary" onclick="hideModal('device-modal')">إلغاء</button>
    </div>
    <p id="dv-msg" style="font-size:.85rem;color:#fca5a5;margin-top:.5rem"></p>
  </div>
</div>

<div class="modal" id="account-create-modal">
  <div class="modal-content" style="max-width:520px;width:94%">
    <div class="modal-header"><h3>حساب جديد</h3><span class="close" onclick="hideModal('account-create-modal')">&times;</span></div>
    <div class="form-group"><label>اسم المستخدم</label><input id="nc-username" type="text" autocomplete="off" placeholder="3–40 حرفًا إنجليزيًا"></div>
    <div class="form-group"><label>كلمة المرور</label><input id="nc-password" type="text" autocomplete="off" placeholder="10 أحرف على الأقل"></div>
    <div class="form-group"><label>مدة التجربة بالأيام (0 = بلا تجربة)</label><input id="nc-trial" type="number" min="0" max="3650" value="0"></div>
    <div class="form-group"><label><input id="nc-lifetime" type="checkbox"> ترخيص مدى الحياة</label></div>
    <div class="flex"><button class="btn btn-success" onclick="createAccount()">إنشاء</button><button class="btn btn-secondary" onclick="hideModal('account-create-modal')">إلغاء</button></div>
    <p id="nc-msg" style="font-size:.85rem;color:#fca5a5;margin-top:.5rem"></p>
  </div>
</div>

<div class="modal" id="account-modal">
  <div class="modal-content" style="max-width:1100px;width:96%;max-height:92vh">
    <div class="modal-header"><h3 id="ac-title">الحساب السحابي</h3><span class="close" onclick="hideModal('account-modal')">&times;</span></div>
    <input type="hidden" id="ac-id">
    <div id="ac-summary" style="color:#cbd5e1;margin-bottom:.75rem"></div>
    <div class="flex" style="flex-wrap:wrap;margin-bottom:1rem">
      <button class="btn btn-success" onclick="setAccountState('active')">تفعيل</button>
      <button class="btn btn-warning" onclick="setAccountState('locked')">قفل</button>
      <button class="btn btn-danger" onclick="setAccountState('disabled')">تعطيل</button>
      <button class="btn btn-secondary" onclick="downloadAccountExport()">تنزيل نسخة JSON</button>
    </div>
    <div class="flex" style="flex-wrap:wrap;margin-bottom:1rem">
      <button class="btn btn-secondary" onclick="setLicense('trial',14)">تجربة 14 يوم</button>
      <button class="btn btn-success" onclick="setLicense('paid',30)">تمديد 30 يوم</button>
      <button class="btn btn-primary" onclick="setLicense('owner',0)">مدى الحياة</button>
      <button class="btn btn-danger" onclick="setLicense('expired',0)">إيقاف الاشتراك</button>
    </div>
    <div id="ac-devices" style="font-size:.85rem;color:#94a3b8;margin-bottom:.75rem"></div>
    <div id="ac-transfers" style="font-size:.85rem;color:#cbd5e1;margin-bottom:1rem"></div>
    <div class="flex search-bar">
      <select id="ac-entity-type" onchange="loadAccountRecords()" style="max-width:230px">
        <option value="">كل أنواع البيانات</option>
        <option value="suppliers">الموردون</option><option value="components">القطع</option>
        <option value="tags">الوسوم</option><option value="component_tags">وسوم القطع</option>
        <option value="component_variants">الأحجام</option><option value="supplier_offers">عروض الموردين</option>
        <option value="cart_items">السلة</option><option value="templates">القوالب</option>
        <option value="template_items">قطع القوالب</option><option value="projects">المشاريع</option>
        <option value="project_items">قطع المشاريع</option><option value="project_notes">ملاحظات المشاريع</option>
        <option value="business_settings">إعدادات النشاط</option>
      </select>
      <button class="btn btn-primary" onclick="startNewAccountRecord()">إضافة سجل</button>
      <span id="ac-record-count" style="color:#94a3b8"></span>
    </div>
    <div class="table-wrap" style="max-height:320px">
      <table><thead><tr><th>النوع</th><th>المعرف</th><th>المراجعة</th><th>آخر تعديل</th><th>البيانات</th><th>إجراءات</th></tr></thead><tbody id="ac-records-tbody"></tbody></table>
    </div>
    <div id="ac-editor" style="display:none;border-top:1px solid #334155;margin-top:1rem;padding-top:1rem">
      <input type="hidden" id="ac-record-id"><input type="hidden" id="ac-record-revision"><input type="hidden" id="ac-record-schema">
      <div class="form-group"><label id="ac-editor-label">تعديل السجل</label><textarea id="ac-record-json" style="min-height:220px;font-family:monospace;direction:ltr;text-align:left"></textarea></div>
      <div class="flex"><button class="btn btn-primary" onclick="saveAccountRecord()">حفظ عبر المزامنة</button><button class="btn btn-secondary" onclick="closeAccountEditor()">إلغاء</button></div>
    </div>
    <p id="ac-msg" style="font-size:.85rem;color:#fca5a5;margin-top:.75rem"></p>
  </div>
</div>

<script>
const CSRF_TOKEN = ${JSON.stringify(csrfToken)};

// Tab switching
document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
    tab.classList.add('active');
    document.getElementById('tab-' + tab.dataset.tab).classList.add('active');
    if (tab.dataset.tab === 'devices') searchDevices();
    if (tab.dataset.tab === 'accounts') searchAccounts();
    if (tab.dataset.tab === 'orders') loadOrders();
    if (tab.dataset.tab === 'codes') loadCodes();
    if (tab.dataset.tab === 'events') searchEvents();
    if (tab.dataset.tab === 'update') loadUpdatePanel();
  });
});

function showModal(id) { document.getElementById(id).classList.add('show'); }
function hideModal(id) { document.getElementById(id).classList.remove('show'); }
const UNIT_SECONDS = { seconds: 1, minutes: 60, hours: 3600, days: 86400 };
function esc(v) {
  return String(v == null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

async function api(path, opts) {
  try {
    const options = opts ? { ...opts } : {};
    const method = String(options.method || 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD') {
      options.headers = { ...(options.headers || {}), 'X-CSRF-Token': CSRF_TOKEN };
    }
    const r = await fetch('/admin/api/' + path, options);
    const ct = r.headers.get('content-type') || '';
    if (!ct.includes('application/json')) {
      return { ok: false, error: 'انتهت الجلسة أو تعذر الاتصال، أعد تحميل الصفحة' };
    }
    return await r.json();
  } catch (e) {
    return { ok: false, error: 'تعذر الاتصال بالخادم' };
  }
}

// Live refresh must not replace nodes under the owner's cursor, so a render only
// happens when the payload actually differs from what is already on screen.
const lastSig = {};
function sameAsRendered(key, value) {
  const sig = JSON.stringify(value);
  if (lastSig[key] === sig) return true;
  lastSig[key] = sig;
  return false;
}

let accountRecords = [];

async function createAccount() {
  const username = document.getElementById('nc-username').value.trim();
  const password = document.getElementById('nc-password').value;
  const trialDays = Number(document.getElementById('nc-trial').value || 0);
  const lifetime = document.getElementById('nc-lifetime').checked;
  const msg = document.getElementById('nc-msg');
  msg.textContent = '';
  const data = await api('accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, trial_days: trialDays, lifetime })
  });
  if (!data.ok) { msg.textContent = data.error || 'تعذر إنشاء الحساب'; return; }
  hideModal('account-create-modal');
  document.getElementById('nc-username').value = '';
  document.getElementById('nc-password').value = '';
  document.getElementById('nc-trial').value = '0';
  document.getElementById('nc-lifetime').checked = false;
  await searchAccounts();
}

async function searchAccounts() {
  const q = document.getElementById('account-search').value.trim();
  const status = document.getElementById('account-status').value;
  const data = await api('accounts?q=' + encodeURIComponent(q) + '&status=' + encodeURIComponent(status));
  const list = data.accounts || [];
  const tbody = document.getElementById('accounts-tbody');
  if (!list.length) {
    tbody.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#94a3b8">لا توجد حسابات</td></tr>';
    return;
  }
  tbody.innerHTML = list.map(a => {
    const stateClass = a.status === 'active' ? 'active' : a.status === 'locked' ? 'trial' : 'blocked';
    const license = a.lifetime ? 'مدى الحياة' : (a.license_status || '-');
    const warnings = Number(a.conflict_count || 0) + Number(a.transfer_count || 0);
    return '<tr><td><b>' + esc(a.username) + '</b><br><small>' + esc(a.id) + '</small></td>'
      + '<td><span class="badge ' + stateClass + '">' + esc(a.status) + '</span></td>'
      + '<td>' + esc(license) + '</td><td>' + esc(a.device_name || a.device_status || '-') + '</td>'
      + '<td>' + Number(a.record_count || 0) + '</td><td>' + (warnings ? '<span style="color:#fbbf24">' + warnings + '</span>' : '0') + '</td>'
      + '<td>' + fmtDate(a.last_seen_at) + '</td><td><button class="btn btn-primary" data-id="' + esc(a.id) + '" onclick="openAccount(this.dataset.id)">فتح</button></td></tr>';
  }).join('');
}

async function openAccount(accountId) {
  const data = await api('accounts/' + encodeURIComponent(accountId));
  if (!data.ok || !data.account) return alert(data.error || 'تعذر فتح الحساب');
  const a = data.account;
  document.getElementById('ac-id').value = a.id;
  document.getElementById('ac-title').textContent = 'الحساب: ' + a.username;
  document.getElementById('ac-summary').textContent = 'الحالة: ' + a.status + ' | الترخيص: ' + (a.lifetime ? 'مدى الحياة' : (a.license_status || '-'))
    + (a.paid_until ? ' (مدفوع حتى ' + fmtDate(a.paid_until) + ')' : '')
    + (a.trial_ends_at ? ' (تجربة حتى ' + fmtDate(a.trial_ends_at) + ')' : '')
    + ' | مؤشر المزامنة: ' + a.latest_change_id + ' | السجلات: ' + ((data.counts && data.counts.records) || 0)
    + ' | التعارضات المفتوحة: ' + ((data.counts && data.counts.conflicts) || 0);
  const devices = data.devices || [];
  document.getElementById('ac-devices').innerHTML = devices.length
    ? '<b>الأجهزة:</b> ' + devices.map(d => esc(d.name || d.id) + ' (' + esc(d.status) + '، مزامنة ' + fmtDate(d.last_sync_at) + ')').join(' — ')
    : 'لا توجد أجهزة مرتبطة';
  const transfers = data.transfers || [];
  document.getElementById('ac-transfers').innerHTML = transfers.length
    ? '<b>طلبات النقل:</b><br>' + transfers.map(t => {
        const controls = t.status === 'requested'
          ? ' <button class="btn btn-success" data-id="' + esc(t.id) + '" data-action="approve" onclick="reviewTransfer(this.dataset.id, this.dataset.action)">موافقة</button>'
            + ' <button class="btn btn-danger" data-id="' + esc(t.id) + '" data-action="reject" onclick="reviewTransfer(this.dataset.id, this.dataset.action)">رفض</button>'
          : '';
        return esc(t.id) + ' — ' + esc(t.status) + ' — ' + fmtDate(t.requested_at) + controls;
      }).join('<br>')
    : 'لا توجد طلبات نقل';
  document.getElementById('ac-msg').textContent = '';
  closeAccountEditor();
  showModal('account-modal');
  await loadAccountRecords();
}

async function setLicense(status, days) {
  const accountId = document.getElementById('ac-id').value;
  if (!accountId) return;
  const body = { status };
  if (status === 'trial') body.trial_days = days;
  if (status === 'paid') body.paid_days = days;
  const data = await api('accounts/' + encodeURIComponent(accountId) + '/license', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (!data.ok) {
    document.getElementById('ac-msg').textContent = data.error || 'تعذر تحديث الترخيص';
    return;
  }
  await openAccount(accountId);
  await searchAccounts();
}

async function loadAccountRecords() {  const accountId = document.getElementById('ac-id').value;
  if (!accountId) return;
  const type = document.getElementById('ac-entity-type').value;
  const data = await api('accounts/' + encodeURIComponent(accountId) + '/records?entity_type=' + encodeURIComponent(type));
  accountRecords = data.records || [];
  document.getElementById('ac-record-count').textContent = accountRecords.length + ' سجل';
  const tbody = document.getElementById('ac-records-tbody');
  if (!accountRecords.length) {
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#94a3b8">لا توجد بيانات من هذا النوع</td></tr>';
    return;
  }
  tbody.innerHTML = accountRecords.map((r, index) => {
    const preview = JSON.stringify(r.payload || {}).slice(0, 160);
    return '<tr><td>' + esc(r.entity_type) + '</td><td><small>' + esc(r.entity_id) + '</small></td><td>' + r.revision + '</td>'
      + '<td>' + fmtDate(r.updated_at) + '<br><small>' + esc(r.updated_by_type) + '</small></td><td><small>' + esc(preview) + '</small></td>'
      + '<td><button class="btn btn-primary" onclick="editAccountRecord(' + index + ')">تعديل</button> '
      + '<button class="btn btn-danger" onclick="deleteAccountRecord(' + index + ')">حذف</button></td></tr>';
  }).join('');
}

function editAccountRecord(index) {
  const record = accountRecords[index];
  if (!record) return;
  document.getElementById('ac-record-id').value = record.entity_id;
  document.getElementById('ac-record-revision').value = record.revision;
  document.getElementById('ac-record-schema').value = record.schema_version;
  document.getElementById('ac-entity-type').value = record.entity_type;
  document.getElementById('ac-editor-label').textContent = 'تعديل ' + record.entity_type + ' / ' + record.entity_id + ' — المراجعة ' + record.revision;
  document.getElementById('ac-record-json').value = JSON.stringify(record.payload, null, 2);
  document.getElementById('ac-editor').style.display = 'block';
}

function startNewAccountRecord() {
  const type = document.getElementById('ac-entity-type').value;
  if (!type) {
    document.getElementById('ac-msg').textContent = 'اختر نوع البيانات أولًا';
    return;
  }
  const id = crypto.randomUUID().replace(/-/g, '');
  document.getElementById('ac-record-id').value = id;
  document.getElementById('ac-record-revision').value = '0';
  document.getElementById('ac-record-schema').value = '1';
  document.getElementById('ac-editor-label').textContent = 'إضافة سجل ' + type + ' / ' + id;
  document.getElementById('ac-record-json').value = '{}';
  document.getElementById('ac-editor').style.display = 'block';
}

function closeAccountEditor() {
  document.getElementById('ac-editor').style.display = 'none';
}

async function saveAccountRecord() {
  const accountId = document.getElementById('ac-id').value;
  const entityType = document.getElementById('ac-entity-type').value;
  let payload;
  try { payload = JSON.parse(document.getElementById('ac-record-json').value); }
  catch (_) {
    document.getElementById('ac-msg').textContent = 'JSON غير صالح';
    return;
  }
  const mutation = {
    entity_type: entityType,
    entity_id: document.getElementById('ac-record-id').value,
    action: 'upsert',
    base_revision: Number(document.getElementById('ac-record-revision').value),
    schema_version: Number(document.getElementById('ac-record-schema').value),
    payload: payload,
    merge_policy: 'strict'
  };
  const data = await api('accounts/' + encodeURIComponent(accountId) + '/records', {
    method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ mutations: [mutation] })
  });
  if (!data.ok) {
    document.getElementById('ac-msg').textContent = (data.result && (data.result.error || data.result.reason || data.result.code)) || data.error || 'فشل التعديل';
    return;
  }
  document.getElementById('ac-msg').textContent = 'حُفظ التعديل وسيصل إلى الجهاز في المزامنة التالية';
  closeAccountEditor();
  await loadAccountRecords();
}

async function deleteAccountRecord(index) {
  const record = accountRecords[index];
  if (!record || !confirm('حذف هذا السجل؟ سيصل الحذف إلى الجهاز في المزامنة التالية.')) return;
  const accountId = document.getElementById('ac-id').value;
  const data = await api('accounts/' + encodeURIComponent(accountId) + '/records', {
    method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ mutations: [{
      entity_type: record.entity_type, entity_id: record.entity_id, action: 'delete',
      base_revision: record.revision, schema_version: record.schema_version, merge_policy: 'strict'
    }] })
  });
  if (!data.ok) {
    document.getElementById('ac-msg').textContent = (data.result && (data.result.error || data.result.reason || data.result.code)) || data.error || 'فشل الحذف';
    return;
  }
  document.getElementById('ac-msg').textContent = 'سُجل الحذف في المزامنة';
  await loadAccountRecords();
}

async function setAccountState(status) {
  if (!confirm('تغيير حالة الحساب إلى ' + status + '؟ ستُلغى جلساته الحالية.')) return;
  const accountId = document.getElementById('ac-id').value;
  const data = await api('accounts/' + encodeURIComponent(accountId) + '/state', {
    method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ status: status })
  });
  if (!data.ok) return alert(data.error || 'فشل تغيير الحالة');
  await openAccount(accountId);
}

async function reviewTransfer(transferId, action) {
  const label = action === 'approve' ? 'الموافقة على' : 'رفض';
  if (!confirm(label + ' طلب نقل الجهاز؟')) return;
  const accountId = document.getElementById('ac-id').value;
  const data = await api('accounts/' + encodeURIComponent(accountId) + '/transfers/' + encodeURIComponent(transferId), {
    method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ action: action })
  });
  if (!data.ok) return alert(data.error || 'فشل تحديث طلب النقل');
  await openAccount(accountId);
}

async function downloadAccountExport() {
  const accountId = document.getElementById('ac-id').value;
  const data = await api('accounts/' + encodeURIComponent(accountId) + '/export');
  if (!data.ok) return alert(data.error || 'فشل التنزيل');
  const blob = new Blob([JSON.stringify(data, null, 2)], {type:'application/json;charset=utf-8'});
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'bordy-account-' + accountId + '.json';
  a.click();
  URL.revokeObjectURL(url);
}

function deviceStatus(d, now) {
  if (d.blocked) return 'blocked';
  if (d.lifetime) return 'lifetime';
  if (d.paid_until > now) return 'paid';
  if (d.trial_ends_at > now) return 'trial';
  return 'expired';
}

// Devices
async function searchDevices() {
  const q = document.getElementById('device-search').value;
  const status = document.getElementById('device-status').value;
  const data = await api('devices?q=' + encodeURIComponent(q) + '&status=' + encodeURIComponent(status));
  const now = Date.now();
  const list = data.devices || [];
  const sig = {
    rows: list.map(d => [d.device_id, d.name, d.phone, d.model, d.paid_until, d.trial_ends_at, d.last_seen, deviceStatus(d, now)]),
    filter: q + '|' + status,
  };
  if (sameAsRendered('devices', sig)) return;

  const tbody = document.getElementById('devices-tbody');
  tbody.innerHTML = '';
  for (const d of list) {
    let st = '<span class="badge red">منتهي</span>';
    if (d.blocked) st = '<span class="badge red">محظور</span>';
    else if (d.lifetime) st = '<span class="badge blue">دائم</span>';
    else if (d.paid_until > now) st = '<span class="badge green">مشترك</span>';
    else if (d.trial_ends_at > now) st = '<span class="badge yellow">تجريبي</span>';

    tbody.innerHTML += '<tr>'
      + '<td><code>' + esc(d.device_id.substring(0,12)) + '...</code></td>'
      + '<td>' + st + '</td>'
      + '<td>' + (d.name ? esc(d.name) : '-') + '</td>'
      + '<td>' + (d.phone ? esc(d.phone) : '-') + '</td>'
      + '<td>' + (d.model ? esc(d.model) : '-') + '</td>'
      + '<td>' + (d.lifetime
          ? 'مدى الحياة' + (d.paid_until > now && d.paid_until < now + 50 * 365 * 86400000
              ? ' <span style="color:#94a3b8;font-size:.75rem">(مدفوع حتى ' + fmtDate(d.paid_until) + ')</span>'
              : '')
          : (d.paid_until > 0 ? fmtDate(d.paid_until) : '-')) + '</td>'
      + '<td>' + (d.trial_ends_at ? fmtDate(d.trial_ends_at) : '-') + '</td>'
      + '<td>' + (d.last_seen ? fmtDate(d.last_seen) : '-') + '</td>'
      + '<td><button class="btn btn-primary" onclick="openDeviceModal(\\'' + d.device_id + '\\')" style="font-size:.75rem">✏️ تعديل</button> '
      + '<button class="btn btn-danger" onclick="deleteDevice(\\'' + d.device_id + '\\')" style="font-size:.75rem">🗑️</button></td>'
      + '</tr>';
  }
}

// ── Device editor ──────────────────────────────────────────────────────────
function msToBaghdadInput(ms) {
  if (!ms) return '';
  const d = new Date(ms + 3*3600000);
  return d.getUTCFullYear() + '-' + String(d.getUTCMonth()+1).padStart(2,'0') + '-' + String(d.getUTCDate()).padStart(2,'0')
    + 'T' + String(d.getUTCHours()).padStart(2,'0') + ':' + String(d.getUTCMinutes()).padStart(2,'0');
}
function baghdadInputToMs(value) {
  if (!value) return null;
  const ms = Date.parse(value + ':00+03:00');
  return Number.isFinite(ms) ? ms : null;
}

let editingDevice = null;
async function openDeviceModal(did) {
  const res = await api('device/' + encodeURIComponent(did));
  if (!res.ok || !res.device) { alert('تعذر تحميل بيانات الجهاز'); return; }
  const d = res.device;
  editingDevice = d;
  document.getElementById('dv-id').value = d.device_id;
  document.getElementById('dv-name').value = d.name || '';
  document.getElementById('dv-phone').value = d.phone || '';
  document.getElementById('dv-notes').value = d.notes || '';
  applyDeviceToForm(d);
  document.getElementById('dv-msg').textContent = '';
  document.getElementById('dv-summary').innerHTML =
    'ID: <code>' + esc(d.device_id) + '</code> • الموديل: ' + esc(d.model || '-')
    + ' • الإصدار: ' + esc(d.app_ver || '-')
    + '<br>أول ظهور: ' + fmtDate(d.first_seen) + ' • آخر ظهور: ' + (d.last_seen ? fmtDate(d.last_seen) : '-');
  showModal('device-modal');
}

/** Fills only the server-owned fields, so unsaved name/phone edits in the form survive. */
function applyDeviceToForm(d) {
  editingDevice = d;
  document.getElementById('dv-paid-until').value = msToBaghdadInput(d.paid_until || 0);
  document.getElementById('dv-trial-ends').value = msToBaghdadInput(d.trial_ends_at || 0);
  document.getElementById('dv-blocked').value = d.blocked ? '1' : '0';
  document.getElementById('dv-block-reason').value = d.block_reason || '';
  document.getElementById('dv-lifetime').checked = !!d.lifetime;
  applyLifetimeUI(!!d.lifetime);
}

async function saveDevice(opts) {
  const did = document.getElementById('dv-id').value;
  const body = {
    action: 'update',
    name: document.getElementById('dv-name').value.trim(),
    phone: document.getElementById('dv-phone').value.trim(),
    notes: document.getElementById('dv-notes').value.trim(),
    // An empty field means "no paid subscription / no trial left", not "keep the old value".
    trial_ends_at: baghdadInputToMs(document.getElementById('dv-trial-ends').value) ?? 0,
    lifetime: document.getElementById('dv-lifetime').checked,
    blocked: document.getElementById('dv-blocked').value === '1',
    block_reason: document.getElementById('dv-block-reason').value.trim(),
  };
  // While lifetime is on, the stored paid date is preserved server-side and keeps counting
  // down in the background, so the field is not sent (it is disabled in the form anyway).
  if (!body.lifetime) {
    body.paid_until = baghdadInputToMs(document.getElementById('dv-paid-until').value) ?? 0;
  }
  const res = await api('device/' + encodeURIComponent(did), {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
  });
  if (res.ok) {
    if (opts && opts.keepOpen) {
      if (res.device) applyDeviceToForm(res.device);
    } else {
      hideModal('device-modal');
    }
    searchDevices();
    return true;
  }
  document.getElementById('dv-msg').textContent = res.error || 'تعذر الحفظ';
  return false;
}

async function deviceAction(payload) {
  const did = document.getElementById('dv-id').value;
  const res = await api('device/' + encodeURIComponent(did), {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)
  });
  if (!res.ok) { document.getElementById('dv-msg').textContent = res.error || 'تعذر التنفيذ'; return false; }
  if (res.device) applyDeviceToForm(res.device);
  document.getElementById('dv-msg').textContent = '';
  return true;
}

/** Lifetime hides the paid date but never clears it: it keeps counting down in the database. */
function applyLifetimeUI(lifetime) {
  const paid = document.getElementById('dv-paid-until');
  const hint = document.getElementById('dv-paid-hint');
  paid.disabled = !!lifetime;
  if (lifetime) {
    // The stored date is irrelevant while lifetime is on (the app never looks at it then),
    // and for old rows it may be a 100-year sentinel — so don't show a misleading date.
    paid.value = '';
    hint.textContent = 'مدى الحياة مفعّل — تاريخ الاشتراك المدفوع محفوظ ويستمر بالنقصان في الخلفية، ويعود تلقائياً عند إلغاء مدى الحياة.';
  } else {
    hint.textContent = 'اتركه فارغاً أو صفّره إن لم يكن هناك اشتراك مدفوع.';
  }
}

/**
 * Enabling saves the whole form so a date typed just before ticking is not lost.
 * Disabling goes through set_lifetime, which keeps the stored paid date; the update action
 * cannot be used because the paid field is empty while lifetime is on and would zero it.
 */
async function toggleLifetime(on) {
  if (on) {
    if (!(await saveDevice({ keepOpen: true }))) {
      document.getElementById('dv-lifetime').checked = false;
      applyLifetimeUI(false);
    }
    return;
  }
  if (!(await deviceAction({ action: 'set_lifetime', value: false }))) {
    document.getElementById('dv-lifetime').checked = true;
    applyLifetimeUI(true);
    return;
  }
  searchDevices();
}

async function zeroPaid() {
  if (await deviceAction({ action: 'zero_paid' })) searchDevices();
}

async function zeroTrial() {
  if (await deviceAction({ action: 'zero_trial' })) searchDevices();
}

async function quickExtend(days) {
  if (await deviceAction({ action: 'extend', days })) searchDevices();
}

async function setLifetime(on) {
  if (await deviceAction({ action: 'set_lifetime', value: on })) searchDevices();
}

async function resetTrial() {
  const value = parseFloat(document.getElementById('dv-trial-value').value || '7');
  const unit = document.getElementById('dv-trial-unit').value;
  const seconds = Math.round(value * (UNIT_SECONDS[unit] || 86400));
  if (!Number.isFinite(seconds) || seconds < 0) { document.getElementById('dv-msg').textContent = 'المدة غير صالحة'; return; }
  if (seconds === 0) { if (await deviceAction({ action: 'zero_trial' })) searchDevices(); return; }
  if (seconds < 30) { document.getElementById('dv-msg').textContent = 'المدة قصيرة جداً (30 ثانية كحد أدنى، أو 0 للتصفير)'; return; }
  if (await deviceAction({ action: 'reset_trial', seconds })) searchDevices();
}

async function deleteDevice(did) {
  did = did || document.getElementById('dv-id').value;
  if (!did) { alert('لم يُحدَّد المشترك'); return; }
  const res = await api('device/' + encodeURIComponent(did));
  if (!res.ok || !res.device) { alert('تعذر تحميل بيانات المشترك'); return; }
  const d = res.device;
  const c = res.counts || { orders: 0, events: 0, redemptions: 0 };
  const label = (d.name ? d.name + ' — ' : '') + d.device_id;
  const answer = prompt(
    'حذف المشترك نهائياً؟\\n' + label + '\\n\\n'
    + 'سيُحذف: الطلبات (' + c.orders + ')، السجل (' + c.events + ')، إثباتات الدفع، وأكواد الاستخدام.\\n'
    + 'سيعود هذا الجهاز كأنه جديد عند أول اتصال (تجربة جديدة).\\n\\n'
    + 'اكتب كلمة: حذف'
  );
  if (answer !== 'حذف') return;
  const r = await api('device/' + encodeURIComponent(did), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'delete', confirm: 'DELETE' })
  });
  if (!r.ok) { alert(r.error || 'تعذر الحذف'); return; }
  hideModal('device-modal');
  searchDevices();
}

// Orders
async function loadOrders() {
  const data = await api('orders');
  const orders = data.orders || [];
  if (sameAsRendered('orders', orders)) return;
  const tbody = document.getElementById('orders-tbody');
  tbody.innerHTML = '';
  for (const o of orders) {
    let st = o.status;
    let badge = 'yellow';
    if (o.status === 'APPROVED') badge = 'green';
    else if (o.status === 'REJECTED' || o.status === 'EXPIRED' || o.status === 'CANCELLED') badge = 'red';

    let actions = '';
    if (o.status === 'PENDING' || o.status === 'SUBMITTED') {
      actions = '<button class="btn btn-success" onclick="approveOrder(\\'' + o.id + '\\')" style="font-size:.75rem">✅</button> '
        + '<button class="btn btn-danger" onclick="rejectOrderModal(\\'' + o.id + '\\')" style="font-size:.75rem">❌</button> ';
    }
    if (o.has_proof) {
      actions += '<a class="btn btn-warning" style="font-size:.75rem;text-decoration:none" href="/admin/api/order/' + o.id + '/proof" target="_blank">🖼️</a>';
    }
    actions += ' <button class="btn btn-danger" onclick="deleteOrder(\\'' + o.id + '\\')" style="font-size:.75rem">🗑️</button>';

    let pay = o.method ? esc(o.method) : '-';
    if (o.txn_ref) pay += '<div style="font-size:.72rem;color:#93c5fd">#' + esc(o.txn_ref) + '</div>';
    if (o.payer_name) pay += '<div style="font-size:.72rem;color:#94a3b8">' + esc(o.payer_name) + (o.payer_phone ? ' — ' + esc(o.payer_phone) : '') + '</div>';
    if (o.reject_reason) pay += '<div style="font-size:.72rem;color:#fca5a5">' + esc(o.reject_reason) + '</div>';

    tbody.innerHTML += '<tr>'
      + '<td><code>' + esc(o.id) + '</code></td>'
      + '<td><code>' + esc(o.device_id.substring(0,8)) + '...</code></td>'
      + '<td>' + esc(o.plan_id) + '</td>'
      + '<td>' + o.final_price.toLocaleString() + '</td>'
      + '<td><span class="badge ' + badge + '">' + st + '</span></td>'
      + '<td>' + pay + '</td>'
      + '<td>' + fmtDate(o.created_at) + '</td>'
      + '<td>' + actions + '</td>'
      + '</tr>';
  }
}

async function approveOrder(id) {
  if (!confirm('تأكيد قبول الطلب ' + id + '؟')) return;
  await api('order/' + id + '/approve', {method:'POST'});
  loadOrders();
}

async function deleteOrder(id) {
  const answer = prompt('حذف الطلب ' + id + ' نهائياً مع صورة الإثبات؟\\nاكتب كلمة: حذف');
  if (answer !== 'حذف') return;
  const res = await api('order/' + id + '/delete', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirm: 'DELETE' })
  });
  if (!res.ok) { alert(res.error || 'تعذر حذف الطلب'); return; }
  loadOrders();
}

function rejectOrderModal(id) {
  document.getElementById('reject-order-id').value = id;
  showModal('reject-modal');
}

async function confirmReject(e) {
  e.preventDefault();
  const id = document.getElementById('reject-order-id').value;
  const reason = document.getElementById('reject-reason').value;
  await api('order/' + id + '/reject', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({reason})});
  hideModal('reject-modal');
  loadOrders();
}

// Codes
async function loadCodes() {
  const data = await api('codes');
  const codes = data.codes || [];
  if (sameAsRendered('codes', codes)) return;
  const tbody = document.getElementById('codes-tbody');
  tbody.innerHTML = '';
  for (const c of codes) {
    tbody.innerHTML += '<tr>'
      + '<td><code>' + c.code + '</code></td>'
      + '<td>' + c.kind + '</td>'
      + '<td>' + c.value + '</td>'
      + '<td>' + c.used_count + '/' + (c.max_uses ?? '∞') + '</td>'
      + '<td><span class="badge ' + (c.active ? 'green' : 'red') + '">' + (c.active ? 'نشط' : 'معطل') + '</span></td>'
      + '<td>' + fmtDate(c.ends_at) + '</td>'
      + '<td><button class="btn btn-warning" onclick="codeAction(\\'' + c.code + '\\', \\'toggle\\')" style="font-size:.75rem">' + (c.active ? '⏸️ تعطيل' : '▶️ تفعيل') + '</button> '
      + '<button class="btn btn-danger" onclick="codeAction(\\'' + c.code + '\\', \\'delete\\')" style="font-size:.75rem">🗑️</button></td>'
      + '</tr>';
  }
}

async function codeAction(code, action) {
  if (action === 'delete') {
    const answer = prompt('حذف الكود ' + code + ' نهائياً؟\\nاكتب كلمة: حذف');
    if (answer !== 'حذف') return;
  }
  const res = await api('codes/' + encodeURIComponent(code), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, confirm: action === 'delete' ? 'DELETE' : undefined })
  });
  if (!res.ok) { alert(res.error || 'تعذر التنفيذ'); return; }
  loadCodes();
}

async function createCode(e) {
  e.preventDefault();
  const body = {
    code: document.getElementById('nc-code').value.toUpperCase(),
    kind: document.getElementById('nc-kind').value,
    value: parseInt(document.getElementById('nc-value').value),
    days: parseInt(document.getElementById('nc-days').value),
    plans: document.getElementById('nc-plans').value,
    max_uses: document.getElementById('nc-max').value ? parseInt(document.getElementById('nc-max').value) : null,
    per_device: parseInt(document.getElementById('nc-per-device').value),
    auto_apply: parseInt(document.getElementById('nc-auto').value),
  };
  await api('codes', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)}).then(res => {
    if (res && res.ok) {
      hideModal('code-modal');
      document.getElementById('nc-code').value = '';
      loadCodes();
    } else {
      alert((res && res.error) || 'تعذر إنشاء الكود');
    }
  });
}

// Events
async function searchEvents() {
  const q = document.getElementById('event-search').value;
  const data = await api('events?q=' + encodeURIComponent(q));
  const events = data.events || [];
  if (sameAsRendered('events', { rows: events, filter: q })) return;
  const tbody = document.getElementById('events-tbody');
  tbody.innerHTML = '';
  for (const e of events) {
    tbody.innerHTML += '<tr>'
      + '<td>' + fmtDate(e.ts) + '</td>'
      + '<td><code>' + (e.device_id ? esc(e.device_id.substring(0,8)) + '...' : '-') + '</code></td>'
      + '<td>' + esc(e.type) + '</td>'
      + '<td style="max-width:400px;overflow:hidden;text-overflow:ellipsis">' + esc(e.detail || '-') + '</td>'
      + '<td><button class="btn btn-danger" onclick="deleteEvent(' + e.id + ')" title="حذف السجل" style="font-size:.72rem">🗑️</button></td>'
      + '</tr>';
  }
}

async function deleteEvent(id) {
  if (!confirm('حذف هذا السجل؟')) return;
  const res = await api('events/delete', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids: [id] })
  });
  if (!res.ok) { alert(res.error || 'تعذر الحذف'); return; }
  searchEvents();
}

async function purgeEvents() {
  const raw = prompt('حذف السجلات الأقدم من كم يوم؟\\n(0 = حذف كل شيء قبل الآن)', '30');
  if (raw === null) return;
  const days = Number(raw);
  if (!Number.isFinite(days) || days < 0) { alert('عدد أيام غير صالح'); return; }
  if (!confirm('حذف السجلات الأقدم من ' + days + ' يوم؟')) return;
  const res = await api('events/delete', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ older_than_days: days })
  });
  if (!res.ok) { alert(res.error || 'تعذر التنظيف'); return; }
  alert('تم حذف ' + res.deleted + ' سجل');
  searchEvents();
}

async function wipeEvents() {
  const answer = prompt('حذف كل سجل الأحداث نهائياً؟\\nاكتب كلمة: حذف');
  if (answer !== 'حذف') return;
  const res = await api('events/delete', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ all: true, confirm: 'DELETE' })
  });
  if (!res.ok) { alert(res.error || 'تعذر المسح'); return; }
  alert('تم حذف ' + res.deleted + ' سجل');
  if (document.getElementById('tab-events').classList.contains('active')) searchEvents();
}

async function resetRateLimits() {
  if (!confirm('تصفير كل حدود المعدل (المحاولات المؤقتة)؟')) return;
  const res = await api('rate-limits/reset', { method: 'POST' });
  alert(res.ok ? ('تم تصفير ' + res.deleted + ' عدّاد') : (res.error || 'تعذر التنفيذ'));
}

// Settings
async function saveSettings(e) {
  e.preventDefault();
  const trialValue = parseFloat(document.getElementById('set-trial-value').value || '7');
  const trialUnit = document.getElementById('set-trial-unit').value;
  const trialSeconds = Math.round(trialValue * (UNIT_SECONDS[trialUnit] || 86400));
  if (!Number.isFinite(trialSeconds) || trialSeconds < 0 || (trialSeconds > 0 && trialSeconds < 30)) {
    alert('مدة التجربة يجب أن تكون 0 (بلا تجربة) أو 30 ثانية على الأقل');
    return;
  }
  const body = {
    tos_text: document.getElementById('set-tos').value,
    privacy_text: document.getElementById('set-privacy').value,
    tos_version: document.getElementById('set-tos-ver').value,
    maintenance: document.getElementById('set-maint').value,
    payment_methods: JSON.stringify(paymentMethodsData),
    support_whatsapp: document.getElementById('set-wa').value,
    support_telegram: document.getElementById('set-tg').value,
    sync_interval_seconds: document.getElementById('set-sync').value,
    foreground_sync_seconds: document.getElementById('set-fg-sync').value,
    trial_seconds: trialSeconds,
    paid_token_ttl_days: document.getElementById('set-ttl').value,
    grace_days: document.getElementById('set-grace').value,
    bot_notifications_enabled: document.getElementById('set-bot').checked ? '1' : '0',
    expiry_notice_enabled: document.getElementById('set-notice-enabled').checked ? '1' : '0',
    expiry_notice_unit: document.getElementById('set-notice-unit').value,
    expiry_notice_value: document.getElementById('set-notice-value').value,
  };
  if (!paymentMethodsData.length) { alert('أضف طريقة دفع واحدة على الأقل قبل الحفظ'); return; }
  const res = await api('settings', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)});
  if (!res.ok) { alert('تعذر الحفظ: ' + (res.error || 'خطأ غير معروف')); return; }
  alert('تم الحفظ — ستظهر التغييرات في التطبيق خلال ثوانٍ');
  location.reload();
}

// Live preview for the expiry notice shown in the Bordy app.
function refreshNoticePreview() {
  const valueEl = document.getElementById('set-notice-value');
  const unitEl = document.getElementById('set-notice-unit');
  const preview = document.getElementById('notice-preview');
  if (!valueEl || !unitEl || !preview) return;
  const value = parseInt(valueEl.value || '0', 10);
  const unit = unitEl.value;
  const label = unit === 'days' ? 'يوم' : unit === 'hours' ? 'ساعة' : 'دقيقة';
  preview.textContent = 'باقي على انتهاء اشتراكك ' + (Number.isFinite(value) && value > 0 ? value : '?') + ' ' + label;
}
document.addEventListener('input', (ev) => {
  if (ev.target && (ev.target.id === 'set-notice-value' || ev.target.id === 'set-notice-unit')) refreshNoticePreview();
});

// Update panel — Arabic-Indic digits and ٫ are normalized so "١.٠" works like "1.0".
function normalizeVersionInput(v) {
  return String(v || '')
    .replace(/[٠-٩]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x0660 + 0x30))
    .replace(/[۰-۹]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x06F0 + 0x30))
    .replace(/[٫,]/g, '.')
    .trim();
}
const VERSION_RE = /^\\d+(\\.\\d+){0,3}$/;
let updateData = null;

function fmtUploadDate(iso) {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '-';
  return d.getUTCFullYear() + '-' + String(d.getUTCMonth()+1).padStart(2,'0') + '-' + String(d.getUTCDate()).padStart(2,'0')
    + ' ' + String(d.getUTCHours()).padStart(2,'0') + ':' + String(d.getUTCMinutes()).padStart(2,'0');
}

async function loadUpdatePanel() {
  const data = await api('update');
  const msg = document.getElementById('upd-msg');
  if (!data.ok) { msg.textContent = data.error || 'تعذر تحميل بيانات التحديث'; return; }
  updateData = data;
  const msgBox = document.getElementById('upd-msg');
  if (msgBox && !msgBox.textContent) {
    msgBox.style.color = data.storage === 'none' ? '#fcd34d' : '#6ee7b7';
    msgBox.textContent = data.storage_message || '';
  }
  document.getElementById('upd-minv').value = data.settings.min_app_version || '1.0';
  document.getElementById('upd-latest').value = data.settings.latest_app_version || '';
  document.getElementById('upd-check-unit').value = data.settings.update_check_unit || 'hours';
  document.getElementById('upd-check-value').value = data.settings.update_check_value || '12';
  const wifi = data.settings.update_wifi_only;
  document.getElementById('upd-wifi').checked = wifi !== '0' && wifi !== 'off';
  document.getElementById('upd-notes').value = data.settings.update_notes || '';
  document.getElementById('upd-url').value = data.settings.update_download_url || '';
  document.getElementById('upd-trig-mode').value = data.settings.update_trigger_mode === 'off' ? 'optional' : (data.settings.update_trigger_mode || 'optional');
  document.getElementById('upd-trig-msg').value = data.settings.update_trigger_message || '';
  renderTriggerState(data.settings);
  renderReleases(data.releases || [], data.settings.update_file || '');
}

function renderTriggerState(s) {
  const box = document.getElementById('upd-trig-state');
  if (!box) return;
  const mode = s.update_trigger_mode || 'off';
  if (mode === 'off') {
    box.style.color = '#94a3b8';
    box.textContent = 'الحالة: لا يوجد تنبيه معلّق. اضبط النوع والرسالة ثم اضغط «أطلق التنبيه الآن».';
    return;
  }
  const id = parseInt(s.update_trigger_id || '0', 10);
  const fired = id > 0 ? fmtUploadDate(new Date(id).toISOString()) : '-';
  box.style.color = mode === 'forced' ? '#fca5a5' : '#6ee7b7';
  box.textContent = 'الحالة: تنبيه ' + (mode === 'forced' ? 'إجباري' : 'اختياري') + ' معلَن منذ ' + fired
    + ' — يصل لكل جهاز يتصل بالخادم، والفحص الدوري باقٍ كما ضبطته.';
}

function renderReleases(releases, currentKey) {
  const box = document.getElementById('upd-releases');
  if (!releases.length) {
    box.innerHTML = '<div style="color:#94a3b8;font-size:.85rem">لا توجد نسخ مرفوعة بعد — ارفع أول APK بالأسفل.</div>';
    return;
  }
  box.innerHTML = '<div class="table-wrap"><table><thead><tr><th>الملف</th><th>الحجم</th><th>تاريخ الرفع</th><th>الحالة</th><th></th></tr></thead><tbody>'
    + releases.map(r => {
      const live = r.key === currentKey;
      const label = r.name || r.key.replace('releases/', '');
      return '<tr><td style="font-family:monospace" dir="ltr">' + esc(label) + '</td>'
        + '<td>' + (Math.round(r.size / 1024 / 1024 * 10) / 10) + ' MB</td>'
        + '<td>' + esc(fmtUploadDate(r.uploaded)) + '</td>'
        + '<td>' + (live ? '<span class="badge green">النسخة الحالية</span>' : '<span class="badge blue">احتياطي</span>') + '</td>'
        + '<td><button type="button" class="btn btn-danger" style="font-size:.75rem" onclick="deleteRelease(\\'' + esc(r.key) + '\\')">حذف</button></td></tr>';
    }).join('') + '</tbody></table></div>';
}

async function saveUpdateSettings() {
  const minv = normalizeVersionInput(document.getElementById('upd-minv').value);
  const latest = normalizeVersionInput(document.getElementById('upd-latest').value);
  if (!VERSION_RE.test(minv)) { alert('أقل إصدار غير صالح — استخدم صيغة مثل 1.0'); return; }
  if (!VERSION_RE.test(latest)) { alert('أحدث إصدار غير صالح — استخدم صيغة مثل 1.1'); return; }
  const checkValue = parseInt(document.getElementById('upd-check-value').value, 10);
  const checkUnit = document.getElementById('upd-check-unit').value;
  if (!Number.isFinite(checkValue) || checkValue < 5) {
    alert('فترة التحقق 5 على الأقل (5 ثوانٍ / 5 دقائق / 5 ساعات)'); return;
  }
  const res = await api('update/settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      min_app_version: minv,
      latest_app_version: latest,
      update_check_unit: checkUnit,
      update_check_value: checkValue,
      update_wifi_only: document.getElementById('upd-wifi').checked ? '1' : '0',
      update_notes: document.getElementById('upd-notes').value,
      update_download_url: document.getElementById('upd-url').value.trim()
    })
  });
  if (!res.ok) { alert(res.error || 'تعذر الحفظ'); return; }
  alert('تم الحفظ — يطبَّق على أجهزة المشتركين عند تحققهم التالي');
}

async function postTrigger(mode) {
  const message = document.getElementById('upd-trig-msg').value;
  const res = await api('update/settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ trigger: { mode, message } })
  });
  if (!res.ok) { alert(res.error || 'تعذر تنفيذ التنبيه'); return false; }
  return true;
}

async function fireUpdateTrigger() {
  const mode = document.getElementById('upd-trig-mode').value;
  if (mode !== 'forced' && !confirm('سيُطلب من كل مشترك متأخر التحديث كخيار اختياري. متابعة؟')) return;
  if (mode === 'forced' && !confirm('سيُمنع كل مشترك متأخر من استخدام التطبيق حتى يحدّث. متابعة؟')) return;
  if (!await postTrigger(mode)) return;
  await loadUpdatePanel();
  alert('أُطلق التنبيه ✓ — يظهر فوراً لمن التطبيق مفتوح لديه، ولغيره عند أول فتح أو فحص دوري.');
}

async function stopUpdateTrigger() {
  if (!await postTrigger('off')) return;
  await loadUpdatePanel();
}

async function uploadRelease() {
  const fileInput = document.getElementById('upd-file');
  const file = fileInput.files && fileInput.files[0];
  const version = normalizeVersionInput(document.getElementById('upd-file-ver').value);
  const msg = document.getElementById('upd-msg');
  if (!file) { alert('اختر ملف APK أولاً'); return; }
  if (!VERSION_RE.test(version)) { alert('رقم الإصدار غير صالح — مثال: 1.1'); return; }
  const maxBytes = updateData ? updateData.max_apk_mb * 1024 * 1024 : 100 * 1024 * 1024;
  if (file.size > maxBytes) { alert('حجم الملف ' + Math.round(file.size/1024/1024) + 'MB — الحد الأقصى ' + (maxBytes/1024/1024) + 'MB'); return; }
  const fd = new FormData();
  fd.append('version', version);
  fd.append('apk', file);
  msg.style.color = '#94a3b8';
  msg.textContent = 'جاري الرفع… لا تغلق الصفحة حتى يكتمل (' + Math.round(file.size/1024/1024) + 'MB)';
  const res = await api('releases', { method: 'POST', body: fd });
  if (!res.ok) { msg.style.color = '#fca5a5'; msg.textContent = res.error || 'فشل الرفع'; return; }
  msg.style.color = '#6ee7b7';
  msg.textContent = 'تم الرفع والتعيين كأحدث إصدار ✓ — سيُحمَّل تلقائياً لدى المشتركين المجبَرين على التحديث';
  fileInput.value = '';
  document.getElementById('upd-file-ver').value = '';
  loadUpdatePanel();
}

async function deleteRelease(key) {
  if (!confirm('حذف الملف ' + key + ' نهائياً؟')) return;
  const res = await api('releases/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ key })
  });
  if (!res.ok) { alert(res.error || 'تعذر الحذف'); return; }
  loadUpdatePanel();
}

async function exportCSV() {
  const data = await api('export');
  if (data.csv) {
    const blob = new Blob([data.csv], {type:'text/csv;charset=utf-8'});
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'devices_export.csv';
    a.click();
  }
}

function fmtDate(ms) {
  if (!ms) return '-';
  const d = new Date(ms + 3*3600000);
  return d.getUTCFullYear() + '-' + String(d.getUTCMonth()+1).padStart(2,'0') + '-' + String(d.getUTCDate()).padStart(2,'0')
    + ' ' + String(d.getUTCHours()).padStart(2,'0') + ':' + String(d.getUTCMinutes()).padStart(2,'0');
}

// Live refresh: keep the open table current without a manual reload.
setInterval(() => {
  if (document.hidden) return;
  if (document.querySelector('.modal.show')) return;
  const active = document.querySelector('.tab.active');
  if (!active) return;
  const tab = active.dataset.tab;
  if (tab === 'devices') searchDevices();
  else if (tab === 'accounts') searchAccounts();
  else if (tab === 'orders') loadOrders();
  else if (tab === 'events') searchEvents();
}, 6000);

// Initial load
searchDevices();
</script>
</body>
</html>`;

  return new Response(html, { headers: { 'Content-Type': 'text/html;charset=UTF-8', 'Cache-Control': 'no-store' } });
}

// --- API handlers ---

/** Same counters as the dashboard cards, exposed for the admin Android app. */
async function apiStats(env: Env): Promise<Response> {
  const now = Date.now();
  const sevenDays = now + 7 * 86400000;
  const thirtyDays = now - 30 * 86400000;

  const results = await env.DB.batch([
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE paid_until > ? OR lifetime = 1').bind(now),
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE trial_ends_at > ? AND paid_until < ? AND lifetime = 0').bind(now, now),
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE paid_until < ? AND (trial_ends_at IS NULL OR trial_ends_at < ?) AND lifetime = 0').bind(now, now),
    env.DB.prepare('SELECT COUNT(*) as c FROM devices WHERE paid_until > ? AND paid_until < ? AND lifetime = 0').bind(now, sevenDays),
    env.DB.prepare(`SELECT COALESCE(SUM(final_price), 0) as c FROM orders WHERE status = 'APPROVED' AND decided_at > ?`).bind(thirtyDays),
    env.DB.prepare(`SELECT COUNT(*) as c FROM orders WHERE status IN ('PENDING','SUBMITTED')`),
  ]);

  const count = (i: number) => Number((results[i].results[0] as any)?.c || 0);
  return Response.json({
    ok: true,
    stats: {
      active_subs: count(0),
      trials: count(1),
      expired: count(2),
      expiring_in_7: count(3),
      monthly_revenue: count(4),
      pending_orders: count(5),
    },
    server_time: now,
  });
}

async function apiDevices(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const q = url.searchParams.get('q') || '';
  const status = url.searchParams.get('status') || '';
  const now = Date.now();

  const where: string[] = [];
  const params: unknown[] = [];

  if (q) {
    where.push('(device_id LIKE ? OR name LIKE ? OR phone LIKE ?)');
    params.push(`%${q}%`, `%${q}%`, `%${q}%`);
  }

  // Status precedence must match the badge logic in the panel UI.
  if (status === 'blocked') {
    where.push('blocked = 1');
  } else if (status === 'active') {
    where.push('blocked = 0 AND (lifetime = 1 OR paid_until > ?)');
    params.push(now);
  } else if (status === 'trial') {
    where.push('blocked = 0 AND lifetime = 0 AND paid_until <= ? AND trial_ends_at > ?');
    params.push(now, now);
  } else if (status === 'expired') {
    where.push('blocked = 0 AND lifetime = 0 AND paid_until <= ? AND (trial_ends_at IS NULL OR trial_ends_at <= ?)');
    params.push(now, now);
  }

  const sql = 'SELECT * FROM devices'
    + (where.length ? ' WHERE ' + where.join(' AND ') : '')
    + ' ORDER BY first_seen DESC LIMIT 50';

  const devices = await env.DB.prepare(sql).bind(...params).all<DeviceRow>();
  return Response.json({ devices: devices.results });
}

async function apiOrders(request: Request, env: Env): Promise<Response> {
  const orders = await env.DB.prepare(
    `SELECT orders.*, (SELECT COUNT(*) FROM proofs p WHERE p.order_id = orders.id) AS has_proof
     FROM orders ORDER BY orders.created_at DESC LIMIT 100`
  ).all<OrderRow & { has_proof: number }>();
  return Response.json({ orders: orders.results });
}

async function apiCodes(request: Request, env: Env): Promise<Response> {
  const codes = await env.DB.prepare('SELECT * FROM codes ORDER BY created_at DESC LIMIT 50').all<CodeRow>();
  return Response.json({ codes: codes.results });
}

async function apiCreateCode(request: Request, env: Env): Promise<Response> {
  const body = await request.json() as any;
  const now = Date.now();

  const code = normalizeCode(String(body.code || ''));
  const kind = String(body.kind || '');
  const value = parseInt(String(body.value ?? '0'), 10);
  const days = parseInt(String(body.days ?? '30'), 10);
  const maxUses = body.max_uses === null || body.max_uses === undefined || body.max_uses === ''
    ? null
    : parseInt(String(body.max_uses), 10);
  const perDevice = Math.max(1, parseInt(String(body.per_device ?? '1'), 10) || 1);
  const plans = String(body.plans || '*').split(',').map(p => p.trim()).filter(Boolean).join(',') || '*';

  if (!code) {
    return Response.json({ ok: false, error: 'أدخل رمز الكود' }, { status: 400 });
  }
  if (!['PERCENT', 'FIXED', 'FULL', 'FREE_DAYS'].includes(kind)) {
    return Response.json({ ok: false, error: 'نوع الكود غير صالح' }, { status: 400 });
  }
  if (!Number.isFinite(value) || value < 0) {
    return Response.json({ ok: false, error: 'قيمة الكود غير صالحة' }, { status: 400 });
  }
  if ((kind === 'PERCENT' || kind === 'FULL') && value > 100) {
    return Response.json({ ok: false, error: 'نسبة الخصم لا تتجاوز 100' }, { status: 400 });
  }
  if (!Number.isFinite(days) || days <= 0) {
    return Response.json({ ok: false, error: 'مدة صلاحية الكود غير صالحة' }, { status: 400 });
  }
  if (maxUses !== null && (!Number.isFinite(maxUses) || maxUses <= 0)) {
    return Response.json({ ok: false, error: 'أقصى استخدام يجب أن يكون رقماً موجباً' }, { status: 400 });
  }

  try {
    // Never REPLACE an existing code: that would reset its usage counters.
    await env.DB.prepare(
      `INSERT INTO codes (code, kind, value, plans, starts_at, ends_at, max_uses, per_device_limit, auto_apply, created_at, active)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)`
    ).bind(
      code, kind, value, plans,
      now, now + days * 86400000,
      maxUses, perDevice,
      body.auto_apply ? 1 : 0, now
    ).run();
  } catch (e) {
    const message = e instanceof Error && /unique|constraint/i.test(e.message)
      ? 'هذا الكود موجود مسبقاً، اختر رمزاً آخر'
      : 'تعذر إنشاء الكود';
    return Response.json({ ok: false, error: message }, { status: 409 });
  }

  await logEvent(env.DB, null, 'CODE_CREATE', `Code ${code}: ${kind} ${value}`, 'admin');
  return Response.json({ ok: true, code });
}

async function apiEvents(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const q = url.searchParams.get('q') || '';
  let events;

  if (q) {
    events = await env.DB.prepare(
      'SELECT * FROM events WHERE device_id LIKE ? ORDER BY ts DESC LIMIT 100'
    ).bind(`%${q}%`).all<EventRow>();
  } else {
    events = await env.DB.prepare('SELECT * FROM events ORDER BY ts DESC LIMIT 100').all<EventRow>();
  }

  return Response.json({ events: events.results });
}

/** Delete individual rows, everything older than N days, or the whole log. */
async function apiDeleteEvents(request: Request, env: Env): Promise<Response> {
  const body = await parseJsonBody<{ ids?: number[]; older_than_days?: number; all?: boolean; confirm?: string }>(request);
  if (!body) return Response.json({ ok: false, error: 'صيغة الطلب غير صالحة' }, { status: 400 });

  let deleted = 0;

  if (Array.isArray(body.ids) && body.ids.length > 0) {
    const ids = body.ids.map(Number).filter(Number.isInteger).slice(0, 200);
    if (ids.length === 0) return Response.json({ ok: false, error: 'لا توجد سجلات محددة' }, { status: 400 });
    for (let i = 0; i < ids.length; i += 50) {
      const chunk = ids.slice(i, i + 50);
      const result = await env.DB
        .prepare(`DELETE FROM events WHERE id IN (${chunk.map(() => '?').join(',')})`)
        .bind(...chunk).run();
      deleted += result.meta.changes || 0;
    }
  } else if (body.all === true) {
    if (body.confirm !== 'DELETE') {
      return Response.json({ ok: false, error: 'تأكيد المسح مطلوب' }, { status: 400 });
    }
    const result = await env.DB.prepare('DELETE FROM events').run();
    deleted = result.meta.changes || 0;
  } else if (body.older_than_days !== undefined) {
    const days = Number(body.older_than_days);
    if (!Number.isFinite(days) || days < 0 || days > 3650) {
      return Response.json({ ok: false, error: 'عدد الأيام غير صالح' }, { status: 400 });
    }
    const cutoff = Date.now() - Math.round(days) * 86400000;
    const result = await env.DB.prepare('DELETE FROM events WHERE ts < ?').bind(cutoff).run();
    deleted = result.meta.changes || 0;
  } else {
    return Response.json({ ok: false, error: 'لم يُحدَّد ما يُحذف' }, { status: 400 });
  }

  await logEvent(env.DB, null, 'EVENTS_PURGE', `Deleted ${deleted} events`, 'admin');
  return Response.json({ ok: true, deleted });
}

async function apiResetRateLimits(env: Env): Promise<Response> {
  const result = await env.DB.prepare('DELETE FROM rate_limits').run();
  await logEvent(env.DB, null, 'RATE_LIMITS_RESET', `Cleared ${result.meta.changes || 0} counters`, 'admin');
  return Response.json({ ok: true, deleted: result.meta.changes || 0 });
}

async function apiCodeAction(request: Request, code: string, env: Env): Promise<Response> {
  const clean = normalizeCode(code);
  if (!clean) return Response.json({ ok: false, error: 'الكود غير صالح' }, { status: 400 });

  const body = await parseJsonBody<{ action?: string; confirm?: string }>(request);
  if (!body) return Response.json({ ok: false, error: 'صيغة الطلب غير صالحة' }, { status: 400 });

  const row = await env.DB.prepare('SELECT code FROM codes WHERE code = ?').bind(clean).first<{ code: string }>();
  if (!row) return Response.json({ ok: false, error: 'الكود غير موجود' }, { status: 404 });

  if (body.action === 'toggle') {
    await env.DB.prepare('UPDATE codes SET active = CASE WHEN active = 1 THEN 0 ELSE 1 END WHERE code = ?')
      .bind(clean).run();
    await logEvent(env.DB, null, 'CODE_TOGGLE', `Code ${clean} toggled`, 'admin');
    return Response.json({ ok: true });
  }

  if (body.action === 'delete') {
    if (body.confirm !== 'DELETE') {
      return Response.json({ ok: false, error: 'تأكيد الحذف مطلوب' }, { status: 400 });
    }
    await env.DB.batch([
      env.DB.prepare('DELETE FROM code_redemptions WHERE code = ?').bind(clean),
      env.DB.prepare('DELETE FROM codes WHERE code = ?').bind(clean),
    ]);
    await logEvent(env.DB, null, 'CODE_DELETE', `Code ${clean} deleted`, 'admin');
    return Response.json({ ok: true });
  }

  return Response.json({ ok: false, error: 'إجراء غير معروف' }, { status: 400 });
}

async function apiDeleteOrder(request: Request, orderId: string, env: Env): Promise<Response> {
  const body = await parseJsonBody<{ confirm?: string }>(request);
  if (!body || body.confirm !== 'DELETE') {
    return Response.json({ ok: false, error: 'تأكيد الحذف مطلوب' }, { status: 400 });
  }

  const order = await env.DB.prepare('SELECT id, device_id, code FROM orders WHERE id = ?')
    .bind(orderId).first<{ id: string; device_id: string; code: string | null }>();
  if (!order) return Response.json({ ok: false, error: 'الطلب غير موجود' }, { status: 404 });

  await env.DB.batch([
    env.DB.prepare('DELETE FROM proofs WHERE order_id = ?').bind(orderId),
    // Free any pending reservation, otherwise the code stays blocked on a row that no longer exists.
    env.DB.prepare(`UPDATE code_redemptions SET state = 'RELEASED' WHERE order_id = ? AND state = 'RESERVED'`).bind(orderId),
    env.DB.prepare('DELETE FROM orders WHERE id = ?').bind(orderId),
  ]);

  await logEvent(env.DB, order.device_id, 'ADMIN_ORDER_DELETE', `Order ${orderId} deleted`, 'admin');
  return Response.json({ ok: true });
}

async function apiApproveOrder(orderId: string, env: Env): Promise<Response> {
  const success = await approveOrder(env, orderId, 'admin');
  return Response.json({ ok: success });
}

async function apiRejectOrder(request: Request, orderId: string, env: Env): Promise<Response> {
  const body = await parseJsonBody<{ reason?: string }>(request);
  const reason = (body?.reason || '').trim() || 'تم الرفض من لوحة الإدارة';
  const success = await rejectOrder(env, orderId, reason, 'admin');
  return Response.json({ ok: success });
}

async function apiGetProof(orderId: string, env: Env): Promise<Response> {
  const proof = await env.DB.prepare('SELECT mime, data FROM proofs WHERE order_id = ?').bind(orderId).first<{ mime: string; data: unknown }>();
  const bytes = proof ? toBytes(proof.data) : null;
  if (!proof || !bytes || bytes.length === 0) {
    return Response.json({ ok: false, error: 'No proof found' }, { status: 404 });
  }
  // Stored bytes come from buyers, so never let the browser treat them as anything but an image.
  const mime = (proof.mime || '').toLowerCase();
  if (!mime.startsWith('image/')) {
    return Response.json({ ok: false, error: 'Unsupported proof type' }, { status: 415 });
  }
  return new Response(bytes, {
    headers: {
      'Content-Type': mime,
      'Content-Length': String(bytes.length),
      'X-Content-Type-Options': 'nosniff',
      'Content-Disposition': 'inline',
      'Content-Security-Policy': "default-src 'none'; img-src 'self'; sandbox",
      'Cache-Control': 'private, no-store',
    },
  });
}

/** D1 hands BLOBs back as ArrayBuffer, a typed array, or a plain byte map depending on runtime. */
function toBytes(data: unknown): Uint8Array | null {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  if (Array.isArray(data)) return Uint8Array.from(data as number[]);
  if (data && typeof data === 'object') {
    const values = Object.values(data as Record<string, unknown>);
    if (values.length && values.every(v => typeof v === 'number')) return Uint8Array.from(values as number[]);
  }
  return null;
}

async function apiGetDevice(deviceId: string, env: Env): Promise<Response> {
  const device = await env.DB.prepare('SELECT * FROM devices WHERE device_id = ?')
    .bind(deviceId).first<DeviceRow>();
  if (!device) return Response.json({ ok: false, error: 'الجهاز غير موجود' }, { status: 404 });

  const counts = await env.DB.batch([
    env.DB.prepare('SELECT COUNT(*) AS c FROM orders WHERE device_id = ?').bind(deviceId),
    env.DB.prepare('SELECT COUNT(*) AS c FROM events WHERE device_id = ?').bind(deviceId),
    env.DB.prepare('SELECT COUNT(*) AS c FROM code_redemptions WHERE device_id = ?').bind(deviceId),
  ]);

  return Response.json({
    ok: true,
    device,
    counts: {
      orders: (counts[0].results[0] as any)?.c || 0,
      events: (counts[1].results[0] as any)?.c || 0,
      redemptions: (counts[2].results[0] as any)?.c || 0,
    },
  });
}

async function apiUpdateDevice(request: Request, deviceId: string, env: Env): Promise<Response> {
  let body: any;
  try {
    body = await request.json();
  } catch {
    return Response.json({ ok: false, error: 'صيغة الطلب غير صالحة' }, { status: 400 });
  }

  const device = await env.DB.prepare('SELECT device_id, paid_until, trial_ends_at FROM devices WHERE device_id = ?')
    .bind(deviceId).first<{ device_id: string; paid_until: number; trial_ends_at: number | null }>();
  if (!device) return Response.json({ ok: false, error: 'الجهاز غير موجود' }, { status: 404 });

  const now = Date.now();
  const MAX_FUTURE = now + 20 * 365 * 86400000;
  const clampText = (value: any, max: number) => String(value == null ? '' : value).trim().substring(0, max);
  const validMs = (value: any) => {
    const n = Number(value);
    return Number.isFinite(n) && n >= 0 && n <= MAX_FUTURE ? Math.round(n) : null;
  };

  if (body.action === 'update') {
    const name = clampText(body.name, 64) || null;
    const phone = clampText(body.phone, 32) || null;
    const notes = clampText(body.notes, 200) || null;
    const hasPaidUntil = body.paid_until !== null && body.paid_until !== undefined && body.paid_until !== '';
    const paidUntil = hasPaidUntil ? validMs(body.paid_until) : device.paid_until;
    if (hasPaidUntil && paidUntil === null) {
      return Response.json({ ok: false, error: 'تاريخ الانتهاء غير صالح' }, { status: 400 });
    }
    const hasTrialEnds = body.trial_ends_at !== null && body.trial_ends_at !== undefined && body.trial_ends_at !== '';
    const trialEnds = hasTrialEnds ? validMs(body.trial_ends_at) : device.trial_ends_at;
    if (hasTrialEnds && trialEnds === null) {
      return Response.json({ ok: false, error: 'تاريخ نهاية التجربة غير صالح' }, { status: 400 });
    }
    const blocked = body.blocked === true || body.blocked === 'true' || body.blocked === 1 ? 1 : 0;
    const reason = blocked ? (clampText(body.block_reason, 200) || 'محظور من الإدارة') : null;
    const lifetime = body.lifetime === true || body.lifetime === 'true' ? 1 : 0;
    // Lifetime is a flag, not a sentinel date: the real paid date must survive so it keeps
    // counting down while lifetime is on and is restored the moment lifetime is removed.
    const finalPaidUntil = paidUntil;

    await env.DB.prepare(
      `UPDATE devices SET name = ?, phone = ?, notes = ?, paid_until = ?, trial_ends_at = ?,
         lifetime = ?, blocked = ?, block_reason = ? WHERE device_id = ?`
    ).bind(
      name, phone, notes, finalPaidUntil, trialEnds,
      lifetime, blocked, reason, deviceId
    ).run();

    await logEvent(env.DB, deviceId, 'ADMIN_UPDATE', 'Device data updated via panel', 'admin');
    const updated = await env.DB.prepare('SELECT * FROM devices WHERE device_id = ?')
      .bind(deviceId).first<DeviceRow>();
    return Response.json({ ok: true, device: updated });
  }

  const stmts: D1PreparedStatement[] = [];

  if (body.action === 'block') {
    const reason = clampText(body.reason, 200) || 'محظور من الإدارة';
    stmts.push(env.DB.prepare('UPDATE devices SET blocked = 1, block_reason = ? WHERE device_id = ?').bind(reason, deviceId));
    await logEvent(env.DB, deviceId, 'ADMIN_BLOCK', reason, 'admin');
  } else if (body.action === 'unblock') {
    stmts.push(env.DB.prepare('UPDATE devices SET blocked = 0, block_reason = NULL WHERE device_id = ?').bind(deviceId));
    await logEvent(env.DB, deviceId, 'ADMIN_UNBLOCK', 'Unblocked via panel', 'admin');
  } else if (body.action === 'extend') {
    const days = parseInt(body.days || '30');
    if (!Number.isFinite(days) || days < 1 || days > 3650) {
      return Response.json({ ok: false, error: 'عدد الأيام غير صالح' }, { status: 400 });
    }
    // Must not touch the lifetime flag: extending a lifetime device only adds background
    // paid time, matching the Telegram bot's behaviour.
    const base = Math.max(now, device.paid_until || 0);
    stmts.push(env.DB.prepare('UPDATE devices SET paid_until = ? WHERE device_id = ?')
      .bind(base + days * 86400000, deviceId));
    await logEvent(env.DB, deviceId, 'ADMIN_EXTEND', `+${days}d via panel`, 'admin');
  } else if (body.action === 'set_lifetime') {
    const on = body.value === true || body.value === 'true' || body.value === 1;
    if (on) {
      // Turning lifetime on only sets the flag; paid_until is left untouched so the paid
      // period (or trial) keeps elapsing in the background.
      stmts.push(env.DB.prepare('UPDATE devices SET lifetime = 1 WHERE device_id = ?').bind(deviceId));
    } else {
      // Older rows stored a 100-year sentinel instead of a real date — there is nothing
      // left to restore, so treat it as "no paid subscription".
      const legacySentinel = (device.paid_until || 0) > now + 50 * 365 * 86400000;
      stmts.push(env.DB.prepare('UPDATE devices SET lifetime = 0, paid_until = ? WHERE device_id = ?')
        .bind(legacySentinel ? 0 : device.paid_until, deviceId));
    }
    await logEvent(env.DB, deviceId, 'ADMIN_LIFETIME', on ? 'Lifetime enabled' : 'Lifetime disabled', 'admin');
  } else if (body.action === 'zero_paid') {
    stmts.push(env.DB.prepare('UPDATE devices SET paid_until = 0 WHERE device_id = ?').bind(deviceId));
    await logEvent(env.DB, deviceId, 'ADMIN_ZERO_PAID', 'Paid period cleared via panel', 'admin');
  } else if (body.action === 'zero_trial') {
    stmts.push(env.DB.prepare('UPDATE devices SET trial_ends_at = 0 WHERE device_id = ?').bind(deviceId));
    await logEvent(env.DB, deviceId, 'ADMIN_ZERO_TRIAL', 'Trial cleared via panel', 'admin');
  } else if (body.action === 'reset_trial') {
    // Accepts seconds (preferred) or the legacy whole-days field. Zero ends the trial now.
    const seconds = body.seconds !== null && body.seconds !== undefined && body.seconds !== ''
      ? Math.round(Number(body.seconds))
      : Math.round((parseInt(body.days || '7', 10) || 7) * 86400);
    if (seconds === 0) {
      stmts.push(env.DB.prepare('UPDATE devices SET trial_ends_at = 0 WHERE device_id = ?').bind(deviceId));
      await logEvent(env.DB, deviceId, 'ADMIN_ZERO_TRIAL', 'Trial cleared via reset', 'admin');
    } else {
      if (!Number.isFinite(seconds) || seconds < TRIAL_MIN_SECONDS || seconds > TRIAL_MAX_SECONDS) {
        return Response.json({
          ok: false,
          error: `مدة التجربة يجب أن تكون بين ${TRIAL_MIN_SECONDS} و${TRIAL_MAX_SECONDS} ثانية، أو 0 للتصفير`,
        }, { status: 400 });
      }
      const end = now + seconds * 1000;
      stmts.push(env.DB.prepare(
        'UPDATE devices SET trial_started_at = ?, trial_ends_at = ?, blocked = 0, block_reason = NULL WHERE device_id = ?'
      ).bind(now, end, deviceId));
      await logEvent(env.DB, deviceId, 'ADMIN_RESET_TRIAL', `trial restarted for ${seconds}s`, 'admin');
    }
  } else if (body.action === 'delete') {
    // Typed confirmation: this wipes the subscriber so the device re-registers as brand new.
    if (body.confirm !== 'DELETE') {
      return Response.json({ ok: false, error: 'تأكيد الحذف مطلوب' }, { status: 400 });
    }
    const orders = await env.DB.prepare('SELECT COUNT(*) AS c FROM orders WHERE device_id = ?')
      .bind(deviceId).first<{ c: number }>();

    await env.DB.batch([
      env.DB.prepare('DELETE FROM proofs WHERE order_id IN (SELECT id FROM orders WHERE device_id = ?)').bind(deviceId),
      env.DB.prepare('DELETE FROM code_redemptions WHERE device_id = ?').bind(deviceId),
      env.DB.prepare('DELETE FROM rate_limits WHERE k IN (?, ?, ?, ?)')
        .bind(`code:${deviceId}`, `redeem:${deviceId}`, `order:${deviceId}`, `proof:${deviceId}`),
      env.DB.prepare('DELETE FROM orders WHERE device_id = ?').bind(deviceId),
      env.DB.prepare('DELETE FROM events WHERE device_id = ?').bind(deviceId),
      env.DB.prepare('DELETE FROM devices WHERE device_id = ?').bind(deviceId),
    ]);

    // device_id is null here because the row is gone; the detail keeps the trail readable.
    await logEvent(env.DB, null, 'DEVICE_DELETE', `Device ${deviceId} deleted (${orders?.c || 0} orders)`, 'admin');
    return Response.json({ ok: true });
  } else {
    return Response.json({ ok: false, error: 'إجراء غير معروف' }, { status: 400 });
  }

  if (stmts.length > 0) await env.DB.batch(stmts);
  const updated = await env.DB.prepare('SELECT * FROM devices WHERE device_id = ?')
    .bind(deviceId).first<DeviceRow>();
  return Response.json({ ok: true, device: updated });
}

async function apiUpdateSettings(request: Request, env: Env): Promise<Response> {
  let body: any;
  try {
    body = await request.json();
  } catch {
    return Response.json({ ok: false, error: 'صيغة الطلب غير صالحة' }, { status: 400 });
  }

  // Validate everything first so a single bad field never leaves a half-saved form.
  const staged = new Map<string, string>();

  if (body.payment_methods !== undefined) {
    let parsed: any;
    try {
      parsed = JSON.parse(body.payment_methods);
    } catch {
      return Response.json({ ok: false, error: 'صيغة طرق الدفع غير صالحة (JSON)' }, { status: 400 });
    }
    if (!Array.isArray(parsed)) {
      return Response.json({ ok: false, error: 'طرق الدفع يجب أن تكون قائمة JSON' }, { status: 400 });
    }
    const clean = parsed
      .filter((m: any) => m && typeof m.name === 'string' && m.name.trim() && typeof m.account === 'string' && m.account.trim())
      .map((m: any) => {
        const name = String(m.name).trim();
        const account = String(m.account).trim();
        // ids and logo bytes never travel through this payload; the logo lives in
        // its own settings key (payment_logo:<id>) managed by /admin/api/payment-logo.
        return {
          id: typeof m.id === 'string' && /^[a-z0-9_-]{1,40}$/.test(m.id.trim())
            ? m.id.trim().toLowerCase()
            : paymentMethodId(name, account),
          name,
          account,
          note: typeof m.note === 'string' ? m.note.trim() : '',
          border_color: normalizeBorderColor(m.border_color),
          has_logo: m.has_logo === true,
        };
      });
    if (clean.length === 0) {
      return Response.json({ ok: false, error: 'أضف طريقة دفع واحدة على الأقل (name و account)' }, { status: 400 });
    }
    staged.set('payment_methods', JSON.stringify(clean));
  }

  if (body.support_whatsapp !== undefined) {
    const value = String(body.support_whatsapp).trim();
    if (value && !/^(https?:\/\/|wa\.me\/|\+?\d)/i.test(value)) {
      return Response.json({ ok: false, error: 'رابط واتساب غير صالح — استخدم wa.me/964... أو رقم هاتف' }, { status: 400 });
    }
    staged.set('support_whatsapp', value);
  }

  if (body.support_telegram !== undefined) {
    let value = String(body.support_telegram).trim();
    if (value.startsWith('@')) value = 'https://t.me/' + value.substring(1);
    if (value && !/^https:\/\//i.test(value)) {
      return Response.json({ ok: false, error: 'رابط تيليجرام يجب أن يبدأ بـ https://' }, { status: 400 });
    }
    staged.set('support_telegram', value);
  }

  if (body.tos_text !== undefined) staged.set('tos_text', String(body.tos_text));
  if (body.privacy_text !== undefined) staged.set('privacy_text', String(body.privacy_text));
  if (body.tos_version !== undefined) staged.set('tos_version', String(body.tos_version));
  if (body.sync_interval_seconds !== undefined) {
    const raw = String(body.sync_interval_seconds).trim();
    const value = parseInt(raw, 10);
    if (!/^\d+$/.test(raw) || !Number.isFinite(value) || value < SYNC_INTERVAL_MIN || value > SYNC_INTERVAL_MAX) {
      return Response.json({
        ok: false,
        error: `مدة المزامنة يجب أن تكون رقماً بين ${SYNC_INTERVAL_MIN} و${SYNC_INTERVAL_MAX} ثانية`,
      }, { status: 400 });
    }
    staged.set('sync_interval_seconds', String(value));
  }
  if (body.foreground_sync_seconds !== undefined) {
    const raw = String(body.foreground_sync_seconds).trim();
    const value = parseInt(raw, 10);
    if (!/^\d+$/.test(raw) || !Number.isFinite(value) || value < FOREGROUND_SYNC_MIN || value > FOREGROUND_SYNC_MAX) {
      return Response.json({
        ok: false,
        error: `مزامنة التطبيق المفتوح يجب أن تكون رقماً بين ${FOREGROUND_SYNC_MIN} و${FOREGROUND_SYNC_MAX} ثانية`,
      }, { status: 400 });
    }
    staged.set('foreground_sync_seconds', String(value));
  }
  if (body.trial_seconds !== undefined) {
    const raw = String(body.trial_seconds).trim();
    const value = Number(raw);
    // 0 disables the free trial for new devices; otherwise keep the sane lower bound.
    if (!/^\d+$/.test(raw) || !Number.isFinite(value) || value > TRIAL_MAX_SECONDS
      || (value !== 0 && value < TRIAL_MIN_SECONDS)) {
      return Response.json({
        ok: false,
        error: `مدة التجربة يجب أن تكون 0 (بلا تجربة) أو بين ${TRIAL_MIN_SECONDS} و${TRIAL_MAX_SECONDS} ثانية`,
      }, { status: 400 });
    }
    staged.set('trial_seconds', String(Math.round(value)));
    staged.set('trial_days', String(value === 0 ? 0 : Math.max(1, Math.ceil(value / 86400))));
  }
  if (body.paid_token_ttl_days !== undefined) {
    const value = parseInt(String(body.paid_token_ttl_days).trim(), 10);
    if (!Number.isFinite(value) || value < 1 || value > 365) {
      return Response.json({ ok: false, error: 'مدة صلاحية الرمز يجب أن تكون بين 1 و365 يوماً' }, { status: 400 });
    }
    staged.set('paid_token_ttl_days', String(value));
  }
  if (body.grace_days !== undefined) {
    const value = parseInt(String(body.grace_days).trim(), 10);
    if (!Number.isFinite(value) || value < 0 || value > 60) {
      return Response.json({ ok: false, error: 'أيام السماح يجب أن تكون بين 0 و60' }, { status: 400 });
    }
    staged.set('grace_days', String(value));
  }
  if (body.min_app_version !== undefined) {
    const value = canonicalVersion(normalizeVersionInput(String(body.min_app_version)));
    if (!isValidVersion(value)) {
      return Response.json({ ok: false, error: 'أقل إصدار غير صالح — استخدم صيغة مثل 1.0 أو 1.2' }, { status: 400 });
    }
    staged.set('min_app_version', value);
  }
  if (body.maintenance !== undefined) {
    staged.set('maintenance', body.maintenance === true || body.maintenance === 'on' ? 'on' : 'off');
  }

  if (body.bot_notifications_enabled !== undefined) {
    staged.set('bot_notifications_enabled', expiryNoticeEnabled(body.bot_notifications_enabled) ? '1' : '0');
  }

  if (body.expiry_notice_enabled !== undefined) {
    staged.set('expiry_notice_enabled', expiryNoticeEnabled(body.expiry_notice_enabled) ? '1' : '0');
  }

  if (body.expiry_notice_unit !== undefined) {
    const raw = String(body.expiry_notice_unit).trim();
    if (!EXPIRY_NOTICE_UNITS.includes(raw as any)) {
      return Response.json({ ok: false, error: 'وحدة التنبيه يجب أن تكون days أو hours أو minutes' }, { status: 400 });
    }
    staged.set('expiry_notice_unit', raw);
  }

  if (body.expiry_notice_value !== undefined) {
    // Validate against the unit in the same request so "365" cannot sneak in as minutes-of-days.
    const unit = clampExpiryNoticeUnit(staged.get('expiry_notice_unit'));
    const raw = String(body.expiry_notice_value).trim();
    const value = parseInt(raw, 10);
    const max = unit === 'days' ? 365 : unit === 'hours' ? 8760 : 525600;
    if (!/^\d+$/.test(raw) || !Number.isFinite(value) || value < 1 || value > max) {
      return Response.json({
        ok: false,
        error: `قيمة التنبيه يجب أن تكون رقماً بين 1 و${max} (${unit})`,
      }, { status: 400 });
    }
    staged.set('expiry_notice_value', String(value));
  }

  await Promise.all(Array.from(staged, ([key, value]) => setSetting(env.DB, key, value)));
  await logEvent(env.DB, null, 'SETTINGS_UPDATE', 'Settings updated via panel', 'admin');

  return Response.json({ ok: true });
}

async function apiGetSettings(env: Env): Promise<Response> {
  const settings = await getSettings(env.DB, [
    'tos_text', 'privacy_text', 'tos_version', 'payment_methods',
    'sync_interval_seconds', 'foreground_sync_seconds',
    'trial_seconds', 'trial_days', 'paid_token_ttl_days', 'grace_days', 'min_app_version',
    'maintenance', 'support_whatsapp', 'support_telegram',
    'bot_notifications_enabled', 'expiry_notice_enabled', 'expiry_notice_unit', 'expiry_notice_value',
  ]);
  const trialSeconds = clampTrialSeconds(settings.trial_seconds, settings.trial_days);
  const noticeUnit = clampExpiryNoticeUnit(settings.expiry_notice_unit);
  // The admin app should not need to know clamp rules; return the effective values too.
  return Response.json({
    ok: true,
    settings,
    effective: {
      trial_seconds: trialSeconds,
      sync_interval_seconds: clampSyncInterval(settings.sync_interval_seconds),
      foreground_sync_seconds: clampForegroundSync(settings.foreground_sync_seconds),
      paid_token_ttl_days: parseInt(settings.paid_token_ttl_days || '7', 10),
      grace_days: parseInt(settings.grace_days || '2', 10),
      min_app_version: String(settings.min_app_version || '1'),
      maintenance: settings.maintenance === 'on',
      bot_notifications_enabled: expiryNoticeEnabled(settings.bot_notifications_enabled),
      expiry_notice_enabled: expiryNoticeEnabled(settings.expiry_notice_enabled),
      expiry_notice_unit: noticeUnit,
      expiry_notice_value: clampExpiryNoticeValue(settings.expiry_notice_value, noticeUnit),
    },
  });
}

async function apiExport(env: Env): Promise<Response> {
  const devices = await env.DB.prepare('SELECT * FROM devices ORDER BY first_seen DESC').all<DeviceRow>();
  let csv = 'device_id,first_seen,paid_until,lifetime,blocked,name,phone,model,transfers_used\n';
  for (const d of devices.results) {
    csv += `${d.device_id},${d.first_seen},${d.paid_until},${d.lifetime},${d.blocked},"${d.name || ''}","${d.phone || ''}","${d.model || ''}",${d.transfers_used}\n`;
  }
  return Response.json({ csv });
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
