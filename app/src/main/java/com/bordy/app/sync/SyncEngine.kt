package com.bordy.app.sync

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import com.bordy.app.BuildConfig
import com.bordy.app.data.AppDatabase
import com.bordy.app.data.SyncConflictEntity
import com.bordy.app.data.SyncDirty
import com.bordy.app.data.SyncShadow
import com.bordy.app.data.SyncState
import com.bordy.app.data.SyncCodec
import com.bordy.app.data.newSyncId
import com.bordy.app.license.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject

data class SyncUiState(
    val loggedIn: Boolean = false,
    val username: String = "",
    val deviceStatus: String = "",
    val lastSyncAt: Long = 0,
    val pendingChanges: Int = 0,
    val openConflicts: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    val mode: String = "login",
    val pendingTransferId: String? = null
)

sealed class SyncOutcome {
    object Ok : SyncOutcome()
    object Busy : SyncOutcome()
    object NotLoggedIn : SyncOutcome()
    object Offline : SyncOutcome()
    object NeedsLogin : SyncOutcome()
    object AppUpdateRequired : SyncOutcome()
    object DeviceNotActive : SyncOutcome()
    object WipeRequired : SyncOutcome()
    data class Suspended(val reason: String) : SyncOutcome()
    data class Failed(val code: String?, val message: String?) : SyncOutcome()
}

sealed class LoginOutcome {
    object Success : LoginOutcome()
    object RestoreNeeded : LoginOutcome()
    object TransferPending : LoginOutcome()
    data class WipeRequired(val transferId: String?) : LoginOutcome()
    object AlreadyClaimed : LoginOutcome()
    object LicenseNotEligible : LoginOutcome()
    object UsernameTaken : LoginOutcome()
    object InvalidCredentials : LoginOutcome()
    object RateLimited : LoginOutcome()
    object Offline : LoginOutcome()
    data class Failed(val code: String?, val message: String?) : LoginOutcome()
}

/**
 * محرّك المزامنة: السحابة مرجع، والجهاز يدفع تغييراته كدمج آمن.
 * كل دفعة تُطبَّق وتُختم في معاملة واحدة، فلا يتقدّم المؤشر على بيانات لم تُكتب.
 */
object SyncEngine {

    private const val MAX_MUTATIONS_PER_PUSH = 50
    private const val SCHEMA = SyncCodec.SCHEMA_VERSION

    private lateinit var app: Context
    private lateinit var db: AppDatabase
    private lateinit var installId: String
    private var api = SyncApi()

    private val syncMutex = Mutex()
    private val authMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    @Volatile private var session: AccountSession? = null
    @Volatile private var headEtag: String? = null
    @Volatile private var blockedBySchema = false

    fun init(context: Context) {
        app = context.applicationContext
        db = AppDatabase.get(app)
        installId = AccountStore.installId(app)
        session = AccountStore.load(app)
        scope.launch {
            session = AccountStore.load(app)
            publishState()
            val dao = db.syncDao()
            launch {
                dao.pendingCount().collectLatest { count ->
                    _state.value = _state.value.copy(pendingChanges = count)
                }
            }
            launch {
                dao.openConflictCount().collectLatest { count ->
                    _state.value = _state.value.copy(openConflicts = count)
                }
            }
            // أي كتابة محلية تُدفع تلقائياً بعد هدوء قصير، دون أن تعرف الشاشات عن المزامنة شيئاً
            launch {
                dao.dirtyCount().collectLatest { count ->
                    if (count > 0 && isLoggedIn()) {
                        delay(3_000)
                        syncNow()
                    }
                }
            }
        }
    }

    fun currentSession(): AccountSession? = session ?: AccountStore.load(app)
    fun isLoggedIn(): Boolean = currentSession()?.isLoggedIn == true

    fun onForeground() {
        if (!isLoggedIn()) return
        scope.launch { syncNow() }
    }

    private suspend fun publishState(message: String? = null) {
        val s = currentSession()
        val dao = db.syncDao()
        val stateRow = dao.stateOnce()
        _state.value = SyncUiState(
            loggedIn = s?.isLoggedIn == true,
            username = s?.username.orEmpty(),
            deviceStatus = s?.deviceStatus.orEmpty(),
            lastSyncAt = stateRow?.lastSyncAt ?: 0L,
            pendingChanges = dao.dirtyCountOnce(),
            openConflicts = dao.openConflictCountOnce(),
            busy = _state.value.busy,
            message = message,
            mode = modeFor(s),
            pendingTransferId = s?.pendingTransferId
        )
    }

    private fun modeFor(s: AccountSession?): String = when {
        s == null -> "login"
        s.deviceStatus == "restoring" -> "restoring"
        s.deviceStatus == "wipe_pending" -> "wipe_pending"
        s.deviceStatus == "frozen" -> "frozen"
        s.pendingTransferId != null && !s.isLoggedIn -> "pending_transfer"
        s.isLoggedIn -> "ready"
        else -> "login"
    }

    // ------------------------------------------------------------------ الجلسة

    private suspend fun saveSession(updated: AccountSession) {
        session = updated
        AccountStore.save(app, updated)
    }

    private suspend fun refreshSession(force: Boolean): String? = authMutex.withLock {
        val current = session ?: AccountStore.load(app) ?: return@withLock null
        if (!current.isLoggedIn) return@withLock null
        val now = System.currentTimeMillis()
        if (!force && current.accessToken.isNotBlank() && current.accessExpiresAt - now > 60_000) {
            return@withLock current.accessToken
        }
        val res = api.refresh(current.refreshToken)
        if (res.network) return@withLock null
        if (!res.ok) {
            val code = res.errorCode()
            if (res.code == 401) {
                // قد يكون رمز التجديد قديماً فقط بعد ردّ ضائع؛ نحاول دخولاً صامتاً
                // بكلمة المرور المحفوظة قبل إجبار المستخدم على كتابتها مرة أخرى.
                val outcome = if (current.username.isNotBlank() && current.password.isNotBlank()) {
                    login(current.username, current.password)
                } else null
                val fresh = session
                if (fresh != null && fresh.isLoggedIn) return@withLock fresh.accessToken
                if (outcome is LoginOutcome.TransferPending || outcome is LoginOutcome.WipeRequired) return@withLock null
                saveSession(current.copy(accessToken = "", refreshToken = "", suspendedReason = messageFor(code)))
            } else if (res.code == 403) {
                saveSession(current.copy(accessToken = "", deviceStatus = if (code == "WIPE_REQUIRED") "wipe_pending" else current.deviceStatus, suspendedReason = messageFor(code)))
            }
            return@withLock null
        }
        val json = res.json ?: return@withLock null
        val updated = current.copy(
            accessToken = json.optString("access_token"),
            refreshToken = json.optString("refresh_token"),
            accessExpiresAt = json.optLong("access_expires_at"),
            refreshExpiresAt = json.optLong("refresh_expires_at"),
            suspendedReason = null,
            lastError = null
        ).mergeAccount(json.optJSONObject("account"))
        saveSession(updated)
        updated.accessToken
    }

    private suspend fun ensureToken(): String? {
        val current = session ?: AccountStore.load(app) ?: return null
        session = current
        if (!current.isLoggedIn) return null
        val now = System.currentTimeMillis()
        if (current.accessToken.isNotBlank() && current.accessExpiresAt - now > 60_000) return current.accessToken
        return refreshSession(force = false)
    }

    /** يجرّب الطلب، ويجدّد الجلسة مرة واحدة عند انتهاء الرمز أو تغيّر الجيل. */
    private suspend fun authed(call: suspend (String) -> ApiResult): ApiResult {
        val token = ensureToken() ?: return ApiResult(401, JSONObject().put("code", "AUTH_REQUIRED"))
        val first = call(token)
        val epochChanged = first.code == 409 &&
            first.errorCode() in setOf("ACCOUNT_EPOCH_CHANGED", "DEVICE_EPOCH_CHANGED")
        if (first.code == 401 || epochChanged) {
            val fresh = refreshSession(force = true) ?: return first
            return call(fresh)
        }
        return first
    }

    private fun AccountSession.mergeAccount(account: JSONObject?): AccountSession {
        if (account == null) return this
        val device = account.optJSONObject("device")
        return copy(
            accountId = account.optString("id").ifBlank { accountId },
            username = account.optString("username").ifBlank { username },
            accountEpoch = account.optLong("account_epoch", accountEpoch),
            deviceEpoch = device?.optLong("device_epoch", deviceEpoch) ?: deviceEpoch,
            serverDeviceId = device?.optString("id").orEmpty().ifBlank { serverDeviceId },
            deviceStatus = device?.optString("status").orEmpty().ifBlank { deviceStatus }
        )
    }

    // ------------------------------------------------------------------ الدخول

    suspend fun claimLegacy(username: String, password: String, licenseToken: String): LoginOutcome =
        withContextIo {
            val res = api.claim(
                username.trim(), password, DeviceId.get(app), installId,
                licenseToken, deviceName(), BuildConfig.VERSION_CODE
            )
            when {
                res.network -> LoginOutcome.Offline
                res.ok -> {
                    resetSyncState()
                    acceptSession(res.json, username.trim(), password)
                    markEverythingDirty()
                    LoginOutcome.Success
                }
                res.code == 409 && res.errorCode() == "USERNAME_TAKEN" -> LoginOutcome.UsernameTaken
                res.code == 409 -> LoginOutcome.AlreadyClaimed
                res.code == 403 -> LoginOutcome.LicenseNotEligible
                res.code == 429 -> LoginOutcome.RateLimited
                else -> LoginOutcome.Failed(res.errorCode(), res.errorMessage())
            }
        }

    suspend fun retryPendingLogin(): LoginOutcome {
        val current = currentSession() ?: return LoginOutcome.Offline
        if (current.username.isBlank() || current.password.isBlank()) return LoginOutcome.InvalidCredentials
        return login(current.username, current.password)
    }

    suspend fun login(username: String, password: String): LoginOutcome = withContextIo {
        val res = api.login(
            username.trim(), password, DeviceId.get(app), installId,
            deviceName(), BuildConfig.VERSION_CODE
        )
        when {
            res.network -> LoginOutcome.Offline
            res.ok -> {
                val previous = currentSession()
                val newAccountId = res.json?.optJSONObject("account")?.optString("id").orEmpty()
                if (previous != null && previous.accountId.isNotBlank() && previous.accountId != newAccountId) {
                    // بيانات حساب آخر لا يجوز أن تُرفع لحساب جديد
                    db.clearAllTables()
                    resetSyncState()
                }
                acceptSession(res.json, username.trim(), password)
                // حساب لم يُرفع له شيء بعد: بيانات هذا التلفون هي مصدر السحابة.
                if ((res.json?.optJSONObject("account")?.optLong("latest_change_id") ?: 0L) == 0L) {
                    markEverythingDirty()
                }
                if (res.json?.optBoolean("restore_required") == true) {
                    LoginOutcome.RestoreNeeded
                } else {
                    LoginOutcome.Success
                }
            }
            res.code == 401 -> LoginOutcome.InvalidCredentials
            res.code == 429 -> LoginOutcome.RateLimited
            res.code == 403 -> LoginOutcome.Failed(res.errorCode(), res.errorMessage())
            res.code == 409 && res.errorCode() == "TRANSFER_REQUIRED" -> {
                rememberPendingLogin(username.trim(), password, res.json?.optString("transfer_id"))
                LoginOutcome.TransferPending
            }
            res.code == 409 && res.errorCode() == "WIPE_REQUIRED" -> {
                rememberPendingLogin(username.trim(), password, res.json?.optString("transfer_id"), "wipe_pending")
                LoginOutcome.WipeRequired(res.json?.optString("transfer_id"))
            }
            res.code == 409 && res.errorCode() == "TRANSFER_IN_PROGRESS" -> {
                rememberPendingLogin(username.trim(), password, res.json?.optString("transfer_id"))
                LoginOutcome.TransferPending
            }
            else -> LoginOutcome.Failed(res.errorCode(), res.errorMessage())
        }
    }

    private suspend fun acceptSession(json: JSONObject?, username: String, password: String) {
        val account = json?.optJSONObject("account")
        val device = account?.optJSONObject("device")
        val updated = AccountSession(
            accountId = account?.optString("id").orEmpty(),
            username = username,
            password = password,
            accessToken = json?.optString("access_token").orEmpty(),
            refreshToken = json?.optString("refresh_token").orEmpty(),
            accessExpiresAt = json?.optLong("access_expires_at") ?: 0,
            refreshExpiresAt = json?.optLong("refresh_expires_at") ?: 0,
            serverDeviceId = device?.optString("id").orEmpty(),
            deviceStatus = device?.optString("status").orEmpty(),
            accountEpoch = account?.optLong("account_epoch") ?: 0,
            deviceEpoch = device?.optLong("device_epoch") ?: 0,
            installId = installId
        )
        saveSession(updated)
        headEtag = null
        val stateRow = db.syncDao().stateOnce() ?: SyncState()
        db.syncDao().saveState(
            stateRow.copy(
                accountId = updated.accountId,
                restoreMode = updated.deviceStatus == "restoring",
                updatedAt = System.currentTimeMillis()
            )
        )
        publishState()
    }

    private suspend fun rememberPendingLogin(
        username: String,
        password: String,
        transferId: String?,
        deviceStatusHint: String = ""
    ) {
        val updated = AccountSession(
            accountId = "",
            username = username,
            password = password,
            installId = installId,
            pendingTransferId = transferId,
            deviceStatus = if (deviceStatusHint == "wipe_pending") "wipe_pending" else "",
            lastError = "بانتظار موافقة نقل الجهاز"
        )
        saveSession(updated)
        publishState()
    }

    suspend fun logout() = withContextIo {
        val current = currentSession()
        if (current != null && current.isLoggedIn) {
            runCatching { api.logout(current.accessToken, current.refreshToken) }
        }
        saveSession(AccountSession(installId = installId))
        resetSyncState()
        publishState()
    }

    private suspend fun resetSyncState() {
        val dao = db.syncDao()
        dao.saveState(SyncState(updatedAt = System.currentTimeMillis()))
        dao.clearShadows()
        dao.clearTombstones()
        dao.clearAllDirty()
        dao.clearConflicts()
    }

    // ------------------------------------------------------------------ المزامنة

    suspend fun syncNow(): SyncOutcome = withContextIo {
        val current = currentSession() ?: return@withContextIo SyncOutcome.NotLoggedIn
        if (!current.isLoggedIn) return@withContextIo SyncOutcome.NotLoggedIn
        if (blockedBySchema) return@withContextIo SyncOutcome.AppUpdateRequired
        if (!syncMutex.tryLock()) return@withContextIo SyncOutcome.Busy
        _state.value = _state.value.copy(busy = true)
        try {
            val result = doSync(current)
            publishState()
            result
        } catch (e: Exception) {
            SyncOutcome.Failed(null, e.message)
        } finally {
            _state.value = _state.value.copy(busy = false)
            syncMutex.unlock()
        }
    }

    private suspend fun doSync(current: AccountSession): SyncOutcome {
        if (current.deviceStatus == "restoring") return restoreFromSnapshot()

        val head = authed { api.head(it, headEtag) }
        when {
            head.code == 304 -> Unit
            head.network -> return SyncOutcome.Offline
            head.code == 426 -> {
                blockedBySchema = true
                return SyncOutcome.AppUpdateRequired
            }
            !head.ok -> return classifyFailure(head)
        }
        if (head.json != null) {
            headEtag = "\"${head.json.optLong("cursor")}:${head.json.optLong("account_epoch")}:${head.json.optLong("device_epoch")}\""
            updateEpochs(head.json)
        }

        val transfer = currentTransferForMe()
        if (transfer != null) {
            val frozen = freezeAndPause(transfer)
            if (frozen != null) return frozen
        }

        val pulled = pullLoop()
        if (pulled != null) return pulled

        val pushResult = pushLoop()
        if (pushResult != null) return pushResult

        val afterPush = pullLoop()
        if (afterPush != null) return afterPush
        pruneOrphanMedia()

        val dao = db.syncDao()
        val row = dao.stateOnce() ?: SyncState()
        dao.saveState(row.copy(lastSyncAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        return SyncOutcome.Ok
    }

    /** ملفات البوردات المحذوفة سحابياً تبقى على القرص بلا صفوف: تُزال بدل أن تتراكم. */
    private suspend fun pruneOrphanMedia() {
        val root = java.io.File(app.filesDir, "board_media")
        val dirs = root.listFiles() ?: return
        val keep = runCatching { db.syncDao().projectIdsWithMedia() }.getOrDefault(emptyList()).toSet()
        for (dir in dirs) {
            val id = dir.name.toLongOrNull() ?: continue
            if (id !in keep) runCatching { dir.deleteRecursively() }
        }
    }

    /** نقل ملكية هذا الجهاز: كل تعديل يُرفع أولاً، ثم يُجمَّد الجهاز فلا يقبل الخادم كتابة منه. */
    private suspend fun freezeAndPause(transferId: String): SyncOutcome? {
        pushLoop()
        pullLoop()
        pushLoop()
        val cursor = db.syncDao().stateOnce()?.cursor ?: 0L
        val res = authed { api.sourceFrozen(it, transferId, cursor) }
        if (res.network) return SyncOutcome.Offline
        if (res.ok) {
            session?.let { saveSession(it.copy(deviceStatus = "frozen", pendingTransferId = transferId)) }
            publishState("تم نقل الحساب إلى جهاز جديد")
        }
        return null
    }

    private suspend fun currentTransferForMe(): String? {
        val res = authed { api.currentTransfer(it) }
        if (!res.ok) return null
        val transfer = res.json?.optJSONObject("transfer") ?: return null
        val me = session ?: return null
        if (transfer.optString("status") != "approved") return null
        if (transfer.optString("source_device_id") != me.serverDeviceId) return null
        return transfer.optString("id").ifBlank { null }
    }

    private suspend fun updateEpochs(json: JSONObject) {
        val current = session ?: return
        val updated = current.copy(
            accountEpoch = json.optLong("account_epoch", current.accountEpoch),
            deviceEpoch = json.optLong("device_epoch", current.deviceEpoch)
        )
        if (updated != current) saveSession(updated)
    }

    private suspend fun classifyFailure(res: ApiResult): SyncOutcome {
        if (res.network) return SyncOutcome.Offline
        return when (res.errorCode()) {
            "DEVICE_NOT_ACTIVE" -> {
                session?.let { saveSession(it.copy(deviceStatus = "frozen")) }
                SyncOutcome.DeviceNotActive
            }
            "WIPE_REQUIRED" -> {
                session?.let { saveSession(it.copy(deviceStatus = "wipe_pending")) }
                SyncOutcome.WipeRequired
            }
            "SCHEMA_UPGRADE_REQUIRED" -> {
                blockedBySchema = true
                SyncOutcome.AppUpdateRequired
            }
            "AUTH_REQUIRED", "TOKEN_REPLAYED", null -> if (res.code == 401) {
                session?.let { saveSession(it.copy(accessToken = "", refreshToken = "")) }
                SyncOutcome.NeedsLogin
            } else {
                SyncOutcome.Failed(res.errorCode(), res.errorMessage())
            }
            else -> SyncOutcome.Failed(res.errorCode(), res.errorMessage())
        }
    }

    /** عند إنشاء الحساب: كل ما هو موجود محلياً يُصبح تغييرات تحتاج رفعاً. */
    private suspend fun markEverythingDirty() {
        val dao = db.syncDao()
        db.withTransaction {
            dao.allSuppliers().forEach { dao.markDirty(SyncDirty("suppliers", it.syncId)) }
            db.componentDao().getAllOnce().forEach { dao.markDirty(SyncDirty("components", it.syncId)) }
            db.componentDao().getAllVariantsOnce().forEach { dao.markDirty(SyncDirty("component_variants", it.syncId)) }
            db.componentDao().getAllOffersOnce().forEach { dao.markDirty(SyncDirty("supplier_offers", it.syncId)) }
            db.tagDao().getAllOnce().forEach { dao.markDirty(SyncDirty("tags", it.syncId)) }
            dao.allLinks().forEach { dao.markDirty(SyncDirty("component_tags", it.syncId)) }
            db.templateDao().getAllOnce().forEach { dao.markDirty(SyncDirty("templates", it.syncId)) }
            db.templateDao().getAllItemsOnce().forEach { dao.markDirty(SyncDirty("template_items", it.syncId)) }
            db.projectDao().getAllOnce().forEach { dao.markDirty(SyncDirty("projects", it.syncId)) }
            db.projectDao().getAllItemsOnce().forEach { dao.markDirty(SyncDirty("project_items", it.syncId)) }
            dao.allNotes().forEach { dao.markDirty(SyncDirty("project_notes", it.syncId)) }
            db.cartDao().getAllOnce().forEach { dao.markDirty(SyncDirty("cart_items", it.syncId)) }
            dao.settingsOnce()?.let { dao.markDirty(SyncDirty("business_settings", it.syncId)) }
        }
    }

    // ------------------------------------------------------------------ السحب

    private suspend fun pullLoop(): SyncOutcome? {
        val dao = db.syncDao()
        retryPendingApplies()
        var guard = 0
        while (guard++ < 40) {
            val cursor = dao.stateOnce()?.cursor ?: 0L
            val res = authed { api.pull(it, cursor) }
            if (res.network) return SyncOutcome.Offline
            if (!res.ok) return classifyFailure(res)
            val json = res.json ?: return null
            val next = json.optLong("next_cursor", cursor)
            val groups = json.optJSONArray("groups") ?: JSONArray()

            db.withTransaction {
                for (gi in 0 until groups.length()) {
                    val changes = groups.optJSONObject(gi)?.optJSONArray("changes") ?: continue
                    for (ci in 0 until changes.length()) {
                        changes.optJSONObject(ci)?.let { applyChange(it) }
                    }
                }
                val row = dao.stateOnce() ?: SyncState()
                dao.saveState(row.copy(cursor = next, accountId = session?.accountId ?: row.accountId, updatedAt = System.currentTimeMillis()))
            }

            if (!json.optBoolean("has_more") || next <= cursor) return null
        }
        return null
    }

    private suspend fun applyChange(change: JSONObject) {
        val dao = db.syncDao()
        val type = change.optString("entity_type")
        val id = change.optString("entity_id")
        if (!SyncCodec.isEntityType(type) || id.isBlank()) return
        val revision = change.optLong("revision")
        val schemaVersion = change.optInt("schema_version", SCHEMA)
        val action = change.optString("action")
        val payload = if (change.isNull("payload")) null else runCatching { JSONObject(change.get("payload").toString()) }.getOrNull()
        val canonical = payload?.let { SyncCodec.canonical(it) }
        val now = System.currentTimeMillis()
        val shadow = dao.shadow(type, id)

        if (shadow != null && shadow.revision > revision) return
        if (shadow != null && shadow.revision == revision && shadow.payloadJson == canonical) return

        // تعديل محلي لم يُدفع بعد: يُترك للدفع كي يحسمه الخادم بدمج آمن أو بتعارض صريح.
        // إن كان "التعديل" المحلي مطابقاً للوارد فلا شيء يُحفظ، ونُكمل التطبيق.
        if (dao.dirtyRow(type, id) != null && !isOwnActor(change)) {
            val local = SyncCodec.localPayload(db, type, id)
            if (local == null || canonical == null || SyncCodec.canonical(local) != canonical) return
        }

        try {
            SyncCodec.apply(db, type, id, payload)
        } catch (e: SyncCodec.MissingReference) {
            // المرجع لم يصل بعد: يُحفظ التغيير كاملاً ويُعاد بعد وصول الأب، ولا يُكتب ظلّ كاذب
            dao.putConflict(
                SyncConflictEntity(
                    conflictId = "pending:${type}:${id}",
                    operationId = "pull:${change.optLong("change_id")}",
                    entityType = type, entityId = id,
                    reason = "MISSING_REFERENCE:${e.entityType}/${e.entityId}",
                    localJson = null, serverJson = canonical,
                    serverRevision = revision, status = "pending_apply", createdAt = now
                )
            )
            return
        }
        dao.putShadow(SyncShadow(type, id, revision, schemaVersion, canonical, action == "delete", now))
        dao.clearDirty(type, id)
        dao.settleOpenConflicts(type, id)
    }

    /** إعادة تطبيق ما تعطّل سابقاً بسبب مرجع لم يكن قد وصل. */
    private suspend fun retryPendingApplies() {
        val dao = db.syncDao()
        val pending = dao.pendingApplies()
        if (pending.isEmpty()) return
        db.withTransaction {
            for (item in pending) {
                val payload = item.serverJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                try {
                    SyncCodec.apply(db, item.entityType, item.entityId, payload)
                } catch (_: SyncCodec.MissingReference) {
                    continue
                }
                dao.putShadow(
                    SyncShadow(
                        item.entityType, item.entityId, item.serverRevision, SCHEMA,
                        item.serverJson, payload == null, System.currentTimeMillis()
                    )
                )
                dao.clearDirty(item.entityType, item.entityId)
                dao.deleteConflict(item.conflictId)
            }
        }
    }

    private fun isOwnActor(change: JSONObject): Boolean {
        val me = session ?: return false
        return change.optString("actor_type") == "device" && change.optString("actor_id") == me.serverDeviceId
    }

    // ------------------------------------------------------------------ الدفع

    /** null = نجح كل شيء، وإلا سبب التوقف. */
    private suspend fun pushLoop(): SyncOutcome? {
        val dao = db.syncDao()
        var guard = 0
        while (guard++ < 40) {
            val dirty = dao.dirtyRows()
            if (dirty.isEmpty()) return null
            val mutations = JSONArray()
            val mutationRows = mutableListOf<SyncDirty>()
            val sentPayloads = mutableListOf<String?>()

            // ترتيب: الحذف من الابن إلى الأب، ثم الإنشاء/التعديل من الأب إلى الابن
            val built = dirty.mapNotNull { row -> buildMutation(row)?.let { row to it } }
                .sortedWith(
                    compareBy(
                        { if (it.second.first.optString("action") == "delete") 1 else 0 },
                        { val rank = SyncCodec.orderOf(it.first.entityType); if (it.second.first.optString("action") == "delete") -rank else rank }
                    )
                )
            for ((row, mutation) in built) {
                mutations.put(mutation.first)
                mutationRows += row
                sentPayloads += mutation.second
                if (mutations.length() >= MAX_MUTATIONS_PER_PUSH) break
            }
            if (mutations.length() == 0) return null

            val me = session ?: return SyncOutcome.NotLoggedIn
            val operation = JSONObject()
                .put("operation_id", newSyncId())
                .put("mutations", mutations)
            val body = JSONObject()
                .put("account_epoch", me.accountEpoch)
                .put("device_epoch", me.deviceEpoch)
                .put("client_schema_version", SCHEMA)
                .put("operations", JSONArray().put(operation))

            val res = authed { api.push(it, body) }
            if (res.network) return SyncOutcome.Offline
            if (!res.ok) return classifyFailure(res)

            val result = res.json?.optJSONArray("results")?.optJSONObject(0)
                ?: return SyncOutcome.Failed(null, "ردّ مزامنة غير مفهوم")
            when (result.optString("status")) {
                "accepted" -> {
                    val records = result.optJSONArray("records") ?: JSONArray()
                    db.withTransaction {
                        for (i in 0 until records.length()) {
                            val record = records.optJSONObject(i) ?: continue
                            val type = record.optString("entity_type")
                            val id = record.optString("entity_id")
                            val revision = record.optLong("revision")
                            dao.putShadow(
                                SyncShadow(
                                    type, id, revision, SCHEMA,
                                    sentPayloads.getOrNull(i)?.let { p -> runCatching { SyncCodec.canonical(JSONObject(p)) }.getOrNull() },
                                    false, System.currentTimeMillis()
                                )
                            )
                        }
                        for (row in mutationRows) {
                            dao.clearDirty(row.entityType, row.entityId)
                            dao.settleOpenConflicts(row.entityType, row.entityId)
                        }
                    }
                }
                "conflict" -> {
                    val type = result.optString("entity_type")
                    val id = result.optString("entity_id")
                    val index = mutationRows.indexOfFirst { it.entityType == type && it.entityId == id }
                    db.withTransaction {
                        val serverJson = if (result.isNull("current_json")) null
                        else runCatching { JSONObject(result.get("current_json").toString()) }.getOrNull()
                        val serverRevision = result.optLong("current_revision")
                        dao.putConflict(
                            SyncConflictEntity(
                                conflictId = result.optString("conflict_id").ifBlank { newSyncId() },
                                operationId = operation.optString("operation_id"),
                                entityType = type, entityId = id,
                                reason = result.optString("reason").ifBlank { "REVISION_CONFLICT" },
                                localJson = mutations.optJSONObject(if (index >= 0) index else 0)?.toString(),
                                serverJson = serverJson?.let { SyncCodec.canonical(it) },
                                serverRevision = serverRevision,
                                // الخادم هو المرجع: يُعتمد نصّه ويُبلّغ المستخدم بما تغيّر
                                status = "resolved_server",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        runCatching { SyncCodec.apply(db, type, id, serverJson) }
                        dao.putShadow(
                            SyncShadow(
                                type, id, serverRevision, SCHEMA,
                                serverJson?.let { SyncCodec.canonical(it) }, serverJson == null,
                                System.currentTimeMillis()
                            )
                        )
                        // الخادم رفض العملية كاملة، فلا شيء منها طُبِّق: السجل المتعارض وحده يُحرَّر
                        // بعد اعتماد نسخة الخادم، وبقية الصفوف تبقى للدفع في الدورة التالية.
                        if (index >= 0) {
                            dao.clearDirty(type, id)
                            dao.settleOpenConflicts(type, id)
                        } else {
                            for (row in mutationRows) dao.clearDirty(row.entityType, row.entityId)
                        }
                    }
                }
                else -> {
                    // رفض كامل للدفعة: كل تغيير فيها يُحفظ كتعارض مفتوح ثم يُحرَّر الصف
                    // حتى لا تعلق المزامنة إلى الأبد، والإصلاح يبقى بيد المستخدم.
                    val reason = result.optString("code").ifBlank { "REJECTED" } + ": " + result.optString("error")
                    val now = System.currentTimeMillis()
                    db.withTransaction {
                        for (i in mutationRows.indices) {
                            val row = mutationRows[i]
                            dao.putConflict(
                                SyncConflictEntity(
                                    conflictId = newSyncId(),
                                    operationId = operation.optString("operation_id"),
                                    entityType = row.entityType, entityId = row.entityId,
                                    reason = reason.trim().trimEnd(':'),
                                    localJson = mutations.optJSONObject(i)?.toString(),
                                    serverJson = null, serverRevision = 0,
                                    status = "open", createdAt = now
                                )
                            )
                            dao.clearDirty(row.entityType, row.entityId)
                        }
                    }
                }
            }
        }
        return null
    }

    /** يعيد (الطفرة، الحمولة الكاملة إن كانت إرسالاً كاملاً) أو null إذا لم يبقَ شيء يحتاج دفعاً. */
    private suspend fun buildMutation(row: SyncDirty): Pair<JSONObject, String?>? {
        val dao = db.syncDao()
        val type = row.entityType
        val id = row.entityId
        if (!SyncCodec.isEntityType(type) || id.length !in 16..64) {
            dao.clearDirty(type, id)
            return null
        }
        val shadow = dao.shadow(type, id)
        val local = SyncCodec.localPayload(db, type, id)

        if (local == null) {
            if (shadow == null || shadow.deleted) {
                dao.clearDirty(type, id)
                return null
            }
            return JSONObject()
                .put("entity_type", type).put("entity_id", id).put("action", "delete")
                .put("base_revision", shadow.revision).put("schema_version", SCHEMA) to null
        }

        val full = SyncCodec.canonical(local)
        if (shadow == null) {
            return JSONObject()
                .put("entity_type", type).put("entity_id", id).put("action", "upsert")
                .put("base_revision", 0).put("schema_version", SCHEMA)
                .put("payload", local) to full
        }

        val base = shadow.payloadJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val (patch, baseValues) = SyncCodec.diff(base, local)
        if (patch.length() == 0) {
            dao.clearDirty(type, id)
            return null
        }
        return JSONObject()
            .put("entity_type", type).put("entity_id", id).put("action", "upsert")
            .put("base_revision", shadow.revision).put("schema_version", SCHEMA)
            .put("patch", patch).put("base_values", baseValues)
            .put("merge_policy", "safe") to full
    }

    // ------------------------------------------------------------------ الاستعادة والنقل

    private class SnapshotRecord(
        val type: String,
        val id: String,
        val revision: Long,
        val schemaVersion: Int,
        val payload: JSONObject
    )

    /** يعيد false إذا لم يكن الأب قد وصل بعد، فلا يُكتب ظلّ لسجل لم يُطبَّق. */
    private suspend fun applySnapshotRecord(record: SnapshotRecord): Boolean {
        try {
            SyncCodec.apply(db, record.type, record.id, record.payload)
        } catch (_: SyncCodec.MissingReference) {
            return false
        }
        db.syncDao().putShadow(
            SyncShadow(record.type, record.id, record.revision, record.schemaVersion,
                SyncCodec.canonical(record.payload), false, System.currentTimeMillis())
        )
        db.syncDao().clearDirty(record.type, record.id)
        return true
    }

    private suspend fun restoreFromSnapshot(): SyncOutcome {
        val transfer = authed { api.currentTransfer(it) }
        if (transfer.network) return SyncOutcome.Offline
        val transferJson = transfer.json?.optJSONObject("transfer")
        val transferId = transferJson?.optString("id").orEmpty()
        if (transferId.isBlank()) return SyncOutcome.DeviceNotActive

        wipeBusinessData()

        // اللقطة تصل مرتّبة بـ (النوع، المعرّف) لا حسب الأبوة، فيسبق الابن أباه غالباً.
        // كل ابن يصل قبل أبيه يُؤجَّل هنا ويُعاد بعد اكتمال الصفحات، فلا يضيع ولا يُكتب له ظلّ كاذب.
        var deferred = mutableListOf<SnapshotRecord>()
        var afterType = ""
        var afterId = ""
        var cursor = 0L
        var guard = 0
        while (guard++ < 500) {
            val res = authed { api.snapshot(it, afterType, afterId) }
            if (res.network) return SyncOutcome.Offline
            if (!res.ok) return classifyFailure(res)
            val json = res.json ?: return SyncOutcome.Failed(null, "لقطة غير مفهومة")
            cursor = json.optLong("cursor", cursor)
            val records = json.optJSONArray("records") ?: JSONArray()
            db.withTransaction {
                for (i in 0 until records.length()) {
                    val record = records.optJSONObject(i) ?: continue
                    val type = record.optString("entity_type")
                    val id = record.optString("entity_id")
                    val payload = runCatching { JSONObject(record.get("payload").toString()) }.getOrNull()
                    if (!SyncCodec.isEntityType(type) || id.isBlank() || payload == null) continue
                    val item = SnapshotRecord(
                        type, id, record.optLong("revision"), record.optInt("schema_version", SCHEMA), payload
                    )
                    if (!applySnapshotRecord(item)) deferred += item
                }
            }
            afterType = json.optString("next_after_type", afterType)
            afterId = json.optString("next_after_id", afterId)
            if (!json.optBoolean("has_more")) break
        }

        // إعادة المحاولة بالأب قبل الابن حتى تتوفّر المراجع.
        while (deferred.isNotEmpty()) {
            val still = mutableListOf<SnapshotRecord>()
            db.withTransaction {
                for (item in deferred.sortedBy { SyncCodec.orderOf(it.type) }) {
                    if (!applySnapshotRecord(item)) still += item
                }
            }
            if (still.size == deferred.size) {
                // مرجع مفقود فعلاً (أب محذوف أو غير مشمول): يُحفظ للتطبيق لاحقاً بدل إسقاط الابن.
                val now = System.currentTimeMillis()
                db.withTransaction {
                    for (item in still) {
                        db.syncDao().putConflict(
                            SyncConflictEntity(
                                conflictId = "pending:${item.type}:${item.id}",
                                operationId = "restore:$transferId",
                                entityType = item.type, entityId = item.id,
                                reason = "MISSING_REFERENCE:restore",
                                localJson = null, serverJson = SyncCodec.canonical(item.payload),
                                serverRevision = item.revision, status = "pending_apply", createdAt = now
                            )
                        )
                    }
                }
                break
            }
            deferred = still
        }

        val confirm = authed { api.targetConfirmed(it, transferId, cursor) }
        if (confirm.network) return SyncOutcome.Offline
        if (!confirm.ok) return classifyFailure(confirm)

        val dao = db.syncDao()
        val row = dao.stateOnce() ?: SyncState()
        dao.saveState(row.copy(cursor = cursor, restoreMode = false, lastSyncAt = System.currentTimeMillis()))
        session?.let { saveSession(it.copy(deviceStatus = "active", pendingTransferId = null)) }
        publishState()
        return SyncOutcome.Ok
    }

    /** الجهاز المصدر: يثبّت المؤشر أولاً فلا يضيع تعديل لم يُرفع، ثم يمسح محلياً. */
    suspend fun freezeSourceForTransfer(transferId: String): SyncOutcome = withContextIo {
        val me = currentSession() ?: return@withContextIo SyncOutcome.NotLoggedIn
        if (pushLoop() != null) return@withContextIo SyncOutcome.Failed(null, "تعذر رفع آخر التعديلات")
        val pulled = pullLoop()
        if (pulled != null) return@withContextIo pulled
        if (pushLoop() != null) return@withContextIo SyncOutcome.Failed(null, "تعذر رفع آخر التعديلات")
        val cursor = db.syncDao().stateOnce()?.cursor ?: 0L
        val res = authed { api.sourceFrozen(it, transferId, cursor) }
        if (res.network) return@withContextIo SyncOutcome.Offline
        if (!res.ok) return@withContextIo classifyFailure(res)
        session?.let { saveSession(it.copy(deviceStatus = "frozen", pendingTransferId = transferId)) }
        publishState("تم تجميد هذا الجهاز ورفع كل تعديلاته إلى الحساب")
        SyncOutcome.Ok
    }

    /** يمسح بيانات هذا الجهاز ثم يُبلّغ الخادم. لا يُبلَّغ قبل المسح حتى لا يبقى الجهاز بلا بيانات دون علم الخادم. */
    suspend fun wipeAndAcknowledge(transferId: String): SyncOutcome {
        wipeBusinessData()
        return acknowledgeWipe(transferId)
    }

    suspend fun acknowledgeWipe(transferId: String): SyncOutcome = withContextIo {
        val me = currentSession() ?: return@withContextIo SyncOutcome.NotLoggedIn
        val res = api.sourceWiped(transferId, me.username, me.password, DeviceId.get(app), installId)
        if (res.network) return@withContextIo SyncOutcome.Offline
        if (!res.ok) return@withContextIo classifyFailure(res)
        saveSession(AccountSession(installId = installId))
        publishState("تم مسح بيانات هذا الجهاز بعد نقل الملكية")
        SyncOutcome.Ok
    }

    suspend fun wipeBusinessData() = withContextIo {
        db.clearAllTables()
        resetSyncState()
        runCatching { java.io.File(app.filesDir, "board_media").deleteRecursively() }
        publishState()
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().take(100)

    private fun messageFor(code: String?): String = when (code) {
        "DEVICE_EPOCH_CHANGED" -> "تغيّرت صلاحية الجلسة، أعد تسجيل الدخول"
        "WIPE_REQUIRED" -> "يجب مسح بيانات هذا الجهاز"
        "DEVICE_NOT_ACTIVE" -> "هذا الجهاز لم يعد الجهاز النشط للحساب"
        "ACCOUNT_LOCKED" -> "الحساب موقوف"
        "TOKEN_REPLAYED" -> "أُبطلت الجلسة لحماية الحساب، أعد تسجيل الدخول"
        else -> "انتهت الجلسة، أعد تسجيل الدخول"
    }
}

private suspend fun <T> withContextIo(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
