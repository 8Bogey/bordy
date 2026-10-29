package com.bordy.app.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import com.bordy.app.components.MainNotchHeader
import com.bordy.app.ui.components.DialogShape
import com.bordy.app.utils.DeliveryReminders
import com.bordy.app.utils.PrivacyAuth
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.privacyAuthMode
import com.bordy.app.utils.requestPrivacyAuth
import com.bordy.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RootNavigation(
    vm: MainViewModel,
    darkOnChange: (Boolean) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // اهتزاز خفيف عند استقرار كل صفحة ليكون الانتقال محسوساً ومريحاً
    LaunchedEffect(pagerState) {
        var lastSettled = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != lastSettled) {
                lastSettled = page
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showActivityLog by remember { mutableStateOf(false) }
    var showSuppliers by remember { mutableStateOf(false) }
    var selectedProjectId by remember { mutableStateOf<Long?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val pricesHidden = settings?.pricesHidden == true
    val projectsForReminders by vm.projects.collectAsState()

    // إعادة جدولة تنبيهات التسليم كلما تغيّر بورد أو إعداد تنبيه
    LaunchedEffect(projectsForReminders, settings) {
        if (settings != null) DeliveryReminders.reschedule(context)
    }

    // الوضع يُقاس عند الضغط لا عند التشغيل: بعض الـ ROM تُبلغ انعدام البصمة مؤقتاً عند الإقلاع.
    // جهاز لا يملك بصمة ولا رقم فتح يبدّل مباشرة بدل أن يتعطل الزر نهائياً
    val onTogglePrivacy: () -> Unit = {
        val mode = privacyAuthMode(context)
        if (mode == PrivacyAuth.None) {
            vm.togglePricesHidden()
            Toast.makeText(
                context,
                "بدون قفل: أضف بصمة أو رمز لتلفونك حتى تأمن هالوضع",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val authWord = if (mode == PrivacyAuth.Pin) "رقم فتح جهازك" else "بصمتك"
            requestPrivacyAuth(
                context = context,
                title = if (pricesHidden) "إظهار الأسعار" else "إخفاء الأسعار",
                subtitle = if (pricesHidden) "أدخل $authWord لإظهار الأسعار مجدداً"
                else "أدخل $authWord لعرض البوردات بدون أسعار",
                onSuccess = { vm.togglePricesHidden() },
                onFailure = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
            )
        }
    }

    BackHandler {
        when {
            showActivityLog -> showActivityLog = false
            showSettings -> showSettings = false
            showSuppliers -> showSuppliers = false
            selectedProjectId != null -> selectedProjectId = null
            pagerState.currentPage != 0 -> scope.launch { pagerState.animateScrollToPage(0) }
            else -> showExitConfirm = true
        }
    }

    val currentScreen = when {
        showActivityLog -> "activity_log"
        showSettings -> "settings"
        showSuppliers -> "suppliers"
        selectedProjectId != null -> "project"
        else -> "main"
    }

    val pagePosition = pagerState.currentPage + pagerState.currentPageOffsetFraction

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                // في RTL يكون التقدم للأمام من الحافة اليسرى، والرجوع من اليمين
                val forward = depthOf(targetState) >= depthOf(initialState)
                val dir = if (forward) -1 else 1

                val enter = fadeIn(
                    animationSpec = tween(260, delayMillis = 60, easing = FastOutSlowInEasing)
                ) + slideInHorizontally(
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                ) { full -> dir * full / 8 } + scaleIn(
                    initialScale = 0.965f,
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                )
                val exit = fadeOut(
                    animationSpec = tween(200, delayMillis = 40, easing = FastOutSlowInEasing)
                ) + slideOutHorizontally(
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                ) { full -> -dir * full / 14 } + scaleOut(
                    targetScale = 0.985f,
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                )
                enter togetherWith exit
            },
            label = "screen_transition"
        ) { screen ->
            when (screen) {
                "main" -> {
                    Column(Modifier.fillMaxSize()) {
                        MainNotchHeader(
                            pagePosition = pagePosition,
                            onSelectTab = { index ->
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        page = index,
                                        animationSpec = spring(
                                            dampingRatio = 0.92f,
                                            stiffness = 420f
                                        )
                                    )
                                }
                            },
                            onOpenSettings = { showSettings = true },
                            onOpenActivityLog = {
                                if (pricesHidden) {
                                    Toast.makeText(context, "السجل مقفل أثناء إخفاء الأسعار", Toast.LENGTH_SHORT).show()
                                } else showActivityLog = true
                            },
                            activityLogLocked = pricesHidden,
                            pricesHidden = pricesHidden,
                            onTogglePrivacy = onTogglePrivacy
                        )
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .navigationBarsPadding()
                                // الشريط العلوي والشريط السفلي مُعالجان هنا، فلا تعيدهما Scaffolds الداخلية
                                .consumeWindowInsets(WindowInsets.statusBars)
                                .consumeWindowInsets(WindowInsets.navigationBars),
                            userScrollEnabled = true,
                            beyondViewportPageCount = 1,
                            // سحب طبيعي: عتبة متوازنة تمنع الانتقال العرضي أثناء التمرير،
                            // مع استقرار نابض سريع بدون ارتداد
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                pagerSnapDistance = PagerSnapDistance.atMost(1),
                                snapAnimationSpec = spring(
                                    dampingRatio = 0.92f,
                                    stiffness = 420f
                                ),
                                snapPositionalThreshold = 0.24f
                            ),
                            key = { it }
                        ) { page ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                when (page) {
                                    0 -> ProjectsScreen(vm, pricesHidden = pricesHidden, onOpen = { id -> selectedProjectId = id })
                                    1 -> TemplatesScreen(vm, pricesHidden = pricesHidden)
                                    2 -> ComponentsScreen(vm, pricesHidden = pricesHidden, onOpenSuppliers = {
                                        if (pricesHidden) {
                                            Toast.makeText(context, "صفحة المجهّزين بيها أسعار الشراء — دخل بصمتك أولاً", Toast.LENGTH_SHORT).show()
                                        } else showSuppliers = true
                                    })
                                    3 -> CalculatorScreen()
                                }
                            }
                        }
                    }
                }
                "settings" -> SettingsScreen(vm, onBack = { showSettings = false }, onChangeTheme = darkOnChange)
                "activity_log" -> ActivityLogScreen(vm, onBack = { showActivityLog = false })
                "suppliers" -> SuppliersScreen(vm, onBack = { showSuppliers = false })
                "project" -> {
                    val id = selectedProjectId
                    if (id != null) {
                        if (!com.bordy.app.license.IntegrityChecks.ensureConsistency()) {
                            selectedProjectId = null
                            Toast.makeText(context, "الاشتراك مو مفعّل أو مخلص", Toast.LENGTH_SHORT).show()
                        } else {
                            ProjectDetailScreen(
                                vm = vm,
                                projectId = id,
                                pricesHidden = pricesHidden,
                                onBack = { selectedProjectId = null }
                            )
                        }
                    }
                }
            }
        }
    }
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("تطلع من بوردي؟", fontWeight = FontWeight.Bold) },
            text = { Text("متأكد تريد تطلع من التطبيق؟") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        (context as? Activity)?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                    shape = CircleShape
                ) {
                    Text("اي، اطلع", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }, shape = CircleShape) {
                    Text("لا، ابقى")
                }
            }
        )
    }
}

private fun depthOf(screen: String): Int = if (screen == "main") 0 else 1
