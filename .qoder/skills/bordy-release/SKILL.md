---
name: bordy-release
description: إصدار نسخة release موقّعة من تطبيق بوردي — رفع الإصدار، فحص R8 والترخيص، التحقق من التوقيع، والنشر على GitHub/تيليجرام.
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

## النشر

- ارفع APK إلى GitHub Releases مع ملاحظات الإصدار العربية + رابط قناة تيليجرام.
- لا ترفع keystore أو المفاتيح، ولا تضع كلمات المرور في أوصاف الإصدار.
- خادم الترخيص: راجع skill `bordy-worker` قبل أي نشر متزامن مع التطبيق (توافق `/v1`).

## رفع خرائط R8 إلى Sentry (بعد كل إصدار موقّع)

بدون هذا الرفع تبقى انهيارات `bordy-android` مبهماً (`a.b.c` بلا أسماء حقيقية) لأن R8 يعيد تسمية الكود.

1. أنشئ token من Sentry بصلاحية `Projects:Write` (لا تلصقه في المحادثة أبداً)، وأضِفه إلى `local.properties`:
   ```properties
   SENTRY_AUTH_TOKEN=<token>
   ```
   الملف مُهمَل من git — لا تلتزمه ولا تطبع محتواه.
2. أضف `io.sentry.android.gradle` إلى `gradle/libs.versions.toml` + الجذر بـ `apply false`، ثم طبّقها في `app/build.gradle.kts` **بشرط وجود التوكن** حتى لا ينكسر بناء من لا يملكه:
   ```kotlin
   if (getReleaseProperty("SENTRY_AUTH_TOKEN").isNotBlank()) apply(plugin = "io.sentry.android.gradle")
   ```
3. اضبط بلوك `sentry { }` على رفع خريطة ProGuard فقط: المنظمة `bogies` والمشروع `bordy-android`، وأوقف تتبّع الأداء وتضمين المصادر (لا نريد أي جمع زائد). راجع أسماء الخصائص في وثائق الإضافة عبر context7 قبل الكتابة — لا تخمّن الأسماء.
4. لا تكتب التوكن في `build.gradle.kts`؛ يُقرأ من البيئة أو من `sentry.properties` (مُهمَل).
5. تحقق: بعد `assembleRelease` ابحث عن سطر رفع الخريطة الناجح، ثم افتح حدثاً في Sentry وتأكد أن الأسماء مقروءة لا مبهمة.
6. لا تُفعّل الرفع في CI قبل إضافة سر `SENTRY_AUTH_TOKEN` إلى أسرار المستودع، وإلا فشل كل بناء release.
