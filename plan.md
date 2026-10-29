<div dir="rtl">

# خطة التنفيذ — نظام الترخيص والاشتراك لتطبيق «المُبتَكَر»
> **هذا الملف هو المرجع الوحيد للتنفيذ.** كل مرحلة تُنفَّذ بالترتيب. لا تتجاوز مرحلة قبل إتمام التي قبلها.

---

## القرارات المُعتمَدة

| # | السؤال | القرار النهائي |
|---|---|---|
| 1 | الأسعار | **إبقاء المقترحة:** شهري 7,500 — 3 أشهر 20,000 — سنوي 70,000 د.ع |
| 2 | طرق الدفع | **آسيا باي، زين كاش، كي كارد، FIB** |
| 3 | بعد الانتهاء | **قفل كامل** (لا تصدير نسخة احتياطية في حالة EXPIRED) |
| 4 | جمع اسم/هاتف | **اختياري عند بدء التجربة، إلزامي عند محاولة الدفع** |
| 5 | مدة السماح | **يومان**، أقصى أوفلاين **7 أيام** (بدلاً من 10) |
| 6 | قناة الدعم | **تيليجرام + واتساب** (كلاهما يُعرضان) |
| 7 | `fallbackToDestructiveMigration` | **يُزال** ويُستبدل بـ Migration صريح + نسخة احتياطية تلقائية قبل الترقية |
| 8 | Key Attestation | **لا يُنفَّذ** (الفائدة لا تُبرّر الجهد مع DeepSeek Flash) |
| 9 | شروط الخدمة | **شاشة TOS عند أول فتح إلزامية** + إدارة النص من بوت تيليجرام ولوحة الويب + رابط في الإعدادات |

### إضافة جديدة: شاشة شروط الخدمة (TOS Gate)
- **عند أول فتح على الإطلاق** لمستخدم جديد: تظهر شاشة تطلب الموافقة على شروط الخدمة.
- **النص الرئيسي:** عبارة قصيرة مثل «باستخدامك التطبيق فإنك توافق على شروط الخدمة وسياسة الخصوصية».
- **تحت النص:** كلمات صغيرة قابلة للضغط «شروط الخدمة» و «سياسة الخصوصية».
- **عند الضغط:** نافذة منبثقة (Dialog) تعرض النص الكامل.
- **النص يُجلب من الخادم** (جدول `settings` مفتاح `tos_text` و `privacy_text`)، ويُدار من بوت تيليجرام أو لوحة الويب.
- **زر «أوافق»** يحفظ حالة القبول في ملف الترخيص المحلي (داخل `noBackupFilesDir`).
- **بدون موافقة = لا دخول للتطبيق نهائياً.**
- يُعرض أيضاً رابط لعرض الشروط في الإعدادات.

---

## المعمارية العامة

```
┌──────────────────────┐        HTTPS (JSON)              ┌──────────────────────────────┐
│  تطبيق أندرويد        │ ──────────────────────────────▶  │  Cloudflare Worker (API)      │
│  • بوابة TOS          │ ◀──────────────────────────────── │  • يوقّع الرموز              │
│  • بوابة الترخيص       │      رمز موقّع + وقت الخادم      │  • يحسب التجربة/الاشتراك     │
│  • يتحقق من التوقيع    │                                  │  • يرسل تنبيهات تيليجرام     │
│  • يعمل بلا إنترنت     │                                  │  • يدير شروط الخدمة          │
└──────────────────────┘                                   └───────────┬───────────────────┘
        ▲                                                              │
        │ مفتاح تفعيل يدوي عبر واتساب/تيليجرام                  ┌──────▼──────┐
        │                                                       │  D1 (SQLite) │
┌───────┴──────────────┐   أوامر + أزرار                         │  كل البيانات  │
│  أنت (المالك)         │ ◀──────────────────────────▶ بوت تيليجرام + لوحة ويب
└──────────────────────┘
```

**تسلسل بوابات الدخول:**
```
فتح التطبيق
    ↓
[1] هل وافق على TOS؟ ← لا → شاشة TOS (إلزامية)
    ↓ نعم
[2] هل لديه رمز ترخيص ساري؟ ← لا → شاشة التفعيل/الاشتراك
    ↓ نعم
[3] التطبيق يعمل طبيعياً
```

---

## ⚠️ قواعد ثابتة للمُنفِّذ (DeepSeek Flash)

1. **لا تُغيّر أي ملف Room أو ترفع إصدار القاعدة.** الترخيص منفصل تماماً عن `mubtaker.db`.
2. **كل الأوقات UTC epoch بالمللي ثانية.** لا تستعمل `Date` أو `Calendar`.
3. **لا تضف أي مكتبة خارجية جديدة.** استعمل `HttpURLConnection` و `java.security` فقط.
4. **لا تمسح تعليقات أو docstrings موجودة** إلا إذا عدّلت الكود المرتبط بها.
5. **كل SQL يستعمل `bind` (prepared statements).** لا تركيب نصي أبداً.
6. **الخادم TypeScript (Cloudflare Workers).** لا تستعمل Node.js APIs غير متوفرة في Workers.
7. **كل إضافة RTL.** الواجهات بالعربية.
8. **احتفظ بنفس نظام التصميم** (`DesignSystem.kt` — الألوان والخطوط والمسافات الموجودة).

---

# المرحلة 0: التحضير

> **الهدف:** تهيئة المشروع لاستقبال نظام الترخيص بدون كسر أي شيء موجود.

## 0.1 إضافة صلاحيات الشبكة

**الملف:** `app/src/main/AndroidManifest.xml`

**الإجراء:** أضف داخل `<manifest>` قبل `<application>`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**الإجراء:** غيّر في `<application>`:
```xml
android:allowBackup="false"
android:usesCleartextTraffic="false"
```

## 0.2 قواعد استبعاد النسخ الاحتياطي

**الملف:** `app/src/main/res/xml/backup_rules.xml`

**المحتوى الكامل:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="root" path="." />
</full-backup-content>
```

**الملف:** `app/src/main/res/xml/data_extraction_rules.xml`

**المحتوى الكامل:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
    </device-transfer>
</data-extraction-rules>
```

## 0.3 إزالة `fallbackToDestructiveMigration`

**الملف:** الملف الذي يحتوي على `AppDatabase` (ابحث عن `fallbackToDestructiveMigration`)

**الإجراء:**
1. أزل `.fallbackToDestructiveMigration()` من بناء قاعدة البيانات.
2. أضف migration فارغ كشبكة أمان:
```kotlin
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No schema changes — placeholder for future migrations
    }
}
```
3. أضفه في البناء: `.addMigrations(MIGRATION_25_26)`
4. **لا ترفع الإصدار الآن.** أبقِ `version = 25`. المايقريشن جاهز للمستقبل فقط.

## 0.4 إنشاء مفتاح التوقيع (signing config)

**الملف:** `app/build.gradle.kts`

**الإجراء:** أضف داخل `android { }`:
```kotlin
signingConfigs {
    create("release") {
        storeFile = file(project.findProperty("RELEASE_STORE_FILE") as? String ?: "mubtaker-release.jks")
        storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as? String ?: ""
        keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as? String ?: "mubtaker"
        keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as? String ?: ""
    }
}
```

عدّل `buildTypes.release`:
```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    signingConfig = signingConfigs.getByName("release")
}
```

أضف في `defaultConfig`:
```kotlin
buildConfigField("String", "API_BASE_URL", "\"https://bordy-license.<ACCOUNT>.workers.dev\"")
buildConfigField("String", "API_FALLBACK_URL", "\"https://bordy-license.<ACCOUNT>.workers.dev\"")
buildConfigField("String", "LICENSE_PUBLIC_KEY_K1", "\"<BASE64_DER_PUBLIC_KEY>\"")
buildConfigField("String", "LICENSE_PUBLIC_KEY_K2", "\"<BASE64_DER_PUBLIC_KEY_BACKUP>\"")
buildConfigField("String", "EXPECTED_CERT_SHA256", "\"<SHA256_OF_RELEASE_SIGNING_CERT>\"")
```

## 0.5 ملف ProGuard

**الملف الجديد:** `app/proguard-rules.pro`

```proguard
# Keep license verification classes from being optimized away
-keep class com.mubtaker.boards.license.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.**

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
```

## 0.6 رفع `versionCode`

**الملف:** `app/build.gradle.kts`

**الإجراء:** ارفع `versionCode` بواحد (مثلاً من القيمة الحالية إلى +1). لا تغيّر `versionName` بعد.

---

## 0.7 التحقق

- ابنِ المشروع (`./gradlew assembleDebug`). يجب أن ينجح بلا أخطاء.
- شغّل التطبيق. يجب أن يعمل **تماماً كما كان قبل أي تغيير**.

---

# المرحلة 1: خادم الترخيص (Cloudflare Worker + D1)

> **الهدف:** خادم يسجّل الأجهزة، يوقّع الرموز، يرسل إشعارات، ويدير الأوامر عبر بوت تيليجرام.

## 1.1 هيكل مشروع الخادم

```
bordy-license/
├── wrangler.toml
├── package.json
├── tsconfig.json
├── schema.sql
└── src/
    ├── index.ts              ← نقطة الدخول (Router)
    ├── types.ts              ← أنواع TypeScript
    ├── crypto.ts             ← توقيع ECDSA + توليد الرمز
    ├── db.ts                 ← دوال قاعدة البيانات
    ├── handlers/
    │   ├── activate.ts       ← POST /v1/activate
    │   ├── refresh.ts        ← POST /v1/refresh
    │   ├── plans.ts          ← GET /v1/plans
    │   ├── orders.ts         ← POST /v1/orders, GET/POST /v1/orders/:id/*
    │   ├── codes.ts          ← POST /v1/codes/check, POST /v1/redeem
    │   ├── transfer.ts       ← POST /v1/transfer/request
    │   ├── update.ts         ← GET /v1/update
    │   └── tos.ts            ← GET /v1/tos (نص شروط الخدمة)
    ├── telegram.ts           ← إرسال رسائل + معالجة أوامر البوت
    ├── cron.ts               ← المهام المجدولة
    ├── rate-limit.ts         ← حدود المحاولات
    ├── admin/
    │   ├── auth.ts           ← مصادقة لوحة الويب
    │   └── panel.ts          ← HTML لوحة الويب
    └── utils.ts              ← دوال مساعدة (تطبيع عربي، تقريب السعر…)
```

## 1.2 قاعدة البيانات — `schema.sql`

**أنشئ الملف بالضبط كما يلي:**

```sql
-- ═══════════════════════════════════════════════════════════
-- Mubtaker License Server — D1 Schema
-- All times are UTC epoch milliseconds
-- ═══════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS devices (
    device_id        TEXT PRIMARY KEY,
    first_seen       INTEGER NOT NULL,
    last_seen        INTEGER,
    trial_started_at INTEGER,
    trial_ends_at    INTEGER,
    paid_until       INTEGER NOT NULL DEFAULT 0,
    lifetime         INTEGER NOT NULL DEFAULT 0,
    blocked          INTEGER NOT NULL DEFAULT 0,
    block_reason     TEXT,
    name             TEXT,
    phone            TEXT,
    notes            TEXT,
    app_ver          INTEGER,
    model            TEXT,
    sdk              INTEGER,
    cert_hash        TEXT,
    tos_accepted     INTEGER NOT NULL DEFAULT 0,
    tos_accepted_at  INTEGER,
    transfers_used   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_devices_phone ON devices(phone);
CREATE INDEX IF NOT EXISTS idx_devices_paid ON devices(paid_until);

CREATE TABLE IF NOT EXISTS plans (
    id     TEXT PRIMARY KEY,
    title  TEXT NOT NULL,
    days   INTEGER NOT NULL,
    price  INTEGER NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    sort   INTEGER NOT NULL DEFAULT 0
);

-- Default plans
INSERT OR IGNORE INTO plans VALUES ('monthly', 'شهري', 30, 7500, 1, 1);
INSERT OR IGNORE INTO plans VALUES ('quarterly', '3 أشهر', 90, 20000, 1, 2);
INSERT OR IGNORE INTO plans VALUES ('yearly', 'سنوي', 365, 70000, 1, 3);

CREATE TABLE IF NOT EXISTS orders (
    id            TEXT PRIMARY KEY,
    device_id     TEXT NOT NULL,
    plan_id       TEXT NOT NULL,
    base_price    INTEGER NOT NULL,
    discount      INTEGER NOT NULL DEFAULT 0,
    final_price   INTEGER NOT NULL,
    code          TEXT,
    days_granted  INTEGER NOT NULL,
    method        TEXT,
    txn_ref       TEXT,
    payer_name    TEXT,
    payer_phone   TEXT,
    status        TEXT NOT NULL DEFAULT 'PENDING',
    reject_reason TEXT,
    provider      TEXT NOT NULL DEFAULT 'manual',
    provider_ref  TEXT,
    created_at    INTEGER NOT NULL,
    submitted_at  INTEGER,
    decided_at    INTEGER,
    decided_by    TEXT,
    expires_at    INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_txn ON orders(method, txn_ref) WHERE txn_ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status, created_at);
CREATE INDEX IF NOT EXISTS idx_orders_device ON orders(device_id);

CREATE TABLE IF NOT EXISTS proofs (
    order_id   TEXT PRIMARY KEY,
    mime       TEXT,
    data       BLOB,
    created_at INTEGER
);

CREATE TABLE IF NOT EXISTS codes (
    code             TEXT PRIMARY KEY,
    kind             TEXT NOT NULL,
    value            INTEGER NOT NULL DEFAULT 0,
    plans            TEXT NOT NULL DEFAULT '*',
    starts_at        INTEGER NOT NULL,
    ends_at          INTEGER NOT NULL,
    max_uses         INTEGER,
    used_count       INTEGER NOT NULL DEFAULT 0,
    reserved_count   INTEGER NOT NULL DEFAULT 0,
    per_device_limit INTEGER NOT NULL DEFAULT 1,
    min_price        INTEGER NOT NULL DEFAULT 0,
    auto_apply       INTEGER NOT NULL DEFAULT 0,
    campaign         TEXT,
    note             TEXT,
    active           INTEGER NOT NULL DEFAULT 1,
    created_at       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS code_redemptions (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    code       TEXT NOT NULL,
    device_id  TEXT NOT NULL,
    order_id   TEXT,
    state      TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_redeem ON code_redemptions(code, device_id) WHERE state IN ('RESERVED','CONSUMED');

CREATE TABLE IF NOT EXISTS events (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    ts        INTEGER NOT NULL,
    device_id TEXT,
    type      TEXT NOT NULL,
    detail    TEXT,
    actor     TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_dev ON events(device_id, ts);

CREATE TABLE IF NOT EXISTS rate_limits (
    k            TEXT PRIMARY KEY,
    window_start INTEGER NOT NULL,
    n            INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS settings (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);

-- Default settings
INSERT OR IGNORE INTO settings VALUES ('trial_days', '7');
INSERT OR IGNORE INTO settings VALUES ('paid_token_ttl_days', '7');
INSERT OR IGNORE INTO settings VALUES ('grace_days', '2');
INSERT OR IGNORE INTO settings VALUES ('refresh_after_hours', '24');
INSERT OR IGNORE INTO settings VALUES ('order_ttl_hours', '48');
INSERT OR IGNORE INTO settings VALUES ('code_attempts_per_hour_device', '10');
INSERT OR IGNORE INTO settings VALUES ('code_attempts_per_hour_ip', '30');
INSERT OR IGNORE INTO settings VALUES ('transfers_per_year', '2');
INSERT OR IGNORE INTO settings VALUES ('transfer_cooldown_h', '24');
INSERT OR IGNORE INTO settings VALUES ('stack_mode', 'best');
INSERT OR IGNORE INTO settings VALUES ('min_app_version', '1');
INSERT OR IGNORE INTO settings VALUES ('maintenance', 'off');
INSERT OR IGNORE INTO settings VALUES ('tos_text', 'شروط الخدمة الافتراضية. يمكنك تعديل هذا النص من بوت تيليجرام أو لوحة الويب.');
INSERT OR IGNORE INTO settings VALUES ('privacy_text', 'سياسة الخصوصية الافتراضية. بياناتك محفوظة على جهازك فقط.');
INSERT OR IGNORE INTO settings VALUES ('tos_version', '1');
INSERT OR IGNORE INTO settings VALUES ('payment_methods', '[{"name":"آسيا باي","account":"XXXXXXXXXX","note":""},{"name":"زين كاش","account":"XXXXXXXXXX","note":""},{"name":"كي كارد","account":"XXXXXXXXXX","note":""},{"name":"FIB","account":"XXXXXXXXXX","note":""}]');
INSERT OR IGNORE INTO settings VALUES ('support_telegram', 'https://t.me/XXXXXXX');
INSERT OR IGNORE INTO settings VALUES ('support_whatsapp', 'https://wa.me/964XXXXXXXXXX');
```

## 1.3 أنواع TypeScript — `src/types.ts`

```typescript
export interface Env {
  DB: D1Database;
  LICENSE_PRIVATE_KEY: string;  // PKCS8 base64
  TG_BOT_TOKEN: string;
  TG_WEBHOOK_SECRET: string;
  OWNER_CHAT_ID: string;
  ADMIN_SECRET: string;
  ADMIN_TOTP_SECRET: string;
}

export interface TokenPayload {
  v: number;        // دائماً 1
  kid: string;      // "k1" أو "k2"
  did: string;      // بصمة الجهاز (32 hex)
  st: 'trial' | 'paid' | 'owner';
  tre: number;      // نهاية التجربة (epoch ms)
  pu: number;       // paid_until (epoch ms)
  iat: number;      // وقت الإصدار
  exp: number;      // وقت الانتهاء
  nonce: string;    // من التطبيق
  minv: number;     // أقل إصدار مطلوب
  msg?: string;     // رسالة اختيارية
  tos_ver: number;  // إصدار شروط الخدمة المقبولة
}

export type OrderStatus = 'PENDING' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';
export type CodeKind = 'PERCENT' | 'FIXED' | 'FREE_DAYS' | 'FULL';
export type RedemptionState = 'RESERVED' | 'CONSUMED' | 'RELEASED';
```

## 1.4 التوقيع — `src/crypto.ts`

**التعليمات:**
1. استورد المفتاح الخاص من `env.LICENSE_PRIVATE_KEY` (PKCS8 base64) باستعمال `crypto.subtle.importKey("pkcs8", ..., { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"])`.
2. أنشئ دالة `signToken(payload: TokenPayload, privateKey: CryptoKey): Promise<string>` تعيد `base64url(JSON.stringify(payload)) + "." + base64url(signature)`.
3. التوقيع: `crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privateKey, payloadBytes)`.
4. WebCrypto يُخرج التوقيع بصيغة raw `r||s` (64 بايت). **لا تحوّله لـ DER.** التحويل يحدث في التطبيق.

## 1.5 نقطة الدخول — `src/index.ts`

**التعليمات:**
1. أنشئ Router بسيط (switch/case على `url.pathname`). لا تستعمل مكتبات routing خارجية.
2. أضف ترويسات أمان لكل رد: `Cache-Control: no-store`, `Content-Type: application/json`.
3. كل رد يتضمن `"server_time": Date.now()`.
4. مسارات:
   - `POST /v1/activate` → `handlers/activate.ts`
   - `POST /v1/refresh` → `handlers/refresh.ts`
   - `GET /v1/plans` → `handlers/plans.ts`
   - `POST /v1/codes/check` → `handlers/codes.ts`
   - `POST /v1/orders` → `handlers/orders.ts`
   - `POST /v1/orders/:id/proof` → `handlers/orders.ts`
   - `GET /v1/orders/:id` → `handlers/orders.ts`
   - `POST /v1/orders/:id/cancel` → `handlers/orders.ts`
   - `POST /v1/redeem` → `handlers/codes.ts`
   - `POST /v1/transfer/request` → `handlers/transfer.ts`
   - `GET /v1/update` → `handlers/update.ts`
   - `GET /v1/tos` → `handlers/tos.ts`
   - `POST /tg/<TG_WEBHOOK_SECRET>` → `telegram.ts`
   - `GET /admin/*` و `POST /admin/*` → `admin/panel.ts`
5. أي مسار آخر يعيد `404`.
6. `scheduled` event → `cron.ts`.

## 1.6 معالج التفعيل — `handlers/activate.ts`

**المدخلات (JSON body):**
```json
{
  "did": "a3f9...32hex",
  "ver": 3,
  "model": "Samsung SM-A125F",
  "sdk": 30,
  "cert_hash": "AB:CD:...",
  "nonce": "random_string"
}
```

**المنطق بالترتيب:**
1. تحقق من `did` (32 حرف hex).
2. تحقق من rate-limit: 20 طلب/ساعة لكل IP.
3. ابحث عن الجهاز في `devices` بالـ `did`.
4. **إن لم يكن موجوداً:**
   - أدخل جهاز جديد: `trial_started_at = NOW`, `trial_ends_at = NOW + 7 * 86400000`.
   - سجّل حدث `TRIAL_START`.
   - أرسل تنبيه تيليجرام (ملخص يومي فقط، لا فوري — خزّن عدّاد في `settings` مفتاح `daily_new_trials`).
5. **إن كان موجوداً:**
   - حدّث `last_seen`, `app_ver`, `model`, `sdk`, `cert_hash`.
   - حدّث `last_seen` فقط إذا مر أكثر من 24 ساعة عن آخر `last_seen` (توفير كتابات D1).
6. **إن كان `blocked = 1`:** أعد `{"status": "BLOCKED", "reason": "...", "server_time": ...}` بدون رمز.
7. **وقّع رمزاً** بالبيانات الحالية:
   - `st`: `"trial"` إن `paid_until < NOW` و `trial_ends_at > 0`، `"paid"` إن `paid_until >= NOW`، `"owner"` إن `lifetime = 1`.
   - `exp = min(trial_ends_at_or_paid_until + grace_days, NOW + paid_token_ttl_days)`.
   - `tos_ver` = الإصدار الحالي من `settings.tos_version`.
8. أعد: `{"token": "...", "status": "...", "server_time": ..., "tos": {"version": N, "text": "...", "privacy": "..."}}`.

## 1.7 معالج التجديد — `handlers/refresh.ts`

**مشابه لـ activate** لكن:
- يتوقع `did` و `nonce` فقط.
- لا يُنشئ جهاز جديد (يعيد خطأ إن لم يكن مسجلاً).
- Rate-limit أقل: 10/ساعة.

## 1.8 معالج الخطط — `handlers/plans.ts`

**`GET /v1/plans`:**
1. اجلب كل الخطط النشطة (`active = 1`) مرتبة بـ `sort`.
2. اجلب أي كود `auto_apply = 1` نشط وضمن الفترة.
3. اجلب طرق الدفع من `settings.payment_methods`.
4. اجلب `support_telegram` و `support_whatsapp`.
5. أعد:
```json
{
  "plans": [...],
  "auto_discount": { "code": "EID25", "kind": "PERCENT", "value": 25 } | null,
  "payment_methods": [...],
  "support": { "telegram": "...", "whatsapp": "..." },
  "server_time": ...
}
```

## 1.9 معالج شروط الخدمة — `handlers/tos.ts`

**`GET /v1/tos`:**
1. اجلب `tos_text`, `privacy_text`, `tos_version` من `settings`.
2. أعد:
```json
{
  "tos_text": "...",
  "privacy_text": "...",
  "tos_version": 1,
  "server_time": ...
}
```

## 1.10 معالج الطلبات — `handlers/orders.ts`

### `POST /v1/orders` — إنشاء طلب

**المدخلات:**
```json
{
  "did": "...",
  "plan_id": "monthly",
  "code": "EID25",          // اختياري
  "payer_name": "أحمد",     // إلزامي
  "payer_phone": "07XXXXXXXXX"  // إلزامي
}
```

**المنطق:**
1. تحقق أن الجهاز مسجّل وغير محظور.
2. تحقق أنه لا يوجد طلب `PENDING` أو `SUBMITTED` لنفس الجهاز.
3. اجلب الخطة وتحقق أنها نشطة.
4. إن وُجد كود:
   - طبّعه (أرقام عربية ← لاتينية، أزل مسافات وشرطات، حروف كبيرة).
   - تحقق من الصلاحية والفترة والحد.
   - تحقق من `per_device_limit`.
   - **احجز ذرياً:** `UPDATE codes SET reserved_count = reserved_count + 1 WHERE code = ? AND active = 1 AND (max_uses IS NULL OR used_count + reserved_count < max_uses)` ثم تحقق `changes() > 0`.
5. إن وُجد كود `auto_apply` وكان أفضل من كود المستخدم (أو لا كود): طبّق الأفضل.
6. احسب السعر:
   - `PERCENT`: `discount = round(base_price * value / 100)`.
   - `FIXED`: `discount = value`.
   - `FULL`: `discount = base_price`.
   - `FREE_DAYS`: لا خصم مالي، أيام مجانية.
   - `final_price = max(0, base_price - discount)`.
   - قرّب لأقرب 250: `final_price = round(final_price / 250) * 250`.
7. وّلد `order_id`: `MB-` + 5 أحرف عشوائية (A-Z0-9).
8. أدخل في `orders` بـ `status = 'PENDING'`, `expires_at = NOW + 48h`.
9. إن كان `final_price = 0` (كود FULL أو FREE_DAYS):
   - وافق على الطلب فوراً (نفس منطق الموافقة).
   - أعد `{"status": "APPROVED", ...}`.
10. وإلا أعد تعليمات الدفع:
```json
{
  "order_id": "MB-7K3Q9",
  "final_price": 20000,
  "payment_methods": [...],
  "reference": "MB-7K3Q9",
  "expires_at": ...,
  "server_time": ...
}
```

### `POST /v1/orders/:id/proof` — إرسال إثبات الدفع

**المدخلات:**
```json
{
  "did": "...",
  "method": "زين كاش",
  "txn_ref": "123456789",
  "payer_name": "أحمد",
  "image": "base64..."       // اختياري، ≤300KB
}
```

**المنطق:**
1. تحقق أن الطلب موجود وحالته `PENDING` ومملوك لنفس `did`.
2. حدّث الطلب: `status = 'SUBMITTED'`, `method`, `txn_ref`, `payer_name`, `submitted_at = NOW`.
3. إن وُجدت صورة: احفظ في `proofs`.
4. أرسل إشعار تيليجرام للمالك:
```
📦 طلب جديد MB-7K3Q9
💰 20,000 د.ع — 3 أشهر
🏷️ كود: EID25 (خصم 25%)
💳 زين كاش — عملية #123456789
👤 أحمد — 07XXXXXXXXX
📱 بصمة: a3f9...

[✅ موافقة]  [❌ رفض]  [🔍 تفاصيل]
```
مع أزرار inline.

### `GET /v1/orders/:id` — فحص حالة الطلب

1. تحقق أن الطلب مملوك لـ `did` المُرسل.
2. أعد `{"status": "...", "reject_reason": "...", "server_time": ...}`.
3. إن كان `APPROVED`: أعد رمز ترخيص جديد أيضاً.

### `POST /v1/orders/:id/cancel` — إلغاء الطلب

1. فقط إن كان `PENDING` (لم يُرسل إثبات).
2. حرّر حجز الكود.
3. حدّث الحالة إلى `CANCELLED`.

## 1.11 منطق الموافقة (دالة مشتركة)

**الدالة `approveOrder(db, orderId, decidedBy)`:**

**في دفعة D1 واحدة (batch):**
1. `UPDATE orders SET status='APPROVED', decided_at=NOW, decided_by=? WHERE id=? AND status='SUBMITTED'` — تحقق `changes > 0`.
2. اجلب الطلب والجهاز.
3. `new_paid_until = max(NOW, device.paid_until) + order.days_granted * 86400000`.
4. `UPDATE devices SET paid_until=?, name=COALESCE(?,name), phone=COALESCE(?,phone) WHERE device_id=?`.
5. إن وُجد كود: `UPDATE codes SET used_count=used_count+1, reserved_count=reserved_count-1 WHERE code=?` و `UPDATE code_redemptions SET state='CONSUMED' WHERE code=? AND device_id=? AND state='RESERVED'`.
6. `INSERT INTO events (ts, device_id, type, detail, actor) VALUES (NOW, ?, 'ORDER_APPROVED', ?, ?)`.

## 1.12 بوت تيليجرام — `src/telegram.ts`

**معالجة webhook:**
1. تحقق من `secret_token` في ترويسة `X-Telegram-Bot-Api-Secret-Token`.
2. تحقق أن `message.chat.id == OWNER_CHAT_ID` أو `callback_query.from.id == OWNER_CHAT_ID`.
3. أوامر النص:

| الأمر | المنطق |
|---|---|
| `/start` | رسالة ترحيب بقائمة الأوامر |
| `/stats` | `SELECT COUNT(*) ... GROUP BY status` من `devices` + إجمالي إيراد الشهر + معدل التحويل |
| `/pending` | اجلب طلبات `SUBMITTED` واعرضها مع أزرار |
| `/expiring N` | `WHERE paid_until BETWEEN NOW AND NOW+N*86400000` |
| `/find <query>` | ابحث في `phone`, `device_id` (أول 8 أحرف), `name` |
| `/grant <did_or_short> <plan_or_days>` | أنشئ طلب `APPROVED` يدوياً + أعد مفتاح تفعيل |
| `/extend <did_or_short> <days>` | مدّد `paid_until` |
| `/revoke <did_or_short>` | صفّر `paid_until` |
| `/block <did_or_short> [reason]` | `blocked = 1` |
| `/unblock <did_or_short>` | `blocked = 0` |
| `/note <did_or_short> <text>` | حدّث `notes` |
| `/code new <code> <kind> <value> <start> <end> [max=N] [plans=*]` | أنشئ كود |
| `/code off <code>` | `active = 0` |
| `/code stats <code>` | إحصائيات الكود |
| `/codes` | قائمة الأكواد النشطة |
| `/price <plan_id> <price>` | غيّر السعر |
| `/report [month]` | تقرير إيراد شهري |
| `/export` | CSV بالمشتركين والطلبات |
| `/maint on/off [msg]` | وضع الصيانة |
| `/tos set <text>` | تحديث نص شروط الخدمة |
| `/tos privacy <text>` | تحديث نص سياسة الخصوصية |
| `/tos bump` | رفع `tos_version` (يُجبر المستخدمين على قبول الشروط الجديدة) |

**أزرار Callback:**
- `approve:<order_id>` → شاشة تأكيد ثانية: «هل أنت متأكد؟ [نعم] [لا]»
- `approve_confirm:<order_id>` → `approveOrder()` → رسالة «تمت الموافقة ✅»
- `reject:<order_id>` → اطلب سبب الرفض (انتظر الرسالة التالية)
- `reject_reason:<order_id>:<reason>` → حدّث الطلب + حرّر الكود + أرسل «تم الرفض ❌»

**إرسال الرسائل:** دالة `sendTelegram(botToken, chatId, text, replyMarkup?)` تستعمل `fetch("https://api.telegram.org/bot${token}/sendMessage", ...)`.

## 1.13 المهام المجدولة — `src/cron.ts`

**Cron: `0 5 * * *` (08:00 بغداد):**

1. **انتهاء الطلبات:** `UPDATE orders SET status='EXPIRED' WHERE status IN ('PENDING','SUBMITTED') AND expires_at < NOW`. لكل طلب منتهٍ: حرّر حجز الكود.
2. **تنظيف الصور:** `DELETE FROM proofs WHERE created_at < NOW - 90*86400000`.
3. **التقرير اليومي:**
   - تجارب جديدة أمس.
   - طلبات مُعتمدة أمس + إيراد.
   - اشتراكات تنتهي خلال 3 أيام.
   - مشتركون نشطون.
   - إجمالي إيراد الشهر.
4. **النسخة الاحتياطية:** صدّر أهم الجداول كـ CSV وأرسلها كملف على تيليجرام.
5. صفّر `daily_new_trials`.

## 1.14 حدود المحاولات — `src/rate-limit.ts`

**دالة `checkRateLimit(db, key, maxPerHour)`:**
1. `SELECT * FROM rate_limits WHERE k = ?`.
2. إن لم يوجد أو `window_start < NOW - 3600000`: `INSERT OR REPLACE INTO rate_limits VALUES (?, NOW, 1)`.
3. إن `n >= maxPerHour`: أعد `false`.
4. وإلا: `UPDATE rate_limits SET n = n + 1 WHERE k = ?`.

## 1.15 دوال مساعدة — `src/utils.ts`

```typescript
// تطبيع الكود: أرقام عربية ← لاتينية، حذف مسافات/شرطات، حروف كبيرة
export function normalizeCode(input: string): string {
    return input
        .replace(/[٠-٩]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x0660 + 0x0030))
        .replace(/[۰-۹]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x06F0 + 0x0030))
        .replace(/[\s\-_]/g, '')
        .toUpperCase();
}

// تقريب لأقرب 250
export function roundPrice(price: number): number {
    return Math.round(price / 250) * 250;
}

// توليد معرّف طلب
export function generateOrderId(): string {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // بدون O/0/1/I
    let result = 'MB-';
    for (let i = 0; i < 5; i++) {
        result += chars[Math.floor(Math.random() * chars.length)];
    }
    return result;
}
```

## 1.16 ملف `wrangler.toml`

```toml
name = "bordy-license"
main = "src/index.ts"
compatibility_date = "2024-01-01"

[[d1_databases]]
binding = "DB"
database_name = "bordy-license"
database_id = "REPLACE_WITH_ACTUAL_ID"

[triggers]
crons = ["0 5 * * *"]
```

---

# المرحلة 2: وحدة الترخيص في التطبيق

> **الهدف:** التطبيق يتصل بالخادم، يتحقق من الرمز، ويعرض البوابات (TOS + الترخيص).

## 2.1 هيكل الملفات الجديدة

**كل الملفات في حزمة `com.mubtaker.boards.license`:**

```
license/
├── DeviceId.kt
├── LicenseToken.kt
├── LicenseStore.kt
├── ClockGuard.kt
├── LicenseApi.kt
├── LicenseManager.kt
├── LicenseState.kt
├── IntegrityChecks.kt
└── ui/
    ├── TosScreen.kt
    ├── ActivationScreen.kt
    ├── PaywallScreen.kt
    └── LicenseBanner.kt
```

## 2.2 `DeviceId.kt`

```kotlin
package com.mubtaker.boards.license

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceId {
    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val raw = "$androidId${context.packageName}mubtaker_salt_2026"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } // 32 hex chars
    }

    fun getShortId(context: Context): String = get(context).take(8)

    fun getDisplayId(context: Context): String {
        val full = get(context)
        return full.chunked(4).joinToString("-") // XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX
    }
}
```

## 2.3 `LicenseToken.kt`

**التعليمات:**
1. فك ترميز الرمز: `payload_b64.signature_b64` (مفصولة بنقطة).
2. فك `base64url` للـ payload → JSON → كائن `TokenPayload`.
3. التحقق من التوقيع:
   - جرّب المفتاح `k1` ثم `k2` (من `BuildConfig`).
   - فك base64 المفتاح العام DER ← `KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))`.
   - **تحويل التوقيع من raw `r||s` إلى DER:** هذه الدالة ضرورية لأن WebCrypto يُخرج raw.
   ```kotlin
   private fun rawToDer(raw: ByteArray): ByteArray {
       val r = raw.sliceArray(0 until 32)
       val s = raw.sliceArray(32 until 64)
       fun encodeInt(v: ByteArray): ByteArray {
           val trimmed = v.dropWhile { it == 0.toByte() }.toByteArray()
           val padded = if (trimmed.isEmpty() || trimmed[0].toInt() and 0x80 != 0)
               byteArrayOf(0) + trimmed else trimmed
           return byteArrayOf(0x02, padded.size.toByte()) + padded
       }
       val dr = encodeInt(r)
       val ds = encodeInt(s)
       return byteArrayOf(0x30, (dr.size + ds.size).toByte()) + dr + ds
   }
   ```
   - `Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(payloadBytes); verify(derSignature) }`.
4. **إذا فشل التحقق:** أعد `null`.

## 2.4 `LicenseStore.kt`

**التعليمات:**
1. الملف: `context.noBackupFilesDir / "license.enc"`.
2. **مفتاح التشفير:** من Android Keystore:
   ```kotlin
   private fun getOrCreateKey(): SecretKey {
       val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
       if (ks.containsAlias("mubtaker_license_key")) {
           return (ks.getEntry("mubtaker_license_key", null) as KeyStore.SecretKeyEntry).secretKey
       }
       val spec = KeyGenParameterSpec.Builder("mubtaker_license_key", PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
           .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
           .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
           .build()
       KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(spec) }.generateKey()
       return (ks.getEntry("mubtaker_license_key", null) as KeyStore.SecretKeyEntry).secretKey
   }
   ```
3. **التشفير:** AES-GCM. التنسيق: `[12-byte IV][ciphertext+tag]`.
4. **دوال:** `save(token: String, tosAccepted: Boolean, tosVersion: Int)`, `load(): StoredLicense?`, `clear()`.
5. `StoredLicense` يحتوي: `token: String`, `tosAccepted: Boolean`, `tosVersion: Int`.

## 2.5 `ClockGuard.kt`

**التعليمات:**
1. ملف: `context.noBackupFilesDir / "clock.dat"` (نص عادي، epoch ms).
2. عند كل فحص: اقرأ `maxSeen`. إن كان `System.currentTimeMillis() < maxSeen - 600_000` (10 دقائق): أعد `CLOCK_TAMPER`.
3. وإلا: حدّث `maxSeen = max(maxSeen, System.currentTimeMillis())` واحفظ.

## 2.6 `LicenseState.kt`

```kotlin
package com.mubtaker.boards.license

sealed class LicenseState {
    object Loading : LicenseState()
    object NeedTos : LicenseState()                                    // لم يوافق على الشروط
    object NoLicense : LicenseState()                                  // لا رمز + لا إنترنت
    data class Trial(val daysLeft: Int, val showBanner: Boolean) : LicenseState()
    data class Active(val daysLeft: Int, val showReminder: Boolean) : LicenseState()
    data class Grace(val daysLeft: Int) : LicenseState()
    object Expired : LicenseState()                                    // قفل كامل
    data class Blocked(val reason: String?) : LicenseState()
    object ClockTamper : LicenseState()
    data class NeedUpdate(val minVersion: Int) : LicenseState()
}
```

## 2.7 `LicenseManager.kt`

**التعليمات:**
1. Singleton (object أو Hilt/Koin إن استُعمل، لكن المشروع لا يستعمل DI فاستعمل `object` أو companion factory).
2. **`_state: MutableStateFlow<LicenseState>`** يبدأ بـ `Loading`.
3. **`init(context)`:**
   - اقرأ `LicenseStore.load()`.
   - إن لم يُقبل TOS أو إصدار TOS أقل من المطلوب: `state = NeedTos`.
   - إن لم يوجد رمز: `state = NoLicense`.
   - إن وُجد رمز: تحقق → استنتج الحالة.
4. **`checkAndRefresh(context)`:** يُستدعى عند الفتح وعند `onResume` (لا أكثر من مرة كل 6 ساعات):
   - إن كان عمر الرمز > 24 ساعة وهناك إنترنت: `/refresh` بالخلفية.
   - إن نجح: خزّن الرمز الجديد.
   - إن فشل بسبب الشبكة: لا تُغيّر الحالة (ما دام الرمز ساري).
   - إن فشل بـ `BLOCKED`: `state = Blocked`.
5. **`acceptTos(context, tosVersion)`:** يحفظ القبول في `LicenseStore` ثم ينتقل لحالة التفعيل.
6. **`activate(context)`:** يُرسل `/activate` ← يخزّن الرمز ← يحدّث الحالة.
7. **استنتاج الحالة من الرمز:**
   ```
   إن كان minv > BuildConfig.VERSION_CODE → NeedUpdate
   إن كان clockGuard.check() = TAMPER → ClockTamper
   إن كان blocked → Blocked
   إن كان now > exp → Expired (أو NoLicense إن لم يكن هناك رمز)
   إن كان st = "trial":
       daysLeft = (tre - now) / 86400000
       إن daysLeft <= 0 → Expired
       إن daysLeft <= 3 → Trial(daysLeft, showBanner=true)
       وإلا → Trial(daysLeft, showBanner=false)
   إن كان st = "paid" أو "owner":
       إن now > pu → Grace أو Expired
       daysLeft = (pu - now) / 86400000
       إن daysLeft <= 5 → Active(daysLeft, showReminder=true)
       وإلا → Active(daysLeft, showReminder=false)
   ```

## 2.8 `IntegrityChecks.kt`

**التعليمات:**
1. **فحص بصمة شهادة التوقيع:**
   ```kotlin
   fun checkSigningCert(context: Context): Boolean {
       val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
       val sig = info.signingInfo.apkContentsSigners[0]
       val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
       val hex = digest.joinToString(":") { "%02X".format(it) }
       return hex == BuildConfig.EXPECTED_CERT_SHA256
   }
   ```
2. **فحوص موزعة:** أنشئ 4 دوال بأسماء مختلفة غير واضحة (مثل `validateSession()`, `checkDataIntegrity()`, `verifyAppState()`, `ensureConsistency()`) كل واحدة تتحقق من `LicenseManager.state` وتعيد `false` إن كان `Expired` أو `Blocked`.
3. **أماكن الاستدعاء:** عند حفظ بورد، عند تصدير PDF، عند تصدير نسخة احتياطية، عند فتح محرر البوردات. كل مكان يستعمل دالة مختلفة.

## 2.9 `LicenseApi.kt`

**التعليمات:**
1. استعمل `HttpURLConnection` فقط. لا OkHttp ولا Retrofit.
2. **مهلة:** 10 ثوانٍ اتصال، 15 ثانية قراءة.
3. **إعادة المحاولة:** 3 محاولات بتأخر متصاعد (1s, 3s, 9s).
4. **دوال:**
   - `activate(did, ver, model, sdk, certHash, nonce): ApiResponse`
   - `refresh(did, nonce): ApiResponse`
   - `getPlans(): PlansResponse`
   - `getTos(): TosResponse`
   - `createOrder(did, planId, code?, payerName, payerPhone): OrderResponse`
   - `submitProof(did, orderId, method, txnRef, payerName, imageBase64?): ApiResponse`
   - `checkOrder(did, orderId): OrderStatusResponse`
   - `cancelOrder(did, orderId): ApiResponse`
   - `redeemCode(did, code): ApiResponse`
5. **العنوان:** `BuildConfig.API_BASE_URL`. إن فشل 3 مرات: جرّب `BuildConfig.API_FALLBACK_URL`.
6. كل طلب وكل رد يُنفّذ في `Dispatchers.IO`.

## 2.10 شاشة شروط الخدمة — `ui/TosScreen.kt`

**التعليمات:**
1. **شاشة Compose كاملة** بخلفية التطبيق ونظام التصميم الحالي.
2. **في المنتصف:**
   - أيقونة التطبيق.
   - نص: «مرحباً بك في المُبتَكَر».
   - نص صغير: «باستخدامك التطبيق فإنك توافق على».
   - **نصوص قابلة للضغط** (بلون مختلف + تحتها خط): «شروط الخدمة» و «سياسة الخصوصية».
   - عند الضغط على أي منهما: `AlertDialog` تعرض النص الكامل (قابل للتمرير).
   - النص يُجلب من الخادم (`GET /v1/tos`). إن لم يكن هناك إنترنت: نص افتراضي مخزّن محلياً.
3. **زر «أوافق وأبدأ»** كبير.
4. عند الضغط: `LicenseManager.acceptTos(context, tosVersion)`.

## 2.11 شاشة التفعيل — `ui/ActivationScreen.kt`

**التعليمات:**
1. **حالة لا رمز + إنترنت:** زر «ابدأ تجربتك المجانية (7 أيام)» + حقل اسم (اختياري) + حقل هاتف (اختياري).
2. **حالة لا رمز + بلا إنترنت:** «اتصل بالإنترنت مرة واحدة لبدء التجربة المجانية. بياناتك تبقى على جهازك ولا تُرسل لأحد.»
3. **حقل مفتاح التفعيل اليدوي:** حقل نصي طويل + زر «تفعيل».
4. **بصمة الجهاز:** تُعرض بصيغة `XXXX-XXXX-...` مع زر نسخ وزر مشاركة (واتساب/تيليجرام).
5. **نص صغير:** «البصمة تُرسل فقط لتفعيل الترخيص.»

## 2.12 شاشة الاشتراك — `ui/PaywallScreen.kt`

**التعليمات:**
1. **قسم الخطط:** بطاقات للخطط (من `/v1/plans`). إن وُجد خصم تلقائي: يُشطب السعر القديم.
2. **حقل كود الخصم:** مع زر «تطبيق» يستدعي `/v1/codes/check`.
3. **ملخص السعر:** السعر الأصلي، الخصم، السعر النهائي.
4. **زر «اشترك»** → ينشئ طلب `/v1/orders`.
5. **شاشة تعليمات الدفع:** تظهر بعد إنشاء الطلب:
   - طرق الدفع (من الخادم) مع أرقام الحسابات.
   - المبلغ المطلوب + زر نسخ.
   - الرقم المرجعي + زر نسخ.
   - «حوّل المبلغ عبر إحدى الطرق أعلاه واكتب رقم العملية.»
   - حقل: طريقة الدفع (dropdown).
   - حقل: رقم العملية (إلزامي).
   - حقل: اسم المرسل.
   - زر: إرفاق صورة إيصال (اختياري).
   - زر «أرسلت الحوالة» → `POST /v1/orders/:id/proof`.
6. **شاشة الانتظار:** بعد الإرسال:
   - «استلمنا طلبك MB-XXXXX. سيُفعَّل اشتراكك فور التأكد من الحوالة.»
   - رسوم متحركة انتظار.
   - polling كل 15 ثانية (`GET /v1/orders/:id`).
   - عند `APPROVED`: رسالة «تم التفعيل ✅» + انتقال للتطبيق.
   - عند `REJECTED`: رسالة بالسبب + زر إعادة المحاولة.
7. **أسفل الشاشة:** «تواصل معنا» مع رابط تيليجرام ورابط واتساب.

## 2.13 شريط التحذير — `ui/LicenseBanner.kt`

- **شريط التجربة** (أصفر): «تبقّى X يوم في التجربة المجانية» — يظهر فقط في آخر 3 أيام.
- **شريط التجديد** (برتقالي): «ينتهي اشتراكك بعد X أيام — جدّد الآن» — يظهر قبل 5 أيام.
- **شريط السماح** (أحمر): «انتهى اشتراكك. لديك X يوم سماح — جدّد لتفادي التوقف.»
- يُعرض في أعلى `MainScreen` (فوق المحتوى الحالي).

## 2.14 تعديلات الملفات الموجودة

### `MainActivity.kt`

**الإجراء:** في `onCreate` أو عند بناء `MainScreen()`:
```kotlin
// قبل MainScreen()
val licenseState by LicenseManager.state.collectAsState()

when (licenseState) {
    is LicenseState.Loading -> LoadingScreen()
    is LicenseState.NeedTos -> TosScreen()
    is LicenseState.NoLicense -> ActivationScreen()
    is LicenseState.Expired -> PaywallScreen()
    is LicenseState.Blocked -> BlockedScreen(reason)
    is LicenseState.ClockTamper -> ClockTamperScreen()
    is LicenseState.NeedUpdate -> UpdateRequiredScreen()
    else -> {
        // Trial, Active, Grace → التطبيق يعمل
        MainScreen() // مع LicenseBanner في الأعلى حسب الحالة
    }
}
```

### `SettingsScreen.kt`

**الإجراء:** أضف قسم «الاشتراك» يحتوي:
- حالة الاشتراك (TRIAL/ACTIVE/GRACE/EXPIRED مع الأيام المتبقية).
- بصمة الجهاز (مع نسخ).
- زر «تجديد الاشتراك» → ينتقل لـ `PaywallScreen`.
- زر «إدخال كود خصم» → حقل + زر.
- زر «إدخال مفتاح تفعيل» → حقل + زر.
- رابط «شروط الخدمة» → يفتح Dialog بالنص.
- رابط «سياسة الخصوصية» → يفتح Dialog بالنص.

### نقاط الفحص الموزعة

أضف فحوص في:
1. **`MainViewModel`** (أو ما يقابله) — عند حفظ/إضافة بورد: `if (!validateSession(context)) return`.
2. **`PdfHelper`** — عند تصدير PDF: `if (!checkDataIntegrity(context)) return`.
3. **`BackupExporter`** — عند تصدير النسخة: `if (!verifyAppState(context)) return`.
4. **فتح محرر البوردات:** `if (!ensureConsistency(context)) return`.

كل فحص فاشل يعرض `Toast` أو `Snackbar` «جدّد اشتراكك للمتابعة» ولا يعطّل التطبيق بشكل صامت.

---

# المرحلة 3: الدفع والأكواد

> **الهدف:** تدفق الدفع الكامل يعمل من الطلب إلى الموافقة.

هذه المرحلة مغطاة في المرحلة 2 (شاشة PaywallScreen + معالجات الخادم). المطلوب هنا:

1. **اختبار شامل** لتدفق الدفع:
   - مستخدم يختار خطة ← يدخل كود ← يرسل إثبات ← المالك يوافق ← التطبيق يُفعَّل.
   - مستخدم يختار خطة ← يدخل كود مستنفد ← رسالة خطأ.
   - مستخدم يدخل كود FREE_DAYS ← يُفعَّل فوراً.
   - مستخدم يدخل كود FULL ← يُفعَّل فوراً.
2. **اختبار أكواد الخصم:**
   - PERCENT, FIXED, FREE_DAYS, FULL — كل نوع.
   - كود بأرقام عربية.
   - كود منتهي الصلاحية.
   - كود مستنفد.
   - كود `auto_apply`.

---

# المرحلة 4: لوحة الويب والتقارير

> **الهدف:** لوحة ويب بسيطة للإدارة كبديل للبوت.

## 4.1 صفحة واحدة HTML

**الملف:** `src/admin/panel.ts` — تعيد HTML كامل.

**المحتويات:**
1. **صفحة دخول:** حقل سر طويل + TOTP. بعد الدخول: كوكي `HttpOnly; Secure; SameSite=Strict` لـ 12 ساعة.
2. **لوحة رئيسية:**
   - بطاقات: مشتركون نشطون | في التجربة | منتهون | ينتهون خلال 7 أيام | إيراد الشهر.
   - جدول مشتركين (بحث + فرز + إجراءات).
   - جدول طلبات (مع عرض الإيصال + أزرار موافقة/رفض).
   - جدول أكواد (مع نموذج إنشاء).
   - سجل أحداث.
   - تحرير شروط الخدمة وسياسة الخصوصية.
   - تحرير طرق الدفع.
   - زر تصدير CSV.

## 4.2 أمان اللوحة

- `ADMIN_SECRET` طويل (32+ حرف).
- TOTP (HMAC-SHA1) بدل PBKDF2 (حد 30ms CPU).
- 5 محاولات فاشلة ← قفل 15 دقيقة + تنبيه تيليجرام.

---

# المرحلة 5: التصليب

> **الهدف:** رفع كلفة الاختراق.

1. **تفعيل R8** (مرحلة 0 جاهزة).
2. **فحص التوقيع** (`IntegrityChecks.kt` جاهز).
3. **فحوص موزعة** (مرحلة 2 جاهزة).
4. **تحديثات مشروطة:** `GET /v1/update` يعيد رابط التنزيل لرمز ساري فقط.
5. **إشعار «اتصل بالإنترنت»** قبل يومين من انتهاء الرمز.
6. **Key Attestation: لا يُنفَّذ** (قرار المالك).

---

# المرحلة 6: الإطلاق

1. **10 مختبرين** لأسبوعين.
2. **سياسة خصوصية + شروط خدمة** تُكتب وتُضاف من البوت.
3. **صفحة تنزيل** بسيطة (Cloudflare Pages أو GitHub Pages).
4. **قناة تيليجرام** للتحديثات.
5. **توجيه GitHub Releases** كمصدر APK رئيسي.

---

## جدول التعارضات — مراجعة نهائية

| # | التعارض | الحل | ✅ مغطّى |
|---|---|---|---|
| 1 | ترخيص داخل Room | ملف مستقل `noBackupFilesDir` | ✅ |
| 2 | Google Backup | `allowBackup=false` | ✅ |
| 3 | `fallbackToDestructiveMigration` | يُزال + Migration صريح | ✅ |
| 4 | تغيير مفتاح التوقيع | ثابت + نسختين | ✅ |
| 5 | debug vs release | `/grant` للمالك | ✅ |
| 6 | دفع بدون إثبات | `/grant` يدوي | ✅ |
| 7 | موافقة مزدوجة | `WHERE status='SUBMITTED'` ذري | ✅ |
| 8 | كود مستنفد | حجز ذري | ✅ |
| 9 | كود انتهى أثناء المراجعة | وقت إنشاء الطلب | ✅ |
| 10 | طلب منتهي بعد الدفع | `/grant` أو `/reopen` | ✅ |
| 11 | أرقام عربية | تطبيع | ✅ |
| 12 | تجديد مبكر | `max(now, end) + days` | ✅ |
| 13 | FREE_DAYS أثناء التجربة | يمدّد `trial_ends_at` | ✅ |
| 14 | مناطق زمنية | UTC epoch فقط | ✅ |
| 15 | ساعة خاطئة بريئة | CLOCK_TAMPER + اتصال | ✅ |
| 16 | أوفلاين + سفر | مفتاح يدوي + 7 أيام + إشعار | ✅ |
| 17 | تبديل جهاز | `/transfer/request` | ✅ |
| 18 | تجارب متعددة | تنبيه للهاتف المتكرر | ✅ |
| 19 | حد 30ms CPU | لا تشفير ثقيل | ✅ |
| 20 | حد D1 | فهارس + `last_seen` يومياً | ✅ |
| 21 | ضياع D1 | نسخة يومية تيليجرام | ✅ |
| 22 | تسرّب المفتاح | تدوير k2 | ✅ |
| 23 | خادم متوقف | رموز سارية + مفتاح يدوي | ✅ |
| 24 | تيليجرام محجوب | لوحة ويب | ✅ |
| 25 | رفض الإنترنت | شاشة توضيحية | ✅ |
| 26 | تنبيهات التسليم | مستقلة | ✅ |
| 27 | أسعار مخفية | غير مرتبط | ✅ |
| 28 | تغيير سعر أثناء طلب | السعر مجمّد | ✅ |
| 29 | استرداد | سياسة + `/revoke` | ✅ |
| 30 | تعدد مالكين | `ADMIN_CHAT_IDS` لاحقاً | ✅ |
| 31 | إصدار قديم | `minv` | ✅ |
| 32 | تغيير عنوان Worker | عنوان بديل في BuildConfig | ✅ |
| 33 | خطأ 500 أثناء الموافقة | دفعة D1 واحدة | ✅ |
| 34 | إغراق طلبات | حدود + طلب معلق واحد | ✅ |
| **35** | **TOS لم يوافق** | **بوابة TOS إلزامية قبل أي شيء** | ✅ |
| **36** | **تحديث TOS** | **`tos_version` يُرفع → يُطلب قبول جديد** | ✅ |

---

## نصوص الواجهة (عربي)

| الحالة | النص |
|---|---|
| TOS | «مرحباً بك في المُبتَكَر. باستخدامك التطبيق فإنك توافق على شروط الخدمة وسياسة الخصوصية.» |
| تفعيل أول | «اتصل بالإنترنت مرة واحدة لبدء تجربتك المجانية 7 أيام. بياناتك تبقى على جهازك ولا تُرسل لأحد.» |
| التجربة (آخر 3 أيام) | «تبقّى {n} يوم في التجربة المجانية» |
| ACTIVE قبل الانتهاء | «ينتهي اشتراكك بعد {n} أيام — جدّد الآن» |
| GRACE | «انتهى اشتراكك. لديك {n} يوم سماح — جدّد لتفادي التوقف.» |
| EXPIRED | «انتهت فترتك. اشترك لمتابعة العمل.» |
| كود غير صالح | «الكود غير صالح أو منتهي.» |
| طلب مُرسل | «استلمنا طلبك {ref}. سيُفعَّل اشتراكك فور التأكد من الحوالة.» |
| رفض | «تعذّر قبول طلبك: {السبب}. تواصل معنا.» |
| ساعة الجهاز | «وقت جهازك غير مضبوط. اتصل بالإنترنت للمزامنة.» |
| BLOCKED | «حسابك موقوف. تواصل مع الدعم.» |
| تحديث مطلوب | «يلزم تحديث التطبيق للاستمرار.» |

</div>
