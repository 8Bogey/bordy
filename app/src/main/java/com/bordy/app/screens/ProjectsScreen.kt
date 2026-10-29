package com.bordy.app.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.data.Project
import com.bordy.app.ui.animations.SolidBadge
import com.bordy.app.ui.animations.StaggeredItemAnimation
import com.bordy.app.ui.animations.bouncyClickable
import com.bordy.app.ui.components.CardShape
import com.bordy.app.ui.components.DialogShape
import com.bordy.app.ui.components.EmptyState
import com.bordy.app.ui.components.BrandButton
import com.bordy.app.ui.components.BrandFab
import com.bordy.app.ui.components.TintedCircleButton
import com.bordy.app.utils.*
import com.bordy.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(vm: MainViewModel, pricesHidden: Boolean = false, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val projects by vm.projects.collectAsState()
    val allProjectItems by vm.allProjectItems.collectAsState()
    val itemsByProject = remember(allProjectItems) { allProjectItems.groupBy { it.projectId } }
    var showNewDialog by remember { mutableStateOf(false) }
    var filterStatus by remember { mutableStateOf("ACTIVE") }
    var showDeleteConfirm by remember { mutableStateOf<Project?>(null) }

    val filtered = projects.filter { it.status == filterStatus }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ProjectFilterSegmentedBar(
                    selectedStatus = filterStatus,
                    onSelect = { filterStatus = it },
                    activeCount = projects.count { it.status == "ACTIVE" },
                    futureCount = projects.count { it.status == "FUTURE" },
                    doneCount = projects.count { it.status == "DONE" }
                )
            }

            if (filtered.isEmpty()) {
                item {
                    EmptyProjectsState(status = filterStatus)
                }
            } else {
                itemsIndexed(filtered, key = { _, it -> it.id }) { index, p ->
                    StaggeredItemAnimation(
                        index = index,
                        modifier = Modifier.animateItem()
                    ) {
                        val items = itemsByProject[p.id].orEmpty()
                        ProjectCard(
                            p = p,
                            pieceCount = projectItemCount(items),
                            totalQuantity = projectTotalQuantity(items),
                            totalCost = projectTotalCost(p, items),
                            pricesHidden = pricesHidden,
                            onClick = { onOpen(p.id) },
                            onDelete = { showDeleteConfirm = p }
                        )
                    }
                }
            }
        }

        BrandFab(
            onClick = { showNewDialog = true },
            icon = Icons.Rounded.Add,
            contentDescription = "بورد جديد",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        )
    }

    if (showNewDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            shape = DialogShape,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "بورد جديد",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم الزبون / البورد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            },
            confirmButton = {
                BrandButton(
                    text = "بلّش البورد",
                    enabled = name.isNotBlank(),
                    icon = Icons.Rounded.Add,
                    onClick = {
                        vm.createProject(name.trim()) { id ->
                            showNewDialog = false
                            onOpen(id)
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewDialog = false },
                    shape = CircleShape
                ) {
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
            title = {
                Text(
                    "تأكيد الحذف",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = DangerRed
                )
            },
            text = {
                Text(
                    "متأكد تريد تحذف بورد '${showDeleteConfirm!!.customerName}'؟ ما تكدر ترده بعد الحذف.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteProject(context, showDeleteConfirm!!)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = CircleShape
                ) {
                    Text("اي، احذف", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null },
                    shape = CircleShape
                ) {
                    Text("إلغاء")
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────
// ▎ شريط الفلترة القطعي المتدرج
// ─────────────────────────────────────────────────
@Composable
private fun ProjectFilterSegmentedBar(
    selectedStatus: String,
    onSelect: (String) -> Unit,
    activeCount: Int,
    futureCount: Int,
    doneCount: Int
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterTabItem(
                label = "قيد العمل",
                count = activeCount,
                isSelected = selectedStatus == "ACTIVE",
                accentColor = BrandYellow,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect("ACTIVE")
                },
                modifier = Modifier.weight(1f)
            )
            FilterTabItem(
                label = "مستقبل",
                count = futureCount,
                isSelected = selectedStatus == "FUTURE",
                accentColor = BrandYellow,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect("FUTURE")
                },
                modifier = Modifier.weight(1f)
            )
            FilterTabItem(
                label = "تم",
                count = doneCount,
                isSelected = selectedStatus == "DONE",
                accentColor = BrandYellow,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect("DONE")
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FilterTabItem(
    label: String,
    count: Int,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "filter_scale"
    )
    val accentContentColor = contentColorOn(accentColor)
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "filter_bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) accentContentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "filter_txt"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .clip(CircleShape)
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                fontSize = 12.sp
            )
            if (count > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                val badgeFill by animateColorAsState(
                    targetValue = if (isSelected) SellGreen else SellGreen.copy(alpha = 0.16f),
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "filter_badge_fill"
                )
                val badgeOutline by animateColorAsState(
                    targetValue = if (isSelected) Color.Black else SellGreen.copy(alpha = 0.6f),
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "filter_badge_outline"
                )
                val badgeTextColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "filter_badge_txt"
                )
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(badgeFill)
                        .border(if (isSelected) 1.5.dp else 1.dp, badgeOutline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$count",
                        color = badgeTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
// ▎ بطاقة البورد الحديثة
// ─────────────────────────────────────────────────
@Composable
fun ProjectCard(
    p: Project,
    pieceCount: Int,
    totalQuantity: Int,
    totalCost: Double,
    pricesHidden: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = BrandYellow
    val statusIcon = when (p.status) {
        "DONE" -> Icons.Rounded.CheckCircle
        "FUTURE" -> Icons.Rounded.DateRange
        else -> Icons.Rounded.Build
    }
    val dueColor = dueDateColor(p)
    val done = p.status == "DONE" && p.completedAt != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = CardShape,
                spotColor = Color.Black.copy(alpha = 0.06f)
            )
            .bouncyClickable(scaleDownTo = 0.97f) {
                onClick()
            },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(statusColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = contentColorOn(statusColor),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = p.customerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(5.dp))
                if (p.dueDate != null) {
                    SolidBadge(
                        text = formatDateWithDay(p.dueDate),
                        color = dueColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        icon = Icons.Rounded.CalendarToday
                    )
                }
                if (done) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SolidBadge(
                        text = "تسلم يوم ${formatDateWithDay(p.completedAt!!)}",
                        color = SellGreen,
                        icon = Icons.Rounded.CheckCircle
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(BrandYellow)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$pieceCount قطعة · الكمية $totalQuantity",
                            color = OnBrandYellow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!pricesHidden) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatCurrency(totalCost).withThousandsDot(),
                            color = SellGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            TintedCircleButton(
                onClick = onDelete,
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "حذف",
                tint = DangerRed,
                size = 36.dp,
                iconSize = 19.dp
            )
        }
    }
}

@Composable
private fun EmptyProjectsState(status: String) {
    val (statusLabel, statusIcon) = when (status) {
        "DONE" -> Pair("ما عندك بوردات مخلصة هسّه", Icons.Rounded.TaskAlt)
        "FUTURE" -> Pair("ما عندك بوردات مجدولة لبعدين", Icons.Rounded.EventAvailable)
        else -> Pair("ما عندك بوردات شغّال عليها هسّه.. اضغط + وبلّش!", Icons.Rounded.Build)
    }

    EmptyState(icon = statusIcon as ImageVector, message = statusLabel)
}
