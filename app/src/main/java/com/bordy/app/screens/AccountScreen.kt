package com.bordy.app.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bordy.app.license.LicenseStore
import com.bordy.app.sync.LoginOutcome
import com.bordy.app.sync.SyncEngine
import com.bordy.app.sync.SyncOutcome
import com.bordy.app.sync.SyncUiState
import com.bordy.app.utils.BrandGreen
import com.bordy.app.utils.DangerRed
import kotlinx.coroutines.launch

private const val PREF_SKIPPED = "account_skipped"
private const val PREF_NAME = "bordy_account_prefs"

/**
 * بوابة الحساب: التطبيق كامل يعمل محلياً، لكن النسخ السحابي وربط الجهاز يبدآن من هنا.
 */
@Composable
fun AccountGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val state by SyncEngine.state.collectAsState()
    val prefs = remember { context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE) }
    var skipped by remember { mutableStateOf(prefs.getBoolean(PREF_SKIPPED, false)) }

    when (state.mode) {
        "frozen" -> FrozenScreen()
        "wipe_pending" -> WipeRequiredScreen(transferId = state.pendingTransferId)
        "restoring" -> RestoringScreen()
        "pending_transfer" -> TransferWaitingScreen()
        "ready" -> content()
        else -> if (skipped) content() else AccountForm(onSkip = {
            prefs.edit().putBoolean(PREF_SKIPPED, true).apply()
            skipped = true
        })
    }
}

@Composable
fun AccountForm(onSkip: () -> Unit, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val licenseToken = remember { LicenseStore.load(context)?.token }
    var claimMode by remember { mutableStateOf(licenseToken != null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun validation(): String? {
        val u = username.trim()
        if (!Regex("^[a-zA-Z0-9._-]{3,40}$").matches(u)) return "اسم المستخدم: 3–40 حرفاً إنجليزياً أو رقماً أو . _ -"
        if (password.length !in 10..128) return "كلمة المرور: 10 أحرف على الأقل"
        if (claimMode && password != confirm) return "كلمتا المرور غير متطابقتين"
        return null
    }

    fun submit() {
        validation()?.let { error = it; return }
        error = null
        busy = true
        scope.launch {
            val outcome = if (claimMode) {
                val token = licenseToken ?: run { busy = false; error = "لا يوجد ترخيص على هذا الجهاز"; return@launch }
                SyncEngine.claimLegacy(username, password, token)
            } else {
                SyncEngine.login(username, password)
            }
            busy = false
            when (outcome) {
                is LoginOutcome.Success, is LoginOutcome.RestoreNeeded -> {
                    context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_SKIPPED, false).apply()
                    onClose?.invoke()
                }
                is LoginOutcome.TransferPending -> Toast.makeText(context, "أُرسل طلب نقل الحساب إلى الإدارة", Toast.LENGTH_LONG).show()
                is LoginOutcome.WipeRequired -> Toast.makeText(context, "يجب مسح هذا الجهاز بعد اكتمال النقل", Toast.LENGTH_LONG).show()
                is LoginOutcome.AlreadyClaimed -> error = "هذا الترخيص مربوط بحساب. سجّل الدخول باسمك."
                is LoginOutcome.LicenseNotEligible -> error = "هذا الجهاز غير مؤهل لإنشاء حساب"
                is LoginOutcome.UsernameTaken -> error = "اسم المستخدم مستخدم مسبقاً"
                is LoginOutcome.InvalidCredentials -> error = "اسم المستخدم أو كلمة المرور غير صحيحة"
                is LoginOutcome.RateLimited -> error = "محاولات كثيرة، حاول بعد قليل"
                is LoginOutcome.Offline -> error = "لا يوجد اتصال بالإنترنت"
                is LoginOutcome.Failed -> error = outcome.message ?: "تعذر إكمال العملية"
                else -> {}
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (onClose != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onClose, enabled = !busy) {
                        Icon(Icons.Filled.Close, contentDescription = "رجوع")
                    }
                }
            }
            Text("حساب بوردي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = BrandGreen)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (claimMode) "أنشئ حسابك مرة واحدة ليُحفظ عملك في السحابة ويُربط بهذا الجهاز"
                else "سجّل الدخول إلى حسابك",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(40) },
                label = { Text("اسم المستخدم") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(128) },
                label = { Text("كلمة المرور") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = "إظهار كلمة المرور"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (claimMode) ImeAction.Next else ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            if (claimMode) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.take(128) },
                    label = { Text("تأكيد كلمة المرور") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = DangerRed, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (claimMode) "إنشاء الحساب" else "دخول")
            }
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = { claimMode = !claimMode; error = null },
                enabled = !busy
            ) {
                Text(if (claimMode) "لدي حساب بالفعل" else "إنشاء حساب جديد من ترخيص هذا الجهاز")
            }
            TextButton(onClick = onSkip, enabled = !busy) {
                Text("متابعة بدون حساب (بلا نسخ سحابي)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** حالة الحساب عندما يكون الجهاز مرتبطاً: مزامنة يدوية وخروج بدون تدمير البيانات المحلية. */
@Composable
fun AccountPanel(onClose: () -> Unit) {
    val state by SyncEngine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onClose, enabled = !busy) {
                    Icon(Icons.Filled.Close, contentDescription = "رجوع")
                }
            }
            Text("حساب بوردي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = BrandGreen)
            Spacer(Modifier.height(8.dp))
            Text("متصل باسم ${state.username}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    state.busy -> "جارٍ النسخ السحابي…"
                    state.pendingChanges > 0 -> "بانتظار رفع ${state.pendingChanges} تغيير"
                    state.lastSyncAt > 0 -> "آخر نسخ: ${com.bordy.app.utils.formatDateTimeUi(state.lastSyncAt)}"
                    else -> "جاهز للنسخ السحابي"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (state.openConflicts > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "يوجد ${state.openConflicts} تعارض بانتظار المراجعة",
                    style = MaterialTheme.typography.bodySmall,
                    color = DangerRed
                )
            }
            note?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    busy = true
                    note = null
                    scope.launch {
                        val outcome = SyncEngine.syncNow()
                        busy = false
                        note = when (outcome) {
                            is SyncOutcome.Ok -> "تم النسخ السحابي"
                            is SyncOutcome.Offline -> "لا يوجد اتصال بالإنترنت"
                            is SyncOutcome.Busy -> "النسخ يعمل الآن"
                            else -> "تعذر إكمال النسخ، أعد المحاولة"
                        }
                    }
                },
                enabled = !busy && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("مزامنة الآن")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { confirmLogout = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("تسجيل الخروج", color = DangerRed)
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("تسجيل الخروج") },
            text = { Text("ستبقى بياناتك على هذا التلفون ويعمل التطبيق بدون نسخ سحابي حتى تسجّل الدخول من جديد.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    busy = true
                    scope.launch {
                        SyncEngine.logout()
                        busy = false
                        onClose()
                    }
                }) { Text("خروج", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun TransferWaitingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    MessageScreen(
        title = "بانتظار موافقة نقل الحساب",
        body = "الحساب مربوط بجهاز آخر. أرسلنا طلب نقل إلى الإدارة، وبعد الموافقة سيُستعاد عملك على هذا الجهاز.",
        tone = BrandGreen,
        primaryLabel = if (busy) "جارٍ التحقق…" else "تحقق من الحالة",
        primaryEnabled = !busy,
        onPrimary = {
            busy = true
            scope.launch {
                SyncEngine.retryPendingLogin()
                busy = false
            }
        },
        secondaryLabel = "تسجيل الخروج",
        onSecondary = { scope.launch { SyncEngine.logout() } }
    )
}

@Composable
private fun FrozenScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    MessageScreen(
        title = "نُقل حسابك إلى جهاز جديد",
        body = "توقّف هذا الجهاز عن المزامنة بعد اعتماد النقل. ستبقى بياناته حتى يكمل الجهاز الجديد استعادته، ثم تُمسح من هنا.",
        tone = BrandGreen,
        primaryLabel = if (busy) "جارٍ التحقق…" else "تحقق من حالة النقل",
        primaryEnabled = !busy,
        onPrimary = {
            busy = true
            scope.launch {
                val result = SyncEngine.retryPendingLogin()
                busy = false
                if (result is LoginOutcome.Success) Toast.makeText(context, "اكتمل النقل، يمكنك البدء", Toast.LENGTH_LONG).show()
            }
        },
        onSecondary = { scope.launch { SyncEngine.logout() } },
        secondaryLabel = "تسجيل الدخول من جديد"
    )
}

@Composable
private fun WipeRequiredScreen(transferId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    MessageScreen(
        title = "يجب مسح بيانات هذا الجهاز",
        body = "اكتملت استعادة حسابك على الجهاز الجديد. لمنع بقاء نسخة ثانية من بياناتك، امسح بيانات هذا الجهاز الآن.",
        tone = DangerRed,
        primaryLabel = if (busy) "جارٍ المسح…" else "مسح بيانات هذا الجهاز",
        primaryEnabled = !busy && transferId != null,
        onPrimary = { confirm = true }
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("تأكيد المسح") },
            text = { Text("ستُحذف كل البيانات الموجودة على هذا الجهاز نهائياً. هل أنت متأكد؟") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val id = transferId ?: return@TextButton
                    busy = true
                    scope.launch {
                        val outcome = SyncEngine.wipeAndAcknowledge(id)
                        busy = false
                        if (outcome !is SyncOutcome.Ok) {
                            Toast.makeText(context, "تعذر إبلاغ الخادم، أعد المحاولة", Toast.LENGTH_LONG).show()
                        }
                    }
                }, enabled = !busy) { Text("امسح", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun RestoringScreen() {
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        busy = true
        error = null
        val outcome = SyncEngine.syncNow()
        busy = false
        error = when (outcome) {
            is SyncOutcome.Ok -> null
            is SyncOutcome.Offline -> "لا يوجد اتصال. أعد المحاولة عندما يعود."
            is SyncOutcome.Failed -> outcome.message ?: "تعذرت الاستعادة"
            else -> "تعذرت الاستعادة، أعد المحاولة"
        }
    }

    MessageScreen(
        title = "نستعيد بياناتك",
        body = if (busy) "جارٍ تنزيل بيانات حسابك إلى هذا الجهاز…" else (error ?: "اكتملت الاستعادة"),
        tone = if (error == null) BrandGreen else DangerRed,
        primaryLabel = if (busy) "جارٍ العمل…" else "إعادة المحاولة",
        primaryEnabled = !busy,
        onPrimary = { attempt++ }
    )
}

@Composable
private fun MessageScreen(
    title: String,
    body: String,
    tone: androidx.compose.ui.graphics.Color,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tone, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
            if (secondaryLabel != null && onSecondary != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}
