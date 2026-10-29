package com.bordy.app.sync

import com.bordy.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ApiResult(val code: Int, val json: JSONObject?, val notModified: Boolean = false, val network: Boolean = false) {
    val ok: Boolean get() = code in 200..299
    fun errorCode(): String? = json?.optString("code")?.ifBlank { null }
    fun errorMessage(): String? = json?.optString("error")?.ifBlank { null }
}

/**
 * عميل نقاط النهاية v2. لا يُخفى رمز الحالة: المحرّك يقرّر بحسب 401/409/426 لا بحسب رسالة نصية.
 */
class SyncApi {

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        ifNoneMatch: String? = null,
        timeoutMs: Int = 20_000,
        retries: Int = 3
    ): ApiResult = withContext(Dispatchers.IO) {
        var lastNetworkError: Exception? = null
        repeat(maxOf(1, retries)) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Accept", "application/json")
                token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                ifNoneMatch?.let { conn.setRequestProperty("If-None-Match", it) }
                if (body != null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                }
                val code = conn.responseCode
                if (code == 304) return@withContext ApiResult(304, null, notModified = true)
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText().orEmpty()
                val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
                return@withContext ApiResult(code, json)
            } catch (e: Exception) {
                lastNetworkError = e
                if (attempt < retries - 1) delay(if (attempt == 0) 1_200L else 4_000L)
            } finally {
                conn?.disconnect()
            }
        }
        ApiResult(0, null, network = true).also { _ -> lastNetworkError }
    }

    suspend fun claim(
        username: String, password: String, deviceId: String, installId: String,
        licenseToken: String, deviceName: String, appVersion: Int
    ) = request(
        "POST", "/v2/accounts/claim-legacy",
        JSONObject()
            .put("username", username).put("password", password)
            .put("device_id", deviceId).put("install_id", installId)
            .put("license_token", licenseToken)
            .put("device_name", deviceName).put("app_version", appVersion)
    )

    suspend fun login(
        username: String, password: String, deviceId: String, installId: String,
        deviceName: String, appVersion: Int
    ) = request(
        "POST", "/v2/auth/login",
        JSONObject()
            .put("username", username).put("password", password)
            .put("device_id", deviceId).put("install_id", installId)
            .put("device_name", deviceName).put("app_version", appVersion)
    )

    // رمز التجديد يدور لدى الخادم: إعادة المحاولة بعد ردّ ضائع تُفسَّر كاستخدام مكرر
    // فتُبطَل عائلة الجلسة كلها. لذلك لا نعيد المحاولة أبداً على هذا الطلب.
    suspend fun refresh(refreshToken: String) = request(
        "POST", "/v2/auth/refresh", JSONObject().put("refresh_token", refreshToken), retries = 1
    )

    suspend fun logout(token: String, refreshToken: String) = request(
        "POST", "/v2/auth/logout", JSONObject().put("refresh_token", refreshToken), token, retries = 1
    )

    suspend fun head(token: String, etag: String?) =
        request("GET", "/v2/sync/head", null, token, etag)

    suspend fun pull(token: String, cursor: Long, limit: Int = 50) =
        request("GET", "/v2/sync/pull?cursor=$cursor&limit=$limit", null, token)

    suspend fun push(token: String, body: JSONObject) =
        request("POST", "/v2/sync/push", body, token, timeoutMs = 30_000)

    suspend fun snapshot(token: String, afterType: String, afterId: String, limit: Int = 100) =
        request("GET", "/v2/sync/snapshot?after_type=$afterType&after_id=$afterId&limit=$limit", null, token, timeoutMs = 30_000)

    suspend fun currentTransfer(token: String) = request("GET", "/v2/transfers/current", null, token)

    suspend fun sourceFrozen(token: String, transferId: String, cursor: Long) =
        request("POST", "/v2/transfers/$transferId/source-frozen", JSONObject().put("cursor", cursor), token)

    suspend fun targetConfirmed(token: String, transferId: String, cursor: Long) =
        request("POST", "/v2/transfers/$transferId/target-confirmed", JSONObject().put("cursor", cursor), token, timeoutMs = 30_000)

    suspend fun sourceWiped(
        transferId: String, username: String, password: String, deviceId: String, installId: String
    ) = request(
        "POST", "/v2/transfers/$transferId/source-wiped",
        JSONObject()
            .put("username", username).put("password", password)
            .put("device_id", deviceId).put("install_id", installId)
            .put("local_data_wiped", true)
    )
}
