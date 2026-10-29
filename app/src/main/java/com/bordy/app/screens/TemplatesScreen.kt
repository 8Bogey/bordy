package com.bordy.app.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.bordy.app.R
import com.bordy.app.data.*
import com.bordy.app.ui.animations.bouncyClickable
import com.bordy.app.ui.components.DialogShape
import com.bordy.app.ui.components.EmptyState
import com.bordy.app.ui.components.PieceGridTile
import com.bordy.app.ui.components.PieceCountBadge
import com.bordy.app.ui.components.TemplateListCard
import com.bordy.app.ui.components.BrandButton
import com.bordy.app.ui.components.BrandFab
import com.bordy.app.ui.components.TintedCircleButton
import com.bordy.app.ui.components.StepButton
import com.bordy.app.ui.components.matchesTagSearch
import com.bordy.app.ui.components.rememberImageCropPicker
import com.bordy.app.utils.*
import com.bordy.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TemplatesScreen(vm: MainViewModel, pricesHidden: Boolean = false) {
    val templates by vm.templates.collectAsState()
    val allTemplateItems by vm.templateItems.collectAsState()
    val allVariants by vm.variants.collectAsState()
    val context = LocalContext.current
    var showAddTemplateDialog by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<Template?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Template?>(null) }
    var newImageUri by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberImageCropPicker { newImageUri = it }

    Scaffold(
        floatingActionButton = {
            BrandFab(
                onClick = { showAddTemplateDialog = true; newImageUri = "" },
                icon = Icons.Default.Add,
                contentDescription = "قالب جديد",
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (templates.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(icon = Icons.Default.BookmarkBorder, message = "ما عندك قوالب محفوظة هسّه.. أضف أول قالب! 📋")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(templates, key = { it.id }) { temp ->
                        val itemsOfTemplate = allTemplateItems.filter { it.templateId == temp.id }
                        val summary = templateSummary(temp, itemsOfTemplate, allVariants)
                        Row(
                            modifier = Modifier.fillMaxWidth().animateItem(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TemplateListCard(
                                template = temp,
                                summary = summary,
                                pricesHidden = pricesHidden,
                                onClick = { selectedTemplate = temp },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            TintedCircleButton(
                                onClick = { showDeleteConfirm = temp },
                                icon = Icons.Default.Delete,
                                contentDescription = "حذف",
                                tint = DangerRed,
                                size = 38.dp,
                                iconSize = 20.dp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddTemplateDialog) {
        var name by remember { mutableStateOf("") }; var w by remember { mutableStateOf("") }; var h by remember { mutableStateOf("") }; var d by remember { mutableStateOf("") }
        var labor by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTemplateDialog = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("قالب جديد +", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            if (newImageUri.isNotEmpty()) {
                                AsyncImage(
                                    model = newImageUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.AddAPhoto, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("صورة القالب (اختياري)", fontWeight = FontWeight.Medium)
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("اسم القالب") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = w,
                            onValueChange = { w = it },
                            label = { Text("العرض") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = h,
                            onValueChange = { h = it },
                            label = { Text("الارتفاع") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = d,
                            onValueChange = { d = it },
                            label = { Text("العمق") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                    OutlinedTextField(
                        value = labor,
                        onValueChange = { labor = it },
                        label = { Text("شغل اليد (اختياري)") },
                        leadingIcon = { Icon(Icons.Default.Payments, null, tint = BrandYellow) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            },
            confirmButton = {
                BrandButton(
                    text = "حفظ",
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.addTemplate(
                            Template(
                                name = name.trim(),
                                imageUri = newImageUri,
                                boxWidth = w.toDoubleOrNull() ?: 0.0,
                                boxHeight = h.toDoubleOrNull() ?: 0.0,
                                boxDepth = d.toDoubleOrNull() ?: 0.0,
                                laborCost = (labor.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                            )
                        )
                        showAddTemplateDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddTemplateDialog = false }, shape = CircleShape) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("تأكيد الحذف", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = { Text("متأكد تريد تحذف قالب '${showDeleteConfirm!!.name}'؟") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteTemplate(showDeleteConfirm!!)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = CircleShape
                ) {
                    Text("حذف", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }, shape = CircleShape) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (selectedTemplate != null) {
        TemplateDetailSheet(
            selectedTemplate!!,
            vm,
            pricesHidden = pricesHidden,
            onDismiss = { selectedTemplate = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TemplateDetailSheet(
    template: Template,
    vm: MainViewModel,
    pricesHidden: Boolean = false,
    onDismiss: () -> Unit
) {
    val tempItems by vm.getTemplateItems(template.id).collectAsState(initial = emptyList())
    val allComponents by vm.components.collectAsState()
    val allVariants by vm.variants.collectAsState()
    val allTemplates by vm.templates.collectAsState()
    val settings by vm.settings.collectAsState()
    val currentTemplate = allTemplates.find { it.id == template.id } ?: template
    val summary = remember(currentTemplate, tempItems, allVariants) {
        templateSummary(currentTemplate, tempItems, allVariants)
    }

    val context = LocalContext.current
    var showAddItemSheet by remember { mutableStateOf(false) }
    var showEditTemplate by remember { mutableStateOf(false) }
    var editImageUri by remember(currentTemplate.imageUri) { mutableStateOf(currentTemplate.imageUri) }
    val templateImagePicker = rememberImageCropPicker { editImageUri = it }

    var localItems by remember { mutableStateOf<List<TemplateItem>>(emptyList()) }
    LaunchedEffect(tempItems) { localItems = tempItems }
    var resolvingItem by remember { mutableStateOf<TemplateItem?>(null) }

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // قياس الارتفاع الفعلي للعنصر
    var measuredItemHeight by remember { mutableStateOf(0) }
    val spacingPx = with(density) { 6.dp.toPx() }
    val slotHeightPx = if (measuredItemHeight > 0) measuredItemHeight + spacingPx else with(density) { 70.dp.toPx() }

    var draggedItemId by remember { mutableStateOf<Long?>(null) }
    var draggedItemInitialIndex by remember { mutableStateOf(-1) }
    var accumulatedDrag by remember { mutableStateOf(0f) }

    var isListView by remember { mutableStateOf(true) }
    var gridActionTItem by remember { mutableStateOf<TemplateItem?>(null) }
    var gridCellPitch by remember { mutableStateOf(IntSize.Zero) }
    var gridDragOffset by remember { mutableStateOf(Offset.Zero) }
    val gridSpacingPx = with(density) { 6.dp.toPx() }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.95f), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentTemplate.imageUri.isNotEmpty()) {
                        AsyncImage(
                            model = currentTemplate.imageUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.Image, null, tint = SellGreen, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(currentTemplate.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(BrandYellow)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("الأبعاد: ${currentTemplate.boxWidth.cleanString()} × ${currentTemplate.boxHeight.cleanString()} × ${currentTemplate.boxDepth.cleanString()} سم", style = MaterialTheme.typography.bodySmall, color = OnBrandYellow, fontWeight = FontWeight.Bold)
                    }
                }
                TintedCircleButton(
                    onClick = { isListView = !isListView },
                    icon = if (isListView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = if (isListView) "عرض شبكة" else "عرض قائمة",
                    tint = SellGreen,
                    size = 40.dp,
                    iconSize = 20.dp
                )
                Spacer(Modifier.width(6.dp))
                TintedCircleButton(
                    onClick = { editImageUri = currentTemplate.imageUri; showEditTemplate = true },
                    icon = Icons.Default.Edit,
                    contentDescription = "تعديل القالب",
                    tint = BrandYellow,
                    size = 40.dp,
                    iconSize = 18.dp
                )
                Spacer(Modifier.width(6.dp))
                TintedCircleButton(
                    onClick = { showAddItemSheet = true },
                    icon = Icons.Default.Add,
                    contentDescription = "إضافة قطعة",
                    tint = SellGreen,
                    size = 42.dp,
                    iconSize = 22.dp
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(BrandYellow.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PieceCountBadge(count = summary.itemCount, size = 22)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "قطعة · الكمية ${summary.totalQuantity}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = OnBrandYellow
                    )
                }
                if (!pricesHidden) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(SellGreen.copy(alpha = 0.13f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "إجمالي التكلفة ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                        Text(
                            formatCurrency(summary.totalCost).withThousandsDot(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = SellGreen
                        )
                        if (summary.laborCost > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "(أجور ${formatCurrency(summary.laborCost)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            if (isListView) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(localItems, key = { it.id }) { tItem ->
                    val isDragging = draggedItemId == tItem.id
                    val currentIndex = localItems.indexOfFirst { it.id == tItem.id }
                    val v = allVariants.find { it.id == tItem.variantId }
                    val c = allComponents.find { it.id == v?.componentId }

                    // ✅ المعادلة الجديدة: الإزاحة المرئية = التراكمية - (فرق المواقع × ارتفاع الخانة)
                    val indexDelta = if (draggedItemInitialIndex >= 0) currentIndex - draggedItemInitialIndex else 0
                    val displayTranslationY = if (isDragging) {
                        accumulatedDrag - (indexDelta * slotHeightPx)
                    } else 0f

                    val visualModifier = if (isDragging) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer {
                                translationY = displayTranslationY
                                scaleX = 1.03f
                                scaleY = 1.03f
                                shadowElevation = 16f
                                alpha = 0.96f
                            }
                    } else {
                        Modifier
                            .zIndex(0f)
                            .animateItem(
                                placementSpec = spring(
                                    stiffness = Spring.StiffnessMediumLow,
                                    dampingRatio = Spring.DampingRatioNoBouncy
                                )
                            )
                    }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, if (isOrphanTemplateItem(tItem)) DangerRed.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                if (measuredItemHeight == 0 && coords.size.height > 0) {
                                    measuredItemHeight = coords.size.height
                                }
                            }
                            .then(visualModifier)
                            .pointerInput(tItem.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemId = tItem.id
                                        draggedItemInitialIndex = localItems.indexOfFirst { it.id == tItem.id }
                                        accumulatedDrag = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        accumulatedDrag += dragAmount.y

                                        // محاولة التبديل (مع حماية من الحلقة اللانهائية)
                                        var swapped = false
                                        var safety = 0
                                        while (safety++ < 10) {
                                            val idx = localItems.indexOfFirst { it.id == tItem.id }
                                            if (idx < 0) break

                                            val visualOffset = accumulatedDrag - ((idx - draggedItemInitialIndex) * slotHeightPx)

                                            if (visualOffset > slotHeightPx * 0.5f && idx < localItems.size - 1) {
                                                val newList = localItems.toMutableList()
                                                val moving = newList.removeAt(idx)
                                                newList.add(idx + 1, moving)
                                                localItems = newList
                                                swapped = true
                                            } else if (visualOffset < -slotHeightPx * 0.5f && idx > 0) {
                                                val newList = localItems.toMutableList()
                                                val moving = newList.removeAt(idx)
                                                newList.add(idx - 1, moving)
                                                localItems = newList
                                                swapped = true
                                            } else {
                                                break
                                            }
                                        }
                                        if (swapped) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onDragEnd = {
                                        val finalIdx = localItems.indexOfFirst { it.id == tItem.id }
                                        val finalIndexDelta = finalIdx - draggedItemInitialIndex
                                        val targetAccumulated = finalIndexDelta * slotHeightPx

                                        scope.launch {
                                            animate(
                                                initialValue = accumulatedDrag,
                                                targetValue = targetAccumulated,
                                                animationSpec = spring(
                                                    stiffness = Spring.StiffnessMediumLow,
                                                    dampingRatio = Spring.DampingRatioNoBouncy
                                                )
                                            ) { value, _ -> accumulatedDrag = value }

                                            draggedItemId = null
                                            accumulatedDrag = 0f
                                            draggedItemInitialIndex = -1
                                            vm.reorderTemplateItems(localItems)
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch {
                                            animate(
                                                initialValue = accumulatedDrag,
                                                targetValue = 0f
                                            ) { value, _ -> accumulatedDrag = value }
                                            draggedItemId = null
                                            accumulatedDrag = 0f
                                            draggedItemInitialIndex = -1
                                            localItems = tempItems
                                        }
                                    }
                                )
                            }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "اسحب",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDragging) 0.8f else 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (c?.imageUri?.isNotEmpty() == true) AsyncImage(model = c.imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Image(painter = painterResource(id = R.drawable.clover_placeholder), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).clickable { gridActionTItem = tItem }) {
                                Text(templateItemTitle(tItem, c?.name, v), fontWeight = FontWeight.Bold,
                                    color = if (isOrphanTemplateItem(tItem)) DangerRed else MaterialTheme.colorScheme.onSurface)
                                Text("الكمية: ${tItem.quantity}", style = MaterialTheme.typography.bodySmall)
                                if (isOrphanTemplateItem(tItem)) {
                                    Text("محذوفة من المخزون", style = MaterialTheme.typography.labelSmall, color = DangerRed, fontWeight = FontWeight.Bold)
                                } else if (tItem.isManual) {
                                    Text("قطعة يدوية", style = MaterialTheme.typography.labelSmall, color = SellGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                            StepButton(
                                onClick = { vm.changeTemplateItemQuantity(tItem.id, -1) },
                                icon = Icons.Default.Remove,
                                contentDescription = "إنقاص الكمية",
                                containerColor = BrandYellow,
                                size = 30.dp,
                                iconSize = 15.dp,
                                enabled = tItem.quantity > 1
                            )
                            Spacer(Modifier.width(4.dp))
                            StepButton(
                                onClick = { vm.changeTemplateItemQuantity(tItem.id, +1) },
                                icon = Icons.Default.Add,
                                contentDescription = "زيادة الكمية",
                                containerColor = SellGreen,
                                size = 30.dp,
                                iconSize = 15.dp
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { vm.deleteTemplateItem(tItem) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "حذف", tint = DangerRed, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            } else {
            if (localItems.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Inventory,
                    message = "القالب فاضي بعده.. أضف أول قطعة! 📋",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    gridItems(items = localItems, key = { it.id }) { tItem ->
                        val v = allVariants.find { it.id == tItem.variantId }
                        val c = allComponents.find { it.id == v?.componentId }
                        val isDragging = draggedItemId == tItem.id
                        val currentIndex = localItems.indexOfFirst { it.id == tItem.id }
                        val currentRow = if (currentIndex >= 0) currentIndex / 5 else 0
                        val currentCol = if (currentIndex >= 0) currentIndex % 5 else 0
                        val startRow = if (draggedItemInitialIndex >= 0) draggedItemInitialIndex / 5 else 0
                        val startCol = if (draggedItemInitialIndex >= 0) draggedItemInitialIndex % 5 else 0
                        val cellWidth = gridCellPitch.width.toFloat().coerceAtLeast(1f)
                        val cellHeight = gridCellPitch.height.toFloat().coerceAtLeast(1f)
                        // في RTL يزيد رقم الخانة نحو اليسار، لذا تُعكس إزاحة الأعمدة
                        val dragTranslationX = if (isDragging) gridDragOffset.x + (currentCol - startCol) * cellWidth else 0f
                        val dragTranslationY = if (isDragging) gridDragOffset.y - (currentRow - startRow) * cellHeight else 0f

                        val dragModifier = Modifier.pointerInput(tItem.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedItemId = tItem.id
                                    draggedItemInitialIndex = localItems.indexOfFirst { it.id == tItem.id }
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
                                        val current = localItems.indexOfFirst { it.id == tItem.id }
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
                                    vm.reorderTemplateItems(localItems)
                                },
                                onDragCancel = {
                                    draggedItemId = null
                                    gridDragOffset = Offset.Zero
                                    draggedItemInitialIndex = -1
                                    localItems = tempItems
                                }
                            )
                        }

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
                                imageUri = c?.imageUri ?: "",
                                title = templateItemTitle(tItem, c?.name, v),
                                titleColor = if (isOrphanTemplateItem(tItem)) DangerRed else MaterialTheme.colorScheme.onSurface,
                                borderColor = if (isOrphanTemplateItem(tItem)) DangerRed else null,
                                fallbackIcon = when {
                                    isOrphanTemplateItem(tItem) -> Icons.Default.Warning
                                    tItem.isManual -> Icons.Default.Edit
                                    else -> null
                                },
                                fallbackIconTint = if (tItem.isManual && !isOrphanTemplateItem(tItem)) SellGreen else DangerRed,
                                modifier = Modifier.clickable { gridActionTItem = tItem },
                                below = {
                                    // دائرة واحدة: رقم كمية القالب (يتغير بـ +/−) ولونه تدرّج المخزون
                                    val badgeStock = if (tItem.isManual) null else v?.stockCount ?: c?.stockCount
                                    Box(Modifier.padding(top = 4.dp).height(22.dp), contentAlignment = Alignment.Center) {
                                        StockBadge(
                                            count = tItem.quantity,
                                            color = if (badgeStock != null) stockColorFor(badgeStock, settings) else BrandYellow
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
            }
        }
    }

    gridActionTItem?.let { action ->
        val item = tempItems.find { it.id == action.id } ?: action
        val v = allVariants.find { it.id == item.variantId }
        val c = allComponents.find { it.id == v?.componentId }
        val orphan = isOrphanTemplateItem(item)
        val stock = if (orphan || item.isManual) null else v?.stockCount ?: c?.stockCount
        AlertDialog(
            onDismissRequest = { gridActionTItem = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(templateItemTitle(item, c?.name, v), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (orphan) {
                        Text(
                            "حُذفت هذه القطعة من المخزون — حوّلها إلى قطعة يدوية ليظل القالب يطبّقها، أو احذف البطاقة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("الكمية", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.width(12.dp))
                        StepButton(
                            onClick = { vm.changeTemplateItemQuantity(item.id, -1) },
                            icon = Icons.Default.Remove,
                            contentDescription = "إنقاص الكمية",
                            containerColor = BrandYellow,
                            size = 32.dp,
                            iconSize = 16.dp,
                            enabled = item.quantity > 1
                        )
                        Text(
                            "${item.quantity}",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 30.dp).padding(horizontal = 8.dp)
                        )
                        StepButton(
                            onClick = { vm.changeTemplateItemQuantity(item.id, +1) },
                            icon = Icons.Default.Add,
                            contentDescription = "زيادة الكمية",
                            containerColor = SellGreen,
                            size = 32.dp,
                            iconSize = 16.dp
                        )
                    }
                    if (stock != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("المخزون المتاح", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Spacer(Modifier.width(12.dp))
                            StockBadge(count = stock, color = stockColorFor(stock, settings))
                        }
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (orphan || item.isManual) {
                        TextButton(onClick = {
                            resolvingItem = item
                            gridActionTItem = null
                        }) {
                            Text(if (item.isManual) "تعديل القطعة" else "تحويل إلى قطعة يدوية", color = SellGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = {
                        vm.deleteTemplateItem(item)
                        gridActionTItem = null
                    }) {
                        Text("حذف", color = DangerRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }

    resolvingItem?.let { item ->
        TemplateManualItemDialog(
            title = if (item.isManual) "تعديل قطعة يدوية" else "تحويل البطاقة إلى قطعة يدوية",
            hint = "بقيت هذه البطاقة بلا حجم في المخزون — أسعارها تُستخدم عند تطبيق القالب، والكمية لا تتغير هنا.",
            initialName = item.manualName,
            initialPurchase = item.purchasePrice,
            initialSell = item.sellPrice,
            initialQuantity = null,
            onDismiss = { resolvingItem = null },
            onSubmit = { name, purchase, sell, _ ->
                var error = ""
                vm.resolveTemplateItemConflict(
                    item = item,
                    name = name,
                    purchasePrice = purchase,
                    sellPrice = sell,
                    onRejected = { error = it },
                    onDone = { if (error.isEmpty()) resolvingItem = null }
                )
                error
            }
        )
    }

    if (showAddItemSheet) {
        AddItemToTemplateSheet(
            vm = vm,
            components = allComponents,
            variants = allVariants,
            settings = settings,
            templateId = template.id,
            pricesHidden = pricesHidden,
            onDismiss = { showAddItemSheet = false }
        )
    }

    if (showEditTemplate) {
        var editName by remember { mutableStateOf(currentTemplate.name) }
        var editW by remember { mutableStateOf(currentTemplate.boxWidth.cleanString()) }
        var editH by remember { mutableStateOf(currentTemplate.boxHeight.cleanString()) }
        var editD by remember { mutableStateOf(currentTemplate.boxDepth.cleanString()) }
        var editLabor by remember {
            mutableStateOf(if (currentTemplate.laborCost > 0) currentTemplate.laborCost.cleanString() else "")
        }
        AlertDialog(
            onDismissRequest = { showEditTemplate = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("تعديل القالب", fontWeight = FontWeight.Bold) },
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
                            .clickable { templateImagePicker.launch(arrayOf("image/*")) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            if (editImageUri.isNotEmpty()) {
                                AsyncImage(
                                    model = editImageUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.AddAPhoto, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("صورة القالب", fontWeight = FontWeight.Medium)
                            Text("اضغط لاختيار صورة جديدة أو استبدالها", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        if (editImageUri.isNotEmpty()) {
                            TextButton(onClick = { editImageUri = "" }) {
                                Text("إزالة", color = DangerRed, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("اسم القالب") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editW,
                            onValueChange = { editW = it },
                            label = { Text("العرض") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = editH,
                            onValueChange = { editH = it },
                            label = { Text("الارتفاع") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = editD,
                            onValueChange = { editD = it },
                            label = { Text("العمق") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                    OutlinedTextField(
                        value = editLabor,
                        onValueChange = { editLabor = it },
                        label = { Text("شغل اليد") },
                        supportingText = { Text("تنسحب ويا القالب وتضاف لشغل يد البورد، وتكدر تعدلها شوكت ما تحب") },
                        leadingIcon = { Icon(Icons.Default.Payments, null, tint = BrandYellow) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            },
            confirmButton = {
                BrandButton(
                    text = "حفظ",
                    enabled = editName.isNotBlank(),
                    onClick = {
                        vm.updateTemplate(
                            currentTemplate.copy(
                                name = editName.trim(),
                                imageUri = editImageUri,
                                boxWidth = editW.toDoubleOrNull() ?: 0.0,
                                boxHeight = editH.toDoubleOrNull() ?: 0.0,
                                boxDepth = editD.toDoubleOrNull() ?: 0.0,
                                laborCost = (editLabor.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                            )
                        )
                        showEditTemplate = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showEditTemplate = false }, shape = CircleShape) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemToTemplateSheet(
    vm: MainViewModel,
    components: List<Component>,
    variants: List<ComponentVariant>,
    settings: AppSettings?,
    templateId: Long,
    pricesHidden: Boolean = false,
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
    var variantPickComponent by remember { mutableStateOf<Component?>(null) }
    var showManualAdd by remember { mutableStateOf(false) }

    var sessionAddedTotal by remember { mutableStateOf(0) }
    var sessionAddedValue by remember { mutableStateOf(0.0) }

    fun registerAddedToTemplate(comp: Component, requestedQty: Int, unitSell: Double) {
        SoundHelper.playAddSuccess()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        lastAddedComponentId = comp.id
        lastAddedCount = requestedQty
        addEventCounter++
        sessionAddedTotal += requestedQty
        sessionAddedValue += unitSell * requestedQty
        scope.launch { snackbarHostState.showSnackbar("تمت إضافة ${comp.name} للقالب (×$requestedQty)", duration = SnackbarDuration.Short) }
    }

    fun addComponentToTemplate(comp: Component, variant: ComponentVariant) {
        vm.addTemplateItem(TemplateItem(templateId = templateId, variantId = variant.id, quantity = qtyMultiplier))
        registerAddedToTemplate(comp, qtyMultiplier, variant.defaultSellPrice)
    }

    fun addBaseComponentToTemplate(comp: Component) {
        vm.addBaseComponentToTemplate(templateId, comp.id, qtyMultiplier)
        registerAddedToTemplate(comp, qtyMultiplier, comp.sellPrice)
    }

    fun onComponentTap(comp: Component) {
        val compVariants = variants.filter { it.componentId == comp.id }
        when {
            compVariants.isEmpty() -> addBaseComponentToTemplate(comp)
            compVariants.size == 1 -> addComponentToTemplate(comp, compVariants[0])
            else -> {
                SoundHelper.playTap()
                variantPickComponent = comp
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.95f),
        dragHandle = null
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
                                Text("إضافة قطعة للقالب", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SellGreen, modifier = Modifier.weight(1f))
                                TintedCircleButton(
                                    onClick = { showManualAdd = true },
                                    icon = Icons.Default.Add,
                                    contentDescription = "إضافة قطعة يدوية",
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
                                Text("أُضيفت كمية $sessionAddedTotal للقالب", fontWeight = FontWeight.Bold, color = Color.White)
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
                                    TemplateFloatingPlusBadge(count = lastAddedCount)
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
                                    addComponentToTemplate(picked, v)
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
        TemplateManualItemDialog(
            title = "إضافة قطعة يدوية للقالب",
            hint = "لن تُضاف إلى المكتبة، تبقى داخل هذا القالب وتُطبّق في البورد كقطعة يدوية.",
            initialName = "",
            initialPurchase = 0.0,
            initialSell = 0.0,
            initialQuantity = qtyMultiplier,
            onDismiss = { showManualAdd = false },
            onSubmit = { name, purchase, sell, qty ->
                var error = ""
                vm.addManualTemplateItem(templateId, name, purchase, sell, qty, onRejected = { error = it })
                if (error.isEmpty()) {
                    SoundHelper.playAddSuccess()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sessionAddedTotal += qty
                    sessionAddedValue += sell * qty
                    scope.launch { snackbarHostState.showSnackbar("تمت إضافة القطعة اليدوية للقالب (×$qty)", duration = SnackbarDuration.Short) }
                    showManualAdd = false
                }
                error
            }
        )
    }
}

// عنوان بطاقة القالب: الاسم الحي من المخزون، أو الاسم المحفوظ داخل البطاقة اليدوية/المنفصلة
fun templateItemTitle(item: TemplateItem, componentName: String?, variant: ComponentVariant?): String {
    val live = !item.isManual && item.variantId != null
    return if (live) componentLabel(componentName, variant, "؟")
    else item.manualName.ifBlank { if (item.isManual) "قطعة يدوية" else "محذوفة من المخزون" }
}

// البطاقة التي كان لها حجم وفقدته: لا يدوية ولا مرتبطة بحجم موجود
fun isOrphanTemplateItem(item: TemplateItem): Boolean = !item.isManual && item.variantId == null

@Composable
private fun TemplateManualItemDialog(
    title: String,
    hint: String,
    initialName: String,
    initialPurchase: Double,
    initialSell: Double,
    initialQuantity: Int?,
    onDismiss: () -> Unit,
    onSubmit: (name: String, purchase: Double, sell: Double, qty: Int) -> String
) {
    var name by remember { mutableStateOf(initialName) }
    var purchase by remember { mutableStateOf(initialPurchase.cleanString()) }
    var sell by remember { mutableStateOf(initialSell.cleanString()) }
    var qty by remember { mutableStateOf(initialQuantity ?: 1) }
    var error by remember { mutableStateOf("") }
    val purchaseValue = purchase.toDoubleOrNull()
    val sellValue = sell.toDoubleOrNull()
    val isLoss = purchaseValue != null && sellValue != null && isSellBelowPurchase(sellValue, purchaseValue)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, fontWeight = FontWeight.Bold, color = BrandYellow) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = BrandYellow)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم القطعة") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = purchase, onValueChange = { purchase = it }, label = { Text("سعر الشراء") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = sell, onValueChange = { sell = it }, label = { Text("سعر البيع") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                if (initialQuantity != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Text("الكمية: ")
                        StepButton(
                            onClick = { if (qty > 1) qty-- },
                            icon = Icons.Default.Remove,
                            contentDescription = "إنقاص الكمية",
                            containerColor = BrandYellow,
                            size = 32.dp,
                            iconSize = 16.dp,
                            enabled = qty > 1
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("$qty", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 24.dp))
                        Spacer(Modifier.width(10.dp))
                        StepButton(
                            onClick = { qty++ },
                            icon = Icons.Default.Add,
                            contentDescription = "زيادة الكمية",
                            containerColor = SellGreen,
                            size = 32.dp,
                            iconSize = 16.dp
                        )
                    }
                }
                if (isLoss) Text(sellBelowPurchaseMessage(sellValue!!, purchaseValue!!), color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                if (error.isNotEmpty()) Text(error, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            BrandButton(
                text = "حفظ",
                enabled = name.isNotBlank() && !isLoss,
                onClick = {
                    val rejected = onSubmit(name.trim(), purchaseValue ?: 0.0, sellValue ?: 0.0, qty)
                    error = rejected
                }
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun TemplateFloatingPlusBadge(count: Int) {
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
