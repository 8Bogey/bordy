---
name: bordy-worker
description: تشغيل ونشر واختبار خادم بوردي على Cloudflare Workers وD1 — dev، اختبارات، migrations، أسرار، ونشر.
---

# خادم بوردي (Worker + D1)

## تطوير محلي

```bash
cd bordy-license
npm install
npx wrangler dev          # يقرأ .dev.vars وD1 المحلي في .wrangler/
npm run db:init           # تطبيق schema.sql على D1 المحلي
```

## قبل أي نشر

1. `npm test` (vitest)
2. `npx tsc --noEmit`
3. راجع توافق `/v1/*` مع نسخ التطبيق المنشورة — لا تكسر الشكل `{ok, error?, code?}`.
4. `./scripts/deploy-worker.sh --dry-run` ثم النشر بعد موافقة المستخدم.

## تغييرات قاعدة البيانات

- عدّل `schema.sql` وأضف ملفاً في `bordy-license/migrations/` (`000N_وصف.sql`).
- طبّق محلياً ثم عن بعد:
  `npx wrangler d1 execute bordy-license --remote --file=migrations/000N_*.sql`
- لا تحذف أعمدة/جداول مستخدمة من نسخة تطبيق منشورة.

## أسرار

- الإنتاج: `npx wrangler secret put NAME`
- المحلي: `.dev.vars` (مُهمَل من git). ممنوع طباعة القيم أو وضعها في الكود/الاختبارات.
- الأسرار: `ACCOUNT_TOKEN_SECRET, PASSWORD_PEPPER, ADMIN_SECRET, ADMIN_TOTP_SECRET, TG_WEBHOOK_SECRET, TG_BOT_TOKEN, OWNER_CHAT_ID`.

## قواعد

- الوقت من الخادم فقط (`Date.now()`)، والردود عبر `jsonResponse`/`errorResponse`.
- تحقق من كل مدخل (الحجم، الصيغة، النطاقات)؛ استخدم `parseBoundedJson` و`isValidDeviceId`.
- معدّل الطلبات في `rate-limit.ts` — لا تعطله.
- لوحة `/admin` تُختبر عبر MCP `browser-use` على `wrangler dev`.
- سجّل الأخطاء بـ `console.error` مع سياق كافٍ، بلا أسرار.
