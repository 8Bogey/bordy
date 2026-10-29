package com.bordy.app.utils

import com.bordy.app.data.Project
import com.bordy.app.data.ProjectItem

val ProjectItem.billableQuantity: Int
    get() = if (isShortage) (quantity - shortageRemaining).coerceAtLeast(0) else quantity

val ProjectItem.billablePurchaseTotal: Double
    get() = if (isShortage) lastKnownPurchase * shortageFromStock + shortageManualPurchase
    else lastKnownPurchase * quantity

val ProjectItem.billableSellTotal: Double
    get() = if (isShortage) sellPrice * shortageFromStock + shortageManualSell
    else sellPrice * quantity

// المُوفَر يدوياً يُخزَّن كمجموع متراكم لكل قطعة، وسعر الوحدة متوسط ما دُفع فعلياً
val ProjectItem.shortageManualPurchaseUnit: Double
    get() = if (shortageManual > 0) shortageManualPurchase / shortageManual else 0.0

val ProjectItem.shortageManualSellUnit: Double
    get() = if (shortageManual > 0) shortageManualSell / shortageManual else 0.0

// القطعة سطر في البورد، والكمية ما استُعمل من كل صنف
fun projectItemCount(items: List<ProjectItem>): Int = items.size

fun projectTotalQuantity(items: List<ProjectItem>): Int = items.sumOf { it.billableQuantity }

fun projectTotalCost(project: Project, items: List<ProjectItem>): Double =
    items.sumOf { it.billableSellTotal } + project.laborCost
