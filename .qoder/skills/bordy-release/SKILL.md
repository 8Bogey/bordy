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
