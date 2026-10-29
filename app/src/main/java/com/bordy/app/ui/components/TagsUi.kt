package com.bordy.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.data.Component
import com.bordy.app.data.Tag
import com.bordy.app.utils.DangerRed
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.TagPalette
import com.bordy.app.utils.contentColorOn
import com.bordy.app.utils.normalizeTagName
import com.bordy.app.utils.tagColorFor
import com.bordy.app.utils.tagColorIndexForName

// ميزة الوسوم معلّقة مؤقتاً بطلب المالك — كل الكود موجود، والتفعيل بجعل القيمة true
val TagsEnabled: Boolean = false

private fun colorIndexForTagName(name: String, allTags: List<Tag>): Int {
    for (t in allTags) if (t.name.equals(name, ignoreCase = true)) return t.colorIndex
    return tagColorIndexForName(name)
}

// بحث قوائم اختيار القطع: يطابق اسم القطعة أو أيّاً من وسومها
fun matchesTagSearch(component: Component, query: String, tagsOf: Map<Long, List<Tag>>): Boolean {
    if (query.isEmpty()) return true
    if (component.name.contains(query, ignoreCase = true)) return true
    val list = tagsOf[component.id] ?: return false
    for (t in list) if (t.name.contains(query, ignoreCase = true)) return true
    return false
}

// ===== شارة وسم واحدة =====
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagPill(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    useCount: Int? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    val background = if (selected) color else color.copy(alpha = 0.13f)
    val content = if (selected) contentColorOn(color) else color
    val clickableModifier = when {
        onClick != null && onLongClick != null ->
            Modifier.combinedClickable(onClick = { onClick() }, onLongClick = { onLongClick() })
        onClick != null -> Modifier.clickable { onClick() }
        else -> Modifier
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(background)
            .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), CircleShape))
            .then(clickableModifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onRemove != null) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "إزالة وسم $name",
                tint = content,
                modifier = Modifier.size(12.dp).clickable { onRemove() }
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = name,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            maxLines = 1
        )
        if (useCount != null) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = "$useCount",
                color = content.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ===== نقاط الوسوم على البطاقات: تدل على الوسوم دون أن تزيد ارتفاع الصف =====
@Composable
fun TagDots(tags: List<Tag>, modifier: Modifier = Modifier, max: Int = 3) {
    if (tags.isEmpty()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until minOf(max, tags.size)) {
            if (i > 0) Spacer(Modifier.width(3.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tagColorFor(tags[i].colorIndex))
            )
        }
        if (tags.size > max) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "+${tags.size - max}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

// ===== شريط تصفية الوسوم فوق قائمة المخزون =====
@Composable
fun TagFilterBar(
    tags: List<Tag>,
    usage: Map<Long, Int>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    onManage: (Tag) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tags.isEmpty()) return
    LazyRow(
        modifier = modifier.height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        if (selectedIds.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onClear() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "مسح التصفية",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
        items(items = tags, key = { t: Tag -> t.id }) { t: Tag ->
            TagPill(
                name = t.name,
                color = tagColorFor(t.colorIndex),
                selected = selectedIds.contains(t.id),
                useCount = usage[t.id] ?: 0,
                onClick = { onToggle(t.id) },
                onLongClick = { onManage(t) }
            )
        }
    }
}

// ===== محرر الوسوم: مستعمل في dialogs الإضافة والتعديل =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(
    selectedNames: List<String>,
    allTags: List<Tag>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "وسوم القطعة"
) {
    var draft by remember { mutableStateOf("") }
    val normalizedDraft = normalizeTagName(draft)
    val alreadyPicked = selectedNames.any { it.equals(normalizedDraft, ignoreCase = true) }

    fun commit() {
        if (normalizedDraft.isNotEmpty() && !alreadyPicked) {
            onAdd(normalizedDraft)
            draft = ""
        }
    }

    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(6.dp))
        if (selectedNames.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (name in selectedNames) {
                    TagPill(
                        name = name,
                        color = tagColorFor(colorIndexForTagName(name, allTags)),
                        onRemove = { onRemove(name) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        val suggestions: MutableList<Tag> = mutableListOf()
        for (t in allTags) {
            if (suggestions.size >= 10) break
            if (selectedNames.none { it.equals(t.name, ignoreCase = true) }) suggestions.add(t)
        }
        if (suggestions.isNotEmpty()) {
            Text(
                text = "اختر من وسومك المستخدمة",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (t in suggestions) {
                    TagPill(
                        name = t.name,
                        color = tagColorFor(t.colorIndex),
                        selected = false,
                        onClick = { onAdd(t.name) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { s: String -> draft = s },
            placeholder = { Text("وسم جديد، مثال: ألمنيوم", style = MaterialTheme.typography.bodyMedium) },
            label = { Text("إضافة وسم") },
            singleLine = true,
            enabled = selectedNames.size < 8,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "إضافة الوسم",
                    tint = if (normalizedDraft.isNotEmpty() && !alreadyPicked) SellGreen
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.clickable { commit() }
                )
            },
            supportingText = if (selectedNames.size >= 8) {
                { Text("ثمانية وسوم تكفي لكل قطعة", fontSize = 11.sp) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
    }
}

// ===== نافذة إدارة الوسم: تسمية + لون + حذف =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagManageDialog(
    tag: Tag,
    useCount: Int,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onRecolor: (Int) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(tag.name) }
    var pickedColor by remember { mutableStateOf(tag.colorIndex) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("إدارة الوسم", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { s: String -> name = s },
                    label = { Text("اسم الوسم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Text(
                    text = "اللون",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in TagPalette.indices) {
                        val c = TagPalette[i]
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(
                                    if (i == pickedColor) Modifier.border(BorderStroke(2.dp, Color.White))
                                    else Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)))
                                )
                                .clickable {
                                    pickedColor = i
                                    onRecolor(i)
                                }
                        )
                    }
                }
                Text(
                    text = if (useCount == 0) "لا تستخدمه أي قطعة الآن"
                          else "يستخدمه $useCount قطعة — تسميته باسم وسم آخر تدمجهما تلقائياً",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (confirmDelete) {
                    Text(
                        text = "سيُزال الوسم من $useCount قطعة. اضغط «حذف الوسم» للتأكيد.",
                        style = MaterialTheme.typography.labelMedium,
                        color = DangerRed
                    )
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                Button(
                    onClick = { onDelete() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) { Text("حذف الوسم") }
            } else {
                BrandButton(
                    text = "تم",
                    enabled = name.isNotBlank(),
                    onClick = { onRename(name); onDismiss() }
                )
            }
        },
        dismissButton = {
            if (confirmDelete) {
                TextButton(onClick = { confirmDelete = false }, shape = CircleShape) { Text("تراجع") }
            } else {
                TextButton(
                    onClick = { confirmDelete = true },
                    shape = CircleShape,
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) { Text("حذف") }
            }
        }
    )
}
