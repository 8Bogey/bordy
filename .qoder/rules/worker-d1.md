---
trigger: always_on
description: قواعد خادم Cloudflare Worker وD1 في bordy-license
---

# Worker وD1

- الـ Worker في `bordy-license/src`، نقطة الدخول `index.ts`، لا إطار عمل HTTP.
- كل استجابة JSON عبر `jsonResponse` / `errorResponse` من `utils.ts` (تحمل `server_time` و`Cache-Control: no-store`). الشكل الموحّد: `{ok, error?, code?}`.
- الوقت المرجعي دائماً `Date.now()` على الخادم؛ لا تثق بوقت العميل.
- أي مدخل خارجي يُتحقق منه صراحةً (حدود الحجم عبر `parseBoundedJson`, صيغة device id عبر `isValidDeviceId`, نطاقات الأسعار/الأيام).
- الأسعار تُقارب بـ `roundPrice` (أقرب 250 د.ع). لا تُغيّر التقريب محلياً.
- المسارات العامة `/v1/*` تبقى متوافقة مع الإصدارات المنشورة من التطبيق؛ أي كسر توافق يحتاج `/v2` جديداً أو ترقية تطبيق متزامنة.
- الحسابات: `Authorization: Bearer` للأجهزة فقط؛ لا تُفعّل CORS للتوكنات (`/v2/*` و`/admin` بلا `Access-Control-Allow-Origin: *`).
- الأسرار عبر `wrangler secret` (الإنتاج) و`.dev.vars` (محلي، مُهمَل من git). ممنوع طباعة قيمها أو تضمينها في الكود/الاختبارات.
- كل جدول D1 جديد يحتاج سطراً في `schema.sql` + ملفاً في `migrations/` + تحديث `docs/PROJECT_CONTEXT.md`.
- قبل النشر: `npm test` ثم `npx tsc --noEmit` ثم `scripts/deploy-worker.sh`. لا تنشر إلى الإنتاج دون طلب صريح.
- الجدولة اليومية في `cron.ts` (05:00) — أي مهمة جديدة تُسجَّل في `wrangler.toml`.
- معدّل الطلبات عبر `rate-limit.ts`؛ لا تعطله ولا ترفع حدوده بلا قرار.
