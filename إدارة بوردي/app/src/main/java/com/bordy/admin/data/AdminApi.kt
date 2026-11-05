package com.bordy.admin.data

import android.content.Context
import com.bordy.admin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─── Errors ──────────────────────────────────────────────────────────────────

class ApiException(message: String, val statusCode: Int = 0) : Exception(message)

/** The panel answered with the login page: the session is gone or expired. */
class SessionExpiredException : Exception("انتهت الجلسة، سجّل الدخول من جديد")

// ─── Session storage ─────────────────────────────────────────────────────────

object SessionStore {
    private const val PREFS = "bordy_admin_session"
    private const val KEY_COOKIE = "session_cookie"
    private const val KEY_CSRF = "csrf_token"

    fun cookie(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COOKIE, null)

    fun saveCookie(context: Context, cookie: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIE, cookie).apply()
    }

    fun csrf(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CSRF, null)

    fun saveCsrf(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CSRF, token).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun hasSession(context: Context): Boolean = !cookie(context).isNullOrBlank()
}

// ─── Models (mirror the admin API shapes) ────────────────────────────────────

data class Device(
    val id: String,
    val name: String?,
    val phone: String?,
    val notes: String?,
    val model: String?,
    val appVer: Int?,
    val firstSeen: Long,
    val lastSeen: Long?,
    val trialEndsAt: Long?,
    val paidUntil: Long,
    val lifetime: Boolean,
    val blocked: Boolean,
    val blockReason: String?,
    val transfersUsed: Int
)

data class AdminOrder(
    val id: String,
    val deviceId: String,
    val planId: String,
    val finalPrice: Int,
    val status: String,
    val method: String?,
    val txnRef: String?,
    val payerName: String?,
    val payerPhone: String?,
    val createdAt: Long,
    val hasProof: Boolean,
    val rejectReason: String?
)

data class DiscountCode(
    val code: String,
    val kind: String,
    val value: Int,
    val plans: String,
    val usedCount: Int,
    val reservedCount: Int,
    val maxUses: Int?,
    val perDeviceLimit: Int,
    val startsAt: Long,
    val endsAt: Long,
    val autoApply: Boolean,
    val active: Boolean
)

data class AdminEvent(
    val id: Long,
    val ts: Long,
    val deviceId: String?,
    val type: String,
    val detail: String?,
    val actor: String?
)

data class AdminSettings(
    val trialSeconds: Int,
    val syncIntervalSeconds: Int,
    val foregroundSyncSeconds: Int,
    val paidTokenTtlDays: Int,
    val graceDays: Int,
    val minAppVersion: String,
    val maintenance: Boolean,
    val tosVersion: Int,
    val supportTelegram: String,
    val supportWhatsapp: String,
    val botNotificationsEnabled: Boolean,
    val expiryNoticeEnabled: Boolean,
    val expiryNoticeUnit: String,
    val expiryNoticeValue: Int,
    val tosText: String,
    val privacyText: String,
    val paymentMethods: String
)

data class AdminStats(
    val activeSubs: Int,
    val trials: Int,
    val expired: Int,
    val expiringIn7: Int,
    val monthlyRevenue: Int,
    val pendingOrders: Int
)

data class CloudAccount(
    val id: String,
    val username: String,
    val status: String,
    val licenseStatus: String?,
    val trialEndsAt: Long,
    val paidUntil: Long,
    val lifetime: Boolean,
    val deviceName: String?,
    val deviceStatus: String?,
    val lastSeenAt: Long?,
    val recordCount: Int,
    val conflictCount: Int,
    val transferCount: Int,
    val createdAt: Long
)

data class AccountTransfer(
    val id: String,
    val status: String,
    val requestedAt: Long,
    val expiresAt: Long?,
    val sourceDeviceId: String?,
    val targetDeviceId: String?
)

data class AccountRecord(
    val entityType: String,
    val entityId: String,
    val revision: Int,
    val schemaVersion: Int,
    val payload: String,
    val updatedAt: Long
)

// ─── API client ──────────────────────────────────────────────────────────────

object AdminApi {

    private const val BASE = BuildConfig.API_BASE_URL
    private const val CONNECT_TIMEOUT = 12_000
    private const val READ_TIMEOUT = 20_000

    private data class RawResponse(
        val code: Int,
        val body: String,
        val contentType: String
    )

    private fun open(context: Context, path: String, method: String): HttpURLConnection {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        SessionStore.cookie(context)?.let { conn.setRequestProperty("Cookie", "session=$it") }
        return conn
    }

    private suspend fun call(
        context: Context,
        path: String,
        method: String = "GET",
        jsonBody: JSONObject? = null,
        retryOn403: Boolean = true
    ): RawResponse = withContext(Dispatchers.IO) {
        val conn = open(context, path, method)
        try {
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (method != "GET") {
                    val csrf = SessionStore.csrf(context)
                        ?: fetchCsrfBlocking(context)
                    if (csrf.isNotBlank()) conn.setRequestProperty("X-CSRF-Token", csrf)
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody.toString()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val type = conn.contentType ?: ""
            if (code == 403 && retryOn403) {
                // CSRF rotates with the session; refresh once and retry the same write.
                SessionStore.saveCsrf(context, fetchCsrfBlocking(context))
                try { conn.disconnect() } catch (_: Exception) {}
                return@withContext call(context, path, method, jsonBody, retryOn403 = false)
            }
            RawResponse(code, body, type)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Blocking helper used while building a request (already on Dispatchers.IO). */
    private fun fetchCsrfBlocking(context: Context): String {
        val conn = open(context, "/admin/api/csrf", "GET")
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw SessionExpiredException()
            val token = JSONObject(body).optString("csrf", "")
            SessionStore.saveCsrf(context, token)
            token
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) {
            ""
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun ensureJson(resp: RawResponse): JSONObject {
        if (resp.contentType.contains("text/html", ignoreCase = true) ||
            resp.body.trimStart().startsWith("<")
        ) {
            throw SessionExpiredException()
        }
        if (resp.code == 401) throw SessionExpiredException()
        val json = try {
            JSONObject(resp.body)
        } catch (_: Exception) {
            throw ApiException("رد غير صالح من السيرفر (${resp.code})", resp.code)
        }
        if (json.optBoolean("ok", true) == false && resp.code !in 200..299) {
            throw ApiException(json.optString("error", "تعذر التنفيذ"), resp.code)
        }
        return json
    }

    private fun errorText(json: JSONObject, fallback: String): String =
        json.optString("error", "").ifBlank { fallback }

    // ── Auth ─────────────────────────────────────────────────────────────────

    suspend fun login(context: Context, secret: String, totp: String) {
        withContext(Dispatchers.IO) {
            val conn = URL(BASE + "/admin/login").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.instanceFollowRedirects = false
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                val form = "secret=" + URLEncoder.encode(secret, "UTF-8") +
                    "&totp=" + URLEncoder.encode(totp, "UTF-8")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(form) }
                val code = conn.responseCode
                if (code !in 300..399) {
                    throw ApiException("كلمة السر أو رمز التحقق غير صحيح")
                }
                val setCookie = conn.getHeaderField("Set-Cookie") ?: ""
                val session = Regex("session=([A-Za-z0-9_.\\-]+)").find(setCookie)?.groupValues?.get(1)
                    ?: throw ApiException("تعذر إنشاء الجلسة")
                SessionStore.saveCookie(context, session)
                SessionStore.saveCsrf(context, "")
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }
        // Prove the new session actually works before leaving the login screen.
        settings(context, useCache = false)
    }

    fun logout(context: Context) {
        SessionStore.clear(context)
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    suspend fun devices(context: Context, query: String = "", status: String = ""): List<Device> {
        val qs = buildString {
            append("/admin/api/devices?")
            append("q=").append(URLEncoder.encode(query, "UTF-8"))
            if (status.isNotBlank()) append("&status=").append(URLEncoder.encode(status, "UTF-8"))
        }
        val json = ensureJson(call(context, qs))
        return json.optJSONArray("devices").mapDevices { o ->
            Device(
                id = o.optString("device_id"),
                name = o.nullableString("name"),
                phone = o.nullableString("phone"),
                notes = o.nullableString("notes"),
                model = o.nullableString("model"),
                appVer = o.optNullableInt("app_ver"),
                firstSeen = o.optLong("first_seen", 0L),
                lastSeen = o.optNullableLong("last_seen"),
                trialEndsAt = o.optNullableLong("trial_ends_at"),
                paidUntil = o.optLong("paid_until", 0L),
                lifetime = o.optInt("lifetime", 0) == 1,
                blocked = o.optInt("blocked", 0) == 1,
                blockReason = o.nullableString("block_reason"),
                transfersUsed = o.optInt("transfers_used", 0)
            )
        }
    }

    suspend fun orders(context: Context): List<AdminOrder> {
        val json = ensureJson(call(context, "/admin/api/orders"))
        return json.optJSONArray("orders").mapOrders { o ->
            AdminOrder(
                id = o.optString("id"),
                deviceId = o.optString("device_id"),
                planId = o.optString("plan_id"),
                finalPrice = o.optInt("final_price", 0),
                status = o.optString("status", ""),
                method = o.nullableString("method"),
                txnRef = o.nullableString("txn_ref"),
                payerName = o.nullableString("payer_name"),
                payerPhone = o.nullableString("payer_phone"),
                createdAt = o.optLong("created_at", 0L),
                hasProof = o.optInt("has_proof", 0) > 0,
                rejectReason = o.nullableString("reject_reason")
            )
        }
    }

    suspend fun codes(context: Context): List<DiscountCode> {
        val json = ensureJson(call(context, "/admin/api/codes"))
        return json.optJSONArray("codes").mapCodes { o ->
            DiscountCode(
                code = o.optString("code"),
                kind = o.optString("kind", ""),
                value = o.optInt("value", 0),
                plans = o.optString("plans", "*"),
                usedCount = o.optInt("used_count", 0),
                reservedCount = o.optInt("reserved_count", 0),
                maxUses = o.optNullableInt("max_uses"),
                perDeviceLimit = o.optInt("per_device_limit", 1),
                startsAt = o.optLong("starts_at", 0L),
                endsAt = o.optLong("ends_at", 0L),
                autoApply = o.optInt("auto_apply", 0) == 1,
                active = o.optInt("active", 0) == 1
            )
        }
    }

    suspend fun events(context: Context, query: String = ""): List<AdminEvent> {
        val path = if (query.isBlank()) "/admin/api/events"
        else "/admin/api/events?q=" + URLEncoder.encode(query, "UTF-8")
        val json = ensureJson(call(context, path))
        return json.optJSONArray("events").mapEvents { o ->
            AdminEvent(
                id = o.optLong("id", 0L),
                ts = o.optLong("ts", 0L),
                deviceId = o.nullableString("device_id"),
                type = o.optString("type", ""),
                detail = o.nullableString("detail"),
                actor = o.nullableString("actor")
            )
        }
    }

    suspend fun settings(context: Context, useCache: Boolean = true): AdminSettings {
        val json = ensureJson(call(context, "/admin/api/settings"))
        val s = json.optJSONObject("settings") ?: JSONObject()
        val e = json.optJSONObject("effective") ?: JSONObject()
        return AdminSettings(
            trialSeconds = e.optInt("trial_seconds", 604800),
            syncIntervalSeconds = e.optInt("sync_interval_seconds", 60),
            foregroundSyncSeconds = e.optInt("foreground_sync_seconds", 10),
            paidTokenTtlDays = e.optInt("paid_token_ttl_days", 7),
            graceDays = e.optInt("grace_days", 2),
            minAppVersion = e.optString("min_app_version", "1"),
            maintenance = e.optBoolean("maintenance", false),
            tosVersion = s.optInt("tos_version", 1),
            supportTelegram = s.optString("support_telegram", ""),
            supportWhatsapp = s.optString("support_whatsapp", ""),
            botNotificationsEnabled = e.optBoolean("bot_notifications_enabled", true),
            expiryNoticeEnabled = e.optBoolean("expiry_notice_enabled", true),
            expiryNoticeUnit = e.optString("expiry_notice_unit", "days"),
            expiryNoticeValue = e.optInt("expiry_notice_value", 3),
            tosText = s.optString("tos_text", ""),
            privacyText = s.optString("privacy_text", ""),
            paymentMethods = s.optString("payment_methods", "[]")
        )
    }

    suspend fun stats(context: Context): AdminStats {
        val json = ensureJson(call(context, "/admin/api/stats"))
        val s = json.optJSONObject("stats") ?: JSONObject()
        return AdminStats(
            activeSubs = s.optInt("active_subs", 0),
            trials = s.optInt("trials", 0),
            expired = s.optInt("expired", 0),
            expiringIn7 = s.optInt("expiring_in_7", 0),
            monthlyRevenue = s.optInt("monthly_revenue", 0),
            pendingOrders = s.optInt("pending_orders", 0)
        )
    }

    suspend fun accounts(context: Context, query: String = "", status: String = ""): List<CloudAccount> {
        val qs = buildString {
            append("/admin/api/accounts?")
            append("q=").append(URLEncoder.encode(query, "UTF-8"))
            if (status.isNotBlank()) append("&status=").append(URLEncoder.encode(status, "UTF-8"))
        }
        val json = ensureJson(call(context, qs))
        val arr = json.optJSONArray("accounts") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                CloudAccount(
                    id = o.optString("id"),
                    username = o.optString("username"),
                    status = o.optString("status", ""),
                    licenseStatus = o.nullableString("license_status"),
                    trialEndsAt = o.optLong("trial_ends_at", 0L),
                    paidUntil = o.optLong("paid_until", 0L),
                    lifetime = o.optInt("lifetime", 0) == 1,
                    deviceName = o.nullableString("device_name"),
                    deviceStatus = o.nullableString("device_status"),
                    lastSeenAt = o.optNullableLong("last_seen_at"),
                    recordCount = o.optInt("record_count", 0),
                    conflictCount = o.optInt("conflict_count", 0),
                    transferCount = o.optInt("transfer_count", 0),
                    createdAt = o.optLong("created_at", 0L)
                )
            }
        }
    }

    suspend fun createAccount(context: Context, username: String, password: String, trialDays: Int, lifetime: Boolean) {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("trial_days", trialDays)
            put("lifetime", lifetime)
        }
        val json = ensureJson(call(context, "/admin/api/accounts", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر إنشاء الحساب"))
    }

    suspend fun setAccountState(context: Context, accountId: String, status: String) {
        val json = ensureJson(
            call(context, "/admin/api/accounts/$accountId/state", "POST", JSONObject().put("status", status))
        )
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر تغيير حالة الحساب"))
    }

    suspend fun setAccountLicense(
        context: Context,
        accountId: String,
        status: String,
        trialDays: Int = 0,
        paidDays: Int = 0
    ) {
        val body = JSONObject().apply {
            put("status", status)
            if (status == "trial") put("trial_days", trialDays)
            if (status == "paid") put("paid_days", paidDays)
        }
        val json = ensureJson(call(context, "/admin/api/accounts/$accountId/license", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر تحديث الترخيص"))
    }

    suspend fun accountTransfers(context: Context, accountId: String): List<AccountTransfer> {
        val json = ensureJson(call(context, "/admin/api/accounts/$accountId"))
        val arr = json.optJSONArray("transfers") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                AccountTransfer(
                    id = o.optString("id"),
                    status = o.optString("status", ""),
                    requestedAt = o.optLong("requested_at", 0L),
                    expiresAt = o.optNullableLong("expires_at"),
                    sourceDeviceId = o.nullableString("source_device_id"),
                    targetDeviceId = o.nullableString("target_device_id")
                )
            }
        }
    }

    suspend fun reviewTransfer(context: Context, accountId: String, transferId: String, approve: Boolean) {
        val body = JSONObject().put("action", if (approve) "approve" else "reject")
        val json = ensureJson(
            call(context, "/admin/api/accounts/$accountId/transfers/$transferId", "POST", body)
        )
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر تحديث طلب النقل"))
    }

    suspend fun accountRecords(context: Context, accountId: String, entityType: String = ""): List<AccountRecord> {
        val path = "/admin/api/accounts/$accountId/records" +
            if (entityType.isBlank()) "" else "?entity_type=" + URLEncoder.encode(entityType, "UTF-8")
        val json = ensureJson(call(context, path))
        val arr = json.optJSONArray("records") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                AccountRecord(
                    entityType = o.optString("entity_type"),
                    entityId = o.optString("entity_id"),
                    revision = o.optInt("revision", 0),
                    schemaVersion = o.optInt("schema_version", 1),
                    payload = o.optJSONObject("payload")?.toString(2) ?: "{}",
                    updatedAt = o.optLong("updated_at", 0L)
                )
            }
        }
    }

    suspend fun mutateAccountRecord(
        context: Context,
        accountId: String,
        record: AccountRecord,
        payload: String?
    ) {
        val mutation = JSONObject().apply {
            put("entity_type", record.entityType)
            put("entity_id", record.entityId)
            put("action", if (payload == null) "delete" else "upsert")
            put("base_revision", record.revision)
            put("schema_version", record.schemaVersion)
            put("merge_policy", "strict")
            if (payload != null) put("payload", JSONObject(payload))
        }
        val body = JSONObject().put("mutations", JSONArray().put(mutation))
        val json = ensureJson(call(context, "/admin/api/accounts/$accountId/records", "POST", body))
        if (!json.optBoolean("ok", false)) {
            val result = json.optJSONObject("result")
            throw ApiException(
                errorText(
                    json,
                    result?.optString("reason")?.ifBlank { result.optString("error") } ?: "تعذر تعديل السجل"
                )
            )
        }
    }

    suspend fun exportAccount(context: Context, accountId: String): String {
        val json = ensureJson(call(context, "/admin/api/accounts/$accountId/export"))
        return json.toString(2)
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    suspend fun updateSettings(context: Context, body: JSONObject) {
        val json = ensureJson(call(context, "/admin/api/settings", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر الحفظ"))
    }

    suspend fun deviceAction(context: Context, deviceId: String, body: JSONObject) {
        val json = ensureJson(call(context, "/admin/api/device/$deviceId", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر تنفيذ الإجراء"))
    }

    suspend fun updateDevice(
        context: Context,
        device: Device,
        name: String?,
        phone: String?,
        notes: String?,
        paidUntil: Long,
        trialEndsAt: Long,
        lifetime: Boolean
    ) = deviceAction(
        context,
        device.id,
        JSONObject().apply {
            put("action", "update")
            put("name", name ?: "")
            put("phone", phone ?: "")
            put("notes", notes ?: "")
            // 0 clears the period explicitly; the panel only keeps the old date when the field is omitted.
            put("paid_until", paidUntil)
            put("trial_ends_at", trialEndsAt)
            put("lifetime", lifetime)
            put("blocked", device.blocked)
            put("block_reason", device.blockReason ?: "")
        }
    )

    suspend fun zeroPaid(context: Context, deviceId: String) = deviceAction(
        context, deviceId,
        JSONObject().put("action", "zero_paid")
    )

    suspend fun zeroTrial(context: Context, deviceId: String) = deviceAction(
        context, deviceId,
        JSONObject().put("action", "zero_trial")
    )

    suspend fun blockDevice(context: Context, deviceId: String, reason: String) = deviceAction(
        context, deviceId,
        JSONObject().apply { put("action", "block"); put("reason", reason) }
    )

    suspend fun unblockDevice(context: Context, deviceId: String) = deviceAction(
        context, deviceId,
        JSONObject().put("action", "unblock")
    )

    suspend fun extendDevice(context: Context, deviceId: String, days: Int) = deviceAction(
        context, deviceId,
        JSONObject().apply { put("action", "extend"); put("days", days) }
    )

    suspend fun setLifetime(context: Context, deviceId: String, on: Boolean) = deviceAction(
        context, deviceId,
        JSONObject().apply { put("action", "set_lifetime"); put("value", on) }
    )

    suspend fun resetTrial(context: Context, deviceId: String, seconds: Int) = deviceAction(
        context, deviceId,
        JSONObject().apply { put("action", "reset_trial"); put("seconds", seconds) }
    )

    suspend fun deleteDevice(context: Context, deviceId: String) = deviceAction(
        context, deviceId,
        JSONObject().apply { put("action", "delete"); put("confirm", "DELETE") }
    )

    suspend fun approveOrder(context: Context, orderId: String) {
        val json = ensureJson(call(context, "/admin/api/order/$orderId/approve", "POST", JSONObject()))
        if (!json.optBoolean("ok", false)) throw ApiException("تعذر اعتماد الطلب")
    }

    suspend fun rejectOrder(context: Context, orderId: String, reason: String) {
        val body = JSONObject().put("reason", reason.ifBlank { "تم الرفض من تطبيق الإدارة" })
        val json = ensureJson(call(context, "/admin/api/order/$orderId/reject", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException("تعذر رفض الطلب")
    }

    suspend fun deleteOrder(context: Context, orderId: String) {
        val body = JSONObject().put("confirm", "DELETE")
        val json = ensureJson(call(context, "/admin/api/order/$orderId/delete", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException("تعذر حذف الطلب")
    }

    suspend fun createCode(
        context: Context,
        code: String,
        kind: String,
        value: Int,
        days: Int,
        maxUses: Int?,
        perDevice: Int,
        plans: String,
        autoApply: Boolean
    ) {
        val body = JSONObject().apply {
            put("code", code)
            put("kind", kind)
            put("value", value)
            put("days", days)
            if (maxUses != null) put("max_uses", maxUses)
            put("per_device", perDevice)
            put("plans", plans.ifBlank { "*" })
            put("auto_apply", autoApply)
        }
        val json = ensureJson(call(context, "/admin/api/codes", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر إنشاء الكود"))
    }

    suspend fun toggleCode(context: Context, code: String) {
        val json = ensureJson(
            call(context, "/admin/api/codes/$code", "POST", JSONObject().put("action", "toggle"))
        )
        if (!json.optBoolean("ok", false)) throw ApiException("تعذر تعديل الكود")
    }

    suspend fun deleteCode(context: Context, code: String) {
        val body = JSONObject().apply { put("action", "delete"); put("confirm", "DELETE") }
        val json = ensureJson(call(context, "/admin/api/codes/$code", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException("تعذر حذف الكود")
    }

    // ── Events / maintenance ─────────────────────────────────────────────────

    suspend fun purgeEvents(context: Context, olderThanDays: Int): Int {
        val body = JSONObject().put("older_than_days", olderThanDays)
        val json = ensureJson(call(context, "/admin/api/events/delete", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر حذف السجلات"))
        return json.optInt("deleted", 0)
    }

    suspend fun wipeEvents(context: Context): Int {
        val body = JSONObject().apply { put("all", true); put("confirm", "DELETE") }
        val json = ensureJson(call(context, "/admin/api/events/delete", "POST", body))
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر مسح السجل"))
        return json.optInt("deleted", 0)
    }

    suspend fun resetRateLimits(context: Context): Int {
        val json = ensureJson(call(context, "/admin/api/rate-limits/reset", "POST", JSONObject()))
        if (!json.optBoolean("ok", false)) throw ApiException(errorText(json, "تعذر تصفير الحدود"))
        return json.optInt("deleted", 0)
    }

    suspend fun exportCsv(context: Context): String {
        val json = ensureJson(call(context, "/admin/api/export"))
        return json.optString("csv", "")
    }

    suspend fun orderProof(context: Context, orderId: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(context, "/admin/api/order/$orderId/proof", "GET")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ApiException(
                    if (code == 404) "لا توجد صورة إثبات لهذا الطلب" else "تعذر تحميل صورة الإثبات ($code)",
                    code
                )
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}

// ─── JSON helpers ────────────────────────────────────────────────────────────

private fun JSONObject.nullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().ifBlank { null }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name, 0L)
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name, 0)
}

private fun JSONArray?.mapDevices(transform: (JSONObject) -> Device): List<Device> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
}

private fun JSONArray?.mapOrders(transform: (JSONObject) -> AdminOrder): List<AdminOrder> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
}

private fun JSONArray?.mapCodes(transform: (JSONObject) -> DiscountCode): List<DiscountCode> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
}

private fun JSONArray?.mapEvents(transform: (JSONObject) -> AdminEvent): List<AdminEvent> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
}
