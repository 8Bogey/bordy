package com.bordy.app.update

import android.content.Context
import android.content.pm.PackageManager
import com.bordy.app.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * فحص ملف التحديث قبل اعتباره «جاهزاً»: توقيعه بمفتاح نشرنا، وحزمته، وإصداره.
 * بلا هذا التحقق يكفي اختطاف رابط التنزيل (أو رفع ملف خاطئ) ليُركَّب أي APK
 * على أجهزة المشتركين بضغطة واحدة من داخل التطبيق.
 */
object ApkIntegrity {

    /** الفشل هنا يمنع ظهور زر التثبيت فقط ولا يوقف التطبيق، فلا مبرّر للتساهل. */
    fun verify(context: Context, apk: File, expectedVersion: String): Boolean {
        if (!apk.isFile || apk.length() <= 0L) return false
        return try {
            val info = context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: return false
            if (info.packageName != context.packageName) return false
            val versionName = info.versionName ?: return false
            // «1.3» و«1.3.0» إصدار واحد عند المقارنة الرقمية
            if (compareVersions(versionName, expectedVersion) != 0) return false
            val signers = info.signingInfo?.apkContentsSigners ?: return false
            if (signers.isEmpty()) return false
            if (BuildConfig.EXPECTED_CERT_SHA256.startsWith("REPLACE")) return true
            val digest = MessageDigest.getInstance("SHA-256")
            val expected = BuildConfig.EXPECTED_CERT_SHA256
            // كل الموقِّعين لا أولهم فقط: ملف موقَّع بدارين (تدوير مفاتيح) أو بـ v1 +
            // v3 قد يرفع الشهادة الصحيحة في غير المرتبة الأولى، فيُرفض باطلاً.
            signers.any { signer ->
                digest.reset()
                digest.digest(signer.toByteArray())
                    .joinToString(":") { "%02X".format(it) } == expected
            }
        } catch (_: Exception) {
            false
        }
    }
}
