package com.bordy.app.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {

    /** تثبيت تحديث: الملف في الكاش ويُقرأ عبر FileProvider (مسار cache-path معرَّف أصلاً). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * حذف التطبيق (خطوة الرجوع لنسخة أقدم — أندرويد يرفض التراجع عن versionCode).
     * ملف التثبيت يجب أن يكون منسوخاً أولاً إلى مجلد التنزيلات العام ليبقى بعد الحذف.
     */
    fun uninstallSelf(context: Context) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** نسخة من ملف APK إلى مجلد التنزيلات العام عبر MediaStore — تبقى بعد حذف التطبيق. */
    fun copyToPublicDownloads(context: Context, apk: File, displayName: String): Boolean {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                apk.inputStream().use { it.copyTo(out) }
            } != null
        } catch (_: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            false
        }
    }

    fun onWifiOrEthernet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
