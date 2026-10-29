package com.bordy.app.license.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.license.LicenseApi
import com.bordy.app.license.LicenseManager
import com.bordy.app.license.TosResponse
import com.bordy.app.ui.animations.bouncyClickable
import com.bordy.app.utils.SellGreen
import kotlinx.coroutines.launch

@Composable
fun TosScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tosResponse by remember { mutableStateOf<TosResponse?>(null) }
    var showTosDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    // Default fallback texts
    val defaultTos = "شروط الاستخدام: باستخدامك لتطبيق بوردي توافق على الشروط التالية:\n\n" +
        "• الاشتراك لجهاز واحد فقط.\n" +
        "• حساباتك وبياناتك محفوظة بجهازك ومأمنة بالكامل.\n" +
        "• الاشتراك ما يرجع بعد التفعيل.\n" +
        "• في حال مخالفة الشروط يحق للإدارة توقيف الترخيص."
    val defaultPrivacy = "سياسة الخصوصية: خصوصيتك خط أحمر عندنا:\n\n" +
        "• بياناتك وشغلك وبورداتك محفوظة بجهازك وما تروح لأي طرف ثالث.\n" +
        "• نستخدم آيدي الجهاز فقط للتأكد من صلاحية الترخيص."

    LaunchedEffect(Unit) {
        try {
            tosResponse = LicenseApi.getTos()
        } catch (_: Exception) { }
    }

    val tosText = tosResponse?.tosText ?: defaultTos
    val privacyText = tosResponse?.privacyText ?: defaultPrivacy
    val tosVersion = tosResponse?.tosVersion ?: 1

    // TOS Dialog
    if (showTosDialog) {
        AlertDialog(
            onDismissRequest = { showTosDialog = false },
            title = { Text("شروط الاستخدام", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = tosText,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    lineHeight = 24.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showTosDialog = false }) {
                    Text("سد النافذة")
                }
            }
        )
    }

    // Privacy Dialog
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("سياسة الخصوصية", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = privacyText,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    lineHeight = 24.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("سد النافذة")
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // App name
            Text(
                text = "بوردي 🍀",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = SellGreen
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "يا هلا بيك",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(48.dp))

            // Agreement text with clickable TOS and Privacy links
            val annotated = buildAnnotatedString {
                append("باستخدامك للتطبيق توافق على ")
                pushStringAnnotation("TOS", "tos")
                withStyle(SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                )) {
                    append("شروط الاستخدام")
                }
                pop()
                append(" و")
                pushStringAnnotation("PRIVACY", "privacy")
                withStyle(SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                )) {
                    append("سياسة الخصوصية")
                }
                pop()
            }

            androidx.compose.foundation.text.ClickableText(
                text = annotated,
                style = TextStyle(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.padding(horizontal = 8.dp),
                onClick = { offset ->
                    annotated.getStringAnnotations("TOS", offset, offset).firstOrNull()?.let {
                        showTosDialog = true
                    }
                    annotated.getStringAnnotations("PRIVACY", offset, offset).firstOrNull()?.let {
                        showPrivacyDialog = true
                    }
                }
            )

            Spacer(Modifier.height(48.dp))

            // Agree button
            Button(
                onClick = {
                    isLoading = true
                    scope.launch {
                        LicenseManager.acceptTos(context, tosVersion)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .bouncyClickable(enabled = !isLoading) {},
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "موافق وبلّش 🍀",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}
