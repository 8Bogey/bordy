package com.bordy.app.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.bordy.app.BuildConfig
import com.bordy.app.update.UpdateManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Central manager for the app's license state.
 * Call init() in Application or MainActivity.onCreate().
 * Call checkAndRefresh() in onResume (throttled to max once per 6h).
 */
object LicenseManager {

    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L   // 6 hours
    private const val TOKEN_AGE_REFRESH_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val MIN_LOCAL_SYNC_GAP_MS = 15_000L
    private const val MIN_FORCED_SYNC_GAP_MS = 5_000L
    private const val FOREGROUND_PENDING_SECONDS = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<LicenseState>(LicenseState.Loading)
    val state: StateFlow<LicenseState> = _state

    /** Wall-clock time of the last successful round-trip (0 = never). Drives the settings row. */
    private val _lastSyncAt = MutableStateFlow(0L)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt

    @Volatile
    private var initialized = false

    /** Latest server-owned config (plans, wallets, campaign, cadence). New value = it really changed. */
    private val _remoteConfig = MutableStateFlow<PlansResponse?>(null)
    val remoteConfig: StateFlow<PlansResponse?> = _remoteConfig

    private var lastRefreshAttempt = 0L
    private var lastProofRetryAt = 0L
    private var lastProofRetryOrder: String? = null
    private var currentTosVersion = 1

    private val syncMutex = Mutex()
    private var syncJob: Job? = null
    private var tickJob: Job? = null
    private var lastAttemptAt = 0L

    /** آخر حالة تنبيه تحديث عولجت (النمط + الطابع) — لا يتكرر التعامل مع كل استطلاع. */
    private var lastUpdateTrigger: Pair<String, Long>? = null

    /**
     * Last verified payload kept in memory so the countdown can move without a network call
     * and without decrypting the licence file on every tick.
     */
    @Volatile
    private var currentPayload: TokenPayload? = null

    private val DAY_MS = 86_400_000L
    private val TICK_MS = 20_000L
    private val LIFETIME_DAYS = 9999

    /** Completed once the stored licence has been read and the local state resolved. */
    private val startupDone = CompletableDeferred<Unit>()

    /** Serialises activations so startup and the poll loop cannot both register the device. */
    private val activateMutex = Mutex()
    private var lastActivateAt = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        scope.launch {
            try {
                val stored = LicenseStore.load(context)

                // 1. No data at all, or TOS not accepted → nothing to refresh yet.
                if (stored == null || !stored.tosAccepted) {
                    LicenseSyncScheduler.cancel(context)
                    // Only the TOS screen needs the server text; skip the request when we already have it.
                    val tosResp = try { LicenseApi.getTos() } catch (_: Exception) { null }
                    currentTosVersion = tosResp?.tosVersion ?: 1
                    _state.value = LicenseState.NeedTos
                    return@launch
                }

                // 2. Stored token → verify and derive the state from disk only (no network here:
                //    the process may have been started by the background worker).
                if (stored.token == "DEV_TEST_TOKEN" && BuildConfig.DEBUG) {
                    _state.value = LicenseState.Active(daysLeft = LIFETIME_DAYS, showReminder = false, lifetime = true)
                    return@launch
                }

                val payload = LicenseToken.verify(stored.token)
                if (payload == null || payload.did != DeviceId.get(context)) {
                    // Invalid, tampered, or issued to another device → reactivate
                    activateOnline(context)
                    return@launch
                }

                // 3. TOS version check: if server bumped tos_version, re-show TOS
                if (stored.tosVersion < payload.tos_ver) {
                    currentTosVersion = payload.tos_ver
                    LicenseSyncScheduler.cancel(context)
                    _state.value = LicenseState.NeedTos
                    return@launch
                }

                // 4. A block issued while the app was closed must not disappear on restart.
                //    Only a clean sync lifts it.
                val blockedReason = LicenseStore.getBlockedReason(context)
                if (blockedReason != null) {
                    _state.value = LicenseState.Blocked(blockedReason.ifBlank { null })
                    LicenseSyncScheduler.ensurePeriodic(context)
                    return@launch
                }

                _lastSyncAt.value = LicenseStore.getLastSyncAt(context)
                applyPayload(context, payload)

                // Keep the silent watcher alive for this install (no-op once armed).
                LicenseSyncScheduler.ensurePeriodic(context)
            } finally {
                startupDone.complete(Unit)
            }
        }
    }

    /** Re-derive the cached state without a round-trip (after a clock or timezone change). */
    fun revalidateLocal(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val stored = LicenseStore.load(appContext) ?: return@launch
            if (!stored.tosAccepted || stored.token == "DEV_TEST_TOKEN") return@launch
            // A clock change must not undo a block either.
            LicenseStore.getBlockedReason(appContext)?.let {
                _state.value = LicenseState.Blocked(it.ifBlank { null })
                return@launch
            }
            val payload = LicenseToken.verify(stored.token) ?: return@launch
            if (payload.did != DeviceId.get(appContext)) return@launch
            applyPayload(appContext, payload)
        }
    }

    fun checkAndRefresh(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val isNonActive = _state.value !is LicenseState.Active
        val hasPending = !LicenseStore.getPendingOrderId(context).isNullOrBlank()

        val minInterval = if (force || isNonActive || hasPending) 5_000L else REFRESH_INTERVAL_MS
        if (now - lastRefreshAttempt < minInterval) return
        lastRefreshAttempt = now

        scope.launch {
            val did = DeviceId.get(context)

            // 1. Check if there is an unconfirmed pending order
            val pendingOrderId = LicenseStore.getPendingOrderId(context)
            if (!pendingOrderId.isNullOrBlank() && LicenseApi.isOnline(context)) {
                // Safety net: if sending the proof failed earlier, retry it once (at most every 10 min).
                val pending = LicenseStore.getPendingOrder(context)
                if (pending != null && !pending.submitted && !pending.method.isNullOrBlank()) {
                    val nowMs = System.currentTimeMillis()
                    val canRetry = pending.id != lastProofRetryOrder || nowMs - lastProofRetryAt > 10 * 60 * 1000L
                    if (canRetry) {
                        lastProofRetryOrder = pending.id
                        lastProofRetryAt = nowMs
                        val submit = try {
                            LicenseApi.submitProof(
                                did,
                                pending.id,
                                pending.method,
                                pending.txnRef ?: "",
                                LicenseStore.getReceiptBase64(context, pending.id) ?: ""
                            )
                        } catch (_: Exception) {
                            null
                        }
                        if (submit != null && submit.optBoolean("ok", false)) {
                            LicenseStore.updatePendingProof(context, pending.method, pending.txnRef, submitted = true)
                        } else if (errorCodeOf(submit) == "METHOD_INVALID" || errorCodeOf(submit) == "METHOD_REQUIRED") {
                            // The stored wallet name no longer exists in settings — drop it instead of retrying forever.
                            LicenseStore.updatePendingProof(context, null, pending.txnRef, submitted = false)
                        }
                    }
                }

                val orderResp = try { LicenseApi.checkOrder(did, pendingOrderId) } catch (_: Exception) { null }
                if (orderResp != null) {
                    when {
                        orderResp.status == "APPROVED" && !orderResp.token.isNullOrEmpty() -> {
                            LicenseStore.clearPendingOrder(context)
                            onSubscriptionApproved(context, orderResp.token)
                            return@launch
                        }
                        orderResp.status == "APPROVED" -> {
                            // Token missing — fetch a new signed one (or a full token if none is stored).
                            LicenseStore.clearPendingOrder(context)
                            val stored2 = LicenseStore.load(context)
                            if (stored2 == null) activateOnline(context) else refreshOnline(context, stored2)
                            return@launch
                        }
                        orderResp.status == "REJECTED" || orderResp.status == "CANCELLED" || orderResp.status == "EXPIRED" -> {
                            LicenseStore.clearPendingOrder(context)
                        }
                        orderResp.errorCode == "ORDER_NOT_FOUND" || orderResp.errorCode == "FORBIDDEN" -> {
                            LicenseStore.clearPendingOrder(context)
                        }
                    }
                }
            }

            // 2. Refresh device license state from server
            val stored = LicenseStore.load(context) ?: return@launch
            if (!stored.tosAccepted) return@launch
            val payload = LicenseToken.verify(stored.token) ?: return@launch
            if (payload.did != did) return@launch
            val tokenAge = System.currentTimeMillis() - payload.iat
            val shouldRefresh = force || isNonActive || (tokenAge > TOKEN_AGE_REFRESH_MS)
            // performSync shares the 3-second gap with the foreground poll, so opening the app
            // right after a sync does not fire a duplicate request.
            if (shouldRefresh && LicenseApi.isOnline(context)) {
                performSync(context, force = force)
            }
        }
    }

    // ── Periodic sync ─────────────────────────────────────────────────────────

    /** Pull config + license + latest order now (used on resume and after payment actions). */
    fun syncOnce(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        scope.launch { performSync(appContext, force) }
    }

    /** Background worker entry: one bounded attempt, no long retry loops. */
    suspend fun syncFromBackground(context: Context): Boolean =
        performSync(context.applicationContext, force = true, quick = true)

    /** Start the polling loop; the cadence comes from the admin panel (clamped on both ends). */
    fun startSync(context: Context) {
        val appContext = context.applicationContext
        ensureTicker(appContext)
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                performSync(appContext, force = true)
                // Foreground cadence is a separate admin knob (seconds), so panel edits land almost instantly.
                val base = LicenseStore.getForegroundSync(appContext)
                // While a payment waits for the owner's approval, answer within ~20s.
                val pending = !LicenseStore.getPendingOrderId(appContext).isNullOrBlank()
                val cadence = if (pending) minOf(base, FOREGROUND_PENDING_SECONDS) else base
                delay(cadence * 1000L)
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        tickJob?.cancel()
        tickJob = null
    }

    /**
     * Re-derives the state from the verified payload every few seconds. This is what makes the
     * countdown actually move between syncs and what enforces expiry while offline.
     */
    private fun ensureTicker(context: Context) {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val payload = currentPayload ?: continue
                val current = _state.value
                // Never tick over a verdict the server owns (block, TOS, update, tamper).
                if (current !is LicenseState.Active &&
                    current !is LicenseState.Trial &&
                    current !is LicenseState.Grace &&
                    current !is LicenseState.Expired
                ) continue
                _state.value = deriveState(context, payload)
            }
        }
    }

    private suspend fun performSync(context: Context, force: Boolean, quick: Boolean = false): Boolean {
        if (!syncMutex.tryLock()) return false
        try {
            // Never poll with a token that startup is still about to replace.
            withTimeoutOrNull(8_000L) { startupDone.await() }
            if (!LicenseApi.isOnline(context)) return false
            val stored = LicenseStore.load(context) ?: return false
            if (!stored.tosAccepted || stored.token == "DEV_TEST_TOKEN") return false

            val now = System.currentTimeMillis()
            // Several triggers can fire at once (network restored, app opened, worker woke up);
            // only the first one inside the window reaches the server.
            val gap = if (force) MIN_FORCED_SYNC_GAP_MS else MIN_LOCAL_SYNC_GAP_MS
            if (now - lastAttemptAt < gap) return false
            lastAttemptAt = now

            val did = DeviceId.get(context)
            val resp = try {
                if (quick) LicenseApi.syncQuiet(did, stored.token)
                else LicenseApi.sync(did, stored.token)
            } catch (_: Exception) {
                null
            } ?: return false

            if (!resp.ok) {
                when {
                    resp.status == "BLOCKED" -> {
                        LicenseStore.saveBlocked(context, resp.blockedReason)
                        _state.value = LicenseState.Blocked(resp.blockedReason)
                    }
                    // The server no longer knows this device — register it again (keeps the trial/paid state it holds).
                    resp.errorCode == "DEVICE_NOT_REGISTERED" -> activateOnline(context)
                    // Too many polls for this window; nothing changed, the next slot handles it.
                    resp.errorCode == "RATE_LIMITED" -> return true
                }
                return false
            }

            // A clean answer from the server is the only thing that lifts a stored block.
            LicenseStore.clearBlocked(context)

            _lastSyncAt.value = now
            LicenseStore.saveLastSyncAt(context, now)

            resp.config?.let { cfg ->
                val prev = _remoteConfig.value
                if (prev == null || prev.version != cfg.version) _remoteConfig.value = cfg
                val previousInterval = LicenseStore.getSyncInterval(context)
                if (previousInterval != cfg.syncIntervalSeconds) {
                    LicenseStore.saveSyncInterval(context, cfg.syncIntervalSeconds)
                    LicenseSyncScheduler.ensurePeriodic(context)
                }
                // Only touch prefs when it actually moved; this runs on every foreground poll.
                if (LicenseStore.getForegroundSync(context) != cfg.foregroundSyncSeconds) {
                    LicenseStore.saveForegroundSync(context, cfg.foregroundSyncSeconds)
                }
                // The owner may change the reminder unit/value at any time; cache it locally.
                val notice = ExpiryNoticeConfig(
                    enabled = cfg.expiryNotice.enabled,
                    unit = NoticeUnit.fromKey(cfg.expiryNotice.unit),
                    value = cfg.expiryNotice.value
                )
                if (LicenseStore.getExpiryNotice(context) != notice) {
                    LicenseStore.saveExpiryNotice(context, notice)
                }
            }

            // تنبيه التحديث الفوري: يصل مع كل مزامنة، فلا يُعاد التعامل معه إلا عند تغيّره.
            resp.trigger?.let { t ->
                val stamp = t.mode to t.id
                if (stamp != lastUpdateTrigger) {
                    lastUpdateTrigger = stamp
                    UpdateManager.onSyncTrigger(
                        context, t.mode, t.id, t.message, t.latestVersion
                    )
                }
            }

            // Drop a pending order the server already settled while the app was closed.
            val pendingId = LicenseStore.getPendingOrderId(context)
            if (!pendingId.isNullOrBlank() && resp.orderId == pendingId) {
                val expiresSoon = (resp.orderStatus == "PENDING" || resp.orderStatus == "SUBMITTED") &&
                    resp.orderExpiresAt in 1..now
                if (expiresSoon || resp.orderStatus in setOf("APPROVED", "REJECTED", "CANCELLED", "EXPIRED")) {
                    LicenseStore.clearPendingOrder(context)
                }
            }

            // A fresh token is sent only when rights changed. Even without one, an
            // unrestricted sync must clear stale states (e.g. the owner lifted a block).
            val fresh = resp.token
            val effectiveToken = if (!fresh.isNullOrEmpty()) {
                val payload = LicenseToken.verify(fresh)
                if (payload != null && payload.did == did) {
                    LicenseStore.save(context, fresh, stored.tosAccepted, stored.tosVersion)
                    fresh
                } else null
            } else null
            val payload = LicenseToken.verify(effectiveToken ?: stored.token)
            if (payload != null && payload.did == did) {
                applyPayload(context, payload)
            }
            return true
        } finally {
            syncMutex.unlock()
        }
    }

    fun acceptTos(context: Context, tosVersion: Int) {
        scope.launch {
            LicenseStore.save(context, "", tosAccepted = true, tosVersion = tosVersion)
            activateOnline(context, ownerCode = null)
        }
    }

    fun activate(context: Context, ownerCode: String? = null) {
        scope.launch { activateOnline(context, ownerCode) }
    }

    /** تفعيل وضع المطور للاختبار المحلي دون الحاجة لاتصال أو سيرفر — نسخ التطوير فقط */
    fun enableDevMode(context: Context, state: LicenseState = LicenseState.Active(daysLeft = 9999, showReminder = false, lifetime = true)) {
        if (!BuildConfig.DEBUG) return
        val existing = LicenseStore.load(context)
        LicenseStore.save(
            context,
            "DEV_TEST_TOKEN",
            tosAccepted = true,
            tosVersion = existing?.tosVersion ?: 1
        )
        LicenseStore.clearBlocked(context)
        _state.value = state
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun activateOnline(context: Context, ownerCode: String? = null) {
        activateMutex.withLock {
            val now = System.currentTimeMillis()
            // Startup and the first poll can both decide to register the device; do it once.
            // An explicit owner-code activation must not be swallowed by that throttle.
            if (ownerCode == null && now - lastActivateAt < 10_000L) return@withLock
            lastActivateAt = now
            activateOnce(context, ownerCode)
        }
    }

    private suspend fun activateOnce(context: Context, ownerCode: String?) {
        if (BuildConfig.API_BASE_URL.contains("ACCOUNT")) {
            if (BuildConfig.DEBUG) {
                enableDevMode(context, LicenseState.Trial(daysLeft = 7, showBanner = false))
            } else {
                _state.value = LicenseState.NoLicense
            }
            return
        }

        if (!LicenseApi.isOnline(context)) {
            _state.value = LicenseState.NoLicense
            return
        }

        val did = DeviceId.get(context)
        val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val model = Build.MODEL
        val sdk = Build.VERSION.SDK_INT
        val certHash = BuildConfig.EXPECTED_CERT_SHA256

        val resp = try {
            LicenseApi.activate(did, BuildConfig.VERSION_CODE, model, sdk, certHash, nonce, ownerCode)
        } catch (_: Exception) {
            null
        }

        if (resp == null) {
            _state.value = LicenseState.NoLicense
            return
        }

        if (resp.status == "BLOCKED") {
            LicenseStore.saveBlocked(context, resp.reason)
            _state.value = LicenseState.Blocked(resp.reason)
            return
        }

        val token = resp.token
        if (token.isNullOrEmpty()) {
            _state.value = LicenseState.NoLicense
            return
        }

        val payload = LicenseToken.verify(token)
        if (payload == null || payload.did != did) {
            _state.value = LicenseState.NoLicense
            return
        }

        // Save with current TOS state
        val existing = LicenseStore.load(context)
        LicenseStore.save(
            context,
            token,
            tosAccepted = existing?.tosAccepted ?: false,
            tosVersion = existing?.tosVersion ?: 0
        )
        LicenseStore.clearBlocked(context)

        applyPayload(context, payload)
        val activatedAt = System.currentTimeMillis()
        _lastSyncAt.value = activatedAt
        LicenseStore.saveLastSyncAt(context, activatedAt)
        LicenseSyncScheduler.ensurePeriodic(context)
    }

    private suspend fun refreshOnline(context: Context, stored: StoredLicense) {
        val did = DeviceId.get(context)
        val nonce = UUID.randomUUID().toString().replace("-", "").take(16)

        val resp = try {
            LicenseApi.refresh(did, nonce)
        } catch (_: Exception) {
            return
        } ?: return

        if (resp.status == "BLOCKED") {
            LicenseStore.saveBlocked(context, resp.reason)
            _state.value = LicenseState.Blocked(resp.reason)
            return
        }

        val newToken = resp.token ?: return
        val payload = LicenseToken.verify(newToken) ?: return
        if (payload.did != did) return

        LicenseStore.save(context, newToken, stored.tosAccepted, stored.tosVersion)
        LicenseStore.clearBlocked(context)
        val syncedAt = System.currentTimeMillis()
        _lastSyncAt.value = syncedAt
        LicenseStore.saveLastSyncAt(context, syncedAt)
        applyPayload(context, payload)
    }

    private fun applyPayload(context: Context, payload: TokenPayload) {
        currentPayload = payload
        _state.value = deriveState(context, payload)
    }

    private fun ceilDays(ms: Long): Int =
        ((ms + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)

    /** Numeric dotted comparison — "1.0" and "1" are equal, "1.0" is older than "1.1". */
    private fun isVersionOlder(current: String, minimum: String): Boolean {
        val a = current.trim().split('.')
        val b = minimum.trim().split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x < y
        }
        return false
    }

    private fun deriveState(context: Context, payload: TokenPayload): LicenseState {
        val now = System.currentTimeMillis()

        // 1. Force update check
        val requiredName = payload.minvName
        if (!requiredName.isNullOrBlank()) {
            if (isVersionOlder(BuildConfig.VERSION_NAME, requiredName)) {
                return LicenseState.NeedUpdate(requiredName)
            }
        } else if (payload.minv > BuildConfig.VERSION_CODE) {
            return LicenseState.NeedUpdate(payload.minv.toString())
        }

        // 2. Clock tamper check
        if (ClockGuard.check(context) == ClockGuard.Result.TAMPER) {
            return LicenseState.ClockTamper
        }

        // 3. Lifetime (owner) — unlimited, and never shows an expiry reminder.
        if (payload.st == "owner") {
            return LicenseState.Active(
                daysLeft = LIFETIME_DAYS,
                showReminder = false,
                msLeft = Long.MAX_VALUE,
                lifetime = true
            )
        }

        // 4. Token expiry (includes the grace window issued by the server)
        if (now > payload.exp) {
            return LicenseState.Expired
        }

        // 5. Trial
        if (payload.st == "trial") {
            val msLeft = payload.tre - now
            // A trial can be shorter than a day, so the remaining milliseconds decide — not whole days.
            if (msLeft <= 0) return LicenseState.Expired
            return LicenseState.Trial(
                daysLeft = ceilDays(msLeft),
                showBanner = msLeft <= 3 * DAY_MS,
                msLeft = msLeft
            )
        }

        // 6. Paid
        if (payload.st == "paid") {
            val msLeft = payload.pu - now
            return when {
                msLeft <= 0 -> {
                    // Grace lasts until the token itself expires (grace_days comes from the server).
                    val graceMs = payload.exp - now
                    if (graceMs > 0) {
                        LicenseState.Grace(ceilDays(graceMs), graceMs)
                    } else LicenseState.Expired
                }
                else -> {
                    // The owner sets how far before expiry the reminder starts (days/hours/minutes).
                    val notice = LicenseStore.getExpiryNotice(context)
                    LicenseState.Active(
                        daysLeft = ceilDays(msLeft),
                        showReminder = notice.enabled && msLeft <= notice.thresholdMs,
                        msLeft = msLeft,
                        lifetime = false
                    )
                }
            }
        }

        return LicenseState.Expired
    }

    fun getCurrentTosVersion(): Int = currentTosVersion

    /** Called after user successfully subscribes to update state immediately */
    fun onSubscriptionApproved(context: Context, newToken: String) {
        scope.launch {
            val payload = LicenseToken.verify(newToken) ?: return@launch
            if (payload.did != DeviceId.get(context)) return@launch
            val stored = LicenseStore.load(context)
            if (stored == null) {
                // No local license file (e.g. data cleared mid-purchase): fetch a full token so the payment isn't lost.
                activateOnline(context)
                return@launch
            }
            LicenseStore.save(context, newToken, stored.tosAccepted, stored.tosVersion)
            applyPayload(context, payload)
            // Pull the next config right away (the campaign may have just been consumed).
            performSync(context, force = true)
        }
    }
}
