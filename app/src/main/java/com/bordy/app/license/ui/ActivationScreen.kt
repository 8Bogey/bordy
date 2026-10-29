package com.bordy.app.license.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.license.DeviceId
import com.bordy.app.license.LicenseApi
import com.bordy.app.license.LicenseManager
import com.bordy.app.license.LicenseToken
import com.bordy.app.license.errorMessageOf
import com.bordy.app.ui.animations.bouncyClickable
import com.bordy.app.ui.components.CardShape
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.DangerRed
import com.bordy.app.utils.OnBrandYellow
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.contentColorOn
import kotlinx.coroutines.launch

@Composable
fun ActivationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isOnline by remember { mutableStateOf(LicenseApi.isOnline(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var promoCode by remember { mutableStateOf("") }
    var promoError by remember { mutableStateOf<String?>(null) }

    var showAdvanced by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }

    val displayId = remember { DeviceId.getDisplayId(context) }
    val rawId = remember { DeviceId.get(context) }

    LaunchedEffect(Unit) {
        isOnline = LicenseApi.isOnline(context)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(16.dp))

            // App Logo & Header
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(SellGreen),
                contentAlignment = Alignment.Center
            ) {
                Text("🍀", fontSize = 42.sp)
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "بوردي",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = SellGreen
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "نظامك المرتب لحساب وتجهيز لوحات السيطرة والبوردات",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // Value Proposition Card (Bordy Card Style)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(SellGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Verified, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "فترة التجربة المجانية 🍀",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    val features = listOf(
                        "7 أيام تجربة كاملة الميزات بدون أي قيود",
                        "شغلك وحساباتك محفوظة بجهازك ومأمنة",
                        "بلّش على طول بدون أي التزام ولا دفع"
                    )

                    features.forEach { feat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(SellGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SellGreen, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(feat, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Promo / Owner code field
            OutlinedTextField(
                value = promoCode,
                onValueChange = {
                    promoCode = it
                    promoError = null
                },
                placeholder = { Text("عندك كود خصم أو تفعيل؟ (اختياري)", fontSize = 13.sp) },
                label = { Text("كود الخصم / التفعيل", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                singleLine = true,
                isError = promoError != null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SellGreen,
                    cursorColor = SellGreen
                )
            )

            if (promoError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = promoError ?: "",
                    color = DangerRed,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(16.dp))

            // Main Action Button
            if (isOnline) {
                Button(
                    onClick = {
                        isLoading = true
                        scope.launch {
                            val code = promoCode.trim().ifBlank { null }
                            if (code != null) {
                                // The server silently ignores unusable codes, so validate first and speak up.
                                val check = LicenseApi.checkCode(DeviceId.get(context), code)
                                if (check == null) {
                                    promoError = "ماكو اتصال بالسيرفر، شيك على النت"
                                    isLoading = false
                                    return@launch
                                }
                                if (!check.optBoolean("ok", false)) {
                                    promoError = errorMessageOf(check) ?: "الكود مو صحيح أو خلصت مدته"
                                    isLoading = false
                                    return@launch
                                }
                                val kind = check.optString("kind")
                                if (kind != "FREE_DAYS" && kind != "OWNER") {
                                    promoError = "هذا الكود تستخدمه بشاشة الاشتراك ويا الدفع، مو للتفعيل المباشر"
                                    isLoading = false
                                    return@launch
                                }
                                promoError = null
                            }
                            LicenseManager.activate(context, ownerCode = code)
                            isLoading = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .bouncyClickable(enabled = !isLoading) {},
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                    shape = CircleShape
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = if (promoCode.isBlank()) "بلّش التجربة هسّه (7 أيام مجاناً)" else "فعّل الحساب",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape,
                    color = BrandYellow,
                    border = BorderStroke(1.dp, BrandYellow)
                ) {
                    Text(
                        text = "⚠️ محتاجين إنترنت بس أول مرة حتى تتفعل عندك التجربة المجانية (7 أيام).",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = OnBrandYellow,
                        modifier = Modifier.padding(14.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        isOnline = LicenseApi.isOnline(context)
                        if (!isOnline) {
                            Toast.makeText(context, "ماكو إنترنت حالياً", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .bouncyClickable {},
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = SellGreen)
                ) {
                    Text("شيك على النت من جديد", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Collapsible Advanced / Offline Activation
            TextButton(
                onClick = { showAdvanced = !showAdvanced }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (showAdvanced) "سد الخيارات الإضافية" else "خيارات إضافية / تفعيل يدوي بدون إنترنت",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = showAdvanced) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = CardShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "آيدي الجهاز (للدعم الفني والتفعيل اليدوي)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = displayId,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Device ID", rawId))
                                    Toast.makeText(context, "نسخنا آيدي الجهاز، تدلل ✅", Toast.LENGTH_SHORT).show()
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("نسخ الآيدي", fontSize = 11.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "آيدي جهازي لتطبيق بوردي:\n$rawId")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "مشاركة آيدي الجهاز"))
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("مشاركة", fontSize = 11.sp)
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = "الصق كود الترخيص اليدوي:",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))

                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = {
                                manualToken = it.trim()
                                manualError = null
                            },
                            placeholder = { Text("الصق كود التفعيل اللي استلمته من عدنا...", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            maxLines = 3,
                            isError = manualError != null
                        )

                        if (manualError != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = manualError ?: "",
                                color = DangerRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (manualToken.isBlank()) {
                                    manualError = "الصق الكود أولاً عيني"
                                    return@Button
                                }
                                val payload = LicenseToken.verify(manualToken)
                                if (payload != null && payload.did == rawId) {
                                    LicenseManager.onSubscriptionApproved(context, manualToken)
                                    Toast.makeText(context, "تفعل التطبيق بنجاح، عاشت ايدك! 🎉", Toast.LENGTH_LONG).show()
                                } else if (payload != null && payload.did != rawId) {
                                    manualError = "هذا الكود مخصص لجهاز ثاني مو جهازك"
                                } else {
                                    manualError = "الكود مو مضبوط أو التوقيع مو مطابق"
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .bouncyClickable {},
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = SellGreen)
                        ) {
                            Text("تأكيد التفعيل اليدوي", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
