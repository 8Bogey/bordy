# PROJECT_CONTEXT — بوردي

> مرجع سياق مختصر (يُقرأ مع `QODER.md`). الخطط الأصلية في `LICENSING_PLAN.md`، `ACCOUNT_SYNC_PLAN.md`، `plan.md`.

## 1. نظرة عامة

| المكوّن | التقنية | المسار |
|---|---|---|
| تطبيق أندرويد | Kotlin 2.0.20، Jetpack Compose (BOM 2024.09.00)، Material3، Navigation Compose، Room 2.6.1 (KSP)، WorkManager 2.9.1، Coil 2.7 | `app/` |
| خادم الترخيص والحسابات | TypeScript، Cloudflare Workers، D1، Wrangler 3، بوت تيليجرام | `bordy-license/` |
| التوثيق والقرارات | عربي | `*.md`, `docs/` |

- `minSdk 30`, `targetSdk 34`, `compileSdk 34`, JDK 17.
- `namespace` و`applicationId`: `com.bordy.app`. الخادم ينشر على `https://bordy-license.bordy-license.workers.dev`.
- الترخيص يُوقَّع بـ ECDSA P-256 ويُتحقق منه محلياً؛ المفتاح العام داخل `app/build.gradle.kts` (`LICENSE_PUBLIC_KEY_K1/K2`)، والخاص في `ec_priv_k*.pem` (ممنوع لمسه/رفعه).
- التوزيع: APK مباشر (GitHub Releases + تيليجرام)، بلا Google Play.

## 2. طبقة البيانات (Room v26)

- `data/Entities.kt`: `Supplier, Component, ComponentVariant, SupplierOffer, CartItem, Template, TemplateItem, Project, ProjectItem, AppSettings, Tag, ComponentTag, ProjectMedia, ProjectNote, ActivityLog`.
- `data/SyncEntities.kt`: `SyncState, SyncShadow, SyncOutboxOperation, SyncOutboxMutation, SyncTombstone, SyncConflictEntity, SyncDirty`.
- كل صف متزامن يحمل `syncId` فريداً عالمياً (index unique) مع بقاء `id` الرقمي المحلي.
- الكتابة المحلية المهمة + إدخال طابور المزامنة في **معاملة Room واحدة** (`SyncTriggers`/`SyncDao`).
- `AppDatabase` فيه 12 migration مسجّلة (13→26). `exportSchema = true` + مجلد `app/schemas` بعد هذه التهيئة.

### قواعد حسابية (utils)

- `ProjectItemMath`: `billableQuantity` (مع `isShortage`)، `billablePurchaseTotal`, `billableSellTotal`, متوسط سعر الوحدة للموفَّر يدوياً، `projectTotalCost = Σ بيع + laborCost`.
- `TemplateMath`: سعر الصنف من المخزون (`ComponentVariant.defaultSellPrice`) وإلا السعر المحفوظ؛ `templateSummary` يجمع الكمية والقيمة + `laborCost ≥ 0`.
- `CalculatorEngine`: تحويل إلى postfix، `BigDecimal`، قسمة على صفر → `""`، `%` = قسمة على 100، حد 6 خانات عشرية.

## 3. تدفق الترخيص

1. أول تشغيل: شاشة TOS إلزامية → جهاز مجهول (`DeviceId`) → تجربة 7 أيام تُسجَّل على الخادم.
2. `POST /v1/activate` → رمز موقّع (ECDSA) يحمل `exp`, `grace`, بصمة الجهاز.
3. التحقق محلياً بلا إنترنت؛ `ClockGuard` يحمي من تغيير الوقت، و`IntegrityChecks` من العبث.
4. `POST /v1/refresh` دوري (WorkManager) + `LicenseWatchReceiver` عند الإقلاع/تغيير الوقت.
5. انتهاء + انتهاء السماح (يومان) → قفل كامل. أقصى عمل أوفلاين 7 أيام.
6. دفع يدوي: `POST /v1/orders` → برهان تحويل (`/proof`) → موافقة الأدمن/تيليجرام → تفعيل.

## 4. تدفق المزامنة (v2)

- Offline-first: Room مصدر الواجهة؛ الخادم مرجع مشترك.
- حساب واحد + جهاز مزامنة نشط واحد؛ نقل الجهاز (`/v2/.../transfer`) ينزّل الجديد كاملاً قبل مسح القديم.
- `outbox` + `sync_id` + `tombs` + كشف تعارض (`sync_conflicts`) — لا أولوية عمياء ولا طمس.
- الصور/الفيديو/`uri` وسجل النشاط محلية فقط.
- فحص متكيف يبدأ 60 ثانية أثناء النشاط ويهدأ إلى 5 دقائق؛ لا polling كل 10 ثوانٍ.
- عند انتهاء الاشتراك: قفل التطبيق فقط، لا حذف بيانات.

## 5. خادم Worker — نقاط النهاية

| المسار | الوصف |
|---|---|
| `POST /v1/activate` | تفعيل جهاز/تجربة |
| `POST /v1/refresh` | تجديد الرمز |
| `GET /v1/plans` | الخطط والأسعار |
| `GET /v1/sync` | حِزمة حالة (ترخيص + إعدادات + آخر طلب) |
| `GET /v1/tos`, `GET /v1/update` | الشروط وآخر إصدار |
| `POST /v1/codes/check`, `POST /v1/redeem` | أكواد الخصم |
| `POST /v1/orders`, `GET /v1/orders/{id}`, `POST .../proof`, `POST .../cancel` | الطلبات والدفع اليدوي |
| `POST /v1/transfer/request` | نقل الترخيص بين الأجهزة |
| `POST /tg/{TG_WEBHOOK_SECRET}` | webhook تيليجرام |
| `/admin` | لوحة الإدارة (HTML + auth) |
| `/v2/*` | الحسابات والمزامنة (`account/router.ts`) |

- `cron: "0 5 * * *"` — مهام يومية (انتهاء تجارب/تنبيهات).
- جداول D1: `devices, plans, orders, proofs, codes, code_redemptions, events, rate_limits, settings, accounts, account_licenses, account_devices, refresh_sessions, sync_records, sync_changes, sync_operations, sync_conflicts, sync_assertions, device_transfers, account_audit_events`.
- أسرار Cloudflare (في `.dev.vars` محلياً): `ACCOUNT_TOKEN_SECRET, PASSWORD_PEPPER, ADMIN_SECRET, ADMIN_TOTP_SECRET, TG_WEBHOOK_SECRET, TG_BOT_TOKEN, OWNER_CHAT_ID`.

## 6. البيئة المحلية

- JDK: `C:\Users\BOGEY\.jdks\jbr-17.0.14` (مثبت في `gradle.properties` كـ `org.gradle.java.home`).
- Android SDK: `C:\Users\BOGEY\AppData\Local\Android\Sdk` (adb في `platform-tools`).
- الجهاز المستخدم للاختبار: Samsung، السيريال `RFCX31PZFBB` (يُكتشف تلقائياً من `adb devices`).
- Node 24 / npm 11، wrangler مثبت عالمياً.

## 7. حالة الجودة (بعد التهيئة الحالية)

- اختبارات وحدة للرياضيات: `app/src/test/java/com/bordy/app/utils/`.
- اختبارات Worker: `bordy-license/test/utils.test.ts` عبر `npm test`.
- CI: `.github/workflows/android.yml` و`worker.yml` — بناء + lint + اختبارات + تحقق لقطات، والنشر عند وسم `v*`.
- مخططات Room تُصدَّر إلى `app/schemas` — تفعّل اختبارات الترقية ابتداءً من النسخة القادمة.
- Lint: `app/lint-baseline.xml` يجمّد الدين الحالي؛ `warningsAsErrors = true` يفشل على أي تحذير جديد.
- لقطات شاشة مرجعية (Robolectric + Roborazzi): `app/src/test/screenshots/`، تُنشأ بـ `recordRoborazziDebug` ويُتحقق منها في CI.
- Sentry: منظمة `bogies`، مشروع `bordy-android`. `BordyApp.initCrashReporting()` يقرأ `SENTRY_DSN` من `local.properties`/`-PSENTRY_DSN`؛ فارغ = معطّل. بلا PII وبلا تتبّع أداء.
  - بقية: رفع خرائط R8 يحتاج إضافة `io.sentry.android.gradle` + `SENTRY_AUTH_TOKEN` في `local.properties` — لم يُضف بعد لأن بناء release حساس.
- التحقق من مدخلات الخادم: `bordy-license/src/validate.ts` بـ`zod` لمساري `activate` و`refresh`؛ بقية المسارات ما زالت يدوية.
- ديون معروفة: لا اختبارات UI آلية سوى اللقطات، وبقية مسارات الخادم بلا `zod`.
