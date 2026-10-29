package com.bordy.app.license

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bordy.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─── Response types ──────────────────────────────────────────────────────────

data class TosResponse(
    val tosText: String,
    val privacyText: String,
    val tosVersion: Int
)

data class PlansResponse(
    val plans: List<PlanItem>,
    val autoDiscount: DiscountInfo?,
    val paymentMethods: List<PaymentMethod>,
    val supportTelegram: String,
    val supportWhatsapp: String,
    val version: Long = 0L,
    val syncIntervalSeconds: Int = 60,
    val foregroundSyncSeconds: Int = 10
)

data class PlanItem(
    val id: String,
    val title: String,
    val days: Int,
    val price: Int
)

data class DiscountInfo(
    val code: String,
    val kind: String,
    val value: Int,
    val plans: String = "*"     // "*" or a comma-separated list of plan ids
) {
    fun appliesTo(planId: String): Boolean =
        plans == "*" || plans.split(",").map { it.trim() }.contains(planId)
}

data class PaymentMethod(
    val name: String,
    val account: String,
    val note: String
)

data class OrderStatusResponse(
    val ok: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val status: String,
    val rejectReason: String?,
    val token: String?,
    val finalPrice: Int,
    val planId: String,
    val daysGranted: Int,
    val method: String?,
    val txnRef: String?,
    val expiresAt: Long,
    val paymentMethods: List<PaymentMethod>
)

data class ApiResponse(
    val ok: Boolean,
    val status: String,
    val token: String?,
    val message: String?,
    val reason: String?,       // block reason
    val tosVersion: Int?,
    val tosText: String?,
    val privacyText: String?
)

data class SyncResponse(
    val ok: Boolean,
    val errorCode: String?,
    val status: String,
    val blockedReason: String?,
    val token: String?,
    val config: PlansResponse?,
    val orderId: String?,
    val orderStatus: String,
    val rejectReason: String?,
    val orderExpiresAt: Long
)

/**
 * org.json's optString turns a JSON null into the literal "null" string, which would be
 * mistaken for a real value (e.g. a wallet name). Always use this for nullable fields.
 */
fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().ifBlank { null }
}

/** Parse the payment-methods array defensively; a malformed entry must not break checkout. */
fun parsePaymentMethods(arr: org.json.JSONArray?): List<PaymentMethod> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val m = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = m.optNullableString("name") ?: return@mapNotNull null
        val account = m.optNullableString("account") ?: return@mapNotNull null
        PaymentMethod(name = name, account = account, note = m.optNullableString("note") ?: "")
    }
}

/** Server error message (already localized) or null. */
fun errorMessageOf(json: JSONObject?): String? =
    json?.optNullableString("error")

fun errorCodeOf(json: JSONObject?): String? =
    json?.optNullableString("code")

private const val SYNC_INTERVAL_MIN = 15
private const val SYNC_INTERVAL_MAX = 3600
private const val SYNC_INTERVAL_DEFAULT = 60

private const val FOREGROUND_SYNC_MIN = 5
private const val FOREGROUND_SYNC_MAX = 120
private const val FOREGROUND_SYNC_DEFAULT = 10

/** Shared by /v1/plans and /v1/sync — both return the same config shape. */
private fun parsePlansResponse(json: JSONObject): PlansResponse? {
    val plansArr = json.optJSONArray("plans") ?: return null
    val plans = (0 until plansArr.length()).mapNotNull { i ->
        val p = plansArr.optJSONObject(i) ?: return@mapNotNull null
        PlanItem(
            id = p.optNullableString("id") ?: return@mapNotNull null,
            title = p.optNullableString("title") ?: "",
            days = p.optInt("days", 0),
            price = p.optInt("price", 0)
        )
    }
    val adObj = json.optJSONObject("auto_discount")
    val autoDiscount = adObj?.let {
        DiscountInfo(
            code = it.optNullableString("code") ?: return@let null,
            kind = it.optNullableString("kind") ?: return@let null,
            value = it.optInt("value", 0),
            plans = it.optString("plans", "*").ifBlank { "*" }
        )
    }
    val support = json.optJSONObject("support")
    return PlansResponse(
        plans = plans,
        autoDiscount = autoDiscount,
        // Empty list means the owner has not configured wallets yet — never invent numbers.
        paymentMethods = parsePaymentMethods(json.optJSONArray("payment_methods")),
        supportTelegram = support?.optNullableString("telegram") ?: "",
        supportWhatsapp = support?.optNullableString("whatsapp") ?: "",
        version = json.optLong("config_version", 0L),
        syncIntervalSeconds = json.optInt("sync_interval_seconds", SYNC_INTERVAL_DEFAULT)
            .coerceIn(SYNC_INTERVAL_MIN, SYNC_INTERVAL_MAX),
        foregroundSyncSeconds = json.optInt("foreground_sync_seconds", FOREGROUND_SYNC_DEFAULT)
            .coerceIn(FOREGROUND_SYNC_MIN, FOREGROUND_SYNC_MAX)
    )
}

// ─── LicenseApi ──────────────────────────────────────────────────────────────

object LicenseApi {

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000
    private const val MAX_RETRIES = 3

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildUrl(path: String): String = "${BuildConfig.API_BASE_URL}$path"
    private fun buildFallbackUrl(path: String): String = "${BuildConfig.API_FALLBACK_URL}$path"

    private suspend fun postJson(path: String, body: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            val urls = listOf(buildUrl(path), buildFallbackUrl(path)).distinct()
            var lastEx: Exception? = null
            var urlIndex = 0

            for (attempt in 1..MAX_RETRIES) {
                val urlStr = if (attempt <= 2) urls[0] else urls.getOrElse(1) { urls[0] }
                try {
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.apply {
                        requestMethod = "POST"
                        connectTimeout = CONNECT_TIMEOUT
                        readTimeout = READ_TIMEOUT
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val resp = stream?.bufferedReader()?.readText() ?: return@withContext null
                    return@withContext JSONObject(resp)
                } catch (e: Exception) {
                    lastEx = e
                    if (attempt < MAX_RETRIES) {
                        val delay = when (attempt) { 1 -> 1000L; 2 -> 3000L; else -> 9000L }
                        kotlinx.coroutines.delay(delay)
                    }
                }
                urlIndex++
            }
            null
        }

    private suspend fun getJson(path: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val urls = listOf(buildUrl(path), buildFallbackUrl(path)).distinct()
            for (attempt in 1..MAX_RETRIES) {
                val urlStr = if (attempt <= 2) urls[0] else urls.getOrElse(1) { urls[0] }
                try {
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.apply {
                        requestMethod = "GET"
                        connectTimeout = CONNECT_TIMEOUT
                        readTimeout = READ_TIMEOUT
                        setRequestProperty("Accept", "application/json")
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val resp = stream?.bufferedReader()?.readText() ?: return@withContext null
                    return@withContext JSONObject(resp)
                } catch (e: Exception) {
                    if (attempt < MAX_RETRIES) {
                        val delay = when (attempt) { 1 -> 1000L; 2 -> 3000L; else -> 9000L }
                        kotlinx.coroutines.delay(delay)
                    }
                }
            }
            null
        }

    /** One attempt, short timeouts, no sleeps. The caller decides whether to try again. */
    private suspend fun getJsonOnce(path: String): JSONObject? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(buildUrl(path)).openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.readText() ?: return@withContext null
                JSONObject(resp)
            } catch (_: Exception) {
                null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

    // ── Public API ────────────────────────────────────────────────────────────
    suspend fun activate(
        did: String,
        ver: Int,
        model: String,
        sdk: Int,
        certHash: String,
        nonce: String,
        ownerCode: String? = null
    ): ApiResponse? {
        val body = JSONObject().apply {
            put("did", did)
            put("ver", ver)
            put("model", model)
            put("sdk", sdk)
            put("cert_hash", certHash)
            put("nonce", nonce)
            if (!ownerCode.isNullOrBlank()) put("owner_code", ownerCode.trim())
        }
        val json = postJson("/v1/activate", body) ?: return null
        return ApiResponse(
            ok = json.optString("status") != "BLOCKED",
            status = json.optString("status", ""),
            token = json.optNullableString("token"),
            message = null,
            reason = json.optNullableString("reason"),
            tosVersion = json.optJSONObject("tos")?.optInt("version"),
            tosText = json.optJSONObject("tos")?.optNullableString("text"),
            privacyText = json.optJSONObject("tos")?.optNullableString("privacy")
        )
    }

    suspend fun refresh(did: String, nonce: String): ApiResponse? {
        val body = JSONObject().apply {
            put("did", did)
            put("nonce", nonce)
        }
        val json = postJson("/v1/refresh", body) ?: return null
        return ApiResponse(
            ok = json.optString("status") != "BLOCKED",
            status = json.optString("status", ""),
            token = json.optNullableString("token"),
            message = null,
            reason = json.optNullableString("reason"),
            tosVersion = null,
            tosText = null,
            privacyText = null
        )
    }

    suspend fun getTos(): TosResponse? {
        val json = getJson("/v1/tos") ?: return null
        return TosResponse(
            tosText = json.optString("tos_text", ""),
            privacyText = json.optString("privacy_text", ""),
            tosVersion = json.optInt("tos_version", 1)
        )
    }

    suspend fun getPlans(): PlansResponse? {
        val json = getJson("/v1/plans") ?: return null
        return parsePlansResponse(json)
    }

    private fun syncPath(did: String, token: String?): String = buildString {
        append("/v1/sync?did=").append(did)
        if (!token.isNullOrBlank()) {
            append("&tok=").append(URLEncoder.encode(token, "UTF-8"))
        }
    }

    private fun parseSync(json: JSONObject): SyncResponse {
        val order = json.optJSONObject("order")
        return SyncResponse(
            ok = json.optBoolean("ok", false),
            errorCode = errorCodeOf(json),
            status = json.optString("status", ""),
            blockedReason = json.optNullableString("reason"),
            token = json.optNullableString("token"),
            config = if (json.has("plans")) parsePlansResponse(json) else null,
            orderId = order?.optNullableString("id"),
            orderStatus = order?.optString("status", "") ?: "",
            rejectReason = order?.optNullableString("reject_reason"),
            orderExpiresAt = order?.optLong("expires_at", 0L) ?: 0L
        )
    }

    /**
     * One round-trip that keeps the whole app in sync: license state, plans,
     * campaign, wallets, support handles and the polling cadence set by the owner.
     */
    suspend fun sync(did: String, token: String?): SyncResponse? {
        val json = getJson(syncPath(did, token)) ?: return null
        return parseSync(json)
    }

    /**
     * Same round-trip with a single bounded attempt: no internal retries and no multi-second
     * sleeps. Used by the background worker, where WorkManager owns the retry policy.
     */
    suspend fun syncQuiet(did: String, token: String?): SyncResponse? {
        val json = getJsonOnce(syncPath(did, token)) ?: return null
        return parseSync(json)
    }

    suspend fun createOrder(
        did: String,
        planId: String,
        code: String?,
        payerName: String,
        payerPhone: String
    ): JSONObject? {
        val body = JSONObject().apply {
            put("did", did)
            put("plan_id", planId)
            if (!code.isNullOrEmpty()) put("code", code)
            put("payer_name", payerName)
            put("payer_phone", payerPhone)
        }
        return postJson("/v1/orders", body)
    }

    suspend fun submitProof(
        did: String,
        orderId: String,
        method: String,
        txnRef: String,
        imageBase64: String? = null,
        payerName: String? = null,
        payerPhone: String? = null
    ): JSONObject? {
        val body = JSONObject().apply {
            put("did", did)
            put("method", method)
            put("txn_ref", txnRef)
            // Send as 'proof_data' (the canonical backend field name)
            if (!imageBase64.isNullOrEmpty()) put("proof_data", imageBase64)
            if (!payerName.isNullOrBlank()) put("payer_name", payerName.trim())
            if (!payerPhone.isNullOrBlank()) put("payer_phone", payerPhone.trim())
        }
        return postJson("/v1/orders/$orderId/proof", body)
    }

    suspend fun checkOrder(did: String, orderId: String): OrderStatusResponse? {
        val json = getJson("/v1/orders/$orderId?did=$did") ?: return null
        val orderObj = json.optJSONObject("order")
        return OrderStatusResponse(
            ok = json.optBoolean("ok", false) && orderObj != null,
            errorCode = errorCodeOf(json),
            errorMessage = errorMessageOf(json),
            status = orderObj?.optString("status", "") ?: "",
            rejectReason = orderObj?.optNullableString("reject_reason"),
            token = json.optNullableString("token") ?: orderObj?.optNullableString("token"),
            finalPrice = orderObj?.optInt("final_price", 0) ?: 0,
            planId = orderObj?.optString("plan_id", "") ?: "",
            daysGranted = orderObj?.optInt("days_granted", 0) ?: 0,
            method = orderObj?.optNullableString("method"),
            txnRef = orderObj?.optNullableString("txn_ref"),
            expiresAt = orderObj?.optLong("expires_at", 0L) ?: 0L,
            paymentMethods = parsePaymentMethods(json.optJSONArray("payment_methods"))
        )
    }

    suspend fun cancelOrder(did: String, orderId: String): Boolean {
        val body = JSONObject().put("did", did)
        val json = postJson("/v1/orders/$orderId/cancel", body)
        return json?.optBoolean("ok", false) ?: false
    }

    suspend fun checkCode(did: String, code: String, planId: String? = null): JSONObject? {
        val body = JSONObject().apply {
            put("did", did)
            put("code", code)
            if (!planId.isNullOrBlank()) put("plan_id", planId)
        }
        return postJson("/v1/codes/check", body)
    }
}
