package com.bordy.app.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.bordy.app.R
import com.bordy.app.data.*
import com.bordy.app.ui.components.*
import com.bordy.app.utils.*
import com.bordy.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SuppliersScreen(vm: MainViewModel, onBack: () -> Unit) {
    val suppliersFromDb by vm.suppliers.collectAsState()
    val offers by vm.offers.collectAsState()
    val variants by vm.variants.collectAsState()
    val components by vm.components.collectAsState()
    val cart by vm.cartItems.collectAsState()

    var localItems by remember { mutableStateOf<List<Supplier>>(emptyList()) }
    LaunchedEffect(suppliersFromDb) { localItems = suppliersFromDb }

    val settings by vm.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSupplier by remember { mutableStateOf<Supplier?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Supplier?>(null) }
    var filteredSupplierId by remember { mutableStateOf<Long?>(null) }
    var catalogSupplier by remember { mutableStateOf<Supplier?>(null) }
    var openPiece by remember { mutableStateOf<Component?>(null) }
    var showCart by remember { mutableStateOf(false) }
    val cartQty: Int = cart.sumOf { it.quantity }

    val variantsByComponent: Map<Long, List<ComponentVariant>> = variants.groupBy { it.componentId }
    val offersByVariant: Map<Long, List<SupplierOffer>> = offers.groupBy { it.variantId }

    // القطعة تُعرض هنا بحسب ارتباطها بمزود لا بحسب مخزونها
    val piecesHere: List<PieceSupply> = components.mapNotNull { comp ->
        val compVariants = variantsByComponent[comp.id] ?: return@mapNotNull null
        val supplierIds = compVariants.flatMap { v ->
            (offersByVariant[v.id] ?: emptyList()).filter { filteredSupplierId == null || it.supplierId == filteredSupplierId }
        }.map { it.supplierId }.distinct()
        if (supplierIds.isEmpty()) return@mapNotNull null
        PieceSupply(
            component = comp,
            supplierCount = supplierIds.size,
            totalStock = compVariants.sumOf { it.stockCount }
        )
    }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val slotHeightPx: Float = with(density) { 92.dp.toPx() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var initialIndex by remember { mutableStateOf(-1) }
    var accumulatedDrag by remember { mutableStateOf(0f) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SlimScreenHeader(title = "المجهّزين والمحلات", onBack = onBack) {
                Box(contentAlignment = Alignment.TopEnd) {
                    TintedCircleButton(
                        onClick = { showCart = true },
                        icon = Icons.Default.ShoppingCart,
                        contentDescription = "سلة الشراء",
                        tint = if (cartQty > 0) SellGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        size = 40.dp,
                        iconSize = 20.dp
                    )
                    if (cartQty > 0) {
                        StockBadge(count = cartQty, color = DangerRed, size = 22.dp, fontSize = 11.sp)
                    }
                }
            }
        },
        floatingActionButton = {
            BrandFab(
                onClick = { editingSupplier = null; showAddDialog = true },
                icon = Icons.Default.Add,
                contentDescription = "إضافة"
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text("المجهّزين والمحلات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("اضغط مزودًا لعرض قطعه", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
            if (localItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(icon = Icons.Default.Storefront, message = "ما عندك مجهّزين مسجلين هسّه.. أضف مجهّز واربط مواده بقائمته! 🏪")
                }
            }
            gridItems(items = localItems, key = { sup: Supplier -> "sup-${sup.id}" }, span = { GridItemSpan(maxLineSpan) }) { sup: Supplier ->
                val isDragging: Boolean = draggedId == sup.id

                var currentIndexLocal: Int = -1
                for (i in localItems.indices) { if (localItems[i].id == sup.id) { currentIndexLocal = i; break } }
                val currentIndex: Int = currentIndexLocal

                val suppliedIds = mutableSetOf<Long>()
                for (v in variants) { if (offers.any { o -> o.variantId == v.id && o.supplierId == sup.id }) suppliedIds.add(v.componentId) }
                val suppliedPieces: Int = suppliedIds.size
                val isFiltered: Boolean = filteredSupplierId == sup.id

                val indexDelta: Int = if (initialIndex >= 0) currentIndex - initialIndex else 0
                val displayTranslationY: Float = if (isDragging) accumulatedDrag - (indexDelta * slotHeightPx) else 0f

                val transformModifier: Modifier = if (isDragging) {
                    Modifier.zIndex(1f).graphicsLayer {
                        translationY = displayTranslationY
                        scaleX = 1.03f
                        scaleY = 1.03f
                        shadowElevation = 16f
                        alpha = 0.96f
                    }
                } else Modifier.zIndex(0f)

                val dragPointerModifier: Modifier = Modifier.pointerInput(key1 = sup.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { _: Offset ->
                            draggedId = sup.id
                            var idx = -1
                            for (i in localItems.indices) { if (localItems[i].id == sup.id) { idx = i; break } }
                            initialIndex = idx
                            accumulatedDrag = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change: PointerInputChange, dragAmount: Offset ->
                            change.consume()
                            accumulatedDrag += dragAmount.y
                            var safety = 0
                            while (safety++ < 10) {
                                var idx = -1
                                for (i in localItems.indices) { if (localItems[i].id == sup.id) { idx = i; break } }
                                if (idx < 0) break
                                val visualOffset = accumulatedDrag - ((idx - initialIndex) * slotHeightPx)
                                if (visualOffset > slotHeightPx * 0.5f && idx < localItems.size - 1) {
                                    val list = localItems.toMutableList()
                                    val moving = list.removeAt(idx)
                                    list.add(idx + 1, moving)
                                    localItems = list
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (visualOffset < -slotHeightPx * 0.5f && idx > 0) {
                                    val list = localItems.toMutableList()
                                    val moving = list.removeAt(idx)
                                    list.add(idx - 1, moving)
                                    localItems = list
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else break
                            }
                        },
                        onDragEnd = {
                            draggedId = null
                            accumulatedDrag = 0f
                            initialIndex = -1
                            vm.reorderSuppliers(localItems)
                        },
                        onDragCancel = {
                            draggedId = null
                            accumulatedDrag = 0f
                            initialIndex = -1
                            localItems = suppliersFromDb
                        }
                    )
                }

                Card(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        filteredSupplierId = if (isFiltered) null else sup.id
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isDragging || isFiltered) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
                    border = BorderStroke(if (isFiltered) 2.dp else 1.dp, if (isFiltered) SellGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().then(transformModifier).then(dragPointerModifier)
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DragHandle, "اسحب", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDragging) 0.8f else 0.3f), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(46.dp).clip(CircleShape).background(SellGreen), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Store, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sup.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (isFiltered) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Default.Check, "محدد", tint = SellGreen, modifier = Modifier.size(18.dp))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Phone, null, modifier = Modifier.size(13.dp), tint = DangerRed)
                                Spacer(Modifier.width(3.dp))
                                Text(sup.phone.ifEmpty { "بلا هاتف" }, style = MaterialTheme.typography.labelSmall, color = DangerRed, fontWeight = FontWeight.Bold, maxLines = 1)
                                Spacer(Modifier.width(10.dp))
                                Icon(Icons.Default.LocalShipping, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(3.dp))
                                Text(formatCurrency(sup.deliveryCost).withThousandsDot(), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(if (suppliedPieces == 0) "قائمته فارغة" else supplierPiecesLabel(suppliedPieces), style = MaterialTheme.typography.labelSmall, color = BrandYellow, fontWeight = FontWeight.Bold)
                        }
                        TintedCircleButton(
                            onClick = { catalogSupplier = sup },
                            icon = Icons.Default.Add,
                            contentDescription = "إضافة قطعة لقائمة ${sup.name}",
                            tint = BrandYellow,
                            size = 32.dp,
                            iconSize = 16.dp
                        )
                        Spacer(Modifier.width(4.dp))
                        TintedCircleButton(
                            onClick = { editingSupplier = sup; showAddDialog = true },
                            icon = Icons.Default.Edit,
                            contentDescription = "تعديل",
                            tint = SellGreen,
                            size = 32.dp,
                            iconSize = 16.dp
                        )
                        Spacer(Modifier.width(4.dp))
                        TintedCircleButton(
                            onClick = { showDeleteConfirm = sup },
                            icon = Icons.Default.Delete,
                            contentDescription = "حذف",
                            tint = DangerRed,
                            size = 32.dp,
                            iconSize = 16.dp
                        )
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                val filteredName = filteredSupplierId?.let { id -> suppliersFromDb.firstOrNull { it.id == id }?.name }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (filteredName == null) "قطع لدى المزودين" else "قطع ${filteredName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${piecesHere.size}", style = MaterialTheme.typography.labelMedium, color = SellGreen, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (filteredName != null) {
                        TextButton(onClick = { filteredSupplierId = null }) { Text("إظهار الكل", color = BrandYellow, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    }
                }
            }

            if (piecesHere.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (localItems.isEmpty()) "أضف مزودًا أولًا لعرض قطعه هنا."
                        else "لم تربط أي قطعة بمزود بعد — القطعة تظهر هنا حسب ارتباطها بمزود لا حسب مخزونك.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                gridItems(items = piecesHere, key = { item -> "pc-${item.component.id}" }) { item ->
                    val comp = item.component
                    Box(modifier = Modifier.padding(2.dp).animateItem()) {
                        PieceGridTile(
                            imageUri = comp.imageUri,
                            title = comp.name,
                            modifier = Modifier.clickable { openPiece = comp },
                            circleSize = 52.dp,
                            below = {
                                StockBadge(count = item.totalStock, color = stockColorFor(item.totalStock, settings))
                                Text(
                                    suppliedByLabel(item.supplierCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (catalogSupplier != null) {
        AddOfferSheet(
            supplier = catalogSupplier!!,
            components = components,
            variants = variants,
            vm = vm,
            onDismiss = { catalogSupplier = null }
        )
    }

    if (openPiece != null) {
        PieceSuppliersSheet(
            component = openPiece!!,
            allVariants = variants,
            allOffers = offers,
            suppliers = suppliersFromDb,
            settings = settings,
            cartByOffer = cart.associate { c: CartItem -> c.offerId to c.quantity },
            cartTotalQty = cartQty,
            onOpenCart = { openPiece = null; showCart = true },
            vm = vm,
            onDismiss = { openPiece = null }
        )
    }

    if (showCart) {
        PurchaseCartSheet(
            cart = cart,
            allOffers = offers,
            allVariants = variants,
            allComponents = components,
            suppliers = suppliersFromDb,
            vm = vm,
            onDismiss = { showCart = false }
        )
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf(editingSupplier?.name ?: "") }
        var phone by remember { mutableStateOf(editingSupplier?.phone ?: "") }
        var delivery by remember { mutableStateOf(editingSupplier?.deliveryCost?.let { d: Double -> if (d % 1.0 == 0.0) d.toLong().toString() else d.toString() } ?: "0") }
        var minOrder by remember { mutableStateOf(editingSupplier?.defaultMinOrder?.toString() ?: "1") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (editingSupplier == null) "مجهّز جديد +" else "تعديل المجهّز", fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { s: String -> name = s }, label = { Text("اسم المجهّز / المحل") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = phone, onValueChange = { s: String -> phone = s }, label = { Text("رقم الهاتف") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = delivery, onValueChange = { s: String -> delivery = s }, label = { Text("كروة التوصيل") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = minOrder, onValueChange = { s: String -> minOrder = s }, label = { Text("أقل كمية طلب") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            } },
            confirmButton = { BrandButton(
                text = "حفظ",
                enabled = name.isNotBlank(),
                onClick = {
                    val sup = Supplier(id = editingSupplier?.id ?: 0, name = name.trim(), phone = phone.trim(), deliveryCost = delivery.toDoubleOrNull() ?: 0.0, defaultMinOrder = minOrder.toIntOrNull() ?: 1, orderIndex = editingSupplier?.orderIndex ?: 0)
                    if (editingSupplier == null) vm.addSupplier(sup) else vm.updateSupplier(sup)
                    showAddDialog = false
                }
            ) },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") } })
    }

    if (showDeleteConfirm != null) {
        val doomed = showDeleteConfirm!!
        val doomedLinks = offers.filter { o -> o.supplierId == doomed.id }
        val stillCoveredVariants = offers.filter { o -> o.supplierId != doomed.id }.map { o -> o.variantId }.toSet()
        val lostPieces = doomedLinks.filter { o -> o.variantId !in stillCoveredVariants }
            .mapNotNull { o -> variants.firstOrNull { v -> v.id == o.variantId }?.componentId }.distinct().size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            shape = DialogShape,
            title = { Text("تأكيد الحذف", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("متأكد تريد تحذف المجهّز '${doomed.name}'؟")
                if (doomedLinks.isNotEmpty()) {
                    Text(
                        "سيُفكّ ${doomedLinks.size} رابطًا من قائمته" + if (lostPieces > 0) "، وستختفي $lostPieces قطعة من شبكة «قطع لدى المزودين»." else ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandYellow,
                        fontWeight = FontWeight.Bold
                    )
                    Text("موادك بالمخزن وأسعار بيعك ما تتأثر أبد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            } },
            confirmButton = { Button(onClick = { vm.deleteSupplier(showDeleteConfirm!!); showDeleteConfirm = null }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), shape = CircleShape) { Text("حذف", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("إلغاء") } }
        )
    }
}

private data class PieceSupply(
    val component: Component,
    val supplierCount: Int,
    val totalStock: Int
)

fun suppliedByLabel(n: Int): String = when {
    n <= 0 -> "بلا مزود"
    n == 1 -> "لدى مزود واحد"
    n == 2 -> "لدى مزودين"
    n in 3..10 -> "لدى $n مزودين"
    else -> "لدى $n مزودًا"
}

fun supplierPiecesLabel(n: Int): String = when {
    n == 1 -> "قطعة واحدة في قائمته"
    n == 2 -> "قطعتان في قائمته"
    n in 3..10 -> "$n قطع في قائمته"
    else -> "$n قطعة في قائمته"
}

fun piecesPlural(n: Int): String = when {
    n == 1 -> "قطعة واحدة"
    n == 2 -> "قطعتان"
    n in 3..10 -> "$n قطع"
    else -> "$n قطعة"
}

fun suppliersPlural(n: Int): String = when {
    n == 1 -> "مزود واحد"
    n == 2 -> "مزودان"
    n in 3..10 -> "$n مزودين"
    else -> "$n مزودًا"
}

// داخل الجملة بعد اللام: المثنى يُجر فيصير «لمزودين» لا «لمزودان»
fun deliverySuppliersPhrase(n: Int): String = when {
    n == 1 -> "لمزود واحد"
    n == 2 -> "لمزودين"
    n in 3..10 -> "لـ$n مزودين"
    else -> "لـ$n مزودًا"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PieceSuppliersSheet(
    component: Component,
    allVariants: List<ComponentVariant>,
    allOffers: List<SupplierOffer>,
    suppliers: List<Supplier>,
    settings: AppSettings?,
    cartByOffer: Map<Long, Int>,
    cartTotalQty: Int,
    onOpenCart: () -> Unit,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    val variantsOfPiece: List<ComponentVariant> = allVariants.filter { v: ComponentVariant -> v.componentId == component.id }
    var selectedVariantId by remember(component.id) {
        mutableStateOf(
            variantsOfPiece.firstOrNull { v: ComponentVariant -> allOffers.any { o: SupplierOffer -> o.variantId == v.id } }?.id
                ?: variantsOfPiece.firstOrNull()?.id
                ?: 0L
        )
    }
    var showLinkDialog by remember { mutableStateOf(false) }
    var editingOffer by remember { mutableStateOf<SupplierOffer?>(null) }
    var unlinkOffer by remember { mutableStateOf<SupplierOffer?>(null) }

    val selectedVariant: ComponentVariant? = variantsOfPiece.firstOrNull { v: ComponentVariant -> v.id == selectedVariantId }
    val totalStock: Int = variantsOfPiece.sumOf { v: ComponentVariant -> v.stockCount }

    val rows: List<SupplierRow> = remember(selectedVariantId, allOffers, suppliers) {
        buildSupplierRows(allOffers.filter { o: SupplierOffer -> o.variantId == selectedVariantId }, suppliers)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.92f),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (component.imageUri.isNotEmpty()) {
                        AsyncImage(model = component.imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
                    } else {
                        Image(painter = painterResource(id = R.drawable.clover_placeholder), contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(component.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StockBadge(count = totalStock, color = stockColorFor(totalStock, settings), size = 22.dp, fontSize = 10.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("في مخزونك", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.width(12.dp))
                        Text(suppliedByLabel(rows.size), style = MaterialTheme.typography.labelSmall, color = BrandYellow, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (variantsOfPiece.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    variantsOfPiece.forEach { v: ComponentVariant ->
                        val offering = allOffers.filter { o: SupplierOffer -> o.variantId == v.id }.map { o: SupplierOffer -> o.supplierId }.distinct().size
                        SizeChip(label = v.specName, supplierCount = offering, selected = v.id == selectedVariantId) { selectedVariantId = v.id }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("المجهّزين (الأرخص أولاً)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("سعر الشراء من المجهّز", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            icon = Icons.Default.Storefront,
                            message = if (selectedVariant == null) "هذه القطعة بلا أحجام — أضف لها حجمًا من المخزون لتتمكن من ربطها بمزود"
                            else "لا مزود يوفّر ${componentLabel(component.name, selectedVariant, "هذه القطعة")} بعد"
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(items = rows) { row: SupplierRow ->
                        SupplierPriceRow(
                            row = row,
                            recordedPurchase = selectedVariant?.defaultPurchasePrice ?: 0.0,
                            inCartQty = cartByOffer[row.offer.id] ?: 0,
                            onEdit = { editingOffer = row.offer },
                            onUnlink = { unlinkOffer = row.offer },
                            onAddToCart = {
                                vm.addToCart(row.offer.id, row.offer.minOrder.coerceAtLeast(1))
                                SoundHelper.playAddSuccess()
                            },
                            onCleanDuplicates = { row.offers.filter { o: SupplierOffer -> o.id != row.offer.id }.forEach { o: SupplierOffer -> vm.deleteOffer(o) } },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            if (selectedVariant != null) {
                BrandButton(
                    text = "ربط القطعة بمزود",
                    icon = Icons.Default.Link,
                    onClick = { showLinkDialog = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (cartTotalQty > 0) {
                Spacer(Modifier.height(8.dp))
                Card(
                    onClick = onOpenCart,
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(BrandYellow),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ShoppingCart, null, tint = contentColorOn(BrandYellow), modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("سلة المسواك", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                            Text(
                                "${piecesPlural(cartTotalQty)} · اضغط للمراجعة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                            )
                        }
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (editingOffer != null && selectedVariant != null) {
        EditOfferDialog(
            offer = editingOffer!!,
            label = componentLabel(component.name, selectedVariant, "القطعة"),
            vm = vm,
            onDismiss = { editingOffer = null }
        )
    }

    if (unlinkOffer != null) {
        val supplierName = suppliers.firstOrNull { s: Supplier -> s.id == unlinkOffer!!.supplierId }?.name ?: "المزود"
        AlertDialog(
            onDismissRequest = { unlinkOffer = null },
            shape = DialogShape,
            title = { Text("فك الربط", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = { Text("سيُزال هذا السعر من قائمة $supplierName. المخزون لا يتأثر.") },
            confirmButton = {
                Button(
                    onClick = { vm.deleteOffer(unlinkOffer!!); unlinkOffer = null },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = CircleShape
                ) { Text("فك", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { unlinkOffer = null }) { Text("إلغاء") } }
        )
    }

    if (showLinkDialog && selectedVariant != null) {
        LinkSupplierDialog(
            component = component,
            variant = selectedVariant,
            suppliers = suppliers,
            existing = allOffers.filter { o: SupplierOffer -> o.variantId == selectedVariant!!.id },
            vm = vm,
            onDismiss = { showLinkDialog = false }
        )
    }
}

private data class SupplierRow(
    val supplier: Supplier,
    val offer: SupplierOffer,
    val offers: List<SupplierOffer>,
    val valid: Boolean,
    val rank: Int,
    val diffAbs: Double,
    val diffPct: Int
)

private fun buildSupplierRows(offers: List<SupplierOffer>, suppliers: List<Supplier>): List<SupplierRow> {
    val priced = ArrayList<SupplierRow>()
    val unpriced = ArrayList<SupplierRow>()
    for ((supplierId, list) in offers.groupBy { o: SupplierOffer -> o.supplierId }) {
        val sup = suppliers.firstOrNull { s: Supplier -> s.id == supplierId } ?: continue
        val best = list.minByOrNull { o: SupplierOffer -> if (o.purchasePrice > 0.0) o.purchasePrice else Double.MAX_VALUE } ?: continue
        val row = SupplierRow(sup, best, list, best.purchasePrice > 0.0, 0, 0.0, 0)
        if (row.valid) priced.add(row) else unpriced.add(row)
    }
    priced.sortWith(compareBy<SupplierRow> { row -> row.offer.purchasePrice }.thenBy { row -> row.supplier.orderIndex })
    unpriced.sortBy { row -> row.supplier.orderIndex }
    val floor = priced.firstOrNull()?.offer?.purchasePrice ?: 0.0
    val ranked = priced.mapIndexed { i: Int, row: SupplierRow ->
        if (i == 0) row.copy(rank = 1)
        else {
            val delta = row.offer.purchasePrice - floor
            row.copy(rank = i + 1, diffAbs = delta, diffPct = if (floor > 0.0) ((delta / floor) * 100.0).toInt() else 0)
        }
    }
    return ranked + unpriced.map { row: SupplierRow -> row.copy(rank = 0) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SizeChip(label: String, supplierCount: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) BrandYellow else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Color.Black else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.height(34.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label.ifEmpty { "حجم" }, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp, color = if (selected) contentColorOn(BrandYellow) else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                if (supplierCount == 0) "بلا مزود" else "$supplierCount",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (supplierCount == 0) DangerRed else if (selected) contentColorOn(BrandYellow) else SellGreen
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SupplierPriceRow(
    row: SupplierRow,
    recordedPurchase: Double,
    inCartQty: Int,
    onEdit: () -> Unit,
    onUnlink: () -> Unit,
    onAddToCart: () -> Unit,
    onCleanDuplicates: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBest: Boolean = row.rank == 1
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (isBest) SellGreen.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (isBest) 1.5.dp else 1.dp, if (isBest) SellGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (isBest) SellGreen else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (row.valid) "${row.rank}" else "؟",
                    color = if (isBest) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.supplier.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isBest) {
                        Spacer(Modifier.width(6.dp))
                        Text("الأرخص", color = SellGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "أقل كمية ${row.offer.minOrder} · توصيل ${formatCurrency(row.supplier.deliveryCost)}".withThousandsDot(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.offers.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, "تكرار", modifier = Modifier.size(13.dp), tint = BrandYellow)
                        Spacer(Modifier.width(4.dp))
                        Text("${row.offers.size} روابط مكررة", style = MaterialTheme.typography.labelSmall, color = BrandYellow, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onCleanDuplicates, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                            Text("حذف المكرر", fontSize = 10.sp, color = SellGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (row.valid) {
                    Text(formatCurrency(row.offer.purchasePrice).withThousandsDot(), color = BuyAmber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (!isBest && row.diffAbs > 0.0) {
                        Text("\u200E+${formatCurrency(row.diffAbs)} (+${row.diffPct}%)\u200E".withThousandsDot(), color = DangerRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    } else if (!isBest && row.valid) {
                        Text("نفس سعر الأرخص", color = SellGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (recordedPurchase > 0.0 && !pricesEqual(row.offer.purchasePrice, recordedPurchase)) {
                        val cheaper: Boolean = row.offer.purchasePrice < recordedPurchase
                        Text(if (cheaper) "أرخص من سعر شراؤك" else "أغلى من سعر شراؤك", fontSize = 9.sp, color = if (cheaper) SellGreen else DangerRed, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("بلا سعر محدد", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
                    Text("حدّده ليشارك في المقارنة", fontSize = 9.sp, color = BrandYellow)
                }
                if (inCartQty > 0) {
                    Text("في السلة: $inCartQty", fontSize = 9.sp, color = BuyAmber, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(6.dp))
            TintedCircleButton(
                onClick = onAddToCart,
                icon = Icons.Default.ShoppingCart,
                contentDescription = "إضافة لسلة الشراء",
                tint = BuyAmber,
                size = 30.dp,
                iconSize = 15.dp
            )
            Spacer(Modifier.width(4.dp))
            TintedCircleButton(
                onClick = onEdit,
                icon = Icons.Default.Edit,
                contentDescription = "تعديل السعر",
                tint = SellGreen,
                size = 30.dp,
                iconSize = 15.dp
            )
            Spacer(Modifier.width(4.dp))
            TintedCircleButton(
                onClick = onUnlink,
                icon = Icons.Default.LinkOff,
                contentDescription = "فك الربط",
                tint = DangerRed,
                size = 30.dp,
                iconSize = 15.dp
            )
        }
    }
}

@Composable
private fun EditOfferDialog(
    offer: SupplierOffer,
    label: String,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    var price by remember(offer.id) { mutableStateOf(offer.purchasePrice.cleanString()) }
    var minOrder by remember(offer.id) { mutableStateOf(offer.minOrder.toString()) }
    val parsedPrice: Double? = price.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("سعر $label", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("سعر الشراء من المزود") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = minOrder, onValueChange = { minOrder = it }, label = { Text("أقل كمية") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                if (parsedPrice == null || parsedPrice <= 0.0) {
                    Text("بلا سعر: لن يدخل هذا المزود المقارنة حتى تكتب سعرًا صحيحًا.", style = MaterialTheme.typography.labelSmall, color = BrandYellow)
                }
            }
        },
        confirmButton = {
            BrandButton(
                text = "حفظ",
                enabled = parsedPrice != null && minOrder.toIntOrNull() != null,
                onClick = {
                    vm.updateOffer(offer.copy(purchasePrice = parsedPrice ?: 0.0, minOrder = minOrder.toIntOrNull() ?: 1))
                    onDismiss()
                }
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSupplierDialog(
    component: Component,
    variant: ComponentVariant,
    suppliers: List<Supplier>,
    existing: List<SupplierOffer>,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    var chosen by remember(variant.id) { mutableStateOf<Supplier?>(null) }
    var price by remember(variant.id) { mutableStateOf(variant.defaultPurchasePrice.cleanString()) }
    val parsedPrice: Double? = price.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (chosen == null) "ربط ${componentLabel(component.name, variant, "القطعة")}" else "${chosen!!.name}", fontWeight = FontWeight.Bold) },
        text = {
            if (suppliers.isEmpty()) {
                Text("لا مزودين بعد — أضف مزودًا من الشاشة الرئيسية أولًا.")
            } else if (chosen == null) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    suppliers.forEach { s: Supplier ->
                        val linked: SupplierOffer? = existing.firstOrNull { o: SupplierOffer -> o.supplierId == s.id }
                        Card(
                            onClick = { chosen = s; price = (linked?.purchasePrice ?: variant.defaultPurchasePrice).cleanString() },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        (if (linked == null) "غير مرتبط بهذا الحجم" else "مربوط بالفعل — ${formatCurrency(linked.purchasePrice)}").withThousandsDot(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (linked == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else BrandYellow
                                    )
                                }
                                if (linked != null) Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }
                    Text("اختيار مزود مربوط مسبقًا يفتح سعره للتعديل بدل تكرار الربط.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("سعر الشراء من ${chosen!!.name}") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    Text(
                        "توصيل ${formatCurrency(chosen!!.deliveryCost)} · أقل كمية ${chosen!!.defaultMinOrder}".withThousandsDot(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (parsedPrice == null || parsedPrice <= 0.0) {
                        Text("يمكن الحفظ بلا سعر، لكنه لن يشارك في المقارنة حتى تحدّده.", style = MaterialTheme.typography.labelSmall, color = BrandYellow)
                    }
                }
            }
        },
        confirmButton = {
            if (chosen == null) {
                TextButton(onClick = onDismiss) { Text("إغلاق") }
            } else {
                BrandButton(
                    text = "حفظ",
                    enabled = parsedPrice != null,
                    onClick = {
                        val supplier = chosen!!
                        val newPrice = parsedPrice ?: 0.0
                        val linked = existing.firstOrNull { o: SupplierOffer -> o.supplierId == supplier.id }
                        if (linked != null) {
                            vm.updateOffer(linked.copy(purchasePrice = newPrice))
                        } else {
                            vm.addOffer(SupplierOffer(variantId = variant.id, supplierId = supplier.id, purchasePrice = newPrice, minOrder = supplier.defaultMinOrder))
                        }
                        SoundHelper.playAddSuccess()
                        onDismiss()
                    }
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOfferSheet(
    supplier: Supplier,
    components: List<Component>,
    variants: List<ComponentVariant>,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    val settings by vm.settings.collectAsState()
    val allOffers by vm.offers.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    val tagsOf by vm.tagsByComponent.collectAsState()
    var pendingVariantComponent by remember { mutableStateOf<Component?>(null) }
    var pricingComponent by remember { mutableStateOf<Component?>(null) }
    var pricingVariant by remember { mutableStateOf<ComponentVariant?>(null) }
    var showNewComponent by remember { mutableStateOf(false) }
    var addedCount by remember { mutableStateOf(0) }

    fun onComponentTap(comp: Component) {
        val compVariants = variants.filter { it.componentId == comp.id }
        when {
            compVariants.isEmpty() -> {
                SoundHelper.playWarning()
                scope.launch { snackbarHostState.showSnackbar("القطعة بدون أحجام — أضف حجماً من قسم المخزون", duration = SnackbarDuration.Short) }
            }
            compVariants.size == 1 -> {
                SoundHelper.playTap()
                pricingComponent = comp
                pricingVariant = compVariants[0]
            }
            else -> {
                SoundHelper.playTap()
                pendingVariantComponent = comp
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.95f),
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
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
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "إغلاق", tint = MaterialTheme.colorScheme.onSurface) }
                            Text("إضافة قطعة إلى المزود", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SellGreen, modifier = Modifier.weight(1f))
                            TintedCircleButton(
                                onClick = { showNewComponent = true },
                                icon = Icons.Default.Add,
                                contentDescription = "قطعة جديدة",
                                tint = BrandYellow,
                                size = 38.dp,
                                iconSize = 19.dp
                            )
                        }
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("ابحث بالاسم والوسم...") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true
                        )
                        Text(
                            "اختيار قطعة هنا للربط والمعلومات فقط — لا يتم خصم أي كمية من المخزون.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                }
            },
            bottomBar = {
                if (addedCount > 0) {
                    Surface(color = SellGreen, shadowElevation = 8.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("تم ربط $addedCount قطعة بقائمة المزود", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        ) { innerPadding ->
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
                    val totalStock = compVariants.sumOf { it.stockCount }
                    val hasVars = compVariants.isNotEmpty()

                    Box(modifier = Modifier.padding(4.dp).animateItem()) {
                        PieceGridTile(
                            imageUri = comp.imageUri,
                            title = comp.name,
                            modifier = Modifier.clickable { onComponentTap(comp) },
                            circleSize = 56.dp,
                            below = {
                                val badgeCount = if (hasVars) totalStock else comp.stockCount
                                StockBadge(count = badgeCount, color = stockColorFor(badgeCount, settings))
                            }
                        )
                    }
                }
            }
        }
    }

    if (pendingVariantComponent != null) {
        val comp = pendingVariantComponent!!
        val compVariants = variants.filter { it.componentId == comp.id }
        AlertDialog(
            onDismissRequest = { pendingVariantComponent = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("اختر الحجم", fontWeight = FontWeight.Bold, color = SellGreen) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    compVariants.forEach { v ->
                        Card(
                            onClick = {
                                pricingComponent = comp
                                pricingVariant = v
                                pendingVariantComponent = null
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(componentLabel(comp.name, v), fontWeight = FontWeight.Bold)
                                    Text("بيع: ${formatCurrency(v.defaultSellPrice)}".withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = SellGreen, fontWeight = FontWeight.Bold)
                                }
                                StockBadge(count = v.stockCount, color = stockColorFor(v.stockCount, settings), size = 26.dp, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pendingVariantComponent = null }) { Text("إلغاء") } }
        )
    }

    if (pricingComponent != null && pricingVariant != null) {
        val comp = pricingComponent!!
        val variant = pricingVariant!!
        val existingOffer = allOffers.find { it.supplierId == supplier.id && it.variantId == variant.id }
        var purchase by remember(variant.id) { mutableStateOf(existingOffer?.purchasePrice?.cleanString() ?: variant.defaultPurchasePrice.cleanString()) }

        AlertDialog(
            onDismissRequest = { pricingComponent = null; pricingVariant = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("ربط القطعة بالمزود", fontWeight = FontWeight.Bold, color = SellGreen) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        componentLabel(comp.name, variant),
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("سعر البيع: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text(formatCurrency(variant.defaultSellPrice).withThousandsDot(), color = SellGreen, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text("المخزون: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(stockColorFor(variant.stockCount, settings)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${variant.stockCount}", color = contentColorOn(stockColorFor(variant.stockCount, settings)), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedTextField(
                        value = purchase,
                        onValueChange = { purchase = it },
                        label = { Text("سعر الشراء من المزود") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Text(
                        "الربط معلوماتي وتنظيمي فقط، ولن يُخصم من المخزون.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandYellow
                    )
                }
            },
            confirmButton = {
                BrandButton(
                    text = if (existingOffer != null) "تحديث الربط" else "حفظ الربط",
                    enabled = purchase.toDoubleOrNull() != null,
                    onClick = {
                        val newPrice = purchase.toDoubleOrNull() ?: 0.0
                        if (existingOffer != null) {
                            vm.updateOffer(existingOffer.copy(purchasePrice = newPrice))
                        } else {
                            vm.addOffer(
                                SupplierOffer(
                                    variantId = variant.id,
                                    supplierId = supplier.id,
                                    purchasePrice = newPrice,
                                    minOrder = supplier.defaultMinOrder
                                )
                            )
                        }
                        SoundHelper.playAddSuccess()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        addedCount++
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "تم ${if (existingOffer != null) "تحديث" else "ربط"} ${comp.name} في قائمة المزود",
                                duration = SnackbarDuration.Short
                            )
                        }
                        pricingComponent = null
                        pricingVariant = null
                    }
                )
            },
            dismissButton = { TextButton(onClick = { pricingComponent = null; pricingVariant = null }) { Text("إلغاء") } }
        )
    }

    if (showNewComponent) {
        NewSupplierComponentDialog(
            supplier = supplier,
            vm = vm,
            onDismiss = { showNewComponent = false },
            onCreated = { comp ->
                addedCount++
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "أُضيفت ${comp.name} إلى المخزون ورُبطت بالمزود",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        )
    }
}

@Composable
private fun NewSupplierComponentDialog(
    supplier: Supplier,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onCreated: (Component) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var purchase by remember { mutableStateOf("") }
    var sell by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("0") }
    var imageUri by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }
    val imagePickerLauncher = rememberImageCropPicker { imageUri = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("قطعة جديدة", fontWeight = FontWeight.Bold, color = BrandYellow) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { imagePickerLauncher.launch(arrayOf("image/*")) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        if (imageUri.isNotEmpty()) AsyncImage(model = imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Default.AddAPhoto, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("صورة القطعة (اختياري)", fontWeight = FontWeight.Medium)
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم القطعة") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = purchase, onValueChange = { purchase = it }, label = { Text("سعر الشراء من المزود") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = sell, onValueChange = { sell = it }, label = { Text("سعر البيع") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = stock, onValueChange = { stock = it }, label = { Text("المخزون الابتدائي") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Text(
                    "ستُضاف القطعة إلى المخزون كقطعة جديدة وتُربط بهذا المزود بسعر الشراء نفسه. الربط لا يخصم من المخزون.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (formError.isNotEmpty()) Text(formError, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            BrandButton(
                text = "إضافة وربط",
                enabled = name.isNotBlank(),
                onClick = {
                    val purchaseValue = purchase.toDoubleOrNull() ?: 0.0
                    vm.addComponentWithVariant(
                        name = name.trim(),
                        imageUri = imageUri,
                        purchasePrice = purchaseValue,
                        sellPrice = sell.toDoubleOrNull() ?: 0.0,
                        stock = stock.toIntOrNull() ?: 0,
                        onDone = { comp, variant ->
                            vm.addOffer(
                                SupplierOffer(
                                    variantId = variant.id,
                                    supplierId = supplier.id,
                                    purchasePrice = purchaseValue,
                                    minOrder = supplier.defaultMinOrder
                                )
                            )
                            SoundHelper.playAddSuccess()
                            onCreated(comp)
                            onDismiss()
                        },
                        onRejected = { formError = it }
                    )
                }
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

// سلة شراء مكدّسة: بطاقة لكل مزود — قطعه، ثم توصيله مرة واحدة في آخرها، ثم مجموعه — وإجمالي واحد أسفل السلة
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseCartSheet(
    cart: List<CartItem>,
    allOffers: List<SupplierOffer>,
    allVariants: List<ComponentVariant>,
    allComponents: List<Component>,
    suppliers: List<Supplier>,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    val offersById: Map<Long, SupplierOffer> = allOffers.associateBy { o: SupplierOffer -> o.id }
    val variantsById: Map<Long, ComponentVariant> = allVariants.associateBy { v: ComponentVariant -> v.id }
    val componentsById: Map<Long, Component> = allComponents.associateBy { c: Component -> c.id }

    val lines: List<CartLine> = cart.sortedBy { c: CartItem -> c.orderIndex }.mapNotNull { c: CartItem ->
        val offer: SupplierOffer = offersById[c.offerId] ?: return@mapNotNull null
        val supplier: Supplier = suppliers.firstOrNull { s: Supplier -> s.id == offer.supplierId } ?: return@mapNotNull null
        val variant: ComponentVariant? = variantsById[offer.variantId]
        val comp: Component? = variant?.let { v: ComponentVariant -> componentsById[v.componentId] }
        CartLine(
            item = c,
            offer = offer,
            supplier = supplier,
            label = componentLabel(comp?.name, variant, "قطعة محذوفة"),
            imageUri = comp?.imageUri ?: ""
        )
    }

    val groups: List<List<CartLine>> = lines
        .groupBy { l: CartLine -> l.supplier.id }
        .entries
        .sortedBy { e: Map.Entry<Long, List<CartLine>> -> e.value.minOf { l: CartLine -> l.item.orderIndex } }
        .map { e: Map.Entry<Long, List<CartLine>> -> e.value.sortedBy { l: CartLine -> l.item.orderIndex } }

    var confirmClear by remember { mutableStateOf(false) }
    val muted: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.92f),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(horizontal = 14.dp).fillMaxHeight()) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(BrandYellow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ShoppingCart, null, tint = contentColorOn(BrandYellow), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("سلة المسواك", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    if (lines.isNotEmpty()) {
                        Text(
                            "${piecesPlural(lines.sumOf { l: CartLine -> l.item.quantity })} · ${suppliersPlural(groups.size)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = muted
                        )
                    }
                }
                if (lines.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("تفريغ", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), thickness = 1.dp)

            if (lines.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Default.ShoppingCart,
                        message = "سلة المسواك فارغة هسّه.. تكدر تضيف مواد من قوائم المجهّزين وتجمع مسواكك كله هنا"
                    )
                }
            } else {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    groups.forEach { group: List<CartLine> -> CartSupplierCard(group, vm) }
                }

                CartGrandTotal(
                    itemsTotal = lines.sumOf { l: CartLine -> l.offer.purchasePrice * l.item.quantity },
                    deliveryTotal = groups.sumOf { g: List<CartLine> -> g.first().supplier.deliveryCost },
                    supplierCount = groups.size
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = DialogShape,
            title = { Text("تفريغ سلة المسواك", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = { Text("راح تنشال كل المواد من السلة. أسعارك وقوائم المجهّزين ما تتأثر.") },
            confirmButton = {
                Button(
                    onClick = { vm.clearCart(); confirmClear = false; onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = CircleShape
                ) { Text("تفريغ", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } }
        )
    }
}

private data class CartLine(
    val item: CartItem,
    val offer: SupplierOffer,
    val supplier: Supplier,
    val label: String,
    val imageUri: String
)

// بطاقة المزود بلغة التطبيق: سطح + حد خفيف، رأس بهوية المزود، القطع، التوصيل، ثم مجموع المزود
@Composable
private fun CartSupplierCard(group: List<CartLine>, vm: MainViewModel) {
    val supplier: Supplier = group.first().supplier
    val itemsTotal: Double = group.sumOf { l: CartLine -> l.offer.purchasePrice * l.item.quantity }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shadowElevation = 2.dp
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(BrandYellow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Storefront, null, modifier = Modifier.size(17.dp), tint = contentColorOn(BrandYellow))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        supplier.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        piecesPlural(group.sumOf { l: CartLine -> l.item.quantity }),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                thickness = 1.dp
            )

            group.forEachIndexed { i: Int, line: CartLine ->
                if (i > 0) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                        thickness = 1.dp
                    )
                }
                CartLineRow(
                    line = line,
                    onDec = { vm.changeCartQuantity(line.item.id, -1) },
                    onInc = { vm.changeCartQuantity(line.item.id, +1) },
                    onRemove = { vm.removeFromCart(line.item.id) }
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.LocalShipping,
                    null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.width(6.dp))
                Text("التوصيل", style = MaterialTheme.typography.bodySmall, color = muted)
                Spacer(Modifier.weight(1f))
                Text(
                    formatCurrency(supplier.deliveryCost).withThousandsDot(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            }

            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "المجموع مع التوصيل",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatCurrency(itemsTotal + supplier.deliveryCost).withThousandsDot(),
                    fontWeight = FontWeight.Bold,
                    color = BuyAmber,
                    fontSize = 15.sp
                )
            }
        }
    }
}

// الإجمالي بلغة التطبيق: بطاقة سطح بحد أصفر رفيع، صفوف هادئة، والمجموع النهائي بلون الشراء
@Composable
private fun CartGrandTotal(itemsTotal: Double, deliveryTotal: Double, supplierCount: Int) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        shape = CardShape,
        border = BorderStroke(1.5.dp, BrandYellow.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(BrandYellow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ShoppingBag, null, tint = contentColorOn(BrandYellow), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "الإجمالي النهائي",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${suppliersPlural(supplierCount)} · أغراض وتوصيل",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted
                    )
                }
                Text(
                    formatCurrency(itemsTotal + deliveryTotal).withThousandsDot(),
                    fontWeight = FontWeight.Bold,
                    color = BuyAmber,
                    fontSize = 20.sp
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), thickness = 1.dp)
            CartTotalRow("أغراض القطع", formatCurrency(itemsTotal).withThousandsDot())
            CartTotalRow("التوصيل (${suppliersPlural(supplierCount)})", formatCurrency(deliveryTotal).withThousandsDot())
        }
    }
}

@Composable
private fun CartTotalRow(label: String, value: AnnotatedString) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CartLineRow(line: CartLine, onDec: () -> Unit, onInc: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (line.imageUri.isNotEmpty()) {
                AsyncImage(model = line.imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Image(painter = painterResource(id = R.drawable.clover_placeholder), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                line.label,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "\u200E${formatCurrency(line.offer.purchasePrice)} للقطعة\u200E".withThousandsDot(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatCurrency(line.offer.purchasePrice * line.item.quantity).withThousandsDot(),
                fontWeight = FontWeight.Bold,
                color = BuyAmber,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton(
                    onClick = onDec,
                    icon = Icons.Default.Remove,
                    contentDescription = "إنقاص",
                    containerColor = BrandYellow,
                    size = 26.dp,
                    iconSize = 15.dp,
                    enabled = line.item.quantity > 1
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${line.item.quantity}",
                    fontWeight = FontWeight.Bold,
                    minLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(26.dp)
                )
                Spacer(Modifier.width(4.dp))
                StepButton(onClick = onInc, icon = Icons.Default.Add, contentDescription = "زيادة", containerColor = SellGreen, size = 26.dp, iconSize = 15.dp)
            }
        }
        Spacer(Modifier.width(6.dp))
        TintedCircleButton(onClick = onRemove, icon = Icons.Default.Delete, contentDescription = "إزالة من السلة", tint = DangerRed, size = 26.dp, iconSize = 14.dp)
    }
}

