package com.bordy.app.update

import com.bordy.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateApi {

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000
    private const val DOWNLOAD_TIMEOUT = 60_000
    private const val MAX_APK_BYTES = 150L * 1024 * 1024

    /** تحقق واحد قصير بلا إعادات محاولات — WorkManager يملك سياسة الإعادة. */
    suspend fun fetchConfig(): UpdateConfig? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(BuildConfig.API_BASE_URL + "/v1/update").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.readText() ?: return@withContext null
            val json = JSONObject(resp)
            if (!json.optBoolean("ok", false)) return@withContext null
            val intervalSeconds = json.optLong("check_interval_seconds", 0L).let { secs ->
                if (secs >= 5L) secs else json.optInt("check_hours", 12).coerceIn(1, 168) * 3600L
            }
            UpdateConfig(
                minVersion = json.optString("min_version", "1.0").ifBlank { "1.0" },
                latestVersion = json.optString("latest_version", "1.0").ifBlank { "1.0" },
                downloadUrl = json.optString("download_url", ""),
                notes = json.optString("notes", ""),
                checkIntervalSeconds = intervalSeconds.coerceIn(5L, 7L * 24 * 3600),
                wifiOnly = json.optBoolean("wifi_only", true),
                triggerMode = json.optString("trigger_mode", UpdateConfig.TRIGGER_OFF)
                    .let { mode -> if (mode == UpdateConfig.TRIGGER_FORCED || mode == UpdateConfig.TRIGGER_OPTIONAL) mode else UpdateConfig.TRIGGER_OFF },
                triggerId = json.optLong("trigger_id", 0L).coerceAtLeast(0L),
                triggerMessage = json.optString("trigger_message", ""),
            )
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * تنزيل صامت للـ APK إلى ملف مؤقت ثم إعادة تسميته عند الاكتمال، حتى لا يبقى
     * ملف ناقص يُحسب «جاهزاً». لا إشعار ولا تقدم: المتابعة عند المستخدم لا تتوقف
     * على هذا التنزيل أبداً. يُرفض أي مصدر غير HTTPS أو ملف يتجاوز الحد.
     */
    suspend fun downloadApk(
        url: String,
        dest: java.io.File,
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        val tmp = java.io.File(dest.absolutePath + ".part")
        try {
            val source = URL(url)
            if (source.protocol != "https") return@withContext false
            conn = source.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = DOWNLOAD_TIMEOUT
            }
            if (conn.responseCode !in 200..299) return@withContext false
            // إعادات التوجيه قد تخرج من HTTPS إلى اتصال مكشوف
            if (conn.url.protocol != "https") return@withContext false
            val total = conn.contentLengthLong
            if (total > MAX_APK_BYTES) return@withContext false
            var tooBig = false
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        done += read
                        // الطول المعلن قد يكون كذباً أو غائباً (transfer-encoding)
                        if (done > MAX_APK_BYTES) {
                            tooBig = true
                            break
                        }
                        out.write(buf, 0, read)
                    }
                }
            }
            val size = tmp.length()
            if (tooBig || size == 0L || (total > 0 && size != total)) {
                tmp.delete()
                return@withContext false
            }
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                return@withContext false
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
