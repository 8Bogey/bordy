package com.bordy.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bordy.admin.ui.AdminTheme
import com.bordy.admin.ui.Bg
import com.bordy.admin.ui.DangerSoft
import com.bordy.admin.ui.Green
import com.bordy.admin.ui.Mint
import com.bordy.admin.ui.Primary
import com.bordy.admin.ui.Surface as SurfaceColor
import com.bordy.admin.ui.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AdminTheme {
                    AdminRoot()
                }
            }
        }
    }
}

@Composable
private fun AdminRoot(vm: AdminViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
        when {
            state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            !state.loggedIn -> LoginScreen(
                loading = state.loading,
                error = state.error,
                onLogin = { secret, totp -> vm.login(secret, totp) },
                onDismissError = vm::consumeMessage
            )
            else -> DashboardScaffold(vm, state)
        }
    }
}

@Composable
private fun LoginScreen(
    loading: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit,
    onDismissError: () -> Unit
) {
    var secret by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Column {
                Text(
                    "🛡️ إدارة بوردي",
                    style = MaterialTheme.typography.titleLarge,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "لوحة تحكم المالك — دخول محمي بكلمة السر ورمز التحقق",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("كلمة سر الإدارة") },
            singleLine = true,
            enabled = !loading,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
        )
        OutlinedTextField(
            value = totp,
            onValueChange = { raw -> totp = raw.filter { it.isDigit() }.take(6) },
            label = { Text("رمز التحقق (6 أرقام)") },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(top = 10.dp)
        )

        if (error != null) {
            Text(
                error,
                color = DangerSoft,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }

        Button(
            onClick = { if (!loading) onLogin(secret, totp) },
            enabled = !loading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(top = 18.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = androidx.compose.ui.graphics.Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Text("جارٍ الدخول…", fontWeight = FontWeight.Bold)
            } else {
                Text("دخول", fontWeight = FontWeight.Bold)
            }
        }

        Text(
            "الدخول محمي بنفس نظام الموقع: كلمة السر + رمز TOTP. تُقفل الحساب محاولات فاشلة متكررة.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(top = 16.dp)
        )
    }
    if (error != null) {
        LaunchedEffect(error) {
            kotlinx.coroutines.delay(6_000)
            onDismissError()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScaffold(vm: AdminViewModel, state: UiState) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message, state.error) {
        val text = state.message ?: state.error
        if (text != null) {
            scope.launch { snackbar.showSnackbar(text) }
            vm.consumeMessage()
        }
    }

    // Keep the dashboard fresh without hammering the Worker.
    LaunchedEffect(state.loggedIn, state.tab) {
        while (true) {
            delay(30_000)
            vm.refresh()
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("إدارة بوردي", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            when (state.tab) {
                                AdminTab.DASHBOARD -> "لوحة المعلومات"
                                AdminTab.DEVICES -> "المشتركين"
                                AdminTab.ACCOUNTS -> "الحسابات السحابية"
                                AdminTab.ORDERS -> "الطلبات"
                                AdminTab.CODES -> "الأكواد"
                                AdminTab.EVENTS -> "السجل"
                                AdminTab.SETTINGS -> "الإعدادات"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        if (state.loading) {
                            CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = Mint)
                        }
                    }
                    IconButton(onClick = { vm.logout() }) {
                        Icon(Icons.Default.Logout, contentDescription = "خروج", tint = DangerSoft)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceColor)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = TABS.indexOfFirst { it.first == state.tab }.coerceAtLeast(0),
                containerColor = SurfaceColor,
                contentColor = Primary,
                edgePadding = 8.dp
            ) {
                TABS.forEach { (tab, label) ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { vm.selectTab(tab) },
                        selectedContentColor = Primary,
                        unselectedContentColor = TextMuted,
                        text = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (state.tab) {
                    AdminTab.DASHBOARD -> DashboardScreen(state, vm)
                    AdminTab.DEVICES -> DevicesScreen(state, vm)
                    AdminTab.ACCOUNTS -> AccountsScreen(state, vm)
                    AdminTab.ORDERS -> OrdersScreen(state, vm)
                    AdminTab.CODES -> CodesScreen(state, vm)
                    AdminTab.EVENTS -> EventsScreen(state, vm)
                    AdminTab.SETTINGS -> SettingsScreen(state, vm)
                }
            }
        }
    }
}

private val TABS = listOf(
    AdminTab.DASHBOARD to "الرئيسية",
    AdminTab.DEVICES to "المشتركين",
    AdminTab.ACCOUNTS to "الحسابات",
    AdminTab.ORDERS to "الطلبات",
    AdminTab.CODES to "الأكواد",
    AdminTab.EVENTS to "السجل",
    AdminTab.SETTINGS to "الإعدادات"
)
