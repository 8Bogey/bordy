package com.bordy.app.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.bordy.app.R
import com.bordy.app.data.*
import com.bordy.app.ui.components.DialogShape
import com.bordy.app.ui.components.BrandButton
import com.bordy.app.ui.components.BrandFab
import com.bordy.app.ui.components.PieceGridTile
import com.bordy.app.ui.components.TagDots
import com.bordy.app.ui.components.TagEditor
import com.bordy.app.ui.components.TagFilterBar
import com.bordy.app.ui.components.TagManageDialog
import com.bordy.app.ui.components.TagPill
import com.bordy.app.ui.components.TintedCircleButton
import com.bordy.app.ui.components.TagsEnabled
import com.bordy.app.ui.components.StockDeleteConfirmDialog
import com.bordy.app.ui.components.StepButton
import com.bordy.app.ui.components.rememberStockDeleteHandler
import com.bordy.app.ui.components.rememberImageCropPicker
import com.bordy.app.utils.*
import com.bordy.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComponentsScreen(vm: MainViewModel, pricesHidden: Boolean = false, onOpenSuppliers: () -> Unit) {
    val componentsFromDb by vm.components.collectAsState()
    val variants by vm.variants.collectAsState()
    val settings by vm.settings.collectAsState()

    var localItems by remember { mutableStateOf<List<Component>>(emptyList()) }
    LaunchedEffect(componentsFromDb) { localItems = componentsFromDb }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isListView by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddComponent by remember { mutableStateOf(false) }
    var selectedComponent by remember { mutableStateOf<Component?>(null) }

    val allTags by vm.tags.collectAsState()
    val tagsOf by vm.tagsByComponent.collectAsState()
    val tagUsage by vm.tagUsage.collectAsState()
    var selectedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var managedTag by remember { mutableStateOf<Tag?>(null) }

    // وسم محذوف أو مدمج لا يبقى عالقاً في التصفية
    LaunchedEffect(allTags) {
        if (selectedTagIds.isNotEmpty()) {
            val alive = allTags.map { t: Tag -> t.id }.toSet()
            val pruned = selectedTagIds.filter { id: Long -> alive.contains(id) }.toSet()
            if (pruned != selectedTagIds) selectedTagIds = pruned
        }
    }

    var newImageUri by remember { mutableStateOf("") }
    val imagePickerLauncher = rememberImageCropPicker { newImageUri = it }

    // ✅ ارتفاع ثابت بدل onGloballyPositioned
    val slotHeightPx: Float = with(density) { 82.dp.toPx() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var initialIndex by remember { mutableStateOf(-1) }
    var accumulatedDrag by remember { mutableStateOf(0f) }
    var gridCellPitch by remember { mutableStateOf(IntSize.Zero) }
    var gridDragOffset by remember { mutableStateOf(Offset.Zero) }
    val gridSpacingPx = with(density) { 6.dp.toPx() }

    // ✅ قائمة مفلترة بالبحث ووسوماً معاً
    val filteringTags = selectedTagIds.isNotEmpty()
    val filteredList: MutableList<Component> = mutableListOf()
    for (item in localItems) {
        val itemTags: List<Tag> = tagsOf[item.id] ?: emptyList()
        val matchesSearch = searchQuery.isEmpty() ||
            item.name.contains(searchQuery, true) ||
            itemTags.any { t: Tag -> t.name.contains(searchQuery, true) }
        val matchesTags = !filteringTags || itemTags.any { t: Tag -> selectedTagIds.contains(t.id) }
        if (matchesSearch && matchesTags) filteredList.add(item)
    }
    val filtered: List<Component> = filteredList

    Scaffold(
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { s: String -> searchQuery = s },
                        placeholder = { Text(if (!TagsEnabled || allTags.isEmpty()) "دوّر بالاسم..." else "دوّر بالاسم أو الوسم...", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                Icon(
                                    Icons.Default.Close,
                                    "مسح البحث",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.clickable { searchQuery = "" }
                                )
                            }
                        } else null,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(SellGreen)
                            .clickable { isListView = !isListView },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isListView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                            "تبديل العرض",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(BrandYellow)
                            .clickable(onClick = onOpenSuppliers),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Store,
                            "المزودون",
                            tint = OnBrandYellow,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (TagsEnabled) TagFilterBar(
                    tags = allTags,
                    usage = tagUsage,
                    selectedIds = selectedTagIds,
                    onToggle = { id: Long ->
                        selectedTagIds = if (selectedTagIds.contains(id)) selectedTagIds - id else selectedTagIds + id
                    },
                    onClear = { selectedTagIds = emptySet() },
                    onManage = { t: Tag -> managedTag = t },
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        },
        floatingActionButton = {
            BrandFab(
                onClick = { showAddComponent = true; newImageUri = "" },
                icon = Icons.Default.Add,
                contentDescription = "إضافة قطعة",
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    ) { padding ->
        if (isListView) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ✅ items() مع أنواع صريحة على كل Lambda
                items(items = filtered, key = { c: Component -> c.id }) { comp: Component ->
                    var totalStockVal: Int = 0
                    var firstVariantLocal: ComponentVariant? = null
                    for (cv in variants) {
                        if (cv.componentId == comp.id) {
                            totalStockVal += cv.stockCount
                            if (firstVariantLocal == null) firstVariantLocal = cv
                        }
                    }
                    val totalStock: Int = if (firstVariantLocal != null) totalStockVal else comp.stockCount
                    val firstVariant: ComponentVariant? = firstVariantLocal

                    var currentIndexLocal: Int = -1
                    for (i in localItems.indices) { if (localItems[i].id == comp.id) { currentIndexLocal = i; break } }
                    val currentIndex: Int = currentIndexLocal

                    val isDragging: Boolean = draggedId == comp.id
                    val indexDelta: Int = if (initialIndex >= 0) currentIndex - initialIndex else 0
                    val displayTranslationY: Float = if (isDragging) accumulatedDrag - (indexDelta * slotHeightPx) else 0f

                    val dragPointerModifier: Modifier = if (searchQuery.isEmpty() && !filteringTags) {
                        Modifier.pointerInput(key1 = comp.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { _: Offset ->
                                    draggedId = comp.id
                                    var idx = -1
                                    for (i in localItems.indices) { if (localItems[i].id == comp.id) { idx = i; break } }
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
                                        for (i in localItems.indices) { if (localItems[i].id == comp.id) { idx = i; break } }
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
                                    vm.reorderComponents(localItems)
                                },
                                onDragCancel = {
                                    draggedId = null
                                    accumulatedDrag = 0f
                                    initialIndex = -1
                                    localItems = componentsFromDb
                                }
                            )
                        }
                    } else Modifier

                    val transformModifier: Modifier = if (isDragging) {
                        Modifier.zIndex(1f).graphicsLayer {
                            translationY = displayTranslationY
                            scaleX = 1.03f
                            scaleY = 1.03f
                            shadowElevation = 16f
                            alpha = 0.96f
                        }
                    } else Modifier.zIndex(0f)

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(transformModifier)
                            .then(dragPointerModifier)
                            .clickable { selectedComponent = comp },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        shadowElevation = if (isDragging) 8.dp else 2.dp
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DragHandle, "اسحب", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDragging) 0.8f else 0.35f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (comp.imageUri.isNotEmpty()) AsyncImage(model = comp.imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Image(painter = painterResource(id = R.drawable.clover_placeholder), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(comp.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, fontSize = 15.sp)
                                val listPurchase = firstVariant?.defaultPurchasePrice ?: comp.purchasePrice
                                val listSell = firstVariant?.defaultSellPrice ?: comp.sellPrice
                                if (!pricesHidden && (listPurchase > 0 || listSell > 0)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("شراء: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                        Text(formatCurrency(listPurchase).withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = BuyAmber, fontWeight = FontWeight.Bold)
                                        Text(" | بيع: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                        Text(formatCurrency(listSell).withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = SellGreen, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (TagsEnabled) TagDots(
                                tags = tagsOf[comp.id] ?: emptyList(),
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(stockColorFor(totalStock, settings))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("$totalStock", color = contentColorOn(stockColorFor(totalStock, settings)), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp)
            ) {
                gridItems(items = filtered, key = { c: Component -> c.id }) { comp: Component ->
                    var totalStockVal: Int = 0
                    var hasVars = false
                    for (cv in variants) {
                        if (cv.componentId == comp.id) {
                            totalStockVal += cv.stockCount
                            hasVars = true
                        }
                    }
                    val totalStock: Int = if (hasVars) totalStockVal else comp.stockCount

                    val isDragging: Boolean = draggedId == comp.id
                    val currentIndex: Int = localItems.indexOfFirst { it.id == comp.id }
                    val cellWidth: Float = gridCellPitch.width.toFloat().coerceAtLeast(1f)
                    val cellHeight: Float = gridCellPitch.height.toFloat().coerceAtLeast(1f)
                    val currentRow: Int = if (currentIndex >= 0) currentIndex / 5 else 0
                    val currentCol: Int = if (currentIndex >= 0) currentIndex % 5 else 0
                    val startRow: Int = if (initialIndex >= 0) initialIndex / 5 else 0
                    val startCol: Int = if (initialIndex >= 0) initialIndex % 5 else 0
                    val dragTranslationX: Float = if (isDragging) gridDragOffset.x + (currentCol - startCol) * cellWidth else 0f
                    val dragTranslationY: Float = if (isDragging) gridDragOffset.y - (currentRow - startRow) * cellHeight else 0f

                    val dragPointerModifier: Modifier = if (searchQuery.isEmpty() && !filteringTags) {
                        Modifier.pointerInput(key1 = comp.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { _: Offset ->
                                    draggedId = comp.id
                                    initialIndex = localItems.indexOfFirst { it.id == comp.id }
                                    gridDragOffset = Offset.Zero
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change: PointerInputChange, dragAmount: Offset ->
                                    change.consume()
                                    gridDragOffset += dragAmount
                                    val start = initialIndex
                                    if (start >= 0 && gridCellPitch.width > 0 && gridCellPitch.height > 0) {
                                        val pitchW = gridCellPitch.width.toFloat()
                                        val pitchH = gridCellPitch.height.toFloat()
                                        val targetRow = (start / 5 + (gridDragOffset.y / pitchH).roundToInt()).coerceAtLeast(0)
                                        val targetCol = (start % 5 - (gridDragOffset.x / pitchW).roundToInt()).coerceIn(0, 4)
                                        val target = (targetRow * 5 + targetCol).coerceIn(0, localItems.lastIndex)
                                        val current = localItems.indexOfFirst { it.id == comp.id }
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
                                    draggedId = null
                                    gridDragOffset = Offset.Zero
                                    initialIndex = -1
                                    vm.reorderComponents(localItems)
                                },
                                onDragCancel = {
                                    draggedId = null
                                    gridDragOffset = Offset.Zero
                                    initialIndex = -1
                                    localItems = componentsFromDb
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
                            .padding(4.dp)
                            .animateItem()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = dragTranslationX
                                translationY = dragTranslationY
                                scaleX = if (isDragging) 1.06f else 1f
                                scaleY = if (isDragging) 1.06f else 1f
                                shadowElevation = if (isDragging) 16f else 0f
                                alpha = if (isDragging) 0.97f else 1f
                            }
                            .then(dragPointerModifier)
                            .clickable { selectedComponent = comp }
                    ) {
                        PieceGridTile(
                            imageUri = comp.imageUri,
                            title = comp.name,
                            below = {
                                // مساحة ثابتة للوسوم حتى لا تتفاوت ارتفاعات الخلايا عند إعادة الترتيب بالسحب
                                if (TagsEnabled) Box(Modifier.height(9.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
                                    TagDots(tags = tagsOf[comp.id] ?: emptyList(), max = 4)
                                }
                                StockBadge(
                                    count = totalStock,
                                    color = stockColorFor(totalStock, settings),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddComponent) {
        var name by remember { mutableStateOf("") }
        var purchase by remember { mutableStateOf("") }
        var sell by remember { mutableStateOf("") }
        var stock by remember { mutableStateOf("0") }
        var tagNames by remember { mutableStateOf<List<String>>(emptyList()) }
        var formError by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddComponent = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("مادة جديدة +", fontWeight = FontWeight.Bold) },
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
                            Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            if (newImageUri.isNotEmpty()) AsyncImage(model = newImageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Default.AddAPhoto, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("اختيار صورة (اختياري)", fontWeight = FontWeight.Medium)
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { s: String -> name = s },
                        label = { Text("اسم القطعة") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = purchase,
                            onValueChange = { s: String -> purchase = s },
                            label = { Text("سعر الشراء") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = sell,
                            onValueChange = { s: String -> sell = s },
                            label = { Text("سعر البيع") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                    OutlinedTextField(
                        value = stock,
                        onValueChange = { s: String -> stock = s },
                        label = { Text("العدد المتوفر بالمخزن") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    if (TagsEnabled) TagEditor(
                        selectedNames = tagNames,
                        allTags = allTags,
                        onAdd = { n: String -> tagNames = tagNames + n },
                        onRemove = { n: String -> tagNames = tagNames.filter { s: String -> !s.equals(n, ignoreCase = true) } },
                        label = "وسوم القطعة (اختياري)"
                    )
                    if (formError.isNotEmpty()) Text(formError, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                BrandButton(
                    text = "حفظ",
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.addPlainComponent(
                            name = name.trim(),
                            imageUri = newImageUri,
                            purchasePrice = purchase.toDoubleOrNull() ?: 0.0,
                            sellPrice = sell.toDoubleOrNull() ?: 0.0,
                            stock = stock.toIntOrNull() ?: 0,
                            onDone = { comp: Component ->
                                if (tagNames.isNotEmpty()) vm.setComponentTags(comp.id, tagNames)
                                showAddComponent = false
                            },
                            onRejected = { formError = it }
                        )
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddComponent = false }, shape = CircleShape) {
                    Text("إلغاء")
                }
            }
        )
    }

    val selectedSnapshot = selectedComponent
    if (selectedSnapshot != null) {
        val liveComponent = componentsFromDb.firstOrNull { it.id == selectedSnapshot.id } ?: selectedSnapshot
        val itemVariants: MutableList<ComponentVariant> = mutableListOf()
        for (v in variants) { if (v.componentId == liveComponent.id) itemVariants.add(v) }
        ComponentDetailSheet(component = liveComponent, variants = itemVariants, componentTags = tagsOf[liveComponent.id] ?: emptyList(), allOffers = vm.offers.collectAsState().value, suppliers = vm.suppliers.collectAsState().value, settings = settings, pricesHidden = pricesHidden, onDismiss = { selectedComponent = null }, onManageTag = { t: Tag -> managedTag = t }, vm = vm)
    }

    val managedSnapshot = if (TagsEnabled) managedTag else null
    if (managedSnapshot != null) {
        val live = allTags.firstOrNull { t: Tag -> t.id == managedSnapshot.id }
        if (live == null) {
            LaunchedEffect(managedSnapshot) { managedTag = null }
        } else {
            TagManageDialog(
                tag = live,
                useCount = tagUsage[live.id] ?: 0,
                onDismiss = { managedTag = null },
                onRename = { n: String -> vm.renameTag(live.id, n) { _, msg -> if (msg.isNotEmpty()) Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } },
                onRecolor = { i: Int -> vm.setTagColor(live.id, i) },
                onDelete = { vm.deleteTag(live.id); managedTag = null }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComponentDetailSheet(component: Component, variants: List<ComponentVariant>, componentTags: List<Tag>, allOffers: List<SupplierOffer>, suppliers: List<Supplier>, settings: AppSettings?, pricesHidden: Boolean = false, onDismiss: () -> Unit, onManageTag: (Tag) -> Unit, vm: MainViewModel) {
    val context = LocalContext.current
    val sheetTags by vm.tags.collectAsState()
    var showEditComponent by remember { mutableStateOf(false) }
    var showAddVariant by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val componentDeletion = rememberStockDeleteHandler("تم حذف القطعة", onDismiss)
    var editImageUri by remember { mutableStateOf(component.imageUri) }
    var editTagNames by remember(component.id, componentTags) { mutableStateOf(componentTags.map { t: Tag -> t.name }) }
    val imagePickerLauncher = rememberImageCropPicker { editImageUri = it }

    // الحجم الخفي للقطعة الاعتيادية لا يُعرض كحجم: القطعة تبقى اعتيادية دون أحجام
    val hiddenBase = variants.singleOrNull()?.takeIf { it.isHiddenBase(component.name) }
    val shownVariants = if (hiddenBase != null) emptyList() else variants
    val isPlain = shownVariants.isEmpty()
    val plainStock = hiddenBase?.stockCount ?: component.stockCount
    val plainPurchase = hiddenBase?.defaultPurchasePrice ?: component.purchasePrice
    val plainSell = hiddenBase?.defaultSellPrice ?: component.sellPrice

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.95f), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                    if (component.imageUri.isNotEmpty()) AsyncImage(model = component.imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Image(painter = painterResource(id = R.drawable.clover_placeholder), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(component.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (isPlain) {
                        Text("المتوفر بالمخزن: $plainStock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    if (TagsEnabled && componentTags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (t in componentTags) {
                                TagPill(
                                    name = t.name,
                                    color = tagColorFor(t.colorIndex),
                                    onClick = { onManageTag(t) },
                                    onLongClick = { onManageTag(t) }
                                )
                            }
                        }
                    }
                }
                if (isPlain) {
                    StepButton(
                        onClick = {
                            if (hiddenBase != null) vm.adjustVariantStock(hiddenBase.id, -1)
                            else vm.adjustPlainComponentStock(component.id, -1)
                        },
                        icon = Icons.Default.Remove,
                        contentDescription = "إنقاص الكمية",
                        containerColor = BrandYellow,
                        size = 34.dp
                    )
                    Spacer(Modifier.width(4.dp))
                    StepButton(
                        onClick = {
                            if (hiddenBase != null) vm.adjustVariantStock(hiddenBase.id, 1)
                            else vm.adjustPlainComponentStock(component.id, 1)
                        },
                        icon = Icons.Default.Add,
                        contentDescription = "زيادة الكمية",
                        containerColor = SellGreen,
                        size = 34.dp
                    )
                    Spacer(Modifier.width(4.dp))
                }
                TintedCircleButton(
                    onClick = { showEditComponent = true },
                    icon = Icons.Default.Edit,
                    contentDescription = "تعديل",
                    tint = BrandYellow,
                    size = 38.dp,
                    iconSize = 18.dp
                )
            }
            Spacer(Modifier.height(12.dp)); Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val variantCountLocal = shownVariants.size
                val variantStockLocal = if (isPlain) plainStock else shownVariants.sumOf { it.stockCount }
                if (shownVariants.size > 1) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$variantCountLocal", style = MaterialTheme.typography.headlineSmall, color = SellGreen, fontWeight = FontWeight.Bold); Text("الأحجام", style = MaterialTheme.typography.bodySmall) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$variantStockLocal", style = MaterialTheme.typography.headlineSmall, color = SellGreen, fontWeight = FontWeight.Bold); Text("إجمالي الكمية", style = MaterialTheme.typography.bodySmall) }
                } else {
                    // قطعة بحجم أساسي واحد تُعرض كقطعة اعتيادية: لا حجم ثانوي يُذكر
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$variantStockLocal", style = MaterialTheme.typography.headlineSmall, color = SellGreen, fontWeight = FontWeight.Bold); Text("المتوفر بالمخزن", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Divider()
            BrandButton(
                text = "إضافة حجم",
                icon = Icons.Default.Add,
                containerColor = BrandYellow,
                contentColor = OnBrandYellow,
                onClick = { showAddVariant = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items = shownVariants, key = { v: ComponentVariant -> v.id }) { v: ComponentVariant ->
                    val itemOffers: MutableList<SupplierOffer> = mutableListOf()
                    for (o in allOffers) { if (o.variantId == v.id) itemOffers.add(o) }
                    VariantDetailCard(
                        variant = v,
                        offers = itemOffers,
                        suppliers = suppliers,
                        settings = settings,
                        pricesHidden = pricesHidden,
                        vm = vm,
                        canDelete = !v.isBase || shownVariants.size == 1,
                        showBaseBadge = shownVariants.size > 1,
                        modifier = Modifier.animateItem()
                    )
                }
                if (isPlain) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("قطعة اعتيادية بدون أحجام", fontWeight = FontWeight.Bold)
                            if (!pricesHidden) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("شراء: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    Text(formatCurrency(plainPurchase).withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = BuyAmber, fontWeight = FontWeight.Bold)
                                    Text(" | بيع: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    Text(formatCurrency(plainSell).withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = SellGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "استخدم زرّي + و − في الأعلى لتعديل الكمية، أو أضف حجماً لاستخدام القطعة في القوالب والمزودين والبوردات.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("احذف المادة بالكامل") }
        }
    }
    if (showDeleteConfirm) { AlertDialog(onDismissRequest = { showDeleteConfirm = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("تأكيد الحذف") }, text = { Text("راح تنحذف المادة وكل قياساتها، متأكد؟") }, confirmButton = { Button(onClick = {
        showDeleteConfirm = false
        componentDeletion.attempt { confirmed, result -> vm.deleteComponent(component, confirmed, result) }
    }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed)) { Text("حذف") } }, dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("إلغاء") } }) }
    StockDeleteConfirmDialog(componentDeletion)
    if (showEditComponent) {
        val singleVariant = shownVariants.singleOrNull() ?: hiddenBase
        var name by remember { mutableStateOf(component.name) }
        var purchase by remember { mutableStateOf((singleVariant?.defaultPurchasePrice ?: component.purchasePrice).cleanString()) }
        var sell by remember { mutableStateOf((singleVariant?.defaultSellPrice ?: component.sellPrice).cleanString()) }
        var stock by remember { mutableStateOf((singleVariant?.stockCount ?: component.stockCount).toString()) }
        var editError by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEditComponent = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("تعديل القطعة", fontWeight = FontWeight.Bold) },
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
                            Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            if (editImageUri.isNotEmpty()) AsyncImage(model = editImageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Default.AddAPhoto, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("تغيير الصورة", fontWeight = FontWeight.Medium)
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { s: String -> name = s },
                        label = { Text("الاسم") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    if (shownVariants.size <= 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = purchase,
                                onValueChange = { s: String -> purchase = s },
                                label = { Text("سعر الشراء") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = sell,
                                onValueChange = { s: String -> sell = s },
                                label = { Text("سعر البيع") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        OutlinedTextField(
                            value = stock,
                            onValueChange = { s: String -> stock = s },
                            label = { Text("العدد المتوفر بالمخزن") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                    } else {
                        Text(
                            "هذه القطعة تحتوي على عدة أحجام — عدّل سعر ومخزون كل حجم من زر التعديل بجانبه.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (TagsEnabled) TagEditor(
                        selectedNames = editTagNames,
                        allTags = sheetTags,
                        onAdd = { n: String -> editTagNames = editTagNames + n },
                        onRemove = { n: String -> editTagNames = editTagNames.filter { s: String -> !s.equals(n, ignoreCase = true) } }
                    )
                    if (editError.isNotEmpty()) Text(editError, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                BrandButton(
                    text = "حفظ",
                    enabled = name.isNotBlank(),
                    onClick = {
                        val newName = name.trim()
                        editError = ""
                        if (shownVariants.size > 1) {
                            vm.updateComponent(
                                component.copy(name = newName, imageUri = editImageUri),
                                onRejected = { editError = it },
                                onDone = {
                                    if (TagsEnabled) vm.setComponentTags(component.id, editTagNames)
                                    showEditComponent = false
                                }
                            )
                        } else {
                            val newPurchase = (purchase.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                            val newSell = (sell.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                            val newStock = (stock.toIntOrNull() ?: 0).coerceAtLeast(0)
                            vm.updateComponent(
                                component.copy(
                                    name = newName,
                                    imageUri = editImageUri,
                                    stockCount = if (singleVariant == null) newStock else component.stockCount,
                                    purchasePrice = newPurchase,
                                    sellPrice = newSell
                                ),
                                onRejected = { editError = it },
                                onDone = {
                                    // الحجم الخفي للقطعة الاعتيادية يحمل اسم القطعة فيتبعه؛ أما الحجم الافتراضي فاسمه مستقل
                                    if (singleVariant != null) {
                                        // لا يُغلق الحوار قبل قبول الحجم حتى لا يضيع خطأ الرفض
                                        vm.updateVariant(
                                            singleVariant.copy(
                                                specName = if (namesEqual(singleVariant.specName, component.name)) newName else singleVariant.specName,
                                                defaultPurchasePrice = newPurchase,
                                                defaultSellPrice = newSell,
                                                stockCount = newStock
                                            ),
                                            onRejected = { editError = it },
                                            onDone = {
                                                if (TagsEnabled) vm.setComponentTags(component.id, editTagNames)
                                                showEditComponent = false
                                            },
                                            silent = true
                                        )
                                    } else {
                                        if (TagsEnabled) vm.setComponentTags(component.id, editTagNames)
                                        showEditComponent = false
                                    }
                                }
                            )
                        }
                    }
                )
            }
        )
    }
    if (showAddVariant) {
        var spec by remember { mutableStateOf("") }
        var sell by remember { mutableStateOf("") }
        var purchase by remember { mutableStateOf("") }
        var stock by remember { mutableStateOf("") }
        var variantError by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddVariant = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("حجم جديد", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isPlain) {
                        Text(
                            if (hiddenBase != null) "المخزون الحالي سيُنقل إلى حجم افتراضي باسم «${DEFAULT_VARIANT_NAME}»، ثم يُضاف حجمك ثانياً."
                            else "سيُنشأ أولاً حجم افتراضي باسم «${DEFAULT_VARIANT_NAME}» يحمل بيانات القطعة الحالية، ثم يُضاف هذا الحجم ثانياً.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(value = spec, onValueChange = { s: String -> spec = s }, label = { Text("الاسم (مثلاً 16A)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = purchase, onValueChange = { s: String -> purchase = s }, label = { Text("سعر الشراء الافتراضي") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = sell, onValueChange = { s: String -> sell = s }, label = { Text("سعر البيع") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = stock, onValueChange = { s: String -> stock = s }, label = { Text("العدد المتوفر بالمخزن") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    if (variantError.isNotEmpty()) Text(variantError, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                BrandButton(
                    text = "حفظ",
                    enabled = spec.isNotBlank(),
                    onClick = {
                        vm.addVariant(
                            ComponentVariant(
                                componentId = component.id,
                                specName = spec.trim(),
                                defaultPurchasePrice = (purchase.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                                defaultSellPrice = (sell.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                                stockCount = (stock.toIntOrNull() ?: 0).coerceAtLeast(0)
                            ),
                            onRejected = { variantError = it },
                            onDone = { showAddVariant = false }
                        )
                    }
                )
            },
            dismissButton = { TextButton(onClick = { showAddVariant = false }, shape = CircleShape) { Text("إلغاء") } }
        )
    }
}

@Composable
fun VariantDetailCard(
    variant: ComponentVariant,
    offers: List<SupplierOffer>,
    suppliers: List<Supplier>,
    settings: AppSettings?,
    pricesHidden: Boolean = false,
    vm: MainViewModel,
    canDelete: Boolean,
    showBaseBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var editingVariant by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val variantDeletion = rememberStockDeleteHandler("تم حذف الحجم")
    val deleteVariant = {
        variantDeletion.attempt { confirmed, result -> vm.deleteVariant(variant, confirmed, result) }
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (variant.specName.isNotEmpty()) variant.specName else DEFAULT_VARIANT_NAME, fontWeight = FontWeight.Bold)
                        if (variant.isBase && showBaseBadge) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BrandYellow.copy(alpha = 0.22f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("افتراضي", style = MaterialTheme.typography.labelSmall, color = OnBrandYellow, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (!pricesHidden) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("شراء: ", style = MaterialTheme.typography.bodySmall)
                            Text(formatCurrency(variant.defaultPurchasePrice).withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = BuyAmber, fontWeight = FontWeight.Bold)
                            Text(" | بيع: ", style = MaterialTheme.typography.bodySmall)
                            Text(formatCurrency(variant.defaultSellPrice).withThousandsDot(), style = MaterialTheme.typography.bodySmall, color = SellGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                StockBadge(
                    count = variant.stockCount,
                    color = stockColorFor(variant.stockCount, settings),
                    size = 32.dp,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(4.dp))
                StepButton(
                    onClick = { vm.adjustVariantStock(variant.id, -1) },
                    icon = Icons.Default.Remove,
                    contentDescription = "إنقاص الكمية",
                    containerColor = BrandYellow
                )
                Spacer(Modifier.width(4.dp))
                StepButton(
                    onClick = { vm.adjustVariantStock(variant.id, 1) },
                    icon = Icons.Default.Add,
                    contentDescription = "زيادة الكمية",
                    containerColor = SellGreen
                )
                Spacer(Modifier.width(4.dp))
                TintedCircleButton(
                    onClick = { editingVariant = true },
                    icon = Icons.Default.Edit,
                    contentDescription = "تعديل",
                    tint = BrandYellow,
                    size = 32.dp,
                    iconSize = 16.dp
                )
                Spacer(Modifier.width(4.dp))
                if (canDelete) {
                    TintedCircleButton(
                        onClick = {
                            if (variant.isBase || variant.stockCount > 0) showDeleteConfirm = true else deleteVariant()
                        },
                        icon = Icons.Default.Delete,
                        contentDescription = "حذف",
                        tint = DangerRed,
                        size = 32.dp,
                        iconSize = 16.dp
                    )
                }
            }
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("تأكيد حذف الحجم") },
            text = {
                Text(
                    if (variant.isBase) "سيتم حذف الحجم الافتراضي وإرجاع بياناته إلى القطعة لتصبح قطعة اعتيادية بدون أحجام، ويختفي اسم الحجم. متأكد؟"
                    else "سيتم حذف الحجم ومخزونه (كمية ${variant.stockCount}). متأكد؟"
                )
            },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; deleteVariant() },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = CircleShape
                ) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("إلغاء") } }
        )
    }
    StockDeleteConfirmDialog(variantDeletion)
    if (editingVariant) {
        var spec by remember { mutableStateOf(variant.specName) }; var purchase by remember { mutableStateOf(variant.defaultPurchasePrice.cleanString()) }; var sell by remember { mutableStateOf(variant.defaultSellPrice.cleanString()) }; var stock by remember { mutableStateOf(variant.stockCount.toString()) }; var rowError by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { editingVariant = false }, shape = DialogShape, containerColor = MaterialTheme.colorScheme.surface, title = { Text(if (variant.isBase) "تعديل الحجم الافتراضي" else "تعديل الحجم") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = spec, onValueChange = { s: String -> spec = s }, label = { Text("الاسم") }); OutlinedTextField(value = purchase, onValueChange = { s: String -> purchase = s }, label = { Text("سعر الشراء") }); OutlinedTextField(value = sell, onValueChange = { s: String -> sell = s }, label = { Text("سعر البيع") }); OutlinedTextField(value = stock, onValueChange = { s: String -> stock = s }, label = { Text("العدد المتوفر بالمخزن") }); if (rowError.isNotEmpty()) Text(rowError, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) } },
            confirmButton = { BrandButton(text = "حفظ", onClick = {
                rowError = ""
                vm.updateVariant(
                    variant.copy(specName = spec, defaultPurchasePrice = purchase.toDoubleOrNull() ?: 0.0, defaultSellPrice = sell.toDoubleOrNull() ?: 0.0, stockCount = stock.toIntOrNull() ?: 0),
                    onRejected = { rowError = it },
                    onDone = { editingVariant = false }
                )
            }) })
    }
}
