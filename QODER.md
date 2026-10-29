# QODER.md — تعليمات مشروع «بوردي»

> هذا الملف هو المرجع الأول لأي وكيل يعمل على هذا المستودع. اقرأه كاملاً قبل أي تعديل،
> ثم ارجع إلى `docs/PROJECT_CONTEXT.md` للتفاصيل المعمارية.

## هوية المشروع

تطبيق أندرويد عربي (RTL) لإدارة بوردات ورش العمل: مخزون بأحجام، موردون وعروض، قوالب،
تسعير بيع/شراء، أرباح، فواتير PDF، بالإضافة إلى نظام ترخيص واشتراك ومزامنة سحابية.

- حزمة التطبيق: `com.bordy.app` — اسم المنتج: «بوردي» (أسماء قديمة: «المُبتَكَر»، `mubtaker`).
- الخادم: Cloudflare Worker + D1 في `bordy-license/` (TypeScript، بدون إطار عمل).
- المستودع محلي على `D:\kk - Copy`؛ لا ريموت بعد.

## البنية

```
app/src/main/java/com/bordy/app/
  data/        Room: Entities, Daos, AppDatabase (v26), SyncCodec, SyncDao, ActivityLog
  license/     تفعيل، تحقق ECDSA P-256، ClockGuard، DeviceId، IntegrityChecks، واجهات
  sync/        AccountStore, SyncApi, SyncEngine, SyncWorker (WorkManager)
  screens/     Compose screens (Projects, Components, Suppliers, Templates, Calculator,
               Account, Settings, ActivityLog, RootNavigation)
  ui/          DesignSystem, theme, animations, مكونات مشتركة
  utils/       رياضيات المشروع/القالب، PdfHelper، Biometrics، MediaStore، Format
  viewmodel/   MainViewModel
bordy-license/src/
  handlers/    activate, refresh, plans, sync, codes, orders, transfer, tos, update
  account/     حسابات، tokens، transfers، sync (v2)
  admin/       لوحة ويب + auth
  cron.ts, telegram.ts, rate-limit.ts, db.ts, crypto.ts, utils.ts, index.ts
```

## أوامر التشغيل (Windows + Git Bash)

`adb` و`java` ليسا في PATH لهذه الجلسة — استخدم المسارات المطلقة أو سكربتات `scripts/`.

```bash
export JAVA_HOME=/c/Users/BOGEY/.jdks/jbr-17.0.14
export ANDROID_HOME=/c/Users/BOGEY/AppData/Local/Android/Sdk
export ADB="$ANDROID_HOME/platform-tools/adb.exe"

./scripts/build-debug.sh        # assembleDebug
./scripts/install-device.sh     # تثبيت على الجهاز
./scripts/verify-ui.sh          # فحص الواجهة عبر uiautomator
./scripts/pull-db.sh            # سحب db + db-wal بأمان
./scripts/deploy-worker.sh      # نشر Worker
```

نسخة التطوير: `./gradlew :app:assembleDebug` — الاختبارات: `./gradlew :app:testDebugUnitTest`.
Lint: `./gradlew :app:lintDebug` (baseline في `app/lint-baseline.xml`).
لقطات الشاشة: `./gradlew :app:recordRoborazziDebug` للتحديث، و`:app:verifyRoborazziDebug` للتحقق.
الخادم: `cd bordy-license && npm test && npx tsc --noEmit && npx wrangler dev`.
جدول الرموز: AGP 8.13.2، Kotlin 2.0.20، Gradle 9.0، JDK 17 (JBR)، compileSdk 34، minSdk 30.

## طقس التحقق الإلزامي (لا تختصره)

1. بناء → تثبيت → تشغيل على الجهاز، ثم اختبر التدفق المتأثر بما فيه الحالات الحدية.
2. النص والإحداثيات تُقرأ من `uiautomator dump` فقط — لا من الصور. الصور للحكم على الشكل والألوان.
3. أعد بيانات الاختبار التي أنشأتها. لا تحذف صفاً ليس من إنشائك في هذه الجلسة (قد يكون عمل المستخدم).
4. لا تستنتج «ضاعت البيانات» من ملف `.db` وحده — Room يستخدم WAL؛ اقرأ `.db` و`.db-wal` معاً بعد `force-stop`.

## قواعد المجال (لا تُخالف)

- «حجم/أحجام» = variant. «قطعة/القطع» = سطر البورد أو عنصر القالب. لا تستخدم «متغير» أو «سعة» أو «بند».
- سعر المخزون هو المرجع للتسعير؛ العنصر اليدوي/المنفصل فقط يحمل سعره المحفوظ.
- تكلفة العمل (`laborCost`) تُجمع مع البيع لحساب إجمالي البورد/القالب.
- لا تهاجر بيانات المستخدم القديمة تلقائياً إلى نماذج جديدة؛ اتركها كما هي وأبلغه.
- الأسعار بالدينار العراقي وتُقارب إلى 250 (تقريب الخادم)؛ لا تُغيّر التقريب محلياً دون قرار.

## أمان وممنوعات

- ممنوع لمس أو طباعة `admin_secrets.txt` و`*.pem` و`*.jks` و`keystore_info.txt` أو كلمات المرور في `local.properties`.
- ممنوع تعطيل أو إضعاف التحقق من الترخيص، أو إضافة مسار يتجاوز الاشتراك، أو حذف `IntegrityChecks`.
- ممنوع `fallbackToDestructiveMigration`. كل تغيير في الكيانات يحتاج `Migration` صريحاً ورفع `version`.
- لا أسرار داخل الكود أو `git`؛ أسرار Worker في `wrangler secret` و`.dev.vars` (مُهمَل من git).
- لا تنشئ APK release أو تنشر Worker أو تدفع إلى ريموت دون طلب صريح من المستخدم.

## أسلوب العمل مع المستخدم

- يكتب بالعربية ويقبل سرعة الكتابة والأخطاء؛ ردّ بالعربية وأبقِ واجهة التطبيق عربية RTL.
- هو صاحب القرار في المنتج: للميزات الكبيرة، استخدم وضع التخطيط واسأل أسئلة محدد قبل الكود.
- عند وصفه عنصراً «فوقه/تحته» تحقّق من الشجرة (dump) قبل التخمين.
- ملخصك النهائي قصير: ماذا تغيّر، وكيف تحققت، وما الخطوة التالية.

## مراجع

- `docs/PROJECT_CONTEXT.md` — البنية والتدفقات.
- `docs/DECISIONS.md` — القرارات المعتمدة (ترخيص، أسعار، مزامنة).
- `plan.md` / `LICENSING_PLAN.md` / `ACCOUNT_SYNC_PLAN.md` — الخطط الأصلية (مرجع تفصيلي عند الحاجة).
- `how2` — دليل إعداد يدوي للخادم والمفاتيح.
