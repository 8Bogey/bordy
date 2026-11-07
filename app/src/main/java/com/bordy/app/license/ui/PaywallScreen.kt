package com.bordy.app.license.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.BuildConfig
import com.bordy.app.R
import com.bordy.app.license.*
import com.bordy.app.ui.animations.bouncyClickable
import com.bordy.app.ui.components.CardShape
import com.bordy.app.ui.components.DialogShape
import com.bordy.app.ui.components.SlimScreenHeader
import com.bordy.app.ui.components.TintedCircleButton
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.DangerRed
import com.bordy.app.utils.OnBrandYellow
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.SellGreenDark
import com.bordy.app.utils.contentColorOn
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PaymentSheetStep {
    SelectMethod,
    PaymentDetails
}

private fun String.toComposeColorOrNull(): Color? =
    if (matches(Regex("#[0-9a-fA-F]{6}"))) Color(android.graphics.Color.parseColor(this)) else null

private fun promoDiscountLabel(disc: DiscountInfo): String = when (disc.kind) {
    "PERCENT" -> "خصم ${disc.value}%"
    "FIXED" -> "خصم ${"%,d".format(disc.value)} د.ع"
    "FULL" -> "خصم كامل 100%"
    "FREE_DAYS" -> "+${disc.value} يوم مجاني"
    else -> "خصم مطبق"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val did = remember { DeviceId.get(context) }

    var step by remember { mutableStateOf<PaywallStep>(PaywallStep.SelectPlan) }
    var plansResponse by remember { mutableStateOf<PlansResponse?>(null) }
    var selectedPlanId by remember { mutableStateOf("monthly") }
    var promoCode by remember { mutableStateOf("") }
    var appliedDiscount by remember { mutableStateOf<DiscountInfo?>(null) }
    var discountIsAuto by remember { mutableStateOf(false) }
    var autoDiscount by remember { mutableStateOf<DiscountInfo?>(null) }
    var promoError by remember { mutableStateOf<String?>(null) }
    var checkoutError by remember { mutableStateOf<String?>(null) }

    // Order state & bottom sheet
    var currentOrderId by remember { mutableStateOf("") }
    var finalAmount by remember { mutableIntStateOf(0) }
    var paymentMethods by remember { mutableStateOf<List<PaymentMethod>>(emptyList()) }
    var showPaymentSheet by remember { mutableStateOf(false) }
    var sheetStep by remember { mutableStateOf(PaymentSheetStep.SelectMethod) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    var showPromoField by remember { mutableStateOf(false) }

    // Inside BottomSheet state
    var selectedMethodIndex by remember { mutableIntStateOf(0) }

    var rejectionReason by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var waitingSubmitted by remember { mutableStateOf(false) }
    var orderPlanTitle by remember { mutableStateOf("") }
    var activationSucceeded by remember { mutableStateOf(false) }
    var approvalNote by remember { mutableStateOf<String?>(null) }

    // Trust the server's approval only when the token actually verifies on this device.
    suspend fun handleApproved(token: String?) {
        showPaymentSheet = false
        LicenseStore.clearPendingOrder(context)
        val payload = token?.takeIf { it.isNotBlank() }?.let { LicenseToken.verify(it) }
        if (payload != null && payload.did == did) {
            LicenseManager.onSubscriptionApproved(context, token!!)
            activationSucceeded = true
            Toast.makeText(context, "تم تفعيل اشتراكك بنجاح! شكراً لك 🍀", Toast.LENGTH_LONG).show()
            return
        }
        // Approved but unsigned — a forced refresh asks the server for a signed token.
        approvalNote = "تم تأكيد الدفع، جارٍ تحديث الترخيص…"
        LicenseManager.checkAndRefresh(context, force = true)
        delay(3_500)
        if (LicenseManager.state.value is LicenseState.Active) {
            approvalNote = null
            activationSucceeded = true
            Toast.makeText(context, "تم تفعيل اشتراكك بنجاح! شكراً لك 🍀", Toast.LENGTH_LONG).show()
        } else {
            approvalNote = "تم تأكيد دفعك، لكن تعذّر تحديث الترخيص الآن. تأكد من الإنترنت وأعد فتح التطبيق، وإذا استمرت المشكلة راسل الدعم."
        }
    }

    // The manual paywall (opened from settings/banner) closes itself once the token lands.
    LaunchedEffect(activationSucceeded) {
        if (activationSucceeded && onClose != null) onClose()
    }

    // Restore a pending order on entry so the user can resume paying after a restart.
    LaunchedEffect(Unit) {
        val pending = LicenseStore.getPendingOrder(context)
        if (pending != null) {
            currentOrderId = pending.id
            finalAmount = pending.amount
            orderPlanTitle = pending.planTitle
            waitingSubmitted = pending.submitted
            if (pending.planId.isNotBlank()) selectedPlanId = pending.planId
            step = PaywallStep.WaitingApproval
        }
    }

    // Fallback default plans
    val defaultPlans = listOf(
        PlanItem("monthly", "شهري", 30, 7500),
        PlanItem("quarterly", "3 أشهر", 90, 20000),
        PlanItem("yearly", "سنوي", 365, 70000)
    )

    // Apply server config without discarding what the user already picked.
    fun applyServerConfig(resp: PlansResponse) {
        plansResponse = resp
        // Never invent wallet numbers: an empty list means "not configured yet".
        paymentMethods = resp.paymentMethods
        autoDiscount = resp.autoDiscount
        val auto = resp.autoDiscount
        if (auto != null && auto.appliesTo(selectedPlanId) && (discountIsAuto || appliedDiscount == null)) {
            appliedDiscount = auto
            discountIsAuto = true
        } else if (discountIsAuto && (auto == null || !auto.appliesTo(selectedPlanId))) {
            // A campaign that ended or hit its cap must stop discounting the price we show.
            appliedDiscount = null
            discountIsAuto = false
        }
        if (resp.plans.isNotEmpty() && resp.plans.none { it.id == selectedPlanId }) {
            selectedPlanId = resp.plans.first().id
            appliedDiscount = auto?.takeIf { it.appliesTo(selectedPlanId) }
            discountIsAuto = appliedDiscount != null
        }
    }

    // The manager pushes config as soon as the owner changes it — never clobber user choices.
    val remoteConfig by LicenseManager.remoteConfig.collectAsState()
    LaunchedEffect(remoteConfig) {
        val cfg = remoteConfig ?: return@LaunchedEffect
        if (cfg.version > 0 && cfg.version == plansResponse?.version) return@LaunchedEffect
        applyServerConfig(cfg)
    }

    // Prices and the automatic campaign can change while an order is in flight (e.g. its
    // reservation is released on reject/cancel), so re-read them whenever the plan picker shows.
    LaunchedEffect(step) {
        if (step !is PaywallStep.SelectPlan) return@LaunchedEffect
        LicenseManager.syncOnce(context, force = true)
        val resp = LicenseApi.getPlans() ?: return@LaunchedEffect
        applyServerConfig(resp)
    }

    val plans = plansResponse?.plans?.ifEmpty { defaultPlans } ?: defaultPlans
    val currentPlan = plans.find { it.id == selectedPlanId } ?: plans.firstOrNull() ?: defaultPlans.first()
    val supportTelegram = plansResponse?.supportTelegram ?: ""
    val supportWhatsapp = plansResponse?.supportWhatsapp ?: ""

    // Mirror the server's 250 IQD rounding so the amount the user transfers is exact.
    fun roundedPrice(value: Int): Int = Math.round(value / 250.0).toInt() * 250

    val effectiveDiscount = appliedDiscount?.takeIf { it.appliesTo(currentPlan.id) }

    val calculatedPrice = remember(currentPlan, effectiveDiscount) {
        val base = currentPlan.price
        val disc = effectiveDiscount
        if (disc == null) {
            base
        } else {
            when (disc.kind) {
                "PERCENT" -> {
                    val discountVal = roundedPrice(Math.round(base * disc.value / 100.0).toInt())
                    roundedPrice((base - discountVal).coerceAtLeast(0))
                }
                "FIXED" -> roundedPrice((base - Math.min(disc.value, base)).coerceAtLeast(0))
                "FULL" -> 0
                "FREE_DAYS" -> 0
                else -> base
            }
        }
    }

    fun buildWhatsAppUrl(rawTarget: String, message: String): String {
        val clean = rawTarget.trim()
        val base = when {
            clean.isBlank() -> "https://wa.me/"
            clean.startsWith("http://") || clean.startsWith("https://") -> {
                clean.removeSuffix("/")
            }
            clean.startsWith("wa.me/") -> {
                "https://" + clean.removeSuffix("/")
            }
            else -> {
                val digits = clean.filter { it.isDigit() }
                val intlPhone = when {
                    digits.startsWith("00964") -> digits.substring(2)
                    digits.startsWith("964") -> digits
                    digits.startsWith("0") -> "964" + digits.substring(1)
                    digits.length == 10 && digits.startsWith("7") -> "964$digits"
                    else -> digits
                }
                "https://wa.me/$intlPhone"
            }
        }
        val encoded = Uri.encode(message)
        return if (base.contains("?")) "$base&text=$encoded" else "$base?text=$encoded"
    }

    // The WhatsApp button is the hand-off point for the receipt; a placeholder number must not be opened.
    fun isSupportTargetValid(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.isEmpty()) return false
        if (clean.contains("XXXX", ignoreCase = true)) return false
        return clean.filter { it.isDigit() }.length >= 10
    }

    // طريقة دفع واحدة فقط = الدخول مباشرة إلى تفاصيل التحويل دون شاشة اختيار.
    fun openPaymentSheet(preferredMethod: String? = null) {
        selectedMethodIndex = paymentMethods.indexOfFirst { it.name == preferredMethod }
            .takeIf { it >= 0 } ?: 0
        sheetStep = if (paymentMethods.size == 1) PaymentSheetStep.PaymentDetails else PaymentSheetStep.SelectMethod
        sheetError = null
        showPaymentSheet = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SlimScreenHeader(
                title = "الاشتراك في بوردي",
                onBack = onClose
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (step) {
                is PaywallStep.SelectPlan -> {
                    // ── Plans List (Bordy Card Style) ──
                    plans.forEach { plan ->
                        val isSelected = plan.id == selectedPlanId
                        val priceFormatted = "${"%,d".format(plan.price)} د.ع"

                        val (planIcon, planTint) = when (plan.id) {
                            "monthly" -> Icons.Default.Schedule to SellGreen
                            "quarterly" -> Icons.Default.Star to BrandYellow
                            else -> Icons.AutoMirrored.Filled.TrendingUp to SellGreen
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CardShape)
                                .bouncyClickable(scaleDownTo = 0.98f) {
                                    if (selectedPlanId != plan.id) {
                                        selectedPlanId = plan.id
                                        promoCode = ""
                                        promoError = null
                                        checkoutError = null
                                        appliedDiscount = autoDiscount?.takeIf { it.appliesTo(plan.id) }
                                        discountIsAuto = appliedDiscount != null
                                    }
                                },
                            shape = CardShape,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.surfaceVariant
                            else
                                MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) SellGreen else MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) SellGreen else planTint),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = planIcon,
                                            contentDescription = null,
                                            tint = if (isSelected) Color.White else contentColorOn(planTint),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = plan.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (plan.id == "quarterly" || plan.id == "yearly") {
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    shape = CircleShape,
                                                    color = BrandYellow
                                                ) {
                                                    Text(
                                                        text = if (plan.id == "yearly") "أفضل توفير ⭐" else "الأكثر طلباً 🔥",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = OnBrandYellow,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "${plan.days} يوم صلاحية كاملة",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Text(
                                    text = priceFormatted,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelected) SellGreen else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // ── كود الخصم: مطوي افتراضياً — لا يشوّش على من لا يملك كوداً ──
                    if (showPromoField || promoError != null || (appliedDiscount != null && !discountIsAuto)) {
                    Surface(
                        shape = CardShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = promoCode,
                                    onValueChange = {
                                        promoCode = it.trim()
                                        promoError = null
                                    },
                                    placeholder = { Text("أدخل الكود هنا", fontSize = 13.sp) },
                                    modifier = Modifier.weight(1f),
                                    shape = CircleShape,
                                    singleLine = true,
                                    isError = promoError != null,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SellGreen,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        cursorColor = SellGreen
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (promoCode.isNotBlank()) {
                                            isLoading = true
                                            scope.launch {
                                                val check = LicenseApi.checkCode(did, promoCode, selectedPlanId)
                                                isLoading = false
                                                if (check != null && check.optBoolean("ok", false)) {
                                                    val kind = check.optString("kind", "PERCENT")
                                                    val value = check.optInt("value", 0)
                                                    val codeStr = check.optString("code", promoCode)
                                                    if (kind == "OWNER") {
                                                        // Owner/lifetime code: activate directly instead of discounting an order.
                                                        appliedDiscount = null
                                                        discountIsAuto = false
                                                        promoError = null
                                                        isLoading = true
                                                        LicenseManager.activate(context, ownerCode = promoCode.trim())
                                                        delay(3_500)
                                                        isLoading = false
                                                        val st = LicenseManager.state.value
                                                        if (st is LicenseState.Active && st.lifetime) {
                                                            activationSucceeded = true
                                                            Toast.makeText(context, "تم تفعيل اشتراك مدى الحياة، أحسنت! 🍀", Toast.LENGTH_LONG).show()
                                                        } else {
                                                            promoError = "تعذر التفعيل، تأكد من اتصال الإنترنت وحاول مرة أخرى"
                                                        }
                                                        return@launch
                                                    }
                                                    appliedDiscount = DiscountInfo(
                                                        code = codeStr,
                                                        kind = kind,
                                                        value = value,
                                                        plans = check.optString("plans", "*")
                                                    )
                                                    discountIsAuto = false
                                                    promoError = null
                                                    Toast.makeText(context, "تم تطبيق الكود وتوفير المبلغ! 🎁", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    appliedDiscount = autoDiscount?.takeIf { it.appliesTo(selectedPlanId) }
                                                    discountIsAuto = appliedDiscount != null
                                                    promoError = if (check == null) "لا يوجد اتصال، حاول مرة أخرى"
                                                    else errorMessageOf(check) ?: "الكود غير صحيح أو انتهت مدته"
                                                }
                                            }
                                        }
                                    },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BrandYellow,
                                        contentColor = OnBrandYellow,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    enabled = promoCode.isNotBlank() && !isLoading,
                                    modifier = Modifier.bouncyClickable(enabled = promoCode.isNotBlank() && !isLoading) {}
                                ) {
                                    Text("طبّق", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (promoError != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = promoError ?: "",
                                    color = DangerRed,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }

                            if (appliedDiscount != null) {
                                val disc = appliedDiscount!!
                                val discDesc = promoDiscountLabel(disc)
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = SellGreenDark,
                                    shape = CircleShape
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "كود: ${disc.code} ($discDesc)" + if (discountIsAuto) " — مطبّق مباشرة" else "",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                        if (!discountIsAuto) {
                                            TextButton(
                                                onClick = {
                                                    promoCode = ""
                                                    appliedDiscount = autoDiscount?.takeIf { it.appliesTo(selectedPlanId) }
                                                    discountIsAuto = appliedDiscount != null
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("إزالة الكود", fontSize = 12.sp, color = DangerRed, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    } else if (appliedDiscount != null) {
                        // خصم حملة تلقائي — شارة صغيرة فقط دون أي حقل
                        val disc = appliedDiscount!!
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = SellGreenDark,
                            shape = CircleShape
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "خصم مطبّق: ${promoDiscountLabel(disc)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CardShape)
                                .bouncyClickable { showPromoField = true },
                            shape = CardShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocalOffer,
                                    contentDescription = null,
                                    tint = BrandYellow,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "لديك كود خصم؟",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // ── زر الاشتراك الرئيسي ──
                    Button(
                        onClick = {
                            if (paymentMethods.isEmpty()) {
                                        checkoutError = if (plansResponse == null)
                                            "جارٍ تحميل طرق الدفع، انتظر قليلاً"
                                        else
                                            "طرق الدفع غير مهيأة الآن، تواصل معنا وسنساعدك في حلها."
                                    } else {
                                        checkoutError = null
                                        isLoading = true
                                        scope.launch {
                                            val res = LicenseApi.createOrder(
                                                did = did,
                                                planId = selectedPlanId,
                                                // Automatic campaigns are applied by the server itself.
                                                code = if (discountIsAuto) null else appliedDiscount?.code,
                                                payerName = "",
                                                payerPhone = ""
                                            )
                                            isLoading = false

                                            when {
                                                res == null -> {
                                                    checkoutError = "لا يوجد اتصال بالإنترنت، تحقق من الاتصال وحاول مرة أخرى"
                                                }
                                                !res.optBoolean("ok", false) -> {
                                                    if (errorCodeOf(res) == "ACTIVE_ORDER") {
                                                        // An old order (e.g. after clearing app data) is still live — resume it.
                                                        val active = res.optJSONObject("order")
                                                        val activeId = active?.optString("id").orEmpty()
                                                        if (activeId.isBlank()) {
                                                            checkoutError = errorMessageOf(res) ?: "لديك طلب قائم بالفعل"
                                                            return@launch
                                                        }
                                                        currentOrderId = activeId
                                                        finalAmount = active!!.optInt("final_price", finalAmount)
                                                        val details = try { LicenseApi.checkOrder(did, activeId) } catch (_: Exception) { null }
                                                        if (details == null || !details.ok) {
                                                            if (details?.errorCode == "ORDER_NOT_FOUND" || details?.errorCode == "FORBIDDEN") {
                                                                LicenseStore.clearPendingOrder(context)
                                                                checkoutError = "تعذر إكمال الطلب السابق، حاول مرة أخرى"
                                                            } else {
                                                                LicenseStore.savePendingOrder(context, activeId, selectedPlanId, currentPlan.title, finalAmount)
                                                                step = PaywallStep.WaitingApproval
                                                            }
                                                            return@launch
                                                        }
                                                        if (details.paymentMethods.isNotEmpty()) paymentMethods = details.paymentMethods
                                                        if (details.finalPrice > 0) finalAmount = details.finalPrice
                                                        if (details.status == "APPROVED") {
                                                            handleApproved(details.token)
                                                            return@launch
                                                        }
                                                        val planTitle = plans.find { it.id == details.planId }?.title ?: currentPlan.title
                                                        val submitted = details.status == "SUBMITTED"
                                                        orderPlanTitle = planTitle
                                                        waitingSubmitted = submitted
                                                        LicenseStore.savePendingOrder(
                                                            context,
                                                            activeId,
                                                            details.planId.ifBlank { selectedPlanId },
                                                            planTitle,
                                                            finalAmount,
                                                            details.method,
                                                            details.txnRef,
                                                            submitted
                                                        )
                                                        if (submitted) {
                                                            step = PaywallStep.WaitingApproval
                                                        } else if (paymentMethods.isEmpty()) {
                                                            checkoutError = "طرق الدفع غير مهيأة حالياً. تواصل مع الدعم لإتمام الاشتراك."
                                                        } else {
                                                            openPaymentSheet(details.method)
                                                        }
                                                    } else {
                                                        checkoutError = errorMessageOf(res) ?: "تعذر إنشاء الطلب، حاول مرة أخرى"
                                                    }
                                                }
                                                else -> {
                                                    val orderObj = res.optJSONObject("order")
                                                    if (orderObj == null) {
                                                        checkoutError = "تعذر إنشاء الطلب، حاول مرة أخرى"
                                                    } else {
                                                        val orderId = orderObj.optString("id")
                                                        val finalPrice = orderObj.optInt("final_price", calculatedPrice)
                                                        val orderStatus = orderObj.optString("status")
                                                        val token = res.optNullableString("token")
                                                        val methods = parsePaymentMethods(res.optJSONArray("payment_methods"))
                                                        if (methods.isNotEmpty()) paymentMethods = methods

                                                        currentOrderId = orderId
                                                        finalAmount = finalPrice
                                                        orderPlanTitle = plans.find { it.id == selectedPlanId }?.title ?: currentPlan.title
                                                        waitingSubmitted = orderStatus == "SUBMITTED"

                                                        when {
                                                            orderStatus == "APPROVED" -> {
                                                                // Free order auto-approved — activate immediately.
                                                                handleApproved(token)
                                                            }
                                                            paymentMethods.isEmpty() -> {
                                                                checkoutError = "طرق الدفع غير مهيأة الآن، تواصل معنا وسنساعدك في حلها."
                                                                LicenseApi.cancelOrder(did, orderId)
                                                            }
                                                            else -> {
                                                                LicenseStore.savePendingOrder(
                                                                    context, orderId, selectedPlanId, currentPlan.title, finalPrice
                                                                )
                                                                openPaymentSheet()
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .bouncyClickable(enabled = !isLoading) {},
                                colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                                shape = CircleShape,
                                enabled = !isLoading
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("اشترك الآن", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (checkoutError != null) {
                                Text(
                                    text = checkoutError ?: "",
                                    color = DangerRed,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                }

                is PaywallStep.WaitingApproval -> {
                    // A restart drops in-memory state — reload the saved order once.
                    LaunchedEffect(Unit) {
                        val pending = LicenseStore.getPendingOrder(context)
                        if (pending != null) {
                            if (currentOrderId.isBlank()) currentOrderId = pending.id
                            if (currentOrderId == pending.id) {
                                if (finalAmount <= 0) finalAmount = pending.amount
                                if (orderPlanTitle.isBlank()) orderPlanTitle = pending.planTitle
                                waitingSubmitted = pending.submitted
                            }
                        }
                    }

                    // Poll for approval: check immediately, then every 8 seconds.
                    LaunchedEffect(currentOrderId) {
                        if (currentOrderId.isBlank()) return@LaunchedEffect
                        while (true) {
                            val resp = try { LicenseApi.checkOrder(did, currentOrderId) } catch (_: Exception) { null }
                            if (resp != null) {
                                if (resp.paymentMethods.isNotEmpty()) paymentMethods = resp.paymentMethods
                                if (resp.finalPrice > 0) finalAmount = resp.finalPrice
                                when {
                                    resp.status == "APPROVED" -> {
                                        handleApproved(resp.token)
                                        break
                                    }
                                    resp.status == "SUBMITTED" -> {
                                        if (!waitingSubmitted) {
                                            waitingSubmitted = true
                                            LicenseStore.updatePendingProof(context, resp.method, resp.txnRef, true)
                                        }
                                    }
                                    resp.status == "REJECTED" -> {
                                        showPaymentSheet = false
                                        LicenseStore.clearPendingOrder(context)
                                        rejectionReason = resp.rejectReason ?: "تم رفض الطلب من قبل الإدارة"
                                        step = PaywallStep.Rejected
                                        break
                                    }
                                    resp.status == "CANCELLED" || resp.status == "EXPIRED" -> {
                                        showPaymentSheet = false
                                        LicenseStore.clearPendingOrder(context)
                                        Toast.makeText(
                                            context,
                                            if (resp.status == "EXPIRED") "انتهت مهلة الطلب، أنشئ طلباً جديداً" else "تم إلغاء الطلب",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        step = PaywallStep.SelectPlan
                                        break
                                    }
                                    !resp.ok && (resp.errorCode == "ORDER_NOT_FOUND" || resp.errorCode == "FORBIDDEN") -> {
                                        showPaymentSheet = false
                                        LicenseStore.clearPendingOrder(context)
                                        checkoutError = resp.errorMessage ?: "تعذر العثور على الطلب، أنشئ طلباً جديداً"
                                        step = PaywallStep.SelectPlan
                                        break
                                    }
                                    else -> { /* Network hiccup or still PENDING — keep polling. */ }
                                }
                            }
                            delay(4_000)
                        }
                    }

                    Surface(
                        shape = CardShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(SellGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
                            }
                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = if (waitingSubmitted) "طلبك قيد المراجعة الآن ⏳" else "أكمل التحويل 💳",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (waitingSubmitted) "وصلنا طلبك. نؤكد الحوالة ويُفعّل اشتراكك تلقائياً خلال دقائق."
                                else "تم تسجيل طلبك ($currentOrderId).\nحوّل المبلغ وأرسل صورة الوصل على واتساب، وسنفعّل حسابك فور التأكيد.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (orderPlanTitle.isNotBlank() || finalAmount > 0) {
                                Spacer(Modifier.height(12.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = SellGreenDark,
                                    border = BorderStroke(1.dp, SellGreen)
                                ) {
                                    Text(
                                        text = listOfNotNull(
                                            orderPlanTitle.takeIf { it.isNotBlank() },
                                            if (finalAmount > 0) "${"%,d".format(finalAmount)} د.ع" else null
                                        ).joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            if (approvalNote != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = approvalNote ?: "",
                                    color = BrandYellow,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(Modifier.height(20.dp))

                            if (paymentMethods.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { openPaymentSheet(LicenseStore.getPendingOrder(context)?.method) },
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                ) {
                                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (waitingSubmitted) "عرض تفاصيل التحويل" else "إعادة إرسال الوصل على واتساب", fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            Button(
                                onClick = {
                                    if (isLoading) return@Button
                                    isLoading = true
                                    scope.launch {
                                        // An unsent receipt (e.g. the app was closed right after the transfer) is retried here.
                                        if (!waitingSubmitted) {
                                            val pendingOrder = LicenseStore.getPendingOrder(context)
                                            val methodName = pendingOrder?.method
                                                ?: paymentMethods.getOrNull(selectedMethodIndex)?.name
                                            if (!methodName.isNullOrBlank()) {
                                                val imageBase64 = withContext(Dispatchers.IO) {
                                                    LicenseStore.getReceiptBase64(context, currentOrderId) ?: ""
                                                }
                                                val storedTxn = pendingOrder?.txnRef ?: ""
                                                val proof = try {
                                                    LicenseApi.submitProof(did, currentOrderId, methodName, storedTxn, imageBase64)
                                                } catch (_: Exception) { null }
                                                if (proof?.optBoolean("ok", false) == true) {
                                                    waitingSubmitted = true
                                                    LicenseStore.updatePendingProof(
                                                        context,
                                                        methodName,
                                                        storedTxn.ifBlank { null },
                                                        true
                                                    )
                                                }
                                            }
                                        }

                                        val s = try { LicenseApi.checkOrder(did, currentOrderId) } catch (_: Exception) { null }
                                        isLoading = false
                                        when {
                                            s == null -> Toast.makeText(context, "تعذر الاتصال بالخادم، تحقق من الإنترنت", Toast.LENGTH_SHORT).show()
                                            s.status == "APPROVED" -> {
                                                handleApproved(s.token)
                                            }
                                            s.status == "REJECTED" -> {
                                                LicenseStore.clearPendingOrder(context)
                                                rejectionReason = s.rejectReason ?: "تم رفض الطلب"
                                                step = PaywallStep.Rejected
                                            }
                                            s.status == "CANCELLED" || s.status == "EXPIRED" -> {
                                                LicenseStore.clearPendingOrder(context)
                                                Toast.makeText(context, "لم يعد الطلب صالحاً، أنشئ طلباً جديداً", Toast.LENGTH_LONG).show()
                                                step = PaywallStep.SelectPlan
                                            }
                                            !s.ok && (s.errorCode == "ORDER_NOT_FOUND" || s.errorCode == "FORBIDDEN") -> {
                                                LicenseStore.clearPendingOrder(context)
                                                checkoutError = s.errorMessage ?: "تعذر العثور على الطلب، أنشئ طلباً جديداً"
                                                step = PaywallStep.SelectPlan
                                            }
                                            s.status == "SUBMITTED" -> {
                                                waitingSubmitted = true
                                                LicenseStore.updatePendingProof(context, s.method, s.txnRef, true)
                                                Toast.makeText(context, "استلمنا الوصل، وطلبك قيد المراجعة الآن", Toast.LENGTH_SHORT).show()
                                            }
                                            else -> Toast.makeText(context, "الطلب قيد المراجعة، ويتم تفعيل الاشتراك فور التأكيد", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .bouncyClickable(enabled = !isLoading) {}
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (waitingSubmitted) "تحقق من التفعيل الآن" else "إرسال الوصل والتحقق من التفعيل",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = {
                                    if (isLoading) return@OutlinedButton
                                    isLoading = true
                                    scope.launch {
                                        val cancelled = LicenseApi.cancelOrder(did, currentOrderId)
                                        isLoading = false
                                        LicenseStore.clearPendingOrder(context)
                                        if (!cancelled) {
                                            Toast.makeText(context, "قد يكون الطلب قيد المراجعة ولم يُلغَ. راجع الدعم إذا استمر الظهور.", Toast.LENGTH_LONG).show()
                                        }
                                        step = PaywallStep.SelectPlan
                                    }
                                },
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                Text("إلغاء الطلب والعودة إلى الباقات", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                is PaywallStep.Rejected -> {
                    Surface(
                        shape = CardShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, DangerRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(DangerRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }

                            Spacer(Modifier.height(14.dp))

                            Text(
                                text = "تعذر تأكيد الطلب",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = DangerRed
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = rejectionReason ?: "السبب غير محدد، يرجى مراجعة إيصال التحويل والتواصل مع الدعم.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(Modifier.height(20.dp))

                            Button(
                                onClick = { step = PaywallStep.SelectPlan },
                                colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .bouncyClickable {}
                            ) {
                                Text("إنشاء طلب جديد", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ── Support Channels ──
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "تحتاج مساعدة أو استفسار؟ تواصل مع الدعم المباشر:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isSupportTargetValid(supportWhatsapp)) {
                            Button(
                                onClick = {
                                    val url = buildWhatsAppUrl(supportWhatsapp, "مرحباً، أود الاستفسار عن الاشتراك في تطبيق بوردي 🍀 (معرف جهازي: $did)")
                                    try {
                                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(i)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "تعذر فتح واتساب", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                                shape = CircleShape,
                                modifier = Modifier.bouncyClickable {}
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_whatsapp),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("واتساب", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        if (supportTelegram.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(supportTelegram))
                                        context.startActivity(i)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "تعذر فتح تيليجرام", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = CircleShape
                            ) {
                                Text("تيليجرام 💬", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Payment Instructions Modal BottomSheet («إتمام الدفع») ──
    if (showPaymentSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showPaymentSheet = false
                sheetStep = PaymentSheetStep.SelectMethod
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = DialogShape
        ) {
            val selectedMethod = paymentMethods.getOrNull(selectedMethodIndex)
                ?: paymentMethods.firstOrNull()
                ?: PaymentMethod(id = "", name = "—", account = "—", note = "", borderColor = "", hasLogo = false)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── زر الإغلاق فقط (حُذف البانر الأخضر وعنوان «إتمام التحويل والاشتراك») ──
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TintedCircleButton(
                        onClick = {
                            showPaymentSheet = false
                            sheetStep = PaymentSheetStep.SelectMethod
                        },
                        icon = Icons.Default.Close,
                        contentDescription = "إغلاق",
                        tint = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 34.dp,
                        iconSize = 17.dp,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }

                if (sheetError != null) {
                    Text(
                        text = sheetError ?: "",
                        color = DangerRed,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (sheetStep == PaymentSheetStep.SelectMethod) {
                    // ── شبكة إيقونات طرق الدفع: الصورة المعيّنة من الإدارة هي الإيقونة نفسها،
                    //    والضغط على الإيقونة ينقل مباشرة إلى إكمال الدفع ──
                    Text(
                        text = "اختر طريقة الدفع",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (paymentMethods.isEmpty()) {
                        Text(
                            text = "لا توجد طرق دفع متاحة حالياً، تواصل مع الدعم.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val columns = 3
                    var tileIndex = 0
                    paymentMethods.chunked(columns).forEach { rowMethods ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowMethods.forEach { pm ->
                                val index = tileIndex++
                                val chosen = index == selectedMethodIndex
                                val frameColor = pm.borderColor.toComposeColorOrNull()
                                    ?: if (chosen) SellGreen else MaterialTheme.colorScheme.outline
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (chosen) SellGreenDark else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(if (chosen) 2.dp else 1.25.dp, frameColor),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(18.dp))
                                        .bouncyClickable {
                                            selectedMethodIndex = index
                                            sheetStep = PaymentSheetStep.PaymentDetails
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = 12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val logoUrl = pm.logoUrl(BuildConfig.API_BASE_URL)
                                        if (logoUrl != null) {
                                            AsyncImage(
                                                model = logoUrl,
                                                contentDescription = pm.name,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .fillMaxWidth(0.68f)
                                                    .aspectRatio(1f)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.68f)
                                                    .aspectRatio(1f)
                                                    .clip(CircleShape)
                                                    .background(if (chosen) SellGreen else MaterialTheme.colorScheme.surface),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = pm.name.take(1),
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 24.sp,
                                                    color = if (chosen) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Text(
                                            text = pm.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (chosen) Color.White else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                            repeat(columns - rowMethods.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                } else {
                    if (sheetStep == PaymentSheetStep.PaymentDetails) {
                        // ── ترويسة تفاصيل التحويل: رجوع إلى شبكة الإيقونات + اسم الطريقة ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TintedCircleButton(
                                onClick = { sheetStep = PaymentSheetStep.SelectMethod },
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 34.dp,
                                iconSize = 17.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "الدفع عبر ${selectedMethod.name}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // ── 4. بطاقة المحفظة: المبلغ في الزاوية اليسرى العلوية + العبارة باليمين + رقم البطاقة بالأصفر ──
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CardShape,
                        border = BorderStroke(1.5.dp, BrandYellow)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // الصف العلوي للبطاقة:
                            // اليمين (RTL): أيقونة صفراء + عبارة "قم بالتحويل على هذا الرقم:"
                            // اليسار (الزاوية اليسرى العلوية): المبلغ المراد تحويله
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(BrandYellow),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Payments,
                                            contentDescription = null,
                                            tint = contentColorOn(BrandYellow),
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "حوّل المبلغ إلى هذا الرقم:",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = BrandYellow
                                    )
                                }

                                // المبلغ بالزاوية اليسرى العلوية
                                Surface(
                                    shape = CircleShape,
                                    color = SellGreenDark,
                                    border = BorderStroke(1.dp, SellGreen)
                                ) {
                                    Text(
                                        text = "${"%,d".format(finalAmount)} د.ع",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // ملاحظة الإدارة (إن وُجدت) — الاسم ظاهر في الترويسة فلا داعي لتكراره
                            if (selectedMethod.note.isNotBlank()) {
                                Text(
                                    text = selectedMethod.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // صف رقم البطاقة وزر النسخ — لمس الرقم نفسه ينسخ أيضاً
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // رقم البطاقة باللون الأصفر كما طلب المستخدم بدقة
                                Text(
                                    text = selectedMethod.account,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BrandYellow,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText(selectedMethod.name, selectedMethod.account))
                                            Toast.makeText(context, "تم نسخ الرقم ✅", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )

                                Button(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText(selectedMethod.name, selectedMethod.account))
                                        Toast.makeText(context, "تم نسخ رقم ${selectedMethod.name}. حوّل من محفظتك ✅", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                                    modifier = Modifier.bouncyClickable {}
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("نسخ الرقم", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // ── زر التأكيد عبر واتساب مع أيقونة واتساب مكان الدائرة الزرقاء بدون أيقونة بالنص ──
                    if (sheetStep == PaymentSheetStep.PaymentDetails) {
                    Button(
                        onClick = {
                            val message = buildString {
                                append("مرحباً، حوّلت مبلغ اشتراك بوردي 🍀\n")
                                append("الباقة: ${orderPlanTitle.ifBlank { currentPlan.title }}\n")
                                append("المبلغ: ${"%,d".format(finalAmount)} د.ع\n")
                                append("طريقة التحويل: ${selectedMethod.name}\n")
                                append("كود الطلب: $currentOrderId\n")
                                append("كود الجهاز: $did\n")
                                append("\nوهذه صورة الوصل 📸")
                            }

                            // A placeholder support number must not be dialed; fall back to WhatsApp's share sheet.
                            val target = if (isSupportTargetValid(supportWhatsapp)) supportWhatsapp else ""
                            val fullUrl = buildWhatsAppUrl(target, message)

                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "تم نسخ تفاصيل الطلب، أرسلها على واتساب ✅", Toast.LENGTH_SHORT).show()
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Order Info", message))
                            }

                            // رسالة واتساب نفسها تصل الإدارة كاملة؛ نسجّل الطلب «مرسلاً» تلقائياً
                            // وننتقل إلى شاشة الانتظار — بلا خطوات إضافية على المستخدم.
                            scope.launch {
                                val txn = LicenseStore.getPendingOrder(context)?.txnRef
                                val resp = try {
                                    LicenseApi.submitProof(did, currentOrderId, selectedMethod.name, txn ?: "", "")
                                } catch (_: Exception) { null }
                                if (resp?.optBoolean("ok", false) == true) {
                                    waitingSubmitted = true
                                    LicenseStore.updatePendingProof(context, selectedMethod.name, txn, true)
                                }
                                showPaymentSheet = false
                                sheetStep = PaymentSheetStep.SelectMethod
                                step = PaywallStep.WaitingApproval
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .bouncyClickable {},
                        colors = ButtonDefaults.buttonColors(containerColor = SellGreen),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // أيقونة واتساب داخل دائرة مكان الدائرة الزرقاء كما حدد بالصورة
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(SellGreenDark),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_whatsapp),
                                    contentDescription = "WhatsApp",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // النص موسط وخالي من أي أيقونة داخله
                            Text(
                                text = "أرسل صورة الحوالة على واتساب",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            // Spacer متوازن للحفاظ على النص في المنتصف بدقة
                            Spacer(Modifier.size(40.dp))
                        }
                    }

                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private sealed class PaywallStep {
    object SelectPlan : PaywallStep()
    object WaitingApproval : PaywallStep()
    object Rejected : PaywallStep()
}
