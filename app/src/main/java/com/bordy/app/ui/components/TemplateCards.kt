package com.bordy.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.bordy.app.R
import com.bordy.app.data.Component
import com.bordy.app.data.ComponentVariant
import com.bordy.app.data.Template
import com.bordy.app.data.TemplateItem
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.OnBrandYellow
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.TemplateSummary
import com.bordy.app.utils.cleanString
import com.bordy.app.utils.componentLabel
import com.bordy.app.utils.formatCurrency
import com.bordy.app.utils.templateItemSellPrice
import com.bordy.app.utils.templateSummary
import com.bordy.app.utils.withThousandsDot

fun templateDimensionsText(t: Template): String =
    "${t.boxWidth.cleanString()} × ${t.boxHeight.cleanString()} × ${t.boxDepth.cleanString()} سم"

// ─────────────────────────────────────────────────
// ▎ دائرة عدد القطع — القطعة صنف، والكمية ما استُعمل منه
// ─────────────────────────────────────────────────
@Composable
fun PieceCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    size: Int = 26
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(BrandYellow),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$count",
            color = OnBrandYellow,
            fontWeight = FontWeight.Bold,
            fontSize = if (count > 99) 10.sp else 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────────
// ▎ بطاقة قالب موحّدة: الصورة يميناً ثم الاسم والأبعاد
//   والقطع والكمية والتكلفة. العين اختيارية — تظهر في
//   شاشة سحب قالب فقط لا في قائمة قسم القوالب
// ─────────────────────────────────────────────────
@Composable
fun TemplateListCard(
    template: Template,
    summary: TemplateSummary,
    pricesHidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPreview: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // الصورة أولاً لأن اتجاه الواجهة RTL فيظهر في أقصى اليمين
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (template.imageUri.isNotEmpty()) {
                    AsyncImage(
                        model = template.imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = SellGreen.copy(alpha = 0.55f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    template.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(BrandYellow.copy(alpha = 0.9f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        templateDimensionsText(template),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = OnBrandYellow,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PieceCountBadge(count = summary.itemCount)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "قطعة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "الكمية ${summary.totalQuantity}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (!pricesHidden) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            formatCurrency(summary.totalCost).withThousandsDot(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = SellGreen
                        )
                    }
                }
            }
            if (onPreview != null) {
                Spacer(Modifier.width(8.dp))
                TintedCircleButton(
                    onClick = onPreview,
                    icon = Icons.Rounded.Visibility,
                    contentDescription = "عرض قطع القالب",
                    tint = SellGreen.copy(alpha = 0.14f),
                    contentColor = SellGreen,
                    size = 38.dp,
                    iconSize = 20.dp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// ▎ نافذة قطع القالب: كل قطعة بتفاصيلها، ثم إجمالي القالب
// ─────────────────────────────────────────────────
@Composable
fun TemplatePiecesDialog(
    template: Template,
    items: List<TemplateItem>,
    variants: List<ComponentVariant>,
    components: List<Component>,
    pricesHidden: Boolean,
    onDismiss: () -> Unit
) {
    val summary = remember(template, items, variants) { templateSummary(template, items, variants) }
    val scrimInteraction = remember { MutableInteractionSource() }
    val ordered = remember(items) { items.sortedBy { it.orderIndex } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = scrimInteraction, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .heightIn(max = 640.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { },
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 14.dp
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (template.imageUri.isNotEmpty()) {
                            AsyncImage(
                                model = template.imageUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Image,
                                contentDescription = null,
                                tint = SellGreen.copy(alpha = 0.45f),
                                modifier = Modifier.align(Alignment.Center).size(48.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "إغلاق",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            template.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            templateDimensionsText(template),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(10.dp))
                        TemplateTotalsRow(summary = summary, pricesHidden = pricesHidden)
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "قطع القالب (${summary.itemCount}) · الكمية ${summary.totalQuantity}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (ordered.isEmpty()) {
                        Text(
                            "لا توجد قطع في هذا القالب بعد",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 280.dp),
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp, bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(ordered, key = { it.id }) { item ->
                                val variant = variants.find { it.id == item.variantId }
                                val component = components.find { it.id == variant?.componentId }
                                val unitPrice = templateItemSellPrice(item, variants)
                                PieceRow(
                                    title = pieceTitle(item, component, variant),
                                    imageUri = component?.imageUri.orEmpty(),
                                    quantity = item.quantity,
                                    unitPrice = unitPrice,
                                    pricesHidden = pricesHidden
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateTotalsRow(summary: TemplateSummary, pricesHidden: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(BrandYellow.copy(alpha = 0.18f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PieceCountBadge(count = summary.itemCount, size = 20)
            Spacer(Modifier.width(5.dp))
            Text(
                "قطعة · الكمية ${summary.totalQuantity}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = OnBrandYellow
            )
        }
        if (!pricesHidden) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SellGreen.copy(alpha = 0.11f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "الإجمالي ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Text(
                        formatCurrency(summary.totalCost).withThousandsDot(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = SellGreen
                    )
                }
                Text(
                    "القطع ${formatCurrency(summary.piecesTotal).withThousandsDot()} · الأجور ${formatCurrency(summary.laborCost).withThousandsDot()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PieceRow(
    title: String,
    imageUri: String,
    quantity: Int,
    unitPrice: Double,
    pricesHidden: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri.isNotEmpty()) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.clover_placeholder),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (pricesHidden) "الكمية: $quantity"
                else "الكمية: $quantity × ${formatCurrency(unitPrice).withThousandsDot()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!pricesHidden) {
            Text(
                formatCurrency(unitPrice * quantity).withThousandsDot(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = SellGreen
            )
        }
    }
}

private fun pieceTitle(
    item: TemplateItem,
    component: Component?,
    variant: ComponentVariant?
): String {
    val live = !item.isManual && item.variantId != null
    return if (live) componentLabel(component?.name, variant, "؟")
    else item.manualName.ifBlank { if (item.isManual) "قطعة يدوية" else "محذوفة من المخزون" }
}
