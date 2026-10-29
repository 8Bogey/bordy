---
name: bordy-release
description: إصدار نسخة release موقّعة من تطبيق بوردي — رفع الإصدار، فحص R8 والترخيص، التحقق من التوقيع، ورفع خرائط Sentry. التوزيع خاص بلا نشر عام.
---

# إصدار بوردي

> لا تبدأ إصداراً أو تنشر شيئاً دون طلب صريح من المستخدم.

## قبل البناء

1. ارفع `versionCode` و`versionName` في `app/build.gradle.kts`.
2. راجع `app/proguard-rules.pro`: يجب أن يحمي `com.bordy.app.license.**` و`RoomDatabase` و`@Entity`. لا تعتمد على `com.mubtaker.*` القديمة.
3. تأكد أن `EXPECTED_CERT_SHA256` يطابق شهادة `bordy-release.jks` المستخدمة فعلاً.
4. تأكد أن الملفات السرية خارج git: `*.jks`, `*.pem`, `local.properties`, `admin_secrets.txt`.

## البناء والفحص

```bash
export JAVA_HOME=/c/Users/BOGEY/.jdks/jbr-17.0.14
./gradlew :app:assembleRelease
$ANDROID_HOME/build-tools/34.0.0/apksigner.bat verify --print-certs app/build/outputs/apk/release/app-release.apk
```

- ثبّت نسخة release على جهاز نظيف (أو بعد إزالة debug) واختبر: TOS، التفعيل، التحقق بلا إنترنت، القفل عند الانتهاء، فتح شاشة المخزون والفاتورة PDF.
- أي `ClassNotFoundException` أو فشل تحقق توقيع بعد R8 = قاعدة keep ناقصة؛ أصلحها ولا تعطّل minify.

## التوزيع (خاص)

- المشروع تطوير خاص: **لا GitHub Releases عامة ولا نشر للجمهور**.
- التسليم يكون بملف APK مباشرة لمن تختاره، أو بتثبيت على أجهزة الاختبار عبر `scripts/install-device.sh`.
- لا ترفع keystore أو المفاتيح، ولا تضع كلمات المرور في أي وصف أو رسالة.
- خادم الترخيص: راجع skill `bordy-worker` قبل أي نشر متزامن مع التطبيق (توافق `/v1`).

## رفع خرائط R8 إلى Sentry (بعد كل إصدار موقّع)

بدون هذا الرفع تبقى انهيارات `bordy-android` مبهماً (`a.b.c` بلا أسماء حقيقية) لأن R8 يعيد تسمية الكود. الربط جاهز في المشروع: `io.sentry.android.gradle` 6.24.0 في `gradle/libs.versions.toml`، و`apply false` في بلوك plugins، ويُطبَّق في `app/build.gradle.kts` **فقط** عند وجود `SENTRY_AUTH_TOKEN`. بدونه يُبنى الـ release طبيعياً.

1. أنشئ token من Sentry بصلاحية `Project: Read & Write` مقيّدة بمشروع `bordy-android`، ولا تلصقه في المحادثة. أضِفه إلى `local.properties`:
   ```properties
   SENTRY_AUTH_TOKEN=<token>
   ```
   الملف مُهمَل من git — لا تلتزمه ولا تطبع محتواه.
2. مسار `cmd /c` على Windows (إصلاح مثبَّت هنا): الإضافة تمرّر مسار `sentry-cli` إلى `cmd /c` بلا اقتباس، ومسار المشروع يحتوي مسافة (`D:\kk - Copy`) فيفشل بـ `'D:\kk' is not recognized`. الحل: ملف `sentry.properties` في الجذر (مُهمَل في git) يحدد cli خارج المشروع:
   ```properties
   cli.executable=C:/Users/BOGEY/AppData/Local/sentry-cli/sentry-cli.exe
   ```
   والملف التنفيذي مستخرج من داخل الإضافة: `unzip -p <plugin-jar> bin/sentry-cli-Windows-i686.exe > .../sentry-cli.exe` (الإصدار 3.8.0، مُجرَّب).
3. الإعدادات في `app/build.gradle.kts`: `org=bogies`، `projectName=bordy-android`، `includeProguardMapping=true`، `autoUploadProguardMapping=true`، `includeSourceContext=false`، `telemetry=false`، و`tracingInstrumentation.enabled=false`.
4. تحقق محلي بدون رفع: توكن وهمي يجب أن يصل لخطأ **«invalid token»** من Sentry، لا خطأ مسار:
   ```bash
   ./gradlew :app:uploadSentryProguardMappingsRelease -PSENTRY_AUTH_TOKEN=dummy
   ```
   بعد إضافة التوكن الحقيقي نفّذ `:app:assembleRelease` وتأكد من نجاح `uploadSentryProguardMappingsRelease`، ثم افتح حدثاً في Sentry وتأكد أن الأسماء مقروءة لا مبهمة.
5. لا تُفعّل الرفع في CI قبل إضافة سر `SENTRY_AUTH_TOKEN` إلى أسرار المستودع، وإلا فشل كل بناء release.
