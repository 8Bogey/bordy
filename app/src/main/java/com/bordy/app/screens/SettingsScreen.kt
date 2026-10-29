package com.bordy.app.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import com.bordy.app.license.IntegrityChecks
import com.bordy.app.license.LicenseApi
import com.bordy.app.license.LicenseManager
import com.bordy.app.license.LicenseState
import com.bordy.app.license.formatRemaining
import com.bordy.app.license.ui.PaywallScreen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.bordy.app.data.AppDatabase
import com.bordy.app.data.AppSettings
import com.bordy.app.data.DefaultBusinessName
import com.bordy.app.data.DefaultBusinessTagline
import com.bordy.app.data.invoiceTagline
import com.bordy.app.data.invoiceTitle
import com.bordy.app.ui.components.DialogShape
import com.bordy.app.ui.components.BrandButton
import com.bordy.app.ui.components.SlimScreenHeader
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.DangerRed
import com.bordy.app.utils.OnBrandYellow
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.cleanString
import com.bordy.app.utils.contentColorOn
import com.bordy.app.utils.formatCurrency
import com.bordy.app.utils.formatShortDateUi
import com.bordy.app.utils.normalizeItemName
import com.bordy.app.utils.withThousandsDot
import com.bordy.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onChangeTheme: (Boolean) -> Unit
) {
    val settings by vm.settings.collectAsState()
    val currentSettings = settings ?: AppSettings()
    val pricesHidden = currentSettings.pricesHidden
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDark by remember { mutableStateOf(currentSettings.isDarkMode) }
    var stockHigh by remember { mutableStateOf(currentSettings.stockHighThreshold.toString()) }
    var stockMed by remember { mutableStateOf(currentSettings.stockMediumThreshold.toString()) }
    var defaultLaborCost by remember { mutableStateOf(currentSettings.defaultLaborCost.cleanString()) }

    // هوية النشاط والفاتورة
    var businessName by remember { mutableStateOf(currentSettings.businessName) }
    var businessTagline by remember { mutableStateOf(currentSettings.businessTagline) }
    var businessPhone by remember { mutableStateOf(currentSettings.businessPhone) }
    var invoiceFooter by remember { mutableStateOf(currentSettings.invoiceFooter) }
    var showPurchase by remember { mutableStateOf(currentSettings.showPurchaseInPdf) }
    var showProfit by remember { mutableStateOf(currentSettings.showProfitInPdf) }

    var profitFrom by remember { mutableStateOf(currentSettings.profitFromDate) }
    var profitTo by remember { mutableStateOf(currentSettings.profitToDate) }
    var profitAmount by remember { mutableStateOf<Double?>(null) }
    var errorMsg by remember { mutableStateOf("") }

    var showDatePickerFrom by remember { mutableStateOf(false) }
    var showDatePickerTo by remember { mutableStateOf(false) }

    // وقت التسليم والتنبيهات
    var deliveryHour by remember { mutableStateOf(currentSettings.deliveryHour) }
    var deliveryMinute by remember { mutableStateOf(currentSettings.deliveryMinute) }
    var reminderDays by remember { mutableStateOf(currentSettings.reminderDaysBefore.toString()) }
    var reminderHours by remember { mutableStateOf(currentSettings.reminderHoursBefore.toString()) }
    var showTimePicker by remember { mutableStateOf(false) }

    // الحقول تُهيّأ مرة واحدة عند وصول الإعدادات من قاعدة البيانات بدل الاعتماد على القيمة الأولى
    var formReady by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!formReady) {
            isDark = s.isDarkMode
            stockHigh = s.stockHighThreshold.toString()
            stockMed = s.stockMediumThreshold.toString()
            defaultLaborCost = s.defaultLaborCost.cleanString()
            businessName = s.businessName
            businessTagline = s.businessTagline
            businessPhone = s.businessPhone
            invoiceFooter = s.invoiceFooter
            showPurchase = s.showPurchaseInPdf
            showProfit = s.showProfitInPdf
            profitFrom = s.profitFromDate
            profitTo = s.profitToDate
            deliveryHour = s.deliveryHour
            deliveryMinute = s.deliveryMinute
            reminderDays = s.reminderDaysBefore.toString()
            reminderHours = s.reminderHoursBefore.toString()
            formReady = true
        }
    }

    val licenseState by LicenseManager.state.collectAsState()
    var showPaywallDialog by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showTosDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var tosContent by remember { mutableStateOf("") }
    var privacyContent by remember { mutableStateOf("") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "الإشعارات غير مسموحة — فعّلها من إعدادات النظام لتصلك التنبيهات", Toast.LENGTH_LONG).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let {
            if (!IntegrityChecks.verifyAppState()) {
                Toast.makeText(context, "اشتراكك مخلص — جدد حتى تكدر تصدر نسخة احتياطية", Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            scope.launch(Dispatchers.IO) {
                try {
                    val db = AppDatabase.get(context)
                    db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
                } catch (e: Exception) { e.printStackTrace() }
                val dbFile = context.getDatabasePath("bordy.db")
                context.contentResolver.openOutputStream(it)?.use { output ->
                    dbFile.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val dbFile = context.getDatabasePath("bordy.db")
                val walFile = File(dbFile.path + "-wal")
                val shmFile = File(dbFile.path + "-shm")
                val imported = try {
                    AppDatabase.closeInstance()
                    val input = context.contentResolver.openInputStream(it)
                    if (input == null) {
                        false
                    } else {
                        input.use { stream -> dbFile.outputStream().use { output -> stream.copyTo(output) } }
                        if (walFile.exists()) walFile.delete()
                        if (shmFile.exists()) shmFile.delete()
                        true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
                withContext(Dispatchers.Main) {
                    if (imported) {
                        Toast.makeText(context, "تم استرجاع النسخة بنجاح! ديعيد تشغيل التطبيق هسّه", Toast.LENGTH_LONG).show()
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            context.startActivity(intent)
                        }
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } else {
                        Toast.makeText(context, "ما كدرنا نسترجع النسخة، تأكد من الملف وحاول ثانية", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SlimScreenHeader(title = "الإعدادات", onBack = onBack)
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            SettingsSection(
                icon = Icons.Default.Verified,
                iconTint = SellGreen,
                title = "الاشتراك وحسابك"
            ) {
                val statusText = when (val s = licenseState) {
                    is LicenseState.Trial -> "فترة تجربة مجانية (باقيلك ${formatRemaining(s.msLeft)})"
                    is LicenseState.Active -> if (s.daysLeft >= 9000) "اشتراك دائمي مدى الحياة 🍀" else "اشتراك فعّال (باقيلك ${s.daysLeft} يوم)"
                    is LicenseState.Grace -> "فترة سماح إضافية (باقيلك ${formatRemaining(s.msLeft)})"
                    is LicenseState.Expired -> "الاشتراك مخلص"
                    is LicenseState.Blocked -> "الحساب موقف"
                    else -> "ديشيك السيرفر..."
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("وضع الاشتراك", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(statusText, style = MaterialTheme.typography.bodySmall, color = SellGreen, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { showPaywallDialog = true },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SellGreen)
                    ) {
                        Text("تجديد / ترقية", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            SettingsSection(
                icon = Icons.Default.CloudDone,
                iconTint = SellGreen,
                title = "الحساب والنسخ السحابي"
            ) {
                val syncState by com.bordy.app.sync.SyncEngine.state.collectAsState()
                val lastSync = syncState.lastSyncAt
                Column {
                    Text(
                        if (syncState.loggedIn) "متصل باسم ${syncState.username}"
                        else "بدون حساب — بياناتك محفوظة على التلفون فقط",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !syncState.loggedIn -> "أنشئ حساباً حتى تُحفظ بورداتك في السحابة وتنقذها لو تغيّر تلفونك."
                            syncState.busy -> "جارٍ النسخ السحابي…"
                            syncState.pendingChanges > 0 -> "بانتظار رفع ${syncState.pendingChanges} تغيير — يرفعها تلقائياً"
                            lastSync > 0 -> "آخر نسخ: ${com.bordy.app.utils.formatDateTimeUi(lastSync)}"
                            else -> "جاهز للنسخ السحابي"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showAccount = true },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SellGreen)
                    ) {
                        Text(if (syncState.loggedIn) "إدارة الحساب" else "إنشاء حساب / دخول", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (syncState.loggedIn) {
                        OutlinedButton(
                            onClick = { scope.launch { com.bordy.app.sync.SyncEngine.syncNow() } },
                            shape = CircleShape,
                            enabled = !syncState.busy
                        ) {
                            Text("مزامنة الآن", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            SettingsSection(
                icon = Icons.Default.DarkMode,
                iconTint = SellGreen,
                title = "المظهر والثيم"
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("الوضع الليلي (الداكن)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("ألوان داكنة مريحة للعين بالشغل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                    Switch(
                        checked = isDark,
                        onCheckedChange = { isDark = it; onChangeTheme(it); vm.setDarkMode(it) }
                    )
                }
            }

            SettingsSection(
                icon = Icons.Default.Inventory2,
                iconTint = SellGreen,
                title = "تنبيهات المخزن والمواد"
            ) {
                Text("يحدد ألوان دوائر المواد (أخضر وأصفر) حتى تعرف النواقص أول بأول.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = stockHigh, onValueChange = { stockHigh = it }, label = { Text("حد المتوفر (أخضر)") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = stockMed, onValueChange = { stockMed = it }, label = { Text("حد النواقص (أصفر)") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                }
            }

            SettingsSection(
                icon = Icons.Default.Engineering,
                iconTint = BrandYellow,
                title = "شغل اليد الافتراضي"
            ) {
                Text("ينزل كأجور شغل يد أساسية عند فتح أي بورد جديد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                OutlinedTextField(
                    value = defaultLaborCost,
                    onValueChange = { defaultLaborCost = it },
                    label = { Text("أجور شغل اليد (دينار عراقي)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            SettingsSection(
                icon = Icons.Default.Storefront,
                iconTint = BrandYellow,
                title = "معلومات ورشتك ومحلك"
            ) {
                Text("تطلع برأس قائمة الـ PDF للزبون. إذا عفتها فارغة تطلع المعلومات الافتراضية.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                OutlinedTextField(
                    value = businessName,
                    onValueChange = { businessName = it },
                    label = { Text("اسم الورشة أو المكتب") },
                    placeholder = { Text(DefaultBusinessName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = businessTagline,
                    onValueChange = { businessTagline = it },
                    label = { Text("وصف النشاط (مثلاً: بوردات تأسيس وسيطرة)") },
                    placeholder = { Text(DefaultBusinessTagline) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = businessPhone,
                    onValueChange = { businessPhone = it },
                    label = { Text("رقم تليفونك للزبائن") },
                    placeholder = { Text("07xx xxx xxxx") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            SettingsSection(
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                iconTint = SellGreen,
                title = "قائمة الحساب (PDF للزبون)"
            ) {
                OutlinedTextField(
                    value = invoiceFooter,
                    onValueChange = { invoiceFooter = it },
                    label = { Text("ملاحظة بأسفل القائمة") },
                    placeholder = { Text("مثال: شكراً لتعاملكم ويا ورشتنا — الضمان سنة كاملة") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                InvoiceToggleRow(
                    title = "عرض كلفة المواد بالقائمة",
                    subtitle = "يطلع سطر كلفة المواد الحقيقية بالقائمة",
                    checked = showPurchase,
                    onCheckedChange = { showPurchase = it }
                )
                InvoiceToggleRow(
                    title = "عرض الربح بالقائمة",
                    subtitle = "يطلع سطر صافي أرباحك بعد شغل اليد",
                    checked = showProfit,
                    onCheckedChange = { showProfit = it }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Text("معاينة شكل رأس القائمة", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                val previewSettings = currentSettings.copy(
                    businessName = businessName,
                    businessTagline = businessTagline,
                    businessPhone = businessPhone,
                    invoiceFooter = invoiceFooter,
                    showPurchaseInPdf = showPurchase,
                    showProfitInPdf = showProfit
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, BrandYellow.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(vertical = 14.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(previewSettings.invoiceTitle(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = BrandYellow, textAlign = TextAlign.Center)
                        Text(previewSettings.invoiceTagline(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                        if (previewSettings.businessPhone.isNotBlank()) {
                            Text("هاتف: ${previewSettings.businessPhone.trim()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("──────────", color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        Text("القطعة · الكمية · سعر البيع · الإجمالي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text("الإجمالي / الأجور / المجموع النهائي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        if (previewSettings.showPurchaseInPdf) Text("كلفة المواد عليّ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        if (previewSettings.showProfitInPdf) Text("الربح بعد الأجور", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        if (previewSettings.invoiceFooter.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            previewSettings.invoiceFooter.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = SellGreen, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }

            SettingsSection(
                icon = Icons.Default.Schedule,
                iconTint = SellGreen,
                title = "مواعيد تسليم البورد والتنبيهات"
            ) {
                Text(
                    "ساعة التسليم المعتمدة عند تحديد تاريخ تسليم البورد للزبون.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Surface(
                    onClick = { showTimePicker = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(BrandYellow),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Schedule, null, tint = OnBrandYellow, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("ساعة التسليم اليومية", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "تتثبت ع البوردات الجديدة واللي تتعدل",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Text(
                            clockText(deliveryHour, deliveryMinute),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = SellGreen
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reminderDays,
                        onValueChange = { reminderDays = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("نبهني قبل التسليم بـ (أيام)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = reminderHours,
                        onValueChange = { reminderHours = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("نبهني قبل التسليم بـ (ساعات)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                Text(
                    "يوصلك إشعارين لكل بورد ديشتغل · حط 0 حتى تلغي التنبيه",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            BrandButton(
                text = "حفظ التغييرات",
                icon = Icons.Default.Save,
                onClick = {
                    val high = (stockHigh.toIntOrNull() ?: 10).coerceAtLeast(2)
                    val medium = (stockMed.toIntOrNull() ?: 5).coerceIn(1, high - 1)
                    val daysValue = (reminderDays.toIntOrNull() ?: 0).coerceIn(0, 30)
                    val hoursValue = (reminderHours.toIntOrNull() ?: 0).coerceIn(0, 72)
                    stockHigh = high.toString()
                    stockMed = medium.toString()
                    reminderDays = daysValue.toString()
                    reminderHours = hoursValue.toString()
                    vm.saveSettings(currentSettings.copy(
                        isDarkMode = isDark,
                        stockHighThreshold = high,
                        stockMediumThreshold = medium,
                        defaultLaborCost = (defaultLaborCost.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                        businessName = normalizeItemName(businessName),
                        businessTagline = normalizeItemName(businessTagline),
                        businessPhone = normalizeItemName(businessPhone),
                        invoiceFooter = invoiceFooter.trim(),
                        showPurchaseInPdf = showPurchase,
                        showProfitInPdf = showProfit,
                        deliveryHour = deliveryHour.coerceIn(0, 23),
                        deliveryMinute = deliveryMinute.coerceIn(0, 59),
                        reminderDaysBefore = daysValue,
                        reminderHoursBefore = hoursValue
                    ))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        (daysValue > 0 || hoursValue > 0) &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            SettingsSection(
                icon = Icons.Default.Backup,
                iconTint = SellGreen,
                title = "النسخ الاحتياطي لشغلك"
            ) {
                Text("احفظ نسخة احتياطية من كل بورداتك وموادك ع التلفون، أو استرجع نسخة سابقة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandButton(
                        text = "حفظ نسخة",
                        onClick = { exportLauncher.launch("bordy_backup.db") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/octet-stream")) },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    ) { Text("استرجاع نسخة", fontWeight = FontWeight.Bold) }
                }
            }

            SettingsSection(
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                iconTint = BrandYellow,
                title = "حساب الأرباح والمدخول"
            ) {
                if (pricesHidden) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = BrandYellow, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "تقرير الأرباح مقفول ومخفي بالبصمة كدام الزبائن.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BrandYellow,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showDatePickerFrom = true },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    ) {
                        Text(if (profitFrom == null) "من تاريخ" else formatShortDateUi(profitFrom!!))
                    }
                    OutlinedButton(
                        onClick = { showDatePickerTo = true },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    ) {
                        Text(if (profitTo == null) "إلى تاريخ" else formatShortDateUi(profitTo!!))
                    }
                }
                BrandButton(
                    text = "احسب صافي الأرباح",
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    containerColor = BrandYellow,
                    contentColor = OnBrandYellow,
                    onClick = {
                        if (profitFrom == null || profitTo == null) { errorMsg = "حدد تاريخ البداية والنهاية أولاً" }
                        else if (profitFrom!! > profitTo!!) { errorMsg = "تاريخ البداية لازم يكون قبل النهاية" }
                        else {
                            errorMsg = ""
                            scope.launch {
                                profitAmount = vm.calculateProfit(profitFrom!!, profitTo!!)
                                vm.saveSettings(currentSettings.copy(
                                    isDarkMode = isDark,
                                    profitFromDate = profitFrom,
                                    profitToDate = profitTo
                                ))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMsg.isNotEmpty()) Text(errorMsg, color = DangerRed, style = MaterialTheme.typography.bodySmall)

                if (profitAmount != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Text("مجموع أرباحك الصافية:", fontWeight = FontWeight.Bold)
                    Text(formatCurrency(profitAmount!!).withThousandsDot(), style = MaterialTheme.typography.headlineMedium, color = BrandYellow, fontWeight = FontWeight.Bold)
                }
                }
            }

            SettingsSection(
                icon = Icons.Default.Security,
                iconTint = SellGreen,
                title = "شروط الاستخدام وسياسة الخصوصية"
            ) {
                Text(
                    "بياناتك ومشاريعك مشفرة ومحفوظة على جهازك بأمان وتطبيق بوردي يحترم خصوصيتك بالكامل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val tosResp = LicenseApi.getTos()
                                tosContent = tosResp?.tosText ?: "شروط الخدمة: بياناتك ومشاريعك محفوظة على جهازك فقط. الاشتراك لجهاز واحد."
                                showTosDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("شروط الخدمة", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val tosResp = LicenseApi.getTos()
                                privacyContent = tosResp?.privacyText ?: "سياسة الخصوصية: التطبيق يحترم خصوصيتك ولا يشارك بياناتك مع أي طرف ثالث."
                                showPrivacyDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Policy, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("سياسة الخصوصية", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAccount) {
        val syncState by com.bordy.app.sync.SyncEngine.state.collectAsState()
        Dialog(
            onDismissRequest = { showAccount = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            if (syncState.loggedIn) AccountPanel(onClose = { showAccount = false })
            else AccountForm(onSkip = { showAccount = false }, onClose = { showAccount = false })
        }
    }

    if (showDatePickerFrom) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = profitFrom)
        DatePickerDialog(
            onDismissRequest = { showDatePickerFrom = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { profitFrom = it }
                    showDatePickerFrom = false
                }) { Text("موافق") }
            },
            dismissButton = { TextButton(onClick = { showDatePickerFrom = false }) { Text("إلغاء") } },
            shape = DialogShape,
            colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
        ) { DatePicker(state = datePickerState) }
    }
    if (showDatePickerTo) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = profitTo)
        DatePickerDialog(
            onDismissRequest = { showDatePickerTo = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { profitTo = it }
                    showDatePickerTo = false
                }) { Text("موافق") }
            },
            dismissButton = { TextButton(onClick = { showDatePickerTo = false }) { Text("إلغاء") } },
            shape = DialogShape,
            colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
        ) { DatePicker(state = datePickerState) }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = deliveryHour,
            initialMinute = deliveryMinute,
            is24Hour = true
        )
        Dialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, BrandYellow.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(
                    Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("وقت التسليم اليومي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "يُدمج مع تاريخ التسليم المختار من التقويم",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    TimePicker(
                        state = timeState,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            selectorColor = BrandYellow,
                            timeSelectorSelectedContainerColor = BrandYellow,
                            timeSelectorSelectedContentColor = OnBrandYellow
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showTimePicker = false }, shape = CircleShape) { Text("إلغاء") }
                        BrandButton(
                            text = "اعتماد",
                            icon = Icons.Default.Schedule,
                            onClick = {
                                deliveryHour = timeState.hour
                                deliveryMinute = timeState.minute
                                vm.saveSettings(currentSettings.copy(deliveryHour = timeState.hour, deliveryMinute = timeState.minute))
                                showTimePicker = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (showPaywallDialog) {
        Dialog(
            onDismissRequest = { showPaywallDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize()) {
                PaywallScreen(onClose = { showPaywallDialog = false })
            }
        }
    }

    if (showTosDialog) {
        AlertDialog(
            onDismissRequest = { showTosDialog = false },
            title = { Text("شروط الخدمة", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = tosContent,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showTosDialog = false }) { Text("إغلاق") }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("سياسة الخصوصية", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = privacyContent,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("إغلاق") }
            }
        )
    }
}

private fun clockText(hour: Int, minute: Int): String =
    // أرقام لاتينية كبقية التطبيق (الأسعار والتواريخ) — افتراضي Locale يعطيها عربية فتختلف
    "\u202A%02d:%02d\u202C".format(java.util.Locale.US, hour.coerceIn(0, 23), minute.coerceIn(0, 59))

// ─────────────────────────────────────────────────
// ▎ سطر تبديل موحّد داخل بطاقات الإعدادات
// ─────────────────────────────────────────────────
@Composable
private fun InvoiceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
        Switch(checked = checked, onCheckedChange = { onCheckedChange(it) }, colors = SwitchDefaults.colors(checkedTrackColor = BrandYellow))
    }
}

// ─────────────────────────────────────────────────
// ▎ بطاقة قسم موحّدة بأيقونة مُصبغة
// ─────────────────────────────────────────────────
@Composable
private fun SettingsSection(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(iconTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = contentColorOn(iconTint), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}
