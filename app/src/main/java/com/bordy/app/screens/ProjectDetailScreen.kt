package com.bordy.app.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.bordy.app.R
import com.bordy.app.data.*
import com.bordy.app.ui.components.DialogShape
import com.bordy.app.ui.components.BrandButton
import com.bordy.app.ui.components.BrandFab
import com.bordy.app.ui.components.EmptyState
import com.bordy.app.ui.components.PieceGridTile
import com.bordy.app.ui.components.PieceCountBadge
import com.bordy.app.ui.components.TemplatePiecesDialog
import com.bordy.app.ui.components.TemplateListCard
import com.bordy.app.ui.components.SlimScreenHeader
import com.bordy.app.ui.components.TintedCircleButton
import com.bordy.app.ui.components.StepButton
import com.bordy.app.ui.components.matchesTagSearch
import com.bordy.app.utils.*
import com.bordy.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectDetailScreen(
    vm: MainViewModel,
    projectId: Long,
    pricesHidden: Boolean = false,
    onBack: () -> Unit
) {
    val project by vm.getProject(projectId).collectAsState(initial = null)
    val itemsFromDb by vm.getProjectItems(projectId).collectAsState(initial = emptyList())
    val components by vm.components.collectAsState()
    val variants by vm.variants.collectAsState()
    val allTemplates by vm.templates.collectAsState()
    val allTemplateItems by vm.templateItems.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val snackbarHostState = remember { SnackbarHostState() }
    var localItems by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    LaunchedEffect(itemsFromDb) { localItems = itemsFromDb }

    var showAddSheet by remember { mutableStateOf(false) }
    var showTemplateSelection by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showLaborDialog by remember { mutableStateOf(false) }
    var showReceivedDialog by remember { mutableStateOf(false) }
    var showRemainingDialog by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }
    var manualFulfillItem by remember { mutableStateOf<ProjectItem?>(null) }
    var previewTemplate by remember { mutableStateOf<Template?>(null) }
    // القطع اليدوية المضافة أثناء وضع إخفاء الأسعار تبقى أسعارها ظاهرة كاستثناء مقصود
    var revealedManualNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun pricesVisibleFor(item: ProjectItem): Boolean =
        !pricesHidden || (item.isManual && revealedManualNames.contains(item.manualName))

    var measuredItemHeights by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    val spacingPx = with(density) { 12.dp.toPx() }
    val fallbackSlotHeightPx = with(density) { 80.dp.toPx() }
    var draggedItemId by remember { mutableStateOf<Long?>(null) }
    var draggedItemInitialIndex by remember { mutableStateOf(-1) }
    var accumulatedDrag by remember { mutableStateOf(0f) }

    var isListView by remember { mutableStateOf(true) }
    var gridActionItem by remember { mutableStateOf<ProjectItem?>(null) }
    var conflictItem by remember { mutableStateOf<ProjectItem?>(null) }
    var gridCellPitch by remember { mutableStateOf(IntSize.Zero) }
    var gridDragOffset by remember { mutableStateOf(Offset.Zero) }
    val gridSpacingPx = with(density) { 6.dp.toPx() }

    if (project == null) return
    val p = project!!

    val outOfPocket = localItems.sumOf { item ->
        when {
            item.isShortage -> item.shortageManualPurchase
            !item.fromStock -> item.lastKnownPurchase * item.quantity
            else -> 0.0
        }
    }
    val totalMaterialCost = localItems.sumOf { it.billablePurchaseTotal }
    val totalSellCost = localItems.sumOf { it.billableSellTotal }
    val totalCost = totalSellCost + p.laborCost
    val remainingAmount = totalCost - p.receivedAmount
    val remainingColor = if (remainingAmount <= 0.0) SellGreen else BrandYellow

    fun showBoardProfitNotification() {
        val profit = totalCost - totalMaterialCost
        val msg = "أرباح البورد الحالية: ${formatCurrency(profit)}"
        scope.launch {
            launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Indefinite) }
            delay(5000)
            if (snackbarHostState.currentSnackbarData?.visuals?.message == msg) {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, SellGreen),
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.TrendingUp,
                            contentDescription = null,
                            tint = SellGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            data.visuals.message,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SlimScreenHeader(
                title = p.customerName,
                onBack = onBack
            ) {
                TintedCircleButton(
                    onClick = { isListView = !isListView },
                    icon = if (isListView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = if (isListView) "عرض شبكة" else "عرض قائمة",
                    tint = SellGreen,
                    size = 40.dp,
                    iconSize = 20.dp
                )
                Spacer(Modifier.width(8.dp))
                TintedCircleButton(
                    onClick = {
                        if (pricesHidden) {
                            scope.launch { snackbarHostState.showSnackbar("الأسعار مخفية — حط بصمتك حتى تشوف الأرباح") }
                        } else showBoardProfitNotification()
                    },
                    icon = Icons.Rounded.MonetizationOn,
                    contentDescription = "أرباح البورد",
                    tint = SellGreen,
                    size = 40.dp,
                    iconSize = 20.dp
                )
                Spacer(Modifier.width(8.dp))
                TintedCircleButton(
                    onClick = {
                        if (pricesHidden) {
                            scope.launch { snackbarHostState.showSnackbar("ما تكدر تصدّر قائمة بالأسعار وانت خافيهن — طلع الأسعار أول") }
                            return@TintedCircleButton
                        }
                        val pdfFile = PdfHelper.generateProjectPdf(context, p, localItems, variants, components, emptyList(), settings ?: AppSettings())
                        if (!pdfFile.exists() || pdfFile.length() == 0L) {
                            scope.launch { snackbarHostState.showSnackbar("ما كدرنا نسوي القائمة، جرب مرة ثانية") }
                        } else {
                            runCatching {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", pdfFile)
                                val intent = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                context.startActivity(Intent.createChooser(intent, "دز قائمة البورد"))
                            }.onFailure {
                                scope.launch { snackbarHostState.showSnackbar("ماكو تطبيق بجهازك لمشاركة الملف") }
                            }
                        }
                    },
                    icon = Icons.Default.Share,
                    contentDescription = "صدّر قائمة PDF",
                    tint = BrandYellow,
                    size = 40.dp,
                    iconSize = 19.dp
                )
                Spacer(Modifier.width(8.dp))
                TintedCircleButton(
                    onClick = { showAttachments = true },
                    icon = Icons.Default.AttachFile,
                    contentDescription = "مرفقات البورد وملاحظته",
                    tint = SellGreen,
                    size = 40.dp,
                    iconSize = 19.dp
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    TintedCircleButton(
                        onClick = { showStatusMenu = true },
                        icon = Icons.Default.MoreVert,
                        contentDescription = "حالة البورد",
                        tint = MaterialTheme.colorScheme.primary,
                        size = 40.dp,
                        iconSize = 20.dp
                    )
                    DropdownMenu(expanded = showStatusMenu, onDismissRequest = { showStatusMenu = false }) {
                        DropdownMenuItem(text = { Text("شغّال عليه") }, onClick = { vm.updateProjectStatus(p, "ACTIVE"); showStatusMenu = false }, leadingIcon = { Icon(Icons.Default.Build, null, tint = BrandYellow) })
                        DropdownMenuItem(text = { Text("مجدول للمستقبل") }, onClick = { vm.updateProjectStatus(p, "FUTURE"); showStatusMenu = false }, leadingIcon = { Icon(Icons.Default.DateRange, null, tint = BrandYellow) })
                        DropdownMenuItem(text = { Text("مكمّل ومنتهي") }, onClick = { vm.updateProjectStatus(p, "DONE"); showStatusMenu = false }, leadingIcon = { Icon(Icons.Default.CheckCircle, null, tint = BrandYellow) })
                    }
                }
            }
        },
        floatingActionButton = {
            if (p.status != "DONE") {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandFab(
                        onClick = { showTemplateSelection = true },
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "سحب قالب",
                        containerColor = BrandYellow,
                        contentColor = OnBrandYellow
                    )
                    BrandFab(
                        onClick = { showAddSheet = true },
                        icon = Icons.Default.Add,
                        contentDescription = "إضافة قطعة"
                    )
                }
            }
        }
    ) { padding ->
        if (isListView) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (localItems.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Inventory,
                        message = "البورد فاضي بعده.. أضف أول قطعة! ⚡"
                    )
                }
            }
            items(localItems, key = { it.id }) { item ->
                val isDragging = draggedItemId == item.id
                val currentIndex = localItems.indexOfFirst { it.id == item.id }
                val variant = if (item.variantId != null) variants.find { it.id == item.variantId } else null
                val comp = if (variant != null) components.find { it.id == variant.componentId } else null
                // قطعة تشير لحجم حُذف من المخزون بعد إغلاق البورد — تُدار يدوياً بلا ارتباط بالمخزون
                val variantMissing = !item.isManual && !item.isShortage && item.variantId != null && variant == null

                val displayName = when {
                    item.isManual -> item.manualName
                    comp != null -> componentLabel(comp.name, variant)
                    item.manualName.isNotBlank() -> item.manualName
                    else -> "قطعة محذوفة"
                }
                val imageUri = comp?.imageUri ?: ""
                val isNotFromStock = !item.fromStock
                val isShortage = item.isShortage
                val shortagePending = isShortage && item.shortageRemaining > 0
                val availableStock = variant?.stockCount ?: 0
                val showStockCard = availableStock > 0 || item.shortageFromStock > 0 || item.shortageAddedQty > 0
                val textColor = when {
                    variantMissing -> DangerRed
                    shortagePending -> DangerRed
                    isShortage -> MaterialTheme.colorScheme.onSurface
                    isNotFromStock -> DangerRed
                    else -> MaterialTheme.colorScheme.onSurface
                }
                // جيل سعري: بطاقات الحجم نفسه قد تحمل لقطات أسعار مختلفة بعد تعديل سعر القطعة
                val priceStale = variant != null && !item.isManual && !isShortage &&
                    (!pricesEqual(item.sellPrice, variant.defaultSellPrice) ||
                        !pricesEqual(item.lastKnownPurchase, variant.defaultPurchasePrice))
                val sameVariantGenerations = if (variant == null) 0 else localItems.count {
                    it.variantId == variant.id && !it.isManual && !it.isShortage
                }
                val itemPricesVisible = pricesVisibleFor(item)
                val priceBadgeText: String? = if (itemPricesVisible && variant != null && !item.isManual && !isShortage &&
                    p.status != "DONE" && (priceStale || sameVariantGenerations > 1)
                ) {
                    if (priceStale) "سعر سابق — الحالي: شراء ${formatCurrency(variant.defaultPurchasePrice)} / بيع ${formatCurrency(variant.defaultSellPrice)}"
                    else "سعر محدّث: شراء ${formatCurrency(item.lastKnownPurchase)} / بيع ${formatCurrency(item.sellPrice)}"
                } else null
                val priceBadgeColor = if (priceStale) BuyAmber else SellGreen
                // دفعات النقص تبقى معلّقة لوقت التوفير، فسعرها يُعرض عند تعدده أو عند تغيّر السعر
                val sameVariantPendingShortages = if (variant == null) 0 else localItems.count {
                    it.variantId == variant.id && it.isShortage && it.shortageRemaining > 0
                }
                val shortagePriceStale = variant != null && isShortage && shortagePending &&
                    (!pricesEqual(item.sellPrice, variant.defaultSellPrice) ||
                        !pricesEqual(item.lastKnownPurchase, variant.defaultPurchasePrice))
                val shortagePriceText: String? = if (itemPricesVisible && shortagePending && p.status != "DONE" &&
                    (sameVariantPendingShortages > 1 || shortagePriceStale)
                ) {
                    val prefix = if (shortagePriceStale) "دفعة بسعر سابق" else "سعر هذه الدفعة"
                    "$prefix: شراء ${formatCurrency(item.lastKnownPurchase)} / بيع ${formatCurrency(item.sellPrice)}"
                } else null

                val indexDelta = if (draggedItemInitialIndex >= 0) currentIndex - draggedItemInitialIndex else 0
                // ارتفاع البطاقة يختلف بين قطعة عادية وبطاقة نقص (لها بطاقة مخزون إضافية) فتُقاس كل قطعة على حدة
                val itemHeightPx = measuredItemHeights[item.id] ?: 0
                val slotHeightPx = if (itemHeightPx > 0) itemHeightPx + spacingPx else fallbackSlotHeightPx
                val liveSlotHeight = rememberUpdatedState(slotHeightPx)
                val displayTranslationY = if (isDragging) accumulatedDrag - (indexDelta * slotHeightPx) else 0f

                val visualModifier: Modifier = if (isDragging) {
                    Modifier.zIndex(1f).graphicsLayer {
                        translationY = displayTranslationY
                        scaleX = 1.03f
                        scaleY = 1.03f
                        shadowElevation = 16f
                        alpha = 0.96f
                    }
                } else {
                    Modifier.zIndex(0f).animateItem(
                        placementSpec = spring<IntOffset>(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioNoBouncy
                        )
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            val h = coords.size.height
                            if (h > 0 && measuredItemHeights[item.id] != h) {
                                measuredItemHeights = measuredItemHeights + (item.id to h)
                            }
                        }
                        .then(if (p.status != "DONE") visualModifier else Modifier)
                ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = when {
                                variantMissing -> 2.dp
                                isShortage -> 1.5.dp
                                else -> 1.dp
                            },
                            color = when {
                                variantMissing -> DangerRed
                                shortagePending -> DangerRed
                                isShortage -> BrandYellow
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            },
                            shape = RoundedCornerShape(18.dp)
                        )
                        .then(
                            if (p.status != "DONE") {
                                Modifier.pointerInput(item.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedItemId = item.id
                                            draggedItemInitialIndex = localItems.indexOfFirst { it.id == item.id }
                                            accumulatedDrag = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            accumulatedDrag += dragAmount.y
                                            var safety = 0
                                            while (safety++ < 10) {
                                                val idx = localItems.indexOfFirst { it.id == item.id }
                                                if (idx < 0) break
                                                val visualOffset = accumulatedDrag - ((idx - draggedItemInitialIndex) * liveSlotHeight.value)
                                                if (visualOffset > liveSlotHeight.value * 0.5f && idx < localItems.size - 1) {
                                                    val list = localItems.toMutableList()
                                                    val moving = list.removeAt(idx)
                                                    list.add(idx + 1, moving)
                                                    localItems = list
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                } else if (visualOffset < -liveSlotHeight.value * 0.5f && idx > 0) {
                                                    val list = localItems.toMutableList()
                                                    val moving = list.removeAt(idx)
                                                    list.add(idx - 1, moving)
                                                    localItems = list
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                } else break
                                            }
                                        },
                                        onDragEnd = {
                                            val finalIdx = localItems.indexOfFirst { it.id == item.id }
                                            val targetAcc = (finalIdx - draggedItemInitialIndex) * liveSlotHeight.value
                                            scope.launch {
                                                animate(
                                                    initialValue = accumulatedDrag,
                                                    targetValue = targetAcc,
                                                    animationSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
                                                ) { v: Float, _: Float -> accumulatedDrag = v }
                                                draggedItemId = null
                                                accumulatedDrag = 0f
                                                draggedItemInitialIndex = -1
                                                vm.reorderProjectItems(localItems)
                                            }
                                        },
                                        onDragCancel = {
                                            scope.launch {
                                                animate(
                                                    initialValue = accumulatedDrag,
                                                    targetValue = 0f,
                                                    animationSpec = spring<Float>()
                                                ) { v: Float, _: Float -> accumulatedDrag = v }
                                                draggedItemId = null
                                                accumulatedDrag = 0f
                                                draggedItemInitialIndex = -1
                                                localItems = itemsFromDb
                                            }
                                        }
                                    )
                                }
                            } else Modifier
                        )
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (p.status != "DONE") {
                            Icon(Icons.Default.DragHandle, "اسحب", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDragging) 0.8f else 0.3f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            if (imageUri.isNotEmpty()) AsyncImage(model = imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else if (shortagePending) Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed)
                            else if (item.isManual) Icon(Icons.Default.Edit, contentDescription = null, tint = SellGreen)
                            else Image(painter = painterResource(id = R.drawable.clover_placeholder), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(displayName, fontWeight = FontWeight.Bold, color = textColor)
                                if (item.isManual) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(color = BrandYellow, shape = RoundedCornerShape(4.dp)) {
                                        Text("يدوي", style = MaterialTheme.typography.labelSmall, color = OnBrandYellow, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                    }
                                }
                                if (isShortage) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = if (shortagePending) DangerRed else BrandYellow,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            if (shortagePending) "نقص" else "تم بعد نقص",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (shortagePending) Color.White else OnBrandYellow,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                if (variantMissing) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(color = DangerRed, shape = RoundedCornerShape(4.dp)) {
                                        Text("تعارض", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                    }
                                }
                            }
                            if (variantMissing) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "هذه القطعة لم تعد في المخزون — عدّلها يدوياً لتصبح قطعة مستقلة بلا ارتباط بالمخزون.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DangerRed,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (isShortage) {
                                Spacer(Modifier.height(4.dp))
                                if (shortagePending) {
                                    Text(
                                        "أحتاج إلى ${item.shortageRemaining} (${displayName})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = DangerRed
                                    )
                                    if (item.shortageManual > 0) {
                                        Text(
                                            "وفّرت يدوياً: الكمية ${item.shortageManual}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SellGreen
                                        )
                                        if (itemPricesVisible) ShortageMathLines(item)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        if (availableStock > 0) "وفّر قطعة يدوياً بأسعار هذا المشروع فقط"
                                        else "لا يوجد مخزون متاح — وفّر القطعة يدوياً بأسعار هذا المشروع",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BrandYellow
                                    )
                                    if (shortagePriceText != null) {
                                        Spacer(Modifier.height(4.dp))
                                        val shortagePillColor = if (shortagePriceStale) BuyAmber else MaterialTheme.colorScheme.onSurfaceVariant
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(shortagePillColor.copy(alpha = 0.16f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                shortagePriceText.withThousandsDot(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = shortagePillColor,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "تم توفير الكمية ${item.billableQuantity} (من المخزون: ${item.shortageFromStock} • يدوياً: ${item.shortageManual})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SellGreen
                                    )
                                    if (itemPricesVisible) ShortageMathLines(item)
                                }
                            } else if (itemPricesVisible) {
                                Spacer(Modifier.height(4.dp))
                                Text("شراء: ${formatCurrency(item.lastKnownPurchase)} × ${item.quantity} = ${formatCurrency(item.lastKnownPurchase * item.quantity)}".withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = BuyAmber)
                                Text("بيع: ${formatCurrency(item.sellPrice)} × ${item.quantity} = ${formatCurrency(item.sellPrice * item.quantity)}".withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = SellGreen)
                            }
                            if (priceBadgeText != null) {
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(priceBadgeColor.copy(alpha = 0.16f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        priceBadgeText.withThousandsDot(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = priceBadgeColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            if (itemPricesVisible && item.billablePurchaseTotal > 0 && item.billableSellTotal < item.billablePurchaseTotal) {
                                Spacer(Modifier.height(2.dp))
                                Text("بيع بأقل من التكلفة", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DangerRed)
                            }
                        }
                        if (p.status != "DONE") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (variantMissing) {
                                    TintedCircleButton(
                                        onClick = { conflictItem = item },
                                        icon = Icons.Default.Edit,
                                        contentDescription = "تعديل القطعة المتعارضة",
                                        tint = BrandYellow,
                                        size = 32.dp,
                                        iconSize = 16.dp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                if (isShortage) {
                                    if (shortagePending) {
                                        TintedCircleButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                manualFulfillItem = item
                                            },
                                            icon = Icons.Default.Edit,
                                            contentDescription = "توفير يدوي بأسعار المشروع",
                                            tint = BrandYellow,
                                            size = 32.dp,
                                            iconSize = 16.dp
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                            .padding(horizontal = 2.dp)
                                    ) {
                                        StepButton(
                                            onClick = { vm.changeProjectItemQuantity(projectId, item.id, -1) },
                                            icon = Icons.Default.Remove,
                                            contentDescription = "إنقاص",
                                            containerColor = BrandYellow,
                                            size = 28.dp,
                                            iconSize = 15.dp,
                                            enabled = item.quantity > 1
                                        )
                                        Text("${item.quantity}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                                        StepButton(
                                            onClick = {
                                                vm.changeProjectItemQuantity(
                                                    projectId = projectId,
                                                    itemId = item.id,
                                                    delta = 1,
                                                    onStockLimit = { available ->
                                                        scope.launch { snackbarHostState.showSnackbar("المخزون لا يكفي — المتاح: $available") }
                                                    },
                                                    onPriceChanged = { added, purchase, sell ->
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                if (pricesHidden) "تغيّر سعر $displayName — أُضيفت $added في بطاقة بسعرها الحالي"
                                                                else "تغيّر سعر ${displayName} — أُضيفت $added في بطاقة بسعرها الحالي (شراء ${formatCurrency(purchase)} / بيع ${formatCurrency(sell)})"
                                                            )
                                                        }
                                                    }
                                                )
                                            },
                                            icon = Icons.Default.Add,
                                            contentDescription = "زيادة",
                                            containerColor = SellGreen,
                                            size = 28.dp,
                                            iconSize = 15.dp
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                                TintedCircleButton(
                                    onClick = { vm.removeProjectItem(item) },
                                    icon = Icons.Default.Delete,
                                    contentDescription = "حذف",
                                    tint = DangerRed,
                                    size = 32.dp,
                                    iconSize = 17.dp
                                )
                            }
                        }
                    }
                }
                if (shortagePending && showStockCard) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, SellGreen, RoundedCornerShape(18.dp))
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (imageUri.isNotEmpty()) AsyncImage(model = imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Icon(Icons.Default.Inventory, contentDescription = null, tint = SellGreen)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(displayName, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(6.dp))
                                    Surface(color = SellGreen, shape = RoundedCornerShape(4.dp)) {
                                        Text("مخزون", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("تعويض من المخزون — قطعة بكل ضغطة", style = MaterialTheme.typography.labelSmall, color = SellGreen)
                                Text(
                                    if (availableStock > 0) "المخزون المتاح: $availableStock"
                                    else "المخزون فارغ حالياً — أضف قطعاً للمخزون ثم اسحبها",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (availableStock > 0) SellGreen else BrandYellow
                                )
                                if (item.shortageFromStock > 0) {
                                    Text("سحبت من المخزون: الكمية ${item.shortageFromStock}", style = MaterialTheme.typography.labelSmall, color = SellGreen)
                                }
                            }
                            if (p.status != "DONE" && availableStock > 0) {
                                TintedCircleButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.fulfillShortageItem(item) { msg: String ->
                                            scope.launch { snackbarHostState.showSnackbar(msg) }
                                        }
                                    },
                                    icon = Icons.Default.Inventory,
                                    contentDescription = "سحب من المخزون",
                                    tint = SellGreen,
                                    size = 32.dp,
                                    iconSize = 17.dp
                                )
                            }
                        }
                    }
                }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                BoardSummaryCard(
                    project = p,
                    outOfPocket = outOfPocket,
                    totalMaterialCost = totalMaterialCost,
                    totalSellCost = totalSellCost,
                    totalCost = totalCost,
                    remainingAmount = remainingAmount,
                    remainingColor = remainingColor,
                    pieceCount = localItems.size,
                    totalQuantity = localItems.sumOf { it.billableQuantity },
                    pricesHidden = pricesHidden,
                    onDueDate = { showDatePicker = true },
                    onLabor = { showLaborDialog = true },
                    onReceived = { showReceivedDialog = true },
                    onRemaining = { showRemainingDialog = true }
                )
            }
            item { Spacer(Modifier.height(150.dp)) }
        }
        } else {
            if (localItems.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Inventory,
                    message = "البورد فاضي بعده.. أضف أول قطعة! ⚡",
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    gridItems(items = localItems, key = { it.id }) { item ->
                        val variant = if (item.variantId != null) variants.find { it.id == item.variantId } else null
                        val comp = if (variant != null) components.find { it.id == variant.componentId } else null
                        val variantMissing = !item.isManual && !item.isShortage && item.variantId != null && variant == null
                        val displayName = when {
                            item.isManual -> item.manualName
                            comp != null -> componentLabel(comp.name, variant)
                            item.manualName.isNotBlank() -> item.manualName
                            else -> "قطعة محذوفة"
                        }
                        val imageUri = comp?.imageUri ?: ""
                        // القطعة اليدوية بلا ارتباط بالمخزون فلا دائرة مخزون لها
                        val badgeStock = if (item.isManual) null else variant?.stockCount ?: comp?.stockCount ?: 0
                        val shortagePending = item.isShortage && item.shortageRemaining > 0
                        val isDragging = draggedItemId == item.id
                        val currentIndex = localItems.indexOfFirst { it.id == item.id }
                        val currentRow = if (currentIndex >= 0) currentIndex / 5 else 0
                        val currentCol = if (currentIndex >= 0) currentIndex % 5 else 0
                        val startRow = if (draggedItemInitialIndex >= 0) draggedItemInitialIndex / 5 else 0
                        val startCol = if (draggedItemInitialIndex >= 0) draggedItemInitialIndex % 5 else 0
                        val cellWidth = gridCellPitch.width.toFloat().coerceAtLeast(1f)
                        val cellHeight = gridCellPitch.height.toFloat().coerceAtLeast(1f)
                        // في RTL يزيد رقم الخانة نحو اليسار، لذا تُعكس إزاحة الأعمدة
                        val dragTranslationX = if (isDragging) gridDragOffset.x + (currentCol - startCol) * cellWidth else 0f
                        val dragTranslationY = if (isDragging) gridDragOffset.y - (currentRow - startRow) * cellHeight else 0f

                        val dragModifier = if (p.status != "DONE") {
                            Modifier.pointerInput(item.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemId = item.id
                                        draggedItemInitialIndex = localItems.indexOfFirst { it.id == item.id }
                                        gridDragOffset = Offset.Zero
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        gridDragOffset += dragAmount
                                        val start = draggedItemInitialIndex
                                        if (start >= 0 && gridCellPitch.width > 0 && gridCellPitch.height > 0) {
                                            val pitchW = gridCellPitch.width.toFloat()
                                            val pitchH = gridCellPitch.height.toFloat()
                                            val targetRow = (start / 5 + (gridDragOffset.y / pitchH).roundToInt()).coerceAtLeast(0)
                                            val targetCol = (start % 5 - (gridDragOffset.x / pitchW).roundToInt()).coerceIn(0, 4)
                                            val target = (targetRow * 5 + targetCol).coerceIn(0, localItems.lastIndex)
                                            val current = localItems.indexOfFirst { it.id == item.id }
                                            if (current >= 0 && target != current) {
                                                val list = localItems.toMutableList()
                                                val moving = list.removeAt(current)
                                                list.add(target, moving)
                                                localItems = list
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggedItemId = null
                                        gridDragOffset = Offset.Zero
                                        draggedItemInitialIndex = -1
                                        vm.reorderProjectItems(localItems)
                                    },
                                    onDragCancel = {
                                        draggedItemId = null
                                        gridDragOffset = Offset.Zero
                                        draggedItemInitialIndex = -1
                                        localItems = itemsFromDb
                                    }
                                )
                            }
                        } else Modifier

                        Box(
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    if (gridCellPitch.width == 0 && coords.size.width > 0) {
                                        gridCellPitch = IntSize(
                                            coords.size.width + gridSpacingPx.roundToInt(),
                                            coords.size.height + gridSpacingPx.roundToInt()
                                        )
                                    }
                                }
                                .animateItem()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationX = dragTranslationX
                                    translationY = dragTranslationY
                                    scaleX = if (isDragging) 1.05f else 1f
                                    scaleY = if (isDragging) 1.05f else 1f
                                    shadowElevation = if (isDragging) 14f else 0f
                                    alpha = if (isDragging) 0.97f else 1f
                                }
                                .then(dragModifier)
                        ) {
                            PieceGridTile(
                                imageUri = imageUri,
                                title = displayName,
                                modifier = Modifier.clickable { gridActionItem = item },
                                titleColor = when {
                                    variantMissing -> DangerRed
                                    shortagePending -> DangerRed
                                    !item.fromStock -> DangerRed
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                borderColor = if (variantMissing) DangerRed else null,
                                fallbackIcon = when {
                                    variantMissing || shortagePending -> Icons.Default.Warning
                                    item.isManual -> Icons.Default.Edit
                                    else -> null
                                },
                                fallbackIconTint = if (item.isManual && !variantMissing && !shortagePending) SellGreen else DangerRed,
                                below = {
                                    Row(
                                        modifier = Modifier.padding(top = 4.dp).height(22.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (badgeStock != null) {
                                            StockBadge(count = badgeStock, color = stockColorFor(badgeStock, settings))
                                        }
                                        if (item.isShortage) {
                                            Text(
                                                if (shortagePending) "نقص" else "تم",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = if (shortagePending) DangerRed else SellGreen
                                            )
                                        } else if (item.isManual) {
                                            Text(
                                                "يدوي",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(Modifier.height(16.dp))
                        BoardSummaryCard(
                            project = p,
                            outOfPocket = outOfPocket,
                            totalMaterialCost = totalMaterialCost,
                            totalSellCost = totalSellCost,
                            totalCost = totalCost,
                            remainingAmount = remainingAmount,
                            remainingColor = remainingColor,
                            pieceCount = localItems.size,
                            totalQuantity = localItems.sumOf { it.billableQuantity },
                            pricesHidden = pricesHidden,
                            onDueDate = { showDatePicker = true },
                            onLabor = { showLaborDialog = true },
                            onReceived = { showReceivedDialog = true },
                            onRemaining = { showRemainingDialog = true }
                        )
                    }
                }
            }
        }
    }

    gridActionItem?.let { action ->
        val item = localItems.find { it.id == action.id } ?: action
        val variant = if (item.variantId != null) variants.find { it.id == item.variantId } else null
        val comp = if (variant != null) components.find { it.id == variant.componentId } else null
        val variantMissing = !item.isManual && !item.isShortage && item.variantId != null && variant == null
        val displayName = when {
            item.isManual -> item.manualName
            comp != null -> componentLabel(comp.name, variant)
            item.manualName.isNotBlank() -> item.manualName
            else -> "قطعة محذوفة"
        }
        val shortagePending = item.isShortage && item.shortageRemaining > 0
        val availableStock = variant?.stockCount ?: 0
        AlertDialog(
            onDismissRequest = { gridActionItem = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(displayName, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.isShortage) {
                        Text(
                            if (shortagePending) "أحتاج إلى الكمية ${item.shortageRemaining}" else "تم توفير الكمية ${item.billableQuantity}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (shortagePending) DangerRed else SellGreen
                        )
                        if (pricesVisibleFor(item)) ShortageMathLines(item)
                        if (shortagePending && p.status != "DONE") {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (availableStock > 0) {
                                    TextButton(onClick = {
                                        vm.fulfillShortageItem(item) { msg: String -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                        gridActionItem = null
                                    }) {
                                        Text("سحب من المخزون", color = SellGreen, fontWeight = FontWeight.Bold)
                                    }
                                }
                                TextButton(onClick = {
                                    gridActionItem = null
                                    manualFulfillItem = item
                                }) {
                                    Text("توفير يدوي", color = BrandYellow, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        if (variantMissing) {
                            Text(
                                "تعارض: القطعة لم تعد في المخزون. عدّلها يدوياً لتصبح قطعة مستقلة بلا ارتباط بالمخزون.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = DangerRed
                            )
                        }
                        if (pricesVisibleFor(item)) {
                            Text("شراء: ${formatCurrency(item.lastKnownPurchase)} × ${item.quantity} = ${formatCurrency(item.lastKnownPurchase * item.quantity)}".withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = BuyAmber)
                            Text("بيع: ${formatCurrency(item.sellPrice)} × ${item.quantity} = ${formatCurrency(item.sellPrice * item.quantity)}".withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = SellGreen)
                        }
                        if (p.status != "DONE") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StepButton(
                                    onClick = { vm.changeProjectItemQuantity(projectId, item.id, -1) },
                                    icon = Icons.Default.Remove,
                                    contentDescription = "إنقاص",
                                    containerColor = BrandYellow,
                                    size = 32.dp,
                                    iconSize = 16.dp,
                                    enabled = item.quantity > 1
                                )
                                Text("${item.quantity}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                                StepButton(
                                    onClick = {
                                        vm.changeProjectItemQuantity(
                                            projectId = projectId,
                                            itemId = item.id,
                                            delta = 1,
                                            onStockLimit = { available ->
                                                scope.launch { snackbarHostState.showSnackbar("المخزون لا يكفي — المتاح: $available") }
                                            },
                                            onPriceChanged = { added, purchase, sell ->
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        if (pricesHidden) "تغيّر سعر $displayName — أُضيفت $added في بطاقة بسعرها الحالي"
                                                        else "تغيّر سعر ${displayName} — أُضيفت $added في بطاقة بسعرها الحالي (شراء ${formatCurrency(purchase)} / بيع ${formatCurrency(sell)})"
                                                    )
                                                }
                                            }
                                        )
                                    },
                                    icon = Icons.Default.Add,
                                    contentDescription = "زيادة",
                                    containerColor = SellGreen,
                                    size = 32.dp,
                                    iconSize = 16.dp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (p.status != "DONE") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (variantMissing) {
                            TextButton(onClick = { conflictItem = item; gridActionItem = null }) {
                                Text("تعديل", color = BrandYellow, fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(onClick = {
                            vm.removeProjectItem(item)
                            gridActionItem = null
                        }) {
                            Text("حذف", color = DangerRed, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { gridActionItem = null }) { Text("إغلاق") }
            }
        )
    }

    conflictItem?.let { action ->
        val item = localItems.find { it.id == action.id } ?: action
        var editName by remember(item.id) { mutableStateOf(item.manualName) }
        var editPurchase by remember(item.id) { mutableStateOf(item.lastKnownPurchase.cleanString()) }
        var editSell by remember(item.id) { mutableStateOf(item.sellPrice.cleanString()) }
        var conflictError by remember(item.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { conflictItem = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("معالجة قطعة متعارضة", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "القطعة لم تعد في المخزون — ستُفصل هذه القطعة عنه وتُدار كميتها وأسعارها يدوياً بلا ارتباط بالمخزون.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("اسم القطعة") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editPurchase,
                            onValueChange = { editPurchase = it },
                            label = { Text("سعر الشراء") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = editSell,
                            onValueChange = { editSell = it },
                            label = { Text("سعر البيع") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                    Text("الكمية الحالية: ${item.quantity}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (conflictError.isNotEmpty()) Text(conflictError, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                BrandButton(
                    text = "حفظ",
                    enabled = editName.isNotBlank(),
                    onClick = {
                        conflictError = ""
                        vm.resolveProjectItemConflict(
                            item = item,
                            name = editName,
                            purchasePrice = editPurchase.toDoubleOrNull() ?: 0.0,
                            sellPrice = editSell.toDoubleOrNull() ?: 0.0,
                            onRejected = { conflictError = it },
                            onDone = { conflictItem = null }
                        )
                    }
                )
            },
            dismissButton = { TextButton(onClick = { conflictItem = null }) { Text("إلغاء") } }
        )
    }

    if (showAddSheet) {
        AddComponentSheet(
            vm = vm,
            components = components,
            variants = variants,
            settings = settings,
            projectId = projectId,
            pricesHidden = pricesHidden,
            onManualAdded = { name -> revealedManualNames = revealedManualNames + name },
            onDismiss = { showAddSheet = false }
        )
    }

    if (showAttachments) {
        BoardAttachmentsSheet(vm = vm, projectId = projectId, onDismiss = { showAttachments = false })
    }

    if (showTemplateSelection) {
        val itemsByTemplate = allTemplateItems.groupBy { it.templateId }
        Dialog(
            onDismissRequest = { showTemplateSelection = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.84f),
                shape = DialogShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "سحب قالب",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "اضغط على العين في أي قالب لعرض قطعه",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        TintedCircleButton(
                            onClick = { showTemplateSelection = false },
                            icon = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (allTemplates.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.BookmarkBorder,
                            message = "لا توجد قوالب محفوظة بعد",
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(items = allTemplates, key = { it.id }) { temp ->
                                val summary = templateSummary(
                                    temp,
                                    itemsByTemplate[temp.id].orEmpty(),
                                    variants
                                )
                                TemplateListCard(
                                    template = temp,
                                    summary = summary,
                                    pricesHidden = pricesHidden,
                                    onClick = {
                                        vm.applyTemplateToProject(projectId, temp.id) { msg: String ->
                                            scope.launch { snackbarHostState.showSnackbar(msg) }
                                        }
                                        showTemplateSelection = false
                                    },
                                    onPreview = { previewTemplate = temp },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    previewTemplate?.let { preview ->
        TemplatePiecesDialog(
            template = preview,
            items = allTemplateItems.filter { it.templateId == preview.id },
            variants = variants,
            components = components,
            pricesHidden = pricesHidden,
            onDismiss = { previewTemplate = null }
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = p.dueDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { picked ->
                        // اليوم المختار يُركَّب عليه وقت التسليم الافتراضي من الإعدادات
                        vm.setDueDate(
                            p,
                            combinePickedDateWithTime(
                                picked,
                                settings?.deliveryHour ?: 18,
                                settings?.deliveryMinute ?: 0
                            )
                        )
                    }
                    showDatePicker = false
                }) { Text("موافق") }
            },
            dismissButton = {
                Row {
                    if (p.dueDate != null) {
                        TextButton(onClick = { vm.setDueDate(p, null); showDatePicker = false }) {
                            Text("مسح", color = DangerRed)
                        }
                    }
                    TextButton(onClick = { showDatePicker = false }) { Text("إلغاء") }
                }
            },
            colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
        ) { DatePicker(state = datePickerState) }
    }

    if (showLaborDialog) {
        var cost by remember { mutableStateOf(p.laborCost.cleanString()) }
        AlertDialog(
            onDismissRequest = { showLaborDialog = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("شغل اليد", fontWeight = FontWeight.Bold) },
            text = { OutlinedTextField(value = cost, onValueChange = { cost = it }, label = { Text("المبلغ") }, shape = RoundedCornerShape(14.dp)) },
            confirmButton = { BrandButton(text = "حفظ", onClick = { vm.updateLaborCost(p, cost.toDoubleOrNull() ?: 0.0); showLaborDialog = false }) })
    }

    if (showReceivedDialog) {
        var amount by remember { mutableStateOf(p.receivedAmount.cleanString()) }
        AlertDialog(
            onDismissRequest = { showReceivedDialog = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("المبلغ المستلم", fontWeight = FontWeight.Bold) },
            text = { OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("المبلغ") }, shape = RoundedCornerShape(14.dp)) },
            confirmButton = { BrandButton(text = "حفظ", onClick = { vm.updateReceivedAmount(p, amount.toDoubleOrNull() ?: 0.0); showReceivedDialog = false }) },
            dismissButton = { TextButton(onClick = { showReceivedDialog = false }) { Text("إلغاء") } }
        )
    }

    if (showRemainingDialog) {
        var amount by remember { mutableStateOf(remainingAmount.cleanString()) }
        AlertDialog(
            onDismissRequest = { showRemainingDialog = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("المبلغ المتبقي", fontWeight = FontWeight.Bold) },
            text = { OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("المبلغ") }, shape = RoundedCornerShape(14.dp)) },
            confirmButton = { BrandButton(text = "حفظ", onClick = { vm.updateReceivedAmount(p, (totalCost - (amount.toDoubleOrNull() ?: remainingAmount)).coerceAtLeast(0.0)); showRemainingDialog = false }) },
            dismissButton = { TextButton(onClick = { showRemainingDialog = false }) { Text("إلغاء") } }
        )
    }

    if (manualFulfillItem != null) {
        val target = manualFulfillItem!!
        val targetVariant = variants.find { it.id == target.variantId }
        val targetComp = components.find { it.id == targetVariant?.componentId }
        val targetName = componentLabel(targetComp?.name, targetVariant, "قطعة")
        var purchase by remember(target.id) { mutableStateOf(target.lastKnownPurchase.cleanString()) }
        var sell by remember(target.id) { mutableStateOf(target.sellPrice.cleanString()) }
        var acceptLoss by remember(target.id) { mutableStateOf(false) }
        val purchaseValue = purchase.toDoubleOrNull()
        val sellValue = sell.toDoubleOrNull()
        val isLoss = purchaseValue != null && sellValue != null && isSellBelowPurchase(sellValue, purchaseValue)
        AlertDialog(
            onDismissRequest = { manualFulfillItem = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("توفير يدوي", fontWeight = FontWeight.Bold, color = BrandYellow) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(targetName, fontWeight = FontWeight.Bold)
                    Text(
                        "قطعة واحدة — الأسعار خاصة بهذا المشروع فقط ولا تغيّر أسعار القطعة في المخزون.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    OutlinedTextField(value = purchase, onValueChange = { purchase = it }, label = { Text("سعر الشراء في المشروع") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = sell, onValueChange = { sell = it }, label = { Text("سعر البيع في المشروع") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    if (isLoss) {
                        Text(sellBelowPurchaseMessage(sellValue!!, purchaseValue!!), color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = acceptLoss, onCheckedChange = { acceptLoss = it }, colors = CheckboxDefaults.colors(checkedColor = DangerRed))
                            Text("أوافق على بيع هذه القطعة بخسارة", color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                BrandButton(
                    text = "توفير",
                    enabled = purchaseValue != null && sellValue != null && (!isLoss || acceptLoss),
                    onClick = {
                        vm.fulfillShortageItemManually(target, purchaseValue ?: 0.0, sellValue ?: 0.0) { msg: String ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                        manualFulfillItem = null
                    }
                )
            },
            dismissButton = { TextButton(onClick = { manualFulfillItem = null }) { Text("إلغاء") } }
        )
    }
}

// بطاقة التفاصيل المالية: تُعرض في أسفل القائمة وفي أسفل الشبكة على حدٍّ سواء
@Composable
private fun BoardSummaryCard(
    project: Project,
    outOfPocket: Double,
    totalMaterialCost: Double,
    totalSellCost: Double,
    totalCost: Double,
    remainingAmount: Double,
    remainingColor: Color,
    pieceCount: Int,
    totalQuantity: Int,
    pricesHidden: Boolean,
    onDueDate: () -> Unit,
    onLabor: () -> Unit,
    onReceived: () -> Unit,
    onRemaining: () -> Unit
) {
    // أحمر للفائت، أصفر لليوم نفسه، أخضر للمستقبل — ويبقى محايداً بعد الإتمام
    val dueColor = dueDateColor(project) ?: MaterialTheme.colorScheme.onSurface
    val done = project.status == "DONE" && project.completedAt != null

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().clickable { onDueDate() }, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (done) "التسليم المتفق:" else "تاريخ التسليم:",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    if (project.dueDate == null) "🍀 غير محدد" else formatDateTimeWithDay(project.dueDate!!),
                    fontWeight = FontWeight.Bold,
                    color = dueColor
                )
            }
            if (done) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("سُلّم فعلياً:", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Text(
                        formatDateTimeWithDay(project.completedAt!!),
                        fontWeight = FontWeight.Bold,
                        color = SellGreen
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("القطع:", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                PieceCountBadge(count = pieceCount, size = 24)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("الكمية:", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Text("$totalQuantity", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            Divider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            if (pricesHidden) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Lock, null, tint = BrandYellow, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "الأسعار مخفية — أعِد البصمة لعرضها",
                        color = BrandYellow,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("مصروف من الجيب:", color = DangerRed, fontWeight = FontWeight.Bold); Text(formatCurrency(outOfPocket).withThousandsDot(), color = DangerRed, fontWeight = FontWeight.Bold) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("كلفتني المواد:", color = BuyAmber, fontWeight = FontWeight.Bold); Text(formatCurrency(totalMaterialCost).withThousandsDot(), color = BuyAmber, fontWeight = FontWeight.Bold) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("كلفته المواد:", color = SellGreen, fontWeight = FontWeight.Bold); Text(formatCurrency(totalSellCost).withThousandsDot(), color = SellGreen, fontWeight = FontWeight.Bold) }
                Divider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("شغل اليد:", fontWeight = FontWeight.Bold)
                    Row {
                        Text(formatCurrency(project.laborCost).withThousandsDot(), fontWeight = FontWeight.Bold)
                        if (!done) IconButton(onClick = onLabor, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Edit, "تعديل", tint = SellGreen, modifier = Modifier.size(16.dp)) }
                    }
                }
                Divider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("استلمت:", fontWeight = FontWeight.Bold, color = SellGreen)
                    Row {
                        Text(formatCurrency(project.receivedAmount).withThousandsDot(), fontWeight = FontWeight.Bold, color = SellGreen)
                        IconButton(onClick = onReceived, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Edit, "تعديل", tint = SellGreen, modifier = Modifier.size(16.dp)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("المبلغ المتبقي:", fontWeight = FontWeight.Bold, color = remainingColor)
                    Row {
                        Text(formatCurrency(remainingAmount).withThousandsDot(), fontWeight = FontWeight.Bold, color = remainingColor)
                        IconButton(onClick = onRemaining, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Edit, "تعديل", tint = remainingColor, modifier = Modifier.size(16.dp)) }
                    }
                }
                Divider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إجمالي التكلفة:", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(formatCurrency(totalCost).withThousandsDot(), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = SellGreen)
                }
            }
        }
    }
}

// سطران لمصدر واحد، وأربعة حين تجتمع قطعة من المخزون مع توفير يدوي في نفس البطاقة
@Composable
private fun ShortageMathLines(item: ProjectItem) {
    if (item.shortageFromStock > 0) {
        Text(
            "شراء من المخزون: ${formatCurrency(item.lastKnownPurchase)} × ${item.shortageFromStock} = ${formatCurrency(item.lastKnownPurchase * item.shortageFromStock)}".withThousandsDot(),
            style = MaterialTheme.typography.bodySmall,
            color = BuyAmber
        )
        Text(
            "بيع من المخزون: ${formatCurrency(item.sellPrice)} × ${item.shortageFromStock} = ${formatCurrency(item.sellPrice * item.shortageFromStock)}".withThousandsDot(),
            style = MaterialTheme.typography.bodySmall,
            color = SellGreen
        )
    }
    if (item.shortageManual > 0) {
        Text(
            "شراء يدوي: ${formatCurrency(item.shortageManualPurchaseUnit)} × ${item.shortageManual} = ${formatCurrency(item.shortageManualPurchase)}".withThousandsDot(),
            style = MaterialTheme.typography.bodySmall,
            color = BuyAmber
        )
        Text(
            "بيع يدوي: ${formatCurrency(item.shortageManualSellUnit)} × ${item.shortageManual} = ${formatCurrency(item.shortageManualSell)}".withThousandsDot(),
            style = MaterialTheme.typography.bodySmall,
            color = SellGreen
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComponentSheet(
    vm: MainViewModel,
    components: List<Component>,
    variants: List<ComponentVariant>,
    settings: AppSettings?,
    projectId: Long,
    pricesHidden: Boolean = false,
    onManualAdded: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    val tagsOf by vm.tagsByComponent.collectAsState()
    var qtyMultiplier by remember { mutableStateOf(1) }
    var lastAddedComponentId by remember { mutableStateOf<Long?>(null) }
    var lastAddedCount by remember { mutableStateOf(0) }
    var addEventCounter by remember { mutableStateOf(0) }
    var showManualAdd by remember { mutableStateOf(false) }
    var variantPickComponent by remember { mutableStateOf<Component?>(null) }

    var sessionAddedTotal by remember { mutableStateOf(0) }
    var sessionAddedValue by remember { mutableStateOf(0.0) }

    fun registerAdded(comp: Component, unitSell: Double, msg: String, success: Boolean, actualQty: Int) {
        if (success && actualQty > 0) {
            SoundHelper.playAddSuccess()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            lastAddedComponentId = comp.id
            lastAddedCount = actualQty
            addEventCounter++
            sessionAddedTotal += actualQty
            sessionAddedValue += unitSell * actualQty
            scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
        } else {
            SoundHelper.playWarning()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
        }
    }

    fun addComponentToProject(comp: Component, variant: ComponentVariant) {
        vm.addComponentToProject(projectId, variant.id, qtyMultiplier) { msg: String, success: Boolean, actualQty: Int ->
            registerAdded(comp, variant.defaultSellPrice, msg, success, actualQty)
        }
    }

    fun addBaseComponentToProject(comp: Component) {
        vm.addBaseComponentToProject(projectId, comp.id, qtyMultiplier) { msg: String, success: Boolean, actualQty: Int ->
            registerAdded(comp, comp.sellPrice, msg, success, actualQty)
        }
    }

    fun onComponentTap(comp: Component) {
        val compVariants = variants.filter { it.componentId == comp.id }
        when {
            compVariants.isEmpty() -> addBaseComponentToProject(comp)
            compVariants.size == 1 -> addComponentToProject(comp, compVariants[0])
            else -> {
                SoundHelper.playTap()
                variantPickComponent = comp
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.95f),
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        BackHandler(enabled = variantPickComponent != null) {
            variantPickComponent = null
            SoundHelper.playTap()
        }
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val picked = variantPickComponent
                            if (picked == null) {
                                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "إغلاق", tint = MaterialTheme.colorScheme.onSurface) }
                                Text("إضافة قطعة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SellGreen, modifier = Modifier.weight(1f))
                                TintedCircleButton(
                                    onClick = { showManualAdd = true },
                                    icon = Icons.Default.Add,
                                    contentDescription = "إضافة يدوية",
                                    tint = BrandYellow,
                                    size = 38.dp,
                                    iconSize = 19.dp
                                )
                            } else {
                                IconButton(onClick = { variantPickComponent = null; SoundHelper.playTap() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = MaterialTheme.colorScheme.onSurface)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        picked.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = SellGreen,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text("اختر الحجم", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("الكمية:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            listOf(1, 2, 5, 10).forEach { mult ->
                                FilterChip(
                                    selected = qtyMultiplier == mult,
                                    onClick = { qtyMultiplier = mult; SoundHelper.playTap() },
                                    label = { Text("×$mult") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SellGreen,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }
                        if (variantPickComponent == null) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("ابحث بالاسم...") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true
                            )
                        }
                    }
                }
            },
            bottomBar = {
                if (sessionAddedTotal > 0) {
                    Surface(color = SellGreen, shadowElevation = 8.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("أُضيفت كمية $sessionAddedTotal", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            if (!pricesHidden) Text(formatCurrency(sessionAddedValue).withThousandsDot(), fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        ) { innerPadding ->
            val picked = variantPickComponent
            if (picked == null) {
                val filtered = components.filter { c: Component -> matchesTagSearch(c, searchQuery, tagsOf) }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    gridItems(filtered, key = { it.id }) { comp ->
                        val compVariants = variants.filter { it.componentId == comp.id }
                        val badgeCount = if (compVariants.isEmpty()) comp.stockCount else compVariants.sumOf { it.stockCount }
                        // العدد الإجمالي يبقى مخفياً حين تُعرض الأحجام عند الدخول
                        val showStockBadge = compVariants.size <= 1
                        val justAdded = lastAddedComponentId == comp.id

                        val pulseScale by animateFloatAsState(
                            targetValue = if (justAdded) 1.15f else 1f,
                            animationSpec = spring<Float>(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
                            label = "pulse"
                        )

                        Box(modifier = Modifier.padding(4.dp).animateItem()) {
                            PieceGridTile(
                                imageUri = comp.imageUri,
                                title = comp.name,
                                modifier = Modifier.clickable { onComponentTap(comp) },
                                circleSize = 56.dp,
                                circleScale = pulseScale,
                                below = {
                                    if (showStockBadge) {
                                        StockBadge(
                                            count = badgeCount,
                                            color = stockColorFor(badgeCount, settings),
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            )

                            if (justAdded) {
                                key(addEventCounter) {
                                    FloatingPlusBadge(count = lastAddedCount)
                                }
                            }
                        }
                    }
                }
            } else {
                val pickedVariants = variants.filter { it.componentId == picked.id }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    gridItems(pickedVariants, key = { it.id }) { v ->
                        PieceGridTile(
                            imageUri = picked.imageUri,
                            title = componentLabel(picked.name, v),
                            modifier = Modifier
                                .animateItem()
                                .clickable {
                                    SoundHelper.playTap()
                                    addComponentToProject(picked, v)
                                    variantPickComponent = null
                                },
                            circleSize = 56.dp,
                            below = {
                                StockBadge(
                                    count = v.stockCount,
                                    color = stockColorFor(v.stockCount, settings),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showManualAdd) {
        var name by remember { mutableStateOf("") }
        var purchase by remember { mutableStateOf("") }
        var sell by remember { mutableStateOf("") }
        var manualQty by remember { mutableStateOf(1) }
        var acceptLoss by remember { mutableStateOf(false) }
        val purchaseValue = purchase.toDoubleOrNull()
        val sellValue = sell.toDoubleOrNull()
        val isLoss = purchaseValue != null && sellValue != null && isSellBelowPurchase(sellValue, purchaseValue)
        AlertDialog(
            onDismissRequest = { showManualAdd = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("إضافة قطعة يدوياً للبورد", fontWeight = FontWeight.Bold, color = BrandYellow) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("لن تُضاف إلى المكتبة، فقط لهذا البورد.", style = MaterialTheme.typography.bodySmall, color = BrandYellow)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم القطعة") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = purchase, onValueChange = { purchase = it }, label = { Text("سعر الشراء") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = sell, onValueChange = { sell = it }, label = { Text("سعر البيع") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Text("الكمية: ")
                        StepButton(
                            onClick = { manualQty-- },
                            icon = Icons.Default.Remove,
                            contentDescription = "إنقاص الكمية",
                            containerColor = BrandYellow,
                            size = 32.dp,
                            iconSize = 16.dp,
                            enabled = manualQty > 1
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("$manualQty", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 24.dp))
                        Spacer(Modifier.width(10.dp))
                        StepButton(
                            onClick = { manualQty++ },
                            icon = Icons.Default.Add,
                            contentDescription = "زيادة الكمية",
                            containerColor = SellGreen,
                            size = 32.dp,
                            iconSize = 16.dp
                        )
                    }
                    if (isLoss) {
                        Text(sellBelowPurchaseMessage(sellValue!!, purchaseValue!!), color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = acceptLoss, onCheckedChange = { acceptLoss = it }, colors = CheckboxDefaults.colors(checkedColor = DangerRed))
                            Text("أوافق على بيع هذه القطعة بخسارة", color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                BrandButton(
                    text = "إضافة",
                    enabled = name.isNotBlank() && (!isLoss || acceptLoss),
                    onClick = {
                        vm.addManualComponentToProject(projectId, name.trim(), purchaseValue ?: 0.0, sellValue ?: 0.0, manualQty)
                        SoundHelper.playAddSuccess()
                        sessionAddedTotal += manualQty
                        sessionAddedValue += (sellValue ?: 0.0) * manualQty
                        // القطعة اليدوية المضافة أثناء الإخفاء تبقى أسعارها ظاهرة
                        if (pricesHidden) onManualAdded(name.trim())
                        showManualAdd = false
                    }
                )
            }
        )
    }
}

@Composable
fun FloatingPlusBadge(count: Int) {
    var started by remember { mutableStateOf(false) }

    val offsetY by animateDpAsState(
        targetValue = if (started) (-70).dp else 0.dp,
        animationSpec = tween<Dp>(900, easing = FastOutSlowInEasing),
        label = "offsetY"
    )
    val alpha by animateFloatAsState(
        targetValue = if (started) 0f else 1f,
        animationSpec = tween<Float>(900, easing = FastOutSlowInEasing),
        label = "alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (started) 1.3f else 1f,
        animationSpec = tween<Float>(400),
        label = "scale"
    )

    LaunchedEffect(Unit) { started = true }

    Box(
        modifier = Modifier
            .offset(y = offsetY)
            .graphicsLayer(alpha = alpha, scaleX = scale, scaleY = scale),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = SellGreen,
            shape = RoundedCornerShape(50),
            shadowElevation = 6.dp
        ) {
            Text(
                text = "+$count",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

// دائرة عدد تتّسع لرقم أو اثنين أو ثلاثة: تبقى دائرة، وتتمدد بيضاوية عند الخانات الثلاث
@Composable
fun StockBadge(
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    fontSize: TextUnit = 11.sp
) {
    val animatedCount by animateIntAsState(
        targetValue = count,
        animationSpec = tween<Int>(300),
        label = "stockCount"
    )
    val label = "$animatedCount"
    val wide = label.length >= 3
    Box(
        modifier = modifier
            .then(if (wide) Modifier.height(size).widthIn(min = size * 1.55f) else Modifier.size(size))
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = contentColorOn(color),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = if (wide) fontSize * 0.85f else fontSize),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = if (wide) 4.dp else 0.dp)
        )
    }
}