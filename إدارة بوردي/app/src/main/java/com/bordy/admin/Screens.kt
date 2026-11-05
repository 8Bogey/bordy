package com.bordy.admin

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bordy.admin.data.AccountRecord
import com.bordy.admin.data.AccountTransfer
import com.bordy.admin.data.AdminEvent
import com.bordy.admin.data.AdminOrder
import com.bordy.admin.data.AdminSettings
import com.bordy.admin.data.CloudAccount
import com.bordy.admin.data.Device
import com.bordy.admin.data.DiscountCode
import com.bordy.admin.ui.Amber
import com.bordy.admin.ui.Border
import com.bordy.admin.ui.Danger
import com.bordy.admin.ui.DangerSoft
import com.bordy.admin.ui.Green
import com.bordy.admin.ui.Mint
import com.bordy.admin.ui.Primary
import com.bordy.admin.ui.Surface as SurfaceColor
import com.bordy.admin.ui.SurfaceAlt
import com.bordy.admin.ui.TextMain
import com.bordy.admin.ui.TextMuted
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BAGHDAD = ZoneId.of("Asia/Baghdad")
private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatDate(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    return Instant.ofEpochMilli(ms).atZone(BAGHDAD).format(DATE_FMT)
}

private val DATE_INPUT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun msToInput(ms: Long?): String =
    if (ms == null || ms <= 0) "" else Instant.ofEpochMilli(ms).atZone(BAGHDAD).format(DATE_INPUT_FMT)

/** Empty means "clear" (0). Returns null only when the text is not a valid Baghdad date. */
private fun inputToMs(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0L
    return try {
        LocalDateTime.parse(trimmed.replace('T', ' '), DATE_INPUT_FMT)
            .atZone(BAGHDAD).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

// ─── Dashboard ──────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(state: UiState, vm: AdminViewModel) {
    val stats = state.stats
    if (stats == null) {
        if (state.loading) LoadingBox() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Muted("تعذر تحميل الإحصاءات — اضغط زر التحديث في الأعلى")
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("مشترك فعال", stats.activeSubs.toString(), Mint, Modifier.weight(1f))
            StatCard("تجريبي نشط", stats.trials.toString(), Amber, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("ينتهون خلال 7 أيام", stats.expiringIn7.toString(), Amber, Modifier.weight(1f))
            StatCard("منتهي", stats.expired.toString(), DangerSoft, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("إيرادات الشهر (د.ع)", "%,d".format(stats.monthlyRevenue), Primary, Modifier.weight(1f))
            StatCard("طلبات معلقة", stats.pendingOrders.toString(), Amber, Modifier.weight(1f))
        }

        PanelCard {
            Text("⚡ إجراءات سريعة", style = MaterialTheme.typography.titleMedium, color = Primary)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("تصدير CSV", Mint) { vm.exportCsv() }
                SmallButton("تصفير حدود المعدل", Amber) { vm.resetRateLimits() }
                SmallButton("تحديث", TextMain) { vm.refresh() }
            }
        }

        PanelCard {
            Text("ℹ️ ملاحظات", style = MaterialTheme.typography.titleMedium, color = Primary)
            Spacer(Modifier.height(8.dp))
            Muted("التحديث التلقائي كل 30 ثانية. اشتراك مدى الحياة يظهر ضمن المشتركين الفعّالين، والتجربة التي انتهت بلا اشتراك مدفوع تُحسب منتهية.")
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = SurfaceColor,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = modifier
    ) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ─── Shared building blocks ─────────────────────────────────────────────────

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Surface(
        color = SurfaceColor,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, color = TextMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
        Text(value, color = TextMain, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primary)
    }
}

private fun deviceStatus(device: Device, now: Long = System.currentTimeMillis()): Pair<String, Color> = when {
    device.blocked -> "محظور" to DangerSoft
    device.lifetime -> "مدى الحياة" to Mint
    device.paidUntil > now -> "مشترك" to Mint
    (device.trialEndsAt ?: 0L) > now -> "تجريبي" to Amber
    else -> "منتهي" to DangerSoft
}

private fun orderStatus(status: String): Pair<String, Color> = when (status) {
    "APPROVED" -> "معتمد" to Mint
    "REJECTED" -> "مرفوض" to DangerSoft
    "EXPIRED" -> "منتهي" to TextMuted
    "CANCELLED" -> "ملغي" to TextMuted
    "SUBMITTED" -> "بانتظار المراجعة" to Amber
    else -> "معلق" to Primary
}

private fun codeKindLabel(kind: String, value: Int): String = when (kind) {
    "PERCENT" -> "$value%"
    "FIXED" -> "$value د.ع"
    "FREE_DAYS" -> "$value يوم"
    "FULL" -> "كامل"
    else -> kind
}

// ─── Devices ────────────────────────────────────────────────────────────────

@Composable
fun DevicesScreen(state: UiState, vm: AdminViewModel) {
    var editTarget by remember { mutableStateOf<Device?>(null) }
    var blockTarget by remember { mutableStateOf<Device?>(null) }
    var deleteTarget by remember { mutableStateOf<Device?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.deviceQuery,
                onValueChange = vm::setDeviceQuery,
                placeholder = { Text("بحث بالمعرف أو الاسم أو الرقم…", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { vm.searchDevices() }) {
                Icon(Icons.Default.Search, contentDescription = "بحث", tint = Mint)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "" to "الكل",
                "active" to "مشترك",
                "trial" to "تجريبي",
                "expired" to "منتهي",
                "blocked" to "محظور"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = state.deviceStatus == value,
                    onClick = { vm.setDeviceStatus(value) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Primary.copy(alpha = 0.25f),
                        selectedLabelColor = Color.White,
                        labelColor = TextMuted
                    )
                )
            }
        }

        if (state.loading && state.devices.isEmpty()) {
            LoadingBox()
        } else if (state.devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Muted("لا يوجد مشتركون مطابقون")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            onBlock = { blockTarget = device },
                            onUnblock = { vm.unblockDevice(device) },
                            onExtend = { vm.extendDevice(device, 30) },
                            onLifetime = { vm.setLifetime(device, !device.lifetime) },
                            onResetTrial = { vm.resetTrial(device, 7 * 86400) },
                            onZeroPaid = { vm.zeroPaid(device) },
                            onZeroTrial = { vm.zeroTrial(device) },
                            onEdit = { editTarget = device },
                            onDelete = { deleteTarget = device }
                        )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    editTarget?.let { device ->
        DeviceEditDialog(device, onDismiss = { editTarget = null }) { name, phone, notes, paidUntil, trialEndsAt, lifetime ->
            vm.updateDevice(device, name, phone, notes, paidUntil, trialEndsAt, lifetime)
            editTarget = null
        }
    }

    blockTarget?.let { device ->
        ReasonDialog(
            title = "حظر ${device.name ?: device.id.take(8)}",
            hint = "سبب الحظر (يظهر للمستخدم)",
            defaultValue = "محظور من الإدارة",
            onDismiss = { blockTarget = null }
        ) { reason ->
            vm.blockDevice(device, reason)
            blockTarget = null
        }
    }

    deleteTarget?.let { device ->
        ConfirmDialog(
            title = "حذف المشترك؟",
            message = "سيُحذف ${device.name ?: device.id.take(10)} مع طلباته وسجله، ويعود كجهاز جديد عند فتح التطبيق. لا يمكن التراجع.",
            confirmText = "حذف نهائي",
            danger = true,
            onDismiss = { deleteTarget = null }
        ) {
            vm.deleteDevice(device)
            deleteTarget = null
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onExtend: () -> Unit,
    onLifetime: () -> Unit,
    onResetTrial: () -> Unit,
    onZeroPaid: () -> Unit,
    onZeroTrial: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val (status, statusColor) = deviceStatus(device)
    val context = LocalContext.current
    PanelCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    device.name ?: "جهاز بدون اسم",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    device.id,
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusBadge(status, statusColor)
        }

        Spacer(Modifier.height(8.dp))
        InfoRow("مدفوع حتى", if (device.lifetime) "مدى الحياة" else formatDate(device.paidUntil))
        InfoRow("نهاية التجربة", formatDate(device.trialEndsAt))
        InfoRow("الموديل", listOfNotNull(device.model, device.appVer?.let { "v$it" }).joinToString(" · ").ifBlank { "—" })
        InfoRow("أول ظهور", formatDate(device.firstSeen))
        InfoRow("آخر ظهور", formatDate(device.lastSeen))
        InfoRow("انتقالات مستخدمة", device.transfersUsed.toString())
        if (!device.phone.isNullOrBlank()) InfoRow("الهاتف", device.phone)
        if (device.blocked && !device.blockReason.isNullOrBlank()) InfoRow("سبب الحظر", device.blockReason)

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallButton("نسخ المعرف", TextMuted) {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("device_id", device.id))
            }
            SmallButton(if (device.blocked) "فك الحظر" else "حظر", if (device.blocked) Mint else DangerSoft) {
                if (device.blocked) onUnblock() else onBlock()
            }
            SmallButton("+30 يوم", Mint, onExtend)
            SmallButton(if (device.lifetime) "إلغاء مدى الحياة" else "مدى الحياة", Primary, onLifetime)
            SmallButton("إعادة التجربة", Amber, onResetTrial)
            SmallButton("تصفير المدفوع", TextMuted, onZeroPaid)
            SmallButton("تصفير التجربة", TextMuted, onZeroTrial)
            SmallButton("تعديل", TextMain, onEdit)
            SmallButton("حذف", DangerSoft, onDelete)
        }
    }
}

@Composable
private fun SmallButton(label: String, tint: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DeviceEditDialog(
    device: Device,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Long, Long, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(device.name ?: "") }
    var phone by remember { mutableStateOf(device.phone ?: "") }
    var notes by remember { mutableStateOf(device.notes ?: "") }
    var paidText by remember { mutableStateOf(msToInput(device.paidUntil)) }
    var trialText by remember { mutableStateOf(msToInput(device.trialEndsAt)) }
    var lifetime by remember { mutableStateOf(device.lifetime) }
    var validation by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("تعديل المشترك", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("الهاتف") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("مدى الحياة", color = TextMain, style = MaterialTheme.typography.titleSmall)
                        Muted("تفعيله يمنح ترخيصاً دائماً، والاشتراك المدفوع يستمر بالنقصان في الخلفية.")
                    }
                    Switch(
                        checked = lifetime,
                        onCheckedChange = { lifetime = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Green)
                    )
                }

                Text(
                    "الاشتراك المدفوع — مدفوع حتى (بغداد)",
                    color = TextMuted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = paidText,
                        onValueChange = { paidText = it },
                        singleLine = true,
                        enabled = !lifetime,
                        placeholder = { Text("yyyy-MM-dd HH:mm", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { paidText = "" }) { Text("تصفير", color = Amber) }
                }
                Muted("اتركه فارغاً أو صفّره إن لم يكن هناك اشتراك مدفوع. التعديل هنا يغيّر التاريخ الفعلي.")

                Text(
                    "التجربة المجانية — تنتهي في (بغداد)",
                    color = TextMuted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = trialText,
                        onValueChange = { trialText = it },
                        singleLine = true,
                        placeholder = { Text("yyyy-MM-dd HH:mm", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { trialText = "" }) { Text("تصفير", color = Amber) }
                }
                Muted("آخر لحظة تُحسب فيها التجربة. التصفير = لا تجربة متبقية.")

                validation?.let {
                    Text(it, color = DangerSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }

                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إلغاء", color = TextMuted) }
                    Button(
                        onClick = {
                            val paid = inputToMs(paidText)
                            val trial = inputToMs(trialText)
                            if (paid == null) {
                                validation = "تاريخ الاشتراك غير صالح — استخدم yyyy-MM-dd HH:mm"
                                return@Button
                            }
                            if (trial == null) {
                                validation = "تاريخ نهاية التجربة غير صالح — استخدم yyyy-MM-dd HH:mm"
                                return@Button
                            }
                            onSave(name.trim(), phone.trim(), notes.trim(), paid, trial, lifetime)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("حفظ") }
                }
            }
        }
    }
}

// ─── Cloud accounts ─────────────────────────────────────────────────────────

private fun accountStatus(status: String): Pair<String, Color> = when (status) {
    "active" -> "فعال" to Mint
    "locked" -> "مقفل" to Amber
    "disabled" -> "معطل" to DangerSoft
    "pending" -> "قيد الإعداد" to Primary
    else -> status to TextMuted
}

private fun licenseLabel(account: CloudAccount): String = when {
    account.lifetime -> "مدى الحياة"
    account.licenseStatus == "trial" -> "تجربة"
    account.licenseStatus == "paid" -> "مدفوع"
    account.licenseStatus == "owner" -> "مالك"
    account.licenseStatus == "expired" -> "منتهي"
    else -> account.licenseStatus ?: "—"
}

@Composable
fun AccountsScreen(state: UiState, vm: AdminViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    val selected = state.accounts.firstOrNull { it.id == openId }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.accountQuery,
                onValueChange = vm::setAccountQuery,
                placeholder = { Text("بحث باسم المستخدم أو المعرف أو الجهاز…", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.searchAccounts() }) {
                Icon(Icons.Default.Search, contentDescription = "بحث", tint = Mint)
            }
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "حساب جديد", tint = Mint)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "" to "الكل",
                "active" to "فعال",
                "locked" to "مقفل",
                "disabled" to "معطل",
                "pending" to "قيد الإعداد"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = state.accountStatus == value,
                    onClick = { vm.setAccountStatus(value) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Primary.copy(alpha = 0.25f),
                        selectedLabelColor = Color.White,
                        labelColor = TextMuted
                    )
                )
            }
        }

        if (state.loading && state.accounts.isEmpty()) {
            LoadingBox()
        } else if (state.accounts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("لا توجد حسابات سحابية") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.accounts, key = { it.id }) { account ->
                    AccountCard(account) { openId = account.id }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (showCreate) {
        CreateAccountDialog(onDismiss = { showCreate = false }) { username, password, trialDays, lifetime ->
            vm.createAccount(username, password, trialDays, lifetime)
            showCreate = false
        }
    }

    selected?.let { account ->
        AccountDialog(account, state, vm, onDismiss = { openId = null })
    }
}

@Composable
private fun AccountCard(account: CloudAccount, onOpen: () -> Unit) {
    val (statusText, statusColor) = accountStatus(account.status)
    val warnings = account.conflictCount + account.transferCount
    PanelCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(account.username, style = MaterialTheme.typography.titleSmall, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(account.id, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusBadge(statusText, statusColor)
        }
        Spacer(Modifier.height(8.dp))
        InfoRow("الترخيص", licenseLabel(account))
        if (account.paidUntil > 0) InfoRow("مدفوع حتى", formatDate(account.paidUntil))
        if (account.trialEndsAt > 0) InfoRow("التجربة", formatDate(account.trialEndsAt))
        InfoRow("الجهاز النشط", account.deviceName ?: account.deviceStatus ?: "—")
        InfoRow("السجلات", account.recordCount.toString())
        InfoRow("تعارضات ونقل", if (warnings > 0) "$warnings" else "0")
        InfoRow("آخر ظهور", formatDate(account.lastSeenAt))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            SmallButton("فتح الحساب", Primary, onOpen)
        }
    }
}

@Composable
private fun CreateAccountDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Int, Boolean) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var trialDays by remember { mutableStateOf("0") }
    var lifetime by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("حساب سحابي جديد", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("كلمة المرور") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = trialDays,
                    onValueChange = { trialDays = it.filter { c -> c.isDigit() } },
                    label = { Text("أيام التجربة (0 = بلا تجربة)") },
                    singleLine = true,
                    enabled = !lifetime,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = lifetime,
                        onCheckedChange = { lifetime = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Green)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("ترخيص مدى الحياة", style = MaterialTheme.typography.bodySmall, color = TextMain)
                }
                validation?.let {
                    Text(it, color = DangerSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إلغاء", color = TextMuted) }
                    Button(
                        onClick = {
                            if (username.isBlank()) { validation = "أدخل اسم المستخدم"; return@Button }
                            if (password.length < 8) { validation = "كلمة المرور 8 أحرف على الأقل"; return@Button }
                            onCreate(username.trim(), password, (trialDays.toIntOrNull() ?: 0).coerceIn(0, 3650), lifetime)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("إنشاء") }
                }
            }
        }
    }
}

@Composable
private fun AccountDialog(
    account: CloudAccount,
    state: UiState,
    vm: AdminViewModel,
    onDismiss: () -> Unit
) {
    var days by remember { mutableStateOf("7") }
    var confirmState by remember { mutableStateOf<String?>(null) }
    var showRecords by remember { mutableStateOf(false) }

    LaunchedEffect(account.id) { vm.loadTransfers(account.id) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("الحساب: ${account.username}", style = MaterialTheme.typography.titleMedium, color = Primary)
                        Text(account.id, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    StatusBadge(accountStatus(account.status).first, accountStatus(account.status).second)
                }

                Spacer(Modifier.height(10.dp))
                InfoRow("الترخيص", licenseLabel(account))
                if (account.paidUntil > 0) InfoRow("مدفوع حتى", formatDate(account.paidUntil))
                if (account.trialEndsAt > 0) InfoRow("التجربة حتى", formatDate(account.trialEndsAt))
                InfoRow("السجلات", account.recordCount.toString())
                InfoRow("التعارضات", account.conflictCount.toString())
                InfoRow("طلبات النقل", account.transferCount.toString())
                InfoRow("آخر ظهور", formatDate(account.lastSeenAt))

                Text("حالة الحساب", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("تفعيل", Mint) { confirmState = "active" }
                    SmallButton("قفل", Amber) { confirmState = "locked" }
                    SmallButton("تعطيل", DangerSoft) { confirmState = "disabled" }
                }

                Text("الترخيص", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it.filter { c -> c.isDigit() } },
                        label = { Text("أيام") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val d = (days.toIntOrNull() ?: 7).coerceIn(1, 3650)
                        SmallButton("تجربة", Amber) { vm.setAccountLicense(account, "trial", d, 0) }
                        SmallButton("مدفوع", Mint) { vm.setAccountLicense(account, "paid", 0, d) }
                        SmallButton("مدى الحياة", Primary) { vm.setAccountLicense(account, "owner", 0, 0) }
                        SmallButton("إنهاء", DangerSoft) { vm.setAccountLicense(account, "expired", 0, 0) }
                    }
                }

                Text("طلبات نقل الجهاز", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
                val transfers = if (state.transfersAccountId == account.id) state.accountTransfers else emptyList()
                if (transfers.isEmpty()) {
                    Muted("لا توجد طلبات نقل")
                } else {
                    transfers.forEach { transfer ->
                        TransferRow(transfer, onApprove = { vm.reviewTransfer(account, transfer, true) }, onReject = { vm.reviewTransfer(account, transfer, false) })
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إغلاق", color = TextMuted) }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SmallButton("سجلات البيانات", Primary) { showRecords = true }
                    SmallButton("نسخ تصدير JSON", Mint) { vm.exportAccount(account.id) }
                }
            }
        }
    }

    if (showRecords) {
        RecordsDialog(account, state, vm, onDismiss = { showRecords = false })
    }

    confirmState?.let { status ->
        val label = when (status) {
            "active" -> "تفعيل"
            "locked" -> "قفل"
            else -> "تعطيل"
        }
        ConfirmDialog(
            title = "$label الحساب؟",
            message = "سيتغير إلى «$label» وتُلغى جلساته الحالية.",
            confirmText = label,
            danger = status != "active",
            onDismiss = { confirmState = null }
        ) {
            vm.setAccountState(account, status)
            confirmState = null
        }
    }
}

@Composable
private fun TransferRow(transfer: AccountTransfer, onApprove: () -> Unit, onReject: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(transfer.status, color = if (transfer.status == "requested") Amber else TextMuted, style = MaterialTheme.typography.bodySmall)
            Text(formatDate(transfer.requestedAt), color = TextMuted, fontSize = 10.sp)
        }
        if (transfer.status == "requested") {
            SmallButton("موافقة", Mint, onApprove)
            Spacer(Modifier.width(6.dp))
            SmallButton("رفض", DangerSoft, onReject)
        }
    }
}

private val ENTITY_TYPES = listOf(
    "" to "كل الأنواع",
    "suppliers" to "الموردون",
    "components" to "القطع",
    "tags" to "الوسوم",
    "component_tags" to "وسوم القطع",
    "component_variants" to "الأحجام",
    "supplier_offers" to "عروض الموردين",
    "cart_items" to "السلة",
    "templates" to "القوالب",
    "template_items" to "قطع القوالب",
    "projects" to "المشاريع",
    "project_items" to "قطع المشاريع",
    "project_notes" to "ملاحظات المشاريع",
    "business_settings" to "إعدادات النشاط"
)

@Composable
private fun RecordsDialog(
    account: CloudAccount,
    state: UiState,
    vm: AdminViewModel,
    onDismiss: () -> Unit
) {
    var typeMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountRecord?>(null) }
    var deleting by remember { mutableStateOf<AccountRecord?>(null) }
    val typeLabel = ENTITY_TYPES.firstOrNull { it.first == state.recordsEntityType }?.second ?: "كل الأنواع"
    val records = if (state.recordsAccountId == account.id) state.records else emptyList()

    LaunchedEffect(account.id) {
        if (state.recordsAccountId != account.id) vm.loadRecords(account.id, "")
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("سجلات ${account.username}", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { typeMenu = true }) { Text(typeLabel) }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            ENTITY_TYPES.forEach { (type, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        typeMenu = false
                                        vm.loadRecords(account.id, type)
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Muted("${records.size} سجل")
                }

                Spacer(Modifier.height(8.dp))
                if (records.isEmpty()) {
                    Muted("لا توجد بيانات من هذا النوع")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(records, key = { it.entityType + "/" + it.entityId }) { record ->
                            PanelCard {
                                Text(record.entityType, color = Primary, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    record.entityId,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("مراجعة ${record.revision} · ${formatDate(record.updatedAt)}", color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    SmallButton("تعديل", Mint) { editing = record }
                                    Spacer(Modifier.width(4.dp))
                                    SmallButton("حذف", DangerSoft) { deleting = record }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إغلاق", color = TextMuted) }
                }
            }
        }
    }

    editing?.let { record ->
        RecordEditorDialog(record, onDismiss = { editing = null }) { payload ->
            vm.saveRecord(account.id, record, payload)
            editing = null
        }
    }

    deleting?.let { record ->
        ConfirmDialog(
            title = "حذف السجل؟",
            message = "سيُسجَّل حذف ${record.entityType} / ${record.entityId} ويصل إلى الجهاز في المزامنة التالية.",
            confirmText = "حذف",
            danger = true,
            onDismiss = { deleting = null }
        ) {
            vm.deleteRecord(account.id, record)
            deleting = null
        }
    }
}

@Composable
private fun RecordEditorDialog(record: AccountRecord, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var payload by remember(record) { mutableStateOf(record.payload) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "تعديل ${record.entityType}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Primary
                )
                Text(
                    record.entityId,
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    minLines = 8,
                    maxLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Muted("المراجعة ${record.revision}. الحفظ يرسل تعديلاً صارماً؛ إذا تغيّر السجل على الجهاز سيُرفض ويظهر السبب.")
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إلغاء", color = TextMuted) }
                    Button(
                        onClick = { onSave(payload) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("حفظ") }
                }
            }
        }
    }
}

// ─── Orders ─────────────────────────────────────────────────────────────────

@Composable
fun OrdersScreen(state: UiState, vm: AdminViewModel) {
    var approveTarget by remember { mutableStateOf<AdminOrder?>(null) }
    var rejectTarget by remember { mutableStateOf<AdminOrder?>(null) }
    var deleteTarget by remember { mutableStateOf<AdminOrder?>(null) }

    if (state.loading && state.orders.isEmpty()) {
        LoadingBox()
        return
    }
    if (state.orders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("لا توجد طلبات") }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.orders, key = { it.id }) { order ->
            val (statusText, statusColor) = orderStatus(order.status)
            val actionable = order.status == "PENDING" || order.status == "SUBMITTED"
            PanelCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("طلب ${order.planId}", style = MaterialTheme.typography.titleSmall, color = TextMain)
                        Text(
                            order.deviceId,
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusBadge(statusText, statusColor)
                }
                Spacer(Modifier.height(8.dp))
                InfoRow("المبلغ", "${order.finalPrice} د.ع")
                InfoRow("الدفع", listOfNotNull(order.method, order.txnRef).joinToString(" · ").ifBlank { "—" })
                if (!order.payerName.isNullOrBlank()) InfoRow("الدافع", listOfNotNull(order.payerName, order.payerPhone).joinToString(" · "))
                InfoRow("التاريخ", formatDate(order.createdAt))
                if (order.hasProof) InfoRow("الإشعار", "مرفق صورة إثبات ✅")
                if (!order.rejectReason.isNullOrBlank()) InfoRow("سبب الرفض", order.rejectReason)

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (actionable) {
                        SmallButton("اعتماد", Mint) { approveTarget = order }
                        SmallButton("رفض", DangerSoft) { rejectTarget = order }
                    }
                    if (order.hasProof) {
                        SmallButton("عرض الإثبات", Primary) { vm.loadProof(order) }
                    }
                    SmallButton("حذف", TextMuted) { deleteTarget = order }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    approveTarget?.let { order ->
        ConfirmDialog(
            title = "اعتماد الطلب؟",
            message = "سيُفعّل الاشتراك فوراً على جهاز المشترك (${order.finalPrice} د.ع).",
            confirmText = "اعتماد",
            danger = false,
            onDismiss = { approveTarget = null }
        ) {
            vm.approveOrder(order)
            approveTarget = null
        }
    }

    rejectTarget?.let { order ->
        ReasonDialog(
            title = "رفض الطلب",
            hint = "سبب الرفض (يظهر للمشترك)",
            defaultValue = "تعذر التحقق من الدفع",
            onDismiss = { rejectTarget = null }
        ) { reason ->
            vm.rejectOrder(order, reason)
            rejectTarget = null
        }
    }

    deleteTarget?.let { order ->
        ConfirmDialog(
            title = "حذف الطلب؟",
            message = "سيُحذف الطلب وإثباته نهائياً.",
            confirmText = "حذف",
            danger = true,
            onDismiss = { deleteTarget = null }
        ) {
            vm.deleteOrder(order)
            deleteTarget = null
        }
    }

    if (state.proofOrderId != null) {
        ProofDialog(bytes = state.proofBytes, onDismiss = vm::closeProof)
    }
}

@Composable
private fun ProofDialog(bytes: ByteArray?, onDismiss: () -> Unit) {
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("إثبات الدفع", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(8.dp))
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "صورة إثبات الدفع",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إغلاق", color = TextMuted) }
                }
            }
        }
    }
}

// ─── Codes ──────────────────────────────────────────────────────────────────

@Composable
fun CodesScreen(state: UiState, vm: AdminViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DiscountCode?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (state.loading && state.codes.isEmpty()) {
            LoadingBox()
        } else if (state.codes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("لا توجد أكواد") }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.codes, key = { it.code }) { code ->
                    PanelCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(code.code, style = MaterialTheme.typography.titleSmall, color = TextMain, fontFamily = FontFamily.Monospace)
                                Text(
                                    "${codeKindLabel(code.kind, code.value)} · ${code.plans}",
                                    color = TextMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            StatusBadge(if (code.active) "فعال" else "موقوف", if (code.active) Mint else TextMuted)
                        }
                        Spacer(Modifier.height(8.dp))
                        InfoRow("الاستخدام", "${code.usedCount}" + (code.maxUses?.let { " / $it" } ?: " / غير محدود"))
                        InfoRow("لكل جهاز", "${code.perDeviceLimit}")
                        InfoRow("ينتهي", formatDate(code.endsAt))
                        InfoRow("تلقائي", if (code.autoApply) "نعم" else "لا")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallButton(if (code.active) "إيقاف" else "تفعيل", Amber) { vm.toggleCode(code) }
                            SmallButton("حذف", DangerSoft) { deleteTarget = code }
                        }
                    }
                }
                item { Spacer(Modifier.height(70.dp)) }
            }
        }

        Button(
            onClick = { showCreate = true },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("كود جديد", fontWeight = FontWeight.Bold)
        }
    }

    if (showCreate) {
        CreateCodeDialog(onDismiss = { showCreate = false }) { code, kind, value, days, maxUses, perDevice, plans, auto ->
            vm.createCode(code, kind, value, days, maxUses, perDevice, plans, auto)
            showCreate = false
        }
    }

    deleteTarget?.let { code ->
        ConfirmDialog(
            title = "حذف الكود؟",
            message = "سيُحذف ${code.code} مع سجل استخداماته.",
            confirmText = "حذف",
            danger = true,
            onDismiss = { deleteTarget = null }
        ) {
            vm.deleteCode(code)
            deleteTarget = null
        }
    }
}

@Composable
private fun CreateCodeDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Int, Int, Int?, Int, String, Boolean) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("PERCENT") }
    var kindMenu by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("10") }
    var days by remember { mutableStateOf("30") }
    var maxUses by remember { mutableStateOf("") }
    var perDevice by remember { mutableStateOf("1") }
    var plans by remember { mutableStateOf("*") }
    var autoApply by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("كود خصم جديد", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = code, onValueChange = { code = it.uppercase() }, label = { Text("الكود") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { kindMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (kind) {
                                "PERCENT" -> "نسبة مئوية %"
                                "FIXED" -> "مبلغ ثابت"
                                "FREE_DAYS" -> "أيام مجانية"
                                else -> "خصم كامل"
                            }
                        )
                    }
                    DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                        listOf(
                            "PERCENT" to "نسبة مئوية %",
                            "FIXED" to "مبلغ ثابت",
                            "FREE_DAYS" to "أيام مجانية",
                            "FULL" to "خصم كامل"
                        ).forEach { (k, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { kind = k; kindMenu = false })
                        }
                    }
                }
                OutlinedTextField(value = value, onValueChange = { value = it.filter { c -> c.isDigit() } }, label = { Text("القيمة") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = days, onValueChange = { days = it.filter { c -> c.isDigit() } }, label = { Text("صالح لكم يوم") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = maxUses, onValueChange = { maxUses = it.filter { c -> c.isDigit() } }, label = { Text("أقصى استخدام (فارغ = غير محدود)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = perDevice, onValueChange = { perDevice = it.filter { c -> c.isDigit() } }, label = { Text("لكل جهاز") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = plans, onValueChange = { plans = it }, label = { Text("الخطط (* = الكل)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = autoApply,
                        onCheckedChange = { autoApply = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Green)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("تطبيق تلقائي", style = MaterialTheme.typography.bodySmall, color = TextMain)
                }
                validation?.let {
                    Text(it, color = DangerSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إلغاء", color = TextMuted) }
                    Button(
                        onClick = {
                            val v = value.toIntOrNull()
                            val d = days.toIntOrNull()
                            if (code.isBlank()) { validation = "أدخل الكود"; return@Button }
                            if (v == null || v < 0) { validation = "قيمة غير صالحة"; return@Button }
                            if (d == null || d <= 0) { validation = "مدة غير صالحة"; return@Button }
                            onCreate(
                                code.trim(), kind, v, d,
                                maxUses.toIntOrNull(),
                                (perDevice.toIntOrNull() ?: 1).coerceAtLeast(1),
                                plans.trim().ifBlank { "*" },
                                autoApply
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("إنشاء") }
                }
            }
        }
    }
}

// ─── Events ─────────────────────────────────────────────────────────────────

@Composable
fun EventsScreen(state: UiState, vm: AdminViewModel) {
    var showPurge by remember { mutableStateOf(false) }
    var showWipe by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.eventQuery,
                onValueChange = vm::setEventQuery,
                placeholder = { Text("بحث بمعرف الجهاز…", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { vm.searchEvents() }) {
                Icon(Icons.Default.Search, contentDescription = "بحث", tint = Mint)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallButton("حذف الأقدم من (أيام)", Amber) { showPurge = true }
            SmallButton("مسح كل السجل", DangerSoft) { showWipe = true }
        }

        Spacer(Modifier.height(4.dp))
        if (state.loading && state.events.isEmpty()) {
            LoadingBox()
        } else if (state.events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("لا توجد أحداث") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.events, key = { it.id }) { event ->
                    PanelCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(event.type, Primary)
                            Spacer(Modifier.width(8.dp))
                            Text(formatDate(event.ts), color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        }
                        if (!event.deviceId.isNullOrBlank()) {
                            Text(event.deviceId, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        if (!event.detail.isNullOrBlank()) {
                            Text(event.detail, color = TextMain, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (showPurge) {
        PurgeEventsDialog(
            onDismiss = { showPurge = false },
            onConfirm = { days ->
                vm.purgeEvents(days)
                showPurge = false
            }
        )
    }

    if (showWipe) {
        ConfirmDialog(
            title = "مسح كل السجل؟",
            message = "سيُحذف سجل الأحداث بالكامل نهائياً. لا يمكن التراجع.",
            confirmText = "مسح الكل",
            danger = true,
            onDismiss = { showWipe = false }
        ) {
            vm.wipeEvents()
            showWipe = false
        }
    }
}

@Composable
private fun PurgeEventsDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var days by remember { mutableStateOf("30") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        title = { Text("حذف الأحداث الأقدم من", color = TextMain) },
        text = {
            Column {
                OutlinedTextField(
                    value = days,
                    onValueChange = { days = it.filter { c -> c.isDigit() } },
                    label = { Text("عدد الأيام") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Muted("حذف كل حدث أقدم من هذا العدد من الأيام. 0 = كل السجل.")
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm((days.toIntOrNull() ?: 30).coerceIn(0, 3650)) },
                colors = ButtonDefaults.buttonColors(containerColor = Danger)
            ) { Text("حذف") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = TextMuted) } }
    )
}

// ─── Settings ───────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(state: UiState, vm: AdminViewModel) {
    val settings = state.settings
    if (settings == null) {
        if (state.loading) LoadingBox() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Muted("تعذر تحميل الإعدادات")
        }
        return
    }

    var bot by remember(settings) { mutableStateOf(settings.botNotificationsEnabled) }
    var notice by remember(settings) { mutableStateOf(settings.expiryNoticeEnabled) }
    var unit by remember(settings) { mutableStateOf(settings.expiryNoticeUnit) }
    var value by remember(settings) { mutableStateOf(settings.expiryNoticeValue.toString()) }
    var maintenance by remember(settings) { mutableStateOf(settings.maintenance) }
    val trialParts = remember(settings) { splitTrialSeconds(settings.trialSeconds) }
    var trialValue by remember(settings) { mutableStateOf(trialParts.first) }
    var trialUnit by remember(settings) { mutableStateOf(trialParts.second) }
    var trialUnitMenu by remember { mutableStateOf(false) }
    var graceDays by remember(settings) { mutableStateOf(settings.graceDays.toString()) }
    var foreground by remember(settings) { mutableStateOf(settings.foregroundSyncSeconds.toString()) }
    var background by remember(settings) { mutableStateOf(settings.syncIntervalSeconds.toString()) }
    var ttl by remember(settings) { mutableStateOf(settings.paidTokenTtlDays.toString()) }
    var minVersion by remember(settings) { mutableStateOf(settings.minAppVersion) }
    var methods by remember(settings) { mutableStateOf(parsePaymentRows(settings.paymentMethods)) }
    var paymentEditorIndex by remember { mutableStateOf(-1) }
    var showPaymentEditor by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var supportWa by remember(settings) { mutableStateOf(settings.supportWhatsapp) }
    var supportTg by remember(settings) { mutableStateOf(settings.supportTelegram) }
    var tosText by remember(settings) { mutableStateOf(settings.tosText) }
    var privacyText by remember(settings) { mutableStateOf(settings.privacyText) }
    var tosVersion by remember(settings) { mutableStateOf(settings.tosVersion.toString()) }
    var unitMenu by remember { mutableStateOf(false) }
    var showResetLimits by remember { mutableStateOf(false) }

    val unitLabel = when (unit) {
        "hours" -> "ساعة"
        "minutes" -> "دقيقة"
        else -> "يوم"
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PanelCard {
                Text("🔔 التنبيهات والبوت", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "إشعارات بوت تيليجرام",
                    description = "إشعارات الطلبات وإثباتات الدفع والتقرير اليومي. الأوامر وتنبيهات الأمان تبقى تعمل دائماً.",
                    checked = bot,
                    onCheckedChange = { bot = it }
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "تنبيه قرب انتهاء الاشتراك في تطبيق بوردي",
                    description = "يظهر للمستخدم «باقي على انتهاء اشتراكك …» ضمن المدة المحددة أدناه. الاشتراك مدى الحياة لا يعرض أي تنبيه.",
                    checked = notice,
                    onCheckedChange = { notice = it }
                )
                Spacer(Modifier.height(12.dp))
                Text("المدة قبل الانتهاء التي يبدأ عندها التنبيه", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        enabled = notice,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        OutlinedButton(onClick = { unitMenu = true }, enabled = notice) { Text(unitLabel) }
                        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                            listOf("days" to "يوم", "hours" to "ساعة", "minutes" to "دقيقة").forEach { (u, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { unit = u; unitMenu = false })
                            }
                        }
                    }
                }
                val max = when (unit) {
                    "hours" -> 8760
                    "minutes" -> 525600
                    else -> 365
                }
                Text(
                    "مثال: 3 أيام = يظهر التنبيه في آخر 72 ساعة. القيمة تُطبَّق على كل الأجهزة عند المزامنة.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("المعاينة في تطبيق بوردي", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                Surface(
                    color = SurfaceColor,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(
                        "باقي على انتهاء اشتراكك ${value.ifBlank { "?" }} ${pluralUnit(unit, value.toIntOrNull() ?: 0)}",
                        color = Amber,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Text(
                    "يُحسب الوقت الفعلي المتبقي ويُعرض بنفس الوحدة المختارة. (الحد الأقصى $max)",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            PanelCard {
                Text("⏱️ التوقيت والترخيص", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(10.dp))
                Text(
                    "مدة النسخة التجريبية",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = trialValue,
                        onValueChange = { trialValue = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        OutlinedButton(onClick = { trialUnitMenu = true }) { Text(trialUnitLabel(trialUnit)) }
                        DropdownMenu(expanded = trialUnitMenu, onDismissRequest = { trialUnitMenu = false }) {
                            listOf(
                                "days" to "أيام",
                                "hours" to "ساعات",
                                "minutes" to "دقائق",
                                "seconds" to "ثوانٍ"
                            ).forEach { (u, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { trialUnit = u; trialUnitMenu = false })
                            }
                        }
                    }
                }
                val trialNow = if (settings.trialSeconds <= 0) {
                    "بلا تجربة مجانية (الجهاز الجديد يبدأ مقفلاً حتى الاشتراك)"
                } else {
                    "${settings.trialSeconds} ثانية"
                }
                Text(
                    "تُطبَّق على الأجهزة الجديدة فقط — الحالي: $trialNow. القيمة 0 تُلغي التجربة المجانية.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                NumberField("المزامنة أثناء فتح التطبيق (ثانية) — شبه آنية", foreground, { foreground = it }, "التطبيق يسأل السيرفر كل هذه المدة وهو مفتوح (5–120 ثانية).")
                NumberField("المزامنة في الخلفية (ثانية)", background, { background = it }, "حد النظام 15 دقيقة فعلياً؛ القيمة هنا تحدّد الجدولة (15–3600 ثانية).")
                NumberField("مدة صلاحية رمز الاشتراك (أيام)", ttl, { ttl = it }, "من 1 إلى 365 يوماً.")
                NumberField("أيام السماح بعد الانتهاء", graceDays, { graceDays = it }, "فترة يبقى فيها التطبيق يعمل بعد انتهاء الاشتراك أو التجربة. 0 = يتوقف فوراً عند الانتهاء بلا فترة سماح.")
                NumberField("أقل إصدار مسموح للتطبيق", minVersion, { minVersion = it }, "يقبل الكسور العشرية مثل 1.0 أو 1.2، ويُقارَن بنسخة التطبيق الظاهرة.")
            }
        }

        item {
            PanelCard {
                Text("💳 الدفع والدعم", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(10.dp))
                Text("طرق الدفع", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                if (methods.isEmpty()) {
                    Muted("لا توجد طرق دفع بعد — أضف واحدة ليظهر زر الدفع في تطبيق بوردي.")
                }
                methods.forEachIndexed { index, method ->
                    Surface(
                        color = SurfaceColor,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    method.name,
                                    color = TextMain,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    method.account,
                                    color = TextMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (method.note.isNotBlank()) Muted(method.note)
                            }
                            TextButton(onClick = {
                                paymentEditorIndex = index
                                showPaymentEditor = true
                            }) { Text("تعديل", color = Primary, fontSize = 12.sp) }
                            TextButton(onClick = {
                                methods = methods.filterIndexed { i, _ -> i != index }
                            }) { Text("حذف", color = DangerSoft, fontSize = 12.sp) }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        paymentEditorIndex = -1
                        showPaymentEditor = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("＋ إضافة طريقة دفع") }
                Muted("يظهر للمشترك اسم الطريقة ورقم التحويل والملاحظة فقط — لا حاجة لأي صيغة تقنية.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = supportWa,
                    onValueChange = { supportWa = it },
                    label = { Text("واتساب الدعم (رابط أو رقم)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = supportTg,
                    onValueChange = { supportTg = it },
                    label = { Text("تيليجرام الدعم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Muted("لا تترك رقم الدعم بقيم XXXXXXXX — زر الدعم في التطبيق يعتمد عليه.")
            }
        }

        item {
            PanelCard {
                Text("📄 الشروط والخصوصية", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tosText,
                    onValueChange = { tosText = it },
                    label = { Text("شروط الخدمة") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = privacyText,
                    onValueChange = { privacyText = it },
                    label = { Text("سياسة الخصوصية") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                NumberField("نسخة الشروط", tosVersion, { tosVersion = it }, "رفعها يعيد عرض الشروط على الجميع.")
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "وضع الصيانة",
                    description = "يمنع المزامنة مؤقتاً ويعرض رسالة صيانة في التطبيق.",
                    checked = maintenance,
                    onCheckedChange = { maintenance = it }
                )
            }
        }

        item {
            saveError?.let {
                Text(
                    it,
                    color = DangerSoft,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = {
                    saveError = null
                    val minRaw = minVersion.trim()
                    if (!Regex("^\\d+(\\.\\d+){0,3}$").matches(minRaw)) {
                        saveError = "أقل إصدار غير صالح — استخدم صيغة مثل 1.0"
                        return@Button
                    }
                    if (methods.isEmpty()) {
                        saveError = "أضف طريقة دفع واحدة على الأقل قبل الحفظ"
                        return@Button
                    }
                    val parsed = value.toIntOrNull() ?: settings.expiryNoticeValue
                    val rawTrialSeconds = (trialValue.toLongOrNull() ?: 0L) * trialUnitFactor(trialUnit)
                    val trialSeconds = when {
                        rawTrialSeconds <= 0L -> 0
                        rawTrialSeconds < 30L -> 30
                        else -> rawTrialSeconds.coerceAtMost(3650L * 86400L).toInt()
                    }
                    val body = JSONObject().apply {
                        put("bot_notifications_enabled", if (bot) "1" else "0")
                        put("expiry_notice_enabled", if (notice) "1" else "0")
                        put("expiry_notice_unit", unit)
                        put("expiry_notice_value", parsed.coerceIn(1, when (unit) {
                            "hours" -> 8760
                            "minutes" -> 525600
                            else -> 365
                        }).toString())
                        put("trial_seconds", trialSeconds.toString())
                        put("grace_days", (graceDays.toIntOrNull() ?: settings.graceDays).coerceIn(0, 60).toString())
                        put("foreground_sync_seconds", (foreground.toIntOrNull() ?: settings.foregroundSyncSeconds).coerceIn(5, 120).toString())
                        put("sync_interval_seconds", (background.toIntOrNull() ?: settings.syncIntervalSeconds).coerceIn(15, 3600).toString())
                        put("paid_token_ttl_days", (ttl.toIntOrNull() ?: settings.paidTokenTtlDays).coerceIn(1, 365).toString())
                        put("min_app_version", minRaw)
                        put("payment_methods", paymentRowsJson(methods))
                        put("support_whatsapp", supportWa.trim())
                        put("support_telegram", supportTg.trim())
                        if (tosText.isNotBlank()) put("tos_text", tosText)
                        if (privacyText.isNotBlank()) put("privacy_text", privacyText)
                        put("tos_version", (tosVersion.toIntOrNull() ?: settings.tosVersion).toString())
                        put("maintenance", if (maintenance) "on" else "off")
                    }
                    vm.saveSettings(body)
                },
                enabled = !state.loading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("💾 حفظ الإعدادات", fontWeight = FontWeight.Bold)
            }
        }

        item {
            PanelCard {
                Text("⚠️ منطقة الخطر", style = MaterialTheme.typography.titleMedium, color = DangerSoft)
                Spacer(Modifier.height(8.dp))
                Muted("تصفير حدود المعدل يمسح عدّادات المحاولات على كل المسارات الحساسة (تفعيل، تحديث، طلبات، أكواد).")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("تصفير حدود المعدل", Amber) { showResetLimits = true }
                }
            }
        }

        item {
            PanelCard {
                Text("ℹ️ قيم النظام الحالية", style = MaterialTheme.typography.titleMedium, color = Primary)
                Spacer(Modifier.height(8.dp))
                InfoRow("التجربة", formatDuration(settings.trialSeconds))
                InfoRow("مزامنة مفتوحة", "${settings.foregroundSyncSeconds} ثانية")
                InfoRow("مزامنة خلفية", "${settings.syncIntervalSeconds} ثانية")
                InfoRow("صلاحية الرمز", "${settings.paidTokenTtlDays} يوم")
                InfoRow("أيام السماح", "${settings.graceDays} يوم")
                InfoRow("أقل إصدار", "${settings.minAppVersion}")
                InfoRow("نسخة الشروط", "${settings.tosVersion}")
                InfoRow("دعم واتساب", settings.supportWhatsapp.ifBlank { "—" })
                InfoRow("دعم تيليجرام", settings.supportTelegram.ifBlank { "—" })
            }
        }

        item { Spacer(Modifier.height(10.dp)) }
    }

    if (showPaymentEditor) {
        PaymentMethodDialog(
            initial = methods.getOrNull(paymentEditorIndex),
            onDismiss = { showPaymentEditor = false },
            onSave = { row ->
                methods = if (paymentEditorIndex >= 0) {
                    methods.toMutableList().also { it[paymentEditorIndex] = row }
                } else {
                    methods + row
                }
                showPaymentEditor = false
            }
        )
    }

    if (showResetLimits) {
        ConfirmDialog(
            title = "تصفير حدود المعدل؟",
            message = "سيُسمح للمحاولات المتكررة من جديد على كل المسارات. استخدمه فقط عند قفل مستخدم بسبب محاولات فاشلة.",
            confirmText = "تصفير",
            danger = false,
            onDismiss = { showResetLimits = false }
        ) {
            vm.resetRateLimits()
            showResetLimits = false
        }
    }
}

private data class PaymentRow(val name: String, val account: String, val note: String)

private fun parsePaymentRows(raw: String): List<PaymentRow> = try {
    val arr = JSONArray(raw.ifBlank { "[]" })
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i)
        val name = o?.optString("name")?.trim().orEmpty()
        val account = o?.optString("account")?.trim().orEmpty()
        if (name.isEmpty() || account.isEmpty()) null
        else PaymentRow(name, account, o.optString("note").trim())
    }
} catch (_: Exception) {
    emptyList()
}

private fun paymentRowsJson(rows: List<PaymentRow>): String {
    val arr = JSONArray()
    rows.forEach { row ->
        arr.put(
            JSONObject().apply {
                put("name", row.name)
                put("account", row.account)
                put("note", row.note)
            }
        )
    }
    return arr.toString()
}

@Composable
private fun PaymentMethodDialog(
    initial: PaymentRow?,
    onDismiss: () -> Unit,
    onSave: (PaymentRow) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var account by remember { mutableStateOf(initial?.account ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (initial == null) "إضافة طريقة دفع" else "تعديل طريقة دفع",
                    style = MaterialTheme.typography.titleMedium,
                    color = Primary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم طريقة الدفع") },
                    placeholder = { Text("مثال: زين كاش") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("رقم أو حساب التحويل") },
                    placeholder = { Text("07XXXXXXXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("ملاحظة (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Muted("تظهر هذه البيانات للمشترك داخل تطبيق بوردي عند الدفع.")
                error?.let {
                    Text(it, color = DangerSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("إلغاء", color = TextMuted) }
                    Button(
                        onClick = {
                            val n = name.trim()
                            val a = account.trim()
                            if (n.isEmpty() || a.isEmpty()) {
                                error = "الاسم ورقم التحويل مطلوبان"
                                return@Button
                            }
                            onSave(PaymentRow(n, a, note.trim()))
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("حفظ") }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
    Text(hint, color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

private fun formatDuration(seconds: Int): String = when {
    seconds <= 0 -> "بلا تجربة"
    seconds % 86400 == 0 -> "${seconds / 86400} ${pluralUnit("days", seconds / 86400)}"
    seconds % 3600 == 0 -> "${seconds / 3600} ${pluralUnit("hours", seconds / 3600)}"
    seconds % 60 == 0 -> "${seconds / 60} ${pluralUnit("minutes", seconds / 60)}"
    else -> "$seconds ثانية"
}

private fun splitTrialSeconds(seconds: Int): Pair<String, String> = when {
    seconds <= 0 -> "0" to "days"
    seconds % 86400 == 0 -> (seconds / 86400).toString() to "days"
    seconds % 3600 == 0 -> (seconds / 3600).toString() to "hours"
    seconds % 60 == 0 -> (seconds / 60).toString() to "minutes"
    else -> seconds.toString() to "seconds"
}

private fun trialUnitFactor(unit: String): Long = when (unit) {
    "hours" -> 3600L
    "minutes" -> 60L
    "seconds" -> 1L
    else -> 86400L
}

private fun trialUnitLabel(unit: String): String = when (unit) {
    "hours" -> "ساعة"
    "minutes" -> "دقيقة"
    "seconds" -> "ثانية"
    else -> "يوم"
}

private fun pluralUnit(unit: String, n: Int): String = when (unit) {
    "hours" -> if (n == 1) "ساعة" else "ساعات"
    "minutes" -> if (n == 1) "دقيقة" else "دقائق"
    else -> if (n == 1) "يوم" else "أيام"
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.titleSmall)
            Text(description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Green)
        )
    }
}

// ─── Dialogs ────────────────────────────────────────────────────────────────

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        title = { Text(title, color = TextMain) },
        text = { Text(message, color = TextMuted) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = if (danger) Danger else Green)
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = TextMuted) } }
    )
}

@Composable
private fun ReasonDialog(
    title: String,
    hint: String,
    defaultValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf(defaultValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        title = { Text(title, color = TextMain) },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(hint) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason.trim().ifBlank { defaultValue }) },
                colors = ButtonDefaults.buttonColors(containerColor = Danger)
            ) { Text("تأكيد") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = TextMuted) }
        }
    )
}
