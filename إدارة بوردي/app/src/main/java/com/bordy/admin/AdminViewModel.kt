package com.bordy.admin

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bordy.admin.data.AccountRecord
import com.bordy.admin.data.AccountTransfer
import com.bordy.admin.data.AdminApi
import com.bordy.admin.data.AdminEvent
import com.bordy.admin.data.AdminOrder
import com.bordy.admin.data.AdminSettings
import com.bordy.admin.data.AdminStats
import com.bordy.admin.data.ApiException
import com.bordy.admin.data.CloudAccount
import com.bordy.admin.data.Device
import com.bordy.admin.data.DiscountCode
import com.bordy.admin.data.SessionExpiredException
import com.bordy.admin.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class AdminTab { DASHBOARD, DEVICES, ACCOUNTS, ORDERS, CODES, EVENTS, SETTINGS }

data class UiState(
    val checking: Boolean = true,
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val tab: AdminTab = AdminTab.DASHBOARD,
    val stats: AdminStats? = null,
    val devices: List<Device> = emptyList(),
    val accounts: List<CloudAccount> = emptyList(),
    val orders: List<AdminOrder> = emptyList(),
    val codes: List<DiscountCode> = emptyList(),
    val events: List<AdminEvent> = emptyList(),
    val settings: AdminSettings? = null,
    val deviceQuery: String = "",
    val deviceStatus: String = "",
    val eventQuery: String = "",
    val accountQuery: String = "",
    val accountStatus: String = "",
    val accountTransfers: List<AccountTransfer> = emptyList(),
    val transfersAccountId: String? = null,
    val records: List<AccountRecord> = emptyList(),
    val recordsAccountId: String? = null,
    val recordsEntityType: String = "",
    val proofOrderId: String? = null,
    val proofBytes: ByteArray? = null,
    val message: String? = null,
    val error: String? = null
)

class AdminViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val hasSession = SessionStore.hasSession(getApplication())
            if (!hasSession) {
                _state.value = _state.value.copy(checking = false, loggedIn = false)
            } else {
                // Verify the stored session against the server before showing the dashboard.
                try {
                    val settings = AdminApi.settings(getApplication())
                    _state.value = _state.value.copy(checking = false, loggedIn = true, settings = settings)
                    loadTab(AdminTab.DASHBOARD)
                } catch (_: SessionExpiredException) {
                    SessionStore.clear(getApplication())
                    _state.value = _state.value.copy(checking = false, loggedIn = false)
                } catch (e: Exception) {
                    // Offline but session may still be valid — keep the user in, show the error.
                    _state.value = _state.value.copy(
                        checking = false,
                        loggedIn = true,
                        error = e.message ?: "تعذر الاتصال بالسيرفر"
                    )
                }
            }
        }
    }

    fun login(secret: String, totp: String) {
        if (secret.isBlank() || totp.length != 6) {
            _state.value = _state.value.copy(error = "أدخل كلمة السر ورمز التحقق المكوّن من 6 أرقام")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                AdminApi.login(getApplication(), secret, totp)
                _state.value = _state.value.copy(loggedIn = true, loading = false)
                loadTab(_state.value.tab)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "تعذر الدخول"
                )
            }
        }
    }

    fun logout() {
        AdminApi.logout(getApplication())
        _state.value = UiState(checking = false, loggedIn = false)
    }

    fun sessionExpired() {
        SessionStore.clear(getApplication())
        _state.value = UiState(checking = false, loggedIn = false, error = "انتهت الجلسة، سجّل الدخول من جديد")
    }

    fun selectTab(tab: AdminTab) {
        if (_state.value.tab == tab) return
        _state.value = _state.value.copy(tab = tab)
        loadTab(tab)
    }

    fun setDeviceQuery(query: String) {
        _state.value = _state.value.copy(deviceQuery = query)
    }

    fun setDeviceStatus(status: String) {
        _state.value = _state.value.copy(deviceStatus = status)
        loadTab(AdminTab.DEVICES)
    }

    fun setEventQuery(query: String) {
        _state.value = _state.value.copy(eventQuery = query)
    }

    fun setAccountQuery(query: String) {
        _state.value = _state.value.copy(accountQuery = query)
    }

    fun setAccountStatus(status: String) {
        _state.value = _state.value.copy(accountStatus = status)
        loadTab(AdminTab.ACCOUNTS)
    }

    fun searchDevices() = loadTab(AdminTab.DEVICES)

    fun searchEvents() = loadTab(AdminTab.EVENTS)

    fun searchAccounts() = loadTab(AdminTab.ACCOUNTS)

    fun refresh() = loadTab(_state.value.tab)

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun loadTab(tab: AdminTab) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                fetch(tab)
                _state.value = _state.value.copy(loading = false)
            } catch (_: SessionExpiredException) {
                sessionExpired()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "تعذر جلب البيانات"
                )
            }
        }
    }

    /** Refreshes the given tab without toggling the global spinner, for silent reloads. */
    private suspend fun fetch(tab: AdminTab) {
        val app = getApplication<Application>()
        when (tab) {
            AdminTab.DASHBOARD -> {
                val stats = AdminApi.stats(app)
                _state.value = _state.value.copy(stats = stats)
            }
            AdminTab.DEVICES -> {
                val list = AdminApi.devices(app, _state.value.deviceQuery, _state.value.deviceStatus)
                _state.value = _state.value.copy(devices = list)
            }
            AdminTab.ACCOUNTS -> {
                val list = AdminApi.accounts(app, _state.value.accountQuery, _state.value.accountStatus)
                _state.value = _state.value.copy(accounts = list)
            }
            AdminTab.ORDERS -> {
                val list = AdminApi.orders(app)
                _state.value = _state.value.copy(orders = list)
            }
            AdminTab.CODES -> {
                val list = AdminApi.codes(app)
                _state.value = _state.value.copy(codes = list)
            }
            AdminTab.EVENTS -> {
                val list = AdminApi.events(app, _state.value.eventQuery)
                _state.value = _state.value.copy(events = list)
            }
            AdminTab.SETTINGS -> {
                val settings = AdminApi.settings(app)
                _state.value = _state.value.copy(settings = settings)
            }
        }
    }

    // ── Device actions ───────────────────────────────────────────────────────

    fun blockDevice(device: Device, reason: String) = runAction {
        AdminApi.blockDevice(getApplication(), device.id, reason)
        "تم حظر ${device.name ?: device.id.take(8)}"
    }

    fun unblockDevice(device: Device) = runAction {
        AdminApi.unblockDevice(getApplication(), device.id)
        "تم فك الحظر عن ${device.name ?: device.id.take(8)}"
    }

    fun extendDevice(device: Device, days: Int) = runAction {
        AdminApi.extendDevice(getApplication(), device.id, days)
        "تمت إضافة $days يوم"
    }

    fun setLifetime(device: Device, on: Boolean) = runAction {
        AdminApi.setLifetime(getApplication(), device.id, on)
        if (on) "تم تفعيل اشتراك مدى الحياة" else "تم إلغاء مدى الحياة"
    }

    fun resetTrial(device: Device, seconds: Int) = runAction {
        AdminApi.resetTrial(getApplication(), device.id, seconds)
        if (seconds == 0) "تم تصفير التجربة" else "أُعيد ضبط التجربة"
    }

    fun zeroPaid(device: Device) = runAction {
        AdminApi.zeroPaid(getApplication(), device.id)
        "تم تصفير الاشتراك المدفوع"
    }

    fun zeroTrial(device: Device) = runAction {
        AdminApi.zeroTrial(getApplication(), device.id)
        "تم تصفير التجربة المجانية"
    }

    fun deleteDevice(device: Device) = runAction {
        AdminApi.deleteDevice(getApplication(), device.id)
        "تم حذف المشترك نهائياً"
    }

    fun updateDevice(
        device: Device,
        name: String,
        phone: String,
        notes: String,
        paidUntil: Long,
        trialEndsAt: Long,
        lifetime: Boolean
    ) = runAction {
        AdminApi.updateDevice(getApplication(), device, name, phone, notes, paidUntil, trialEndsAt, lifetime)
        "تم حفظ بيانات المشترك"
    }

    // ── Orders ───────────────────────────────────────────────────────────────

    fun approveOrder(order: AdminOrder) = runAction {
        AdminApi.approveOrder(getApplication(), order.id)
        "تم اعتماد الطلب"
    }

    fun rejectOrder(order: AdminOrder, reason: String) = runAction {
        AdminApi.rejectOrder(getApplication(), order.id, reason)
        "تم رفض الطلب"
    }

    fun deleteOrder(order: AdminOrder) = runAction {
        AdminApi.deleteOrder(getApplication(), order.id)
        "تم حذف الطلب"
    }

    fun loadProof(order: AdminOrder) {
        viewModelScope.launch {
            _state.value = _state.value.copy(proofOrderId = order.id, proofBytes = null)
            try {
                val bytes = AdminApi.orderProof(getApplication(), order.id)
                _state.value = _state.value.copy(proofBytes = bytes)
            } catch (_: SessionExpiredException) {
                sessionExpired()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    proofOrderId = null,
                    error = e.message ?: "تعذر تحميل صورة الإثبات"
                )
            }
        }
    }

    fun closeProof() {
        _state.value = _state.value.copy(proofOrderId = null, proofBytes = null)
    }

    // ── Cloud accounts ───────────────────────────────────────────────────────

    fun createAccount(username: String, password: String, trialDays: Int, lifetime: Boolean) = runAction {
        AdminApi.createAccount(getApplication(), username, password, trialDays, lifetime)
        "تم إنشاء الحساب $username"
    }

    fun setAccountState(account: CloudAccount, status: String) = runAction {
        AdminApi.setAccountState(getApplication(), account.id, status)
        "حالة الحساب الآن: $status"
    }

    fun setAccountLicense(account: CloudAccount, status: String, trialDays: Int, paidDays: Int) = runAction {
        AdminApi.setAccountLicense(getApplication(), account.id, status, trialDays, paidDays)
        "تم تحديث ترخيص الحساب"
    }

    fun loadTransfers(accountId: String) {
        viewModelScope.launch {
            try {
                val list = AdminApi.accountTransfers(getApplication(), accountId)
                _state.value = _state.value.copy(accountTransfers = list, transfersAccountId = accountId)
            } catch (_: SessionExpiredException) {
                sessionExpired()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "تعذر جلب طلبات النقل")
            }
        }
    }

    fun reviewTransfer(account: CloudAccount, transfer: AccountTransfer, approve: Boolean) = runAction {
        AdminApi.reviewTransfer(getApplication(), account.id, transfer.id, approve)
        if (approve) "تمت الموافقة على نقل الجهاز" else "تم رفض طلب النقل"
    }

    fun loadRecords(accountId: String, entityType: String = "") {
        viewModelScope.launch {
            try {
                val list = AdminApi.accountRecords(getApplication(), accountId, entityType)
                _state.value = _state.value.copy(
                    records = list,
                    recordsAccountId = accountId,
                    recordsEntityType = entityType
                )
            } catch (_: SessionExpiredException) {
                sessionExpired()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "تعذر جلب السجلات")
            }
        }
    }

    fun saveRecord(accountId: String, record: AccountRecord, payload: String) = runRecordAction(accountId) {
        val text = payload.trim()
        if (text.isEmpty()) throw ApiException("JSON فارغ")
        try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ApiException("JSON غير صالح")
        }
        AdminApi.mutateAccountRecord(getApplication(), accountId, record, text)
        "حُفظ السجل وسيصل إلى الجهاز في المزامنة التالية"
    }

    fun deleteRecord(accountId: String, record: AccountRecord) = runRecordAction(accountId) {
        AdminApi.mutateAccountRecord(getApplication(), accountId, record, null)
        "سُجّل حذف السجل في المزامنة"
    }

    fun exportAccount(accountId: String) = runAction {
        val text = AdminApi.exportAccount(getApplication(), accountId)
        copyToClipboard("bordy-account-$accountId.json", text)
        "نُسخ تصدير الحساب إلى الحافظة"
    }

    private fun runRecordAction(accountId: String, block: suspend () -> String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, message = null)
            try {
                val message = block()
                val list = AdminApi.accountRecords(getApplication(), accountId, _state.value.recordsEntityType)
                _state.value = _state.value.copy(loading = false, message = message, records = list)
            } catch (_: SessionExpiredException) {
                sessionExpired()
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "تعذر تنفيذ الإجراء")
            }
        }
    }

    // ── Codes ────────────────────────────────────────────────────────────────

    fun createCode(code: String, kind: String, value: Int, days: Int, maxUses: Int?, perDevice: Int, plans: String, autoApply: Boolean) = runAction {
        AdminApi.createCode(getApplication(), code, kind, value, days, maxUses, perDevice, plans, autoApply)
        "تم إنشاء الكود"
    }

    fun toggleCode(code: DiscountCode) = runAction {
        AdminApi.toggleCode(getApplication(), code.code)
        if (code.active) "تم إيقاف الكود" else "تم تفعيل الكود"
    }

    fun deleteCode(code: DiscountCode) = runAction {
        AdminApi.deleteCode(getApplication(), code.code)
        "تم حذف الكود"
    }

    // ── Events / maintenance ─────────────────────────────────────────────────

    fun purgeEvents(days: Int) = runAction {
        val deleted = AdminApi.purgeEvents(getApplication(), days)
        "حُذف $deleted حدثاً أقدم من $days يوم"
    }

    fun wipeEvents() = runAction {
        val deleted = AdminApi.wipeEvents(getApplication())
        "مُسح السجل بالكامل ($deleted حدثاً)"
    }

    fun resetRateLimits() = runAction {
        val deleted = AdminApi.resetRateLimits(getApplication())
        "صُفّرت حدود المعدل ($deleted عدّاداً)"
    }

    fun exportCsv() = runAction {
        val csv = AdminApi.exportCsv(getApplication())
        copyToClipboard("bordy-devices.csv", csv)
        "نُسخ CSV إلى الحافظة (${(csv.lines().size - 1).coerceAtLeast(0)} مشتركاً)"
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun saveSettings(body: JSONObject) = runAction {
        AdminApi.updateSettings(getApplication(), body)
        "تم حفظ الإعدادات — ستظهر في تطبيق بوردي خلال ثوانٍ"
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun runAction(block: suspend () -> String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, message = null)
            try {
                val message = block()
                _state.value = _state.value.copy(loading = false, message = message)
                reloadCurrent()
            } catch (_: SessionExpiredException) {
                sessionExpired()
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "تعذر تنفيذ الإجراء")
            }
        }
    }

    private fun reloadCurrent() {
        val tab = _state.value.tab
        viewModelScope.launch {
            try {
                fetch(tab)
            } catch (_: Exception) {
                // The action already succeeded; a failed silent refresh must not scare the owner.
            }
        }
    }
}
