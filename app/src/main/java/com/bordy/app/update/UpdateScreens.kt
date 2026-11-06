package com.bordy.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bordy.app.sync.SyncEngine
import com.bordy.app.sync.SyncOutcome
import com.bordy.app.utils.BrandGreen
import com.bordy.app.utils.DangerRed
import kotlinx.coroutines.launch
import java.io.File

@Composable
private fun GateScaffold(title: String, titleColor: androidx.compose.ui.graphics.Color, body: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            body()
        }
    }
}

/**
 * نص الإدارة وحده ثم زر واحد. الملف مُحمَّل ومُتحقَّق منه في الخفاء قبل ظهور هذه
 * الشاشة، فالضغط يفتح المُثبِّت مباشرة بلا انتظار ولا شريط تقدم.
 */
@Composable
private fun UpdateAction(message: String, version: String, apk: File?) {
    val context = LocalContext.current
    Text(
        text = message.ifBlank { "يتوفر الإصدار $version — ثبّته لمتابعة العمل." },
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))
    Button(
        onClick = {
            val app = context.applicationContext
            val file = apk
            if (file != null) ApkInstaller.install(app, file) else UpdateScheduler.nudge(app)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("تثبيت")
    }
    if (apk == null) {
        // الحد الأدنى رُفع أثناء انقطاع الشبكة: لا يوجد ملف بعد، فلا زر تنزيل ولا تقدم.
        Spacer(Modifier.height(12.dp))
        Text(
            text = "اتصالك بالإنترنت مطلوب لتجهيز الملف — أعد الضغط بعد توفره.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** بوابة التحديث الإجباري (أقدم من الحد الأدنى) — شاشة حاجزة بلا انتظار ظاهر. */
@Composable
fun UpdateGateScreen(version: String, message: String, apk: File?) {
    GateScaffold("تحديث جديد", BrandGreen) {
        UpdateAction(message, version, apk)
    }
}

/**
 * الإدارة رجّعت «أحدث إصدار» إلى نسخة أقدم (بسبب مشكلة في الإصدار الحديث).
 * أندرويد لا يسمح بتركيب versionCode أقدم فوق الأحدث، لذا: مزامنة سحابية ←
 * حذف التطبيق ← تثبيت النسخة من مجلد التنزيلات ← استعادة البيانات بالدخول.
 */
@Composable
fun RollbackScreen(u: UpdateUi.RollbackRequired) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    GateScaffold("الرجوع إلى إصدار سابق 🔧", DangerRed) {
        Text(
            text = "تبيّن أن الإصدار الحالي يحتاج استبدالاً. يجب تثبيت الإصدار ${u.version} بدلاً منه.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        if (u.notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = u.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))

        if (u.copiedFile != null) {
            Text(
                text = "١) تأكد من مزامنة بياناتك  ٢) احذف التطبيق  ٣) افتح مجلد «التنزيلات» واضغط على «${u.copiedFile}» وثبّته  ٤) سجّل دخولك وستعود بياناتك كما هي.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        syncing = true
                        syncMessage = when (SyncEngine.syncNow()) {
                            SyncOutcome.Ok, SyncOutcome.Busy -> "تمت مزامنة بياناتك ✓ يمكنك المتابعة للحذف والتثبيت."
                            SyncOutcome.NotLoggedIn, SyncOutcome.NeedsLogin ->
                                "لا يوجد حساب سحابي على هذا الجهاز — إن كانت بياناتك مهمة سجّل الدخول أولاً من إعدادات الحساب."
                            SyncOutcome.Offline -> "لا يوجد اتصال بالإنترنت — فعّل الشبكة ثم أعد المحاولة قبل الحذف."
                            else -> "تعذرت المزامنة — أعد المحاولة قبل الحذف لضمان سلامة بياناتك."
                        }
                        syncing = false
                    }
                },
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (syncing) "جاري المزامنة…" else "١) مزامنة بياناتي الآن")
            }
            syncMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { ApkInstaller.uninstallSelf(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("٢) حذف التطبيق الآن")
            }
        } else {
            Text(
                text = "جارٍ تجهيز ملف الإصدار ${u.version} في الخلفية — سيظهر هنا تلقائياً عند اكتماله.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * تنبيه المالك الفوري: الإجباري شاشة حاجزة لا تُغلق، والاختياري نافذة فوق العمل
 * فيها «لاحقاً» — لا تعود إلا إذا أطلق المالك تنبيهًا جديداً. في الحالتين الملف
 * مُنزَّل مسبقاً، فالزر واحد: تثبيت.
 */
@Composable
fun UpdatePromptView(p: UpdatePrompt, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (!p.forced) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("تحديث جديد", fontWeight = FontWeight.Bold, color = BrandGreen) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = p.message.ifBlank { "يتوفر الإصدار ${p.version}." },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { ApkInstaller.install(context.applicationContext, p.apk) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("تثبيت")
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("لاحقاً")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
        return
    }
    GateScaffold("تحديث جديد", BrandGreen) {
        UpdateAction(p.message, p.version, p.apk)
    }
}
