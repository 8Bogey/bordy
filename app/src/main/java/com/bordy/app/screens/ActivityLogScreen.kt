package com.bordy.app.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.data.ActivityLog
import com.bordy.app.data.AppSettings
import com.bordy.app.data.LogAction
import com.bordy.app.data.LogCategory
import com.bordy.app.ui.components.BrandButton
import com.bordy.app.ui.components.CardShape
import com.bordy.app.ui.components.DialogShape
import com.bordy.app.ui.components.EmptyState
import com.bordy.app.ui.components.SlimScreenHeader
import com.bordy.app.ui.components.TintedCircleButton
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.BuyAmber
import com.bordy.app.utils.DangerRed
import com.bordy.app.utils.OnBrandYellow
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.SellGreenDark
import com.bordy.app.utils.TagPalette
import com.bordy.app.utils.contentColorOn
import com.bordy.app.utils.formatDateWithDay
import com.bordy.app.utils.stockColorFor
import com.bordy.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val logTimeFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun timeLabel(timestamp: Long): String = logTimeFormat.format(Date(timestamp))

private fun startOfDay(timestamp: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestamp
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun dayLabel(dayStart: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    val oneDay = 86_400_000L
    return when (dayStart) {
        today -> "اليوم"
        today - oneDay -> "أمس"
        else -> formatDateWithDay(dayStart)
    }
}

private fun categoryIcon(category: LogCategory): ImageVector = when (category) {
    LogCategory.INVENTORY -> Icons.Default.Inventory2
    LogCategory.SUPPLIERS -> Icons.Default.Storefront
    LogCategory.BOARDS -> Icons.Default.SpaceDashboard
    LogCategory.TEMPLATES -> Icons.Default.LocalOffer
    LogCategory.CART -> Icons.Default.ShoppingCart
    LogCategory.SETTINGS -> Icons.Default.Settings
}

@Composable
private fun categoryColor(category: LogCategory): Color = when (category) {
    LogCategory.INVENTORY -> SellGreen
    LogCategory.SUPPLIERS -> TagPalette[2]
    LogCategory.BOARDS -> BrandYellow
    LogCategory.TEMPLATES -> TagPalette[3]
    LogCategory.CART -> TagPalette[5]
    LogCategory.SETTINGS -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun actionColor(action: LogAction): Color = when (action) {
    LogAction.ADD -> SellGreen
    LogAction.EDIT -> BrandYellow
    LogAction.DELETE -> DangerRed
    LogAction.COMPLETE -> SellGreenDark
    LogAction.STOCK -> BuyAmber
    LogAction.STATUS -> TagPalette[2]
    LogAction.REORDER -> TagPalette[3]
}

@Composable
fun ActivityLogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val logs by vm.activityLogs.collectAsState()
    val settings by vm.settings.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<LogCategory?>(null) }
    var editingEntry by remember { mutableStateOf<ActivityLog?>(null) }
    var deletingEntry by remember { mutableStateOf<ActivityLog?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    val counts: Map<LogCategory, Int> = remember(logs) {
        logs.groupingBy { LogCategory.from(it.category) }.eachCount()
    }
    val filtered = remember(logs, query, selectedCategory) {
        val q = query.trim()
        logs.filter { entry ->
            val category = LogCategory.from(entry.category)
            val action = LogAction.from(entry.action)
            (selectedCategory == null || category == selectedCategory) &&
                (q.isEmpty() ||
                    entry.title.contains(q, ignoreCase = true) ||
                    entry.details.contains(q, ignoreCase = true) ||
                    category.label.contains(q, ignoreCase = true) ||
                    action.label.contains(q, ignoreCase = true))
        }
    }
    val groups = remember(filtered) {
        filtered.groupBy { startOfDay(it.createdAt) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SlimScreenHeader(title = "سجل الشغل والعمليات", onBack = onBack) {
                if (logs.isNotEmpty()) {
                    TintedCircleButton(
                        onClick = { showClearAll = true },
                        icon = Icons.Default.DeleteSweep,
                        contentDescription = "مسح السجل",
                        tint = DangerRed,
                        size = 38.dp,
                        iconSize = 19.dp
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (logs.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("دوّر بالسجل على عملية أو مادة...", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "مسح البحث",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { query = "" }
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Spacer(Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    item {
                        LogFilterChip(
                            label = "الكل",
                            count = logs.size,
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null }
                        )
                    }
                    items(LogCategory.entries, key = { it.name }) { category ->
                        val count = counts[category] ?: 0
                        if (count > 0) {
                            LogFilterChip(
                                label = category.label,
                                count = count,
                                selected = selectedCategory == category,
                                onClick = {
                                    selectedCategory = if (selectedCategory == category) null else category
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
            }

            when {
                logs.isEmpty() -> EmptyState(
                    icon = Icons.Default.History,
                    message = "سجل الشغل فاضي هسّه — كل حركة تسويها بالبوردات والمخزن راح تتسجل هنا"
                )
                filtered.isEmpty() -> EmptyState(
                    icon = Icons.Default.SearchOff,
                    message = "ماكو عمليات تطابق اللي دوّرت عليه"
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groups.forEach { (day, entries) ->
                        item(key = "day-$day") {
                            DayHeader(label = dayLabel(day), count = entries.size)
                        }
                        items(items = entries, key = { it.id }) { entry ->
                            LogCard(
                                entry = entry,
                                settings = settings,
                                onEdit = { editingEntry = entry },
                                onDelete = { deletingEntry = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    val editing = editingEntry
    if (editing != null) {
        var editTitle by remember(editing.id) { mutableStateOf(editing.title) }
        var editDetails by remember(editing.id) { mutableStateOf(editing.details) }
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("تعديل نص السجل", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("عنوان العملية") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = editDetails,
                        onValueChange = { editDetails = it },
                        label = { Text("التفاصيل") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Text(
                        "التعديل يغيّر نص السجل فقط — بيانات التطبيق نفسها لا تتأثر.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                BrandButton(
                    text = "حفظ",
                    enabled = editTitle.isNotBlank(),
                    onClick = {
                        vm.editActivityLog(editing, editTitle, editDetails)
                        editingEntry = null
                    }
                )
            },
            dismissButton = { TextButton(onClick = { editingEntry = null }, shape = CircleShape) { Text("إلغاء") } }
        )
    }

    val deleting = deletingEntry
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingEntry = null },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("احذف هالسطر من السجل", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = { Text("سيُزال هذا السطر من السجل فقط. العمليات نفسها وبيانات التطبيق لا تتأثر.") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteActivityLog(deleting.id)
                        deletingEntry = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = CircleShape
                ) { Text("حذف", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deletingEntry = null }, shape = CircleShape) { Text("إلغاء") } }
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("مسح سجل الشغل كله", fontWeight = FontWeight.Bold, color = DangerRed) },
            text = {
                val count = logs.size
                Text("راح ينمسح كل السجل (${if (count == 1) "عملية واحدة" else "$count عملية"}). بورداتك والمخزن والمجهّزين ما يتأثرون أبد.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.clearActivityLog()
                        showClearAll = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = CircleShape
                ) { Text("مسح السجل", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }, shape = CircleShape) { Text("إلغاء") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) BrandYellow else MaterialTheme.colorScheme.surface
    val content = if (selected) OnBrandYellow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = background,
        border = BorderStroke(
            1.dp,
            if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = content
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = content.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (count == 1) "عملية واحدة" else "$count عمليات",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun LogCard(entry: ActivityLog, settings: AppSettings?, onEdit: () -> Unit, onDelete: () -> Unit) {
    val category = LogCategory.from(entry.category)
    val action = LogAction.from(entry.action)
    val tint = categoryColor(category)
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(categoryIcon(category), null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        ActionPill(action)
                        if (entry.repeatCount > 1) {
                            Spacer(Modifier.width(4.dp))
                            RepeatBadge(entry.repeatCount)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${timeLabel(entry.createdAt)} · ${category.label}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        if (entry.isEdited) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "نص معدّل",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "خيارات السجل",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("تعديل نص السجل") },
                            leadingIcon = { Icon(Icons.Default.Edit, null, tint = BrandYellow) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("حذف سطر السجل", color = DangerRed) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = DangerRed) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            val hasChange = entry.changeDirection != 0 && entry.changeAfter != null && !entry.isEdited
            if (hasChange) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Spacer(Modifier.height(6.dp))
                ChangeDetailsRow(entry = entry, settings = settings)
            } else if (entry.details.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.details,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
    }
}

// سطر تغيّر الكمية: زيادة خضراء أو نقصان أحمر، وبجانبه دائرة العدد الحالي بتدرّج الإعدادات
@Composable
private fun ChangeDetailsRow(entry: ActivityLog, settings: AppSettings?) {
    val increase = entry.changeDirection > 0
    val tint = if (increase) SellGreen else DangerRed
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val strong = MaterialTheme.colorScheme.onSurface
    val before = entry.changeBefore ?: 0
    val after = entry.changeAfter ?: 0

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = muted)) { append("قطعة: ") }
                withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold)) {
                    append(if (increase) "زيادة" else "نقصان")
                }
                withStyle(SpanStyle(color = muted)) { append(" ${entry.changeLabel} من ") }
                withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append("$before") }
                withStyle(SpanStyle(color = muted)) { append(" إلى ") }
                withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold)) { append("$after") }
            },
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        StockBadge(
            count = after,
            color = stockColorFor(after, settings),
            size = 30.dp,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ActionPill(action: LogAction) {
    val color = actionColor(action)
    Box(
        Modifier
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = action.label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = contentColorOn(color)
        )
    }
}

@Composable
private fun RepeatBadge(count: Int) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "×$count",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}
