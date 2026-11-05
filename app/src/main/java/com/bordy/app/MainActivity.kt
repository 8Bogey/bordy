package com.bordy.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.data.AppDatabase
import com.bordy.app.license.LicenseManager
import com.bordy.app.license.LicenseState
import com.bordy.app.license.ui.ActivationScreen
import com.bordy.app.license.ui.LicenseBanner
import com.bordy.app.license.ui.PaywallScreen
import com.bordy.app.license.ui.TosScreen
import com.bordy.app.screens.AccountGate
import com.bordy.app.screens.RootNavigation
import com.bordy.app.sync.SyncEngine
import com.bordy.app.ui.theme.BordyTheme
import com.bordy.app.utils.BrandGreen
import com.bordy.app.utils.DangerRed
import com.bordy.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.bordy.app.license.IntegrityChecks.checkSigningCert(this)) {
            finish()
            return
        }
        setContent {
            MainAppRoot()
        }
    }

    override fun onResume() {
        super.onResume()
        LicenseManager.checkAndRefresh(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        LicenseManager.startSync(applicationContext)
        SyncEngine.onForeground()
    }

    override fun onStop() {
        LicenseManager.stopSync()
        super.onStop()
    }
}

@Composable
fun MainAppRoot() {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }

    var dark by remember { mutableStateOf(true) }
    var showManualPaywall by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.settingsDao().getOnce()?.let { dark = it.isDarkMode }
    }

    BordyTheme(darkTheme = dark) {
        val licenseState by LicenseManager.state.collectAsState()

        when (val state = licenseState) {
            is LicenseState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BrandGreen)
                }
            }
            is LicenseState.NeedTos -> {
                TosScreen()
            }
            is LicenseState.NoLicense -> {
                ActivationScreen()
            }
            is LicenseState.Expired -> {
                PaywallScreen()
            }
            is LicenseState.Blocked -> {
                BlockedScreen(reason = state.reason)
            }
            is LicenseState.ClockTamper -> {
                ClockTamperScreen()
            }
            is LicenseState.NeedUpdate -> {
                UpdateRequiredScreen(minVersion = state.minVersion)
            }
            else -> {
                // Active, Trial, or Grace -> Normal app experience
                if (showManualPaywall) {
                    androidx.activity.compose.BackHandler { showManualPaywall = false }
                    Box(modifier = Modifier.fillMaxSize()) {
                        PaywallScreen(onClose = { showManualPaywall = false })
                    }
                } else {
                    val vm: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                        factory = MainViewModel.Factory(db)
                    )
                    Column(modifier = Modifier.fillMaxSize()) {
                        LicenseBanner(
                            state = state,
                            onRenewClick = { showManualPaywall = true }
                        )
                        AccountGate {
                            RootNavigation(vm = vm, darkOnChange = { newVal -> dark = newVal })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedScreen(reason: String?) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "حسابك موقوف ⛔",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = DangerRed
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = reason ?: "تواصل مع الدعم الفني وسنساعدك في حلها",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/"))
                    context.startActivity(intent)
                }
            ) {
                Text("تواصل مع الدعم الفني")
            }
        }
    }
}

@Composable
fun ClockTamperScreen() {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "وقت الجهاز غير مضبوط 🕒",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = DangerRed
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "تاريخ جهازك أو وقته غير مضبوطين. اضبط الساعة والتاريخ من إعدادات الجهاز وفعّل الضبط التلقائي حتى تتمكن من متابعة عملك.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    LicenseManager.checkAndRefresh(context)
                }
            ) {
                Text("تحقق الآن")
            }
        }
    }
}

@Composable
fun UpdateRequiredScreen(minVersion: String) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "تحديث جديد لتطبيق بوردي 🚀",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = BrandGreen
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "لمتابعة عملك دون مشكلات، يجب تحديث التطبيق إلى الإصدار الجديد ($minVersion).",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}
