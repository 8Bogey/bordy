package com.bordy.app.utils

import com.bordy.app.data.ComponentVariant
import com.bordy.app.data.Template
import com.bordy.app.data.TemplateItem

// ملخّص القالب: القطعة = صنف واحد في القالب، والكمية = مجموع ما استُعمل من كل صنف
data class TemplateSummary(
    val itemCount: Int,
    val totalQuantity: Int,
    val piecesTotal: Double,
    val laborCost: Double
) {
    val totalCost: Double get() = piecesTotal + laborCost
}

// القطعة السليمة تُسعَّر من المخزون مباشرة، واليدوية/المنفصلة تحمل سعرها المحفوظ
fun templateItemSellPrice(item: TemplateItem, variants: List<ComponentVariant>): Double =
    item.variantId?.let { id -> variants.find { it.id == id }?.defaultSellPrice } ?: item.sellPrice

fun templateItemPurchasePrice(item: TemplateItem, variants: List<ComponentVariant>): Double =
    item.variantId?.let { id -> variants.find { it.id == id }?.defaultPurchasePrice } ?: item.purchasePrice

fun templateSummary(
    template: Template,
    items: List<TemplateItem>,
    variants: List<ComponentVariant>
): TemplateSummary {
    var quantity = 0
    var value = 0.0
    for (item in items) {
        quantity += item.quantity
        value += templateItemSellPrice(item, variants) * item.quantity
    }
    return TemplateSummary(
        itemCount = items.size,
        totalQuantity = quantity,
        piecesTotal = value,
        laborCost = template.laborCost.coerceAtLeast(0.0)
    )
}
