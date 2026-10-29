package com.bordy.app.utils

import com.bordy.app.data.Project
import com.bordy.app.data.ProjectItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectItemMathTest {

    private fun item(
        quantity: Int = 10,
        sellPrice: Double = 100.0,
        lastKnownPurchase: Double = 60.0,
        isShortage: Boolean = false,
        shortageRemaining: Int = 0,
        shortageFromStock: Int = 0,
        shortageManual: Int = 0,
        shortageManualPurchase: Double = 0.0,
        shortageManualSell: Double = 0.0
    ) = ProjectItem(
        projectId = 1,
        quantity = quantity,
        sellPrice = sellPrice,
        lastKnownPurchase = lastKnownPurchase,
        isShortage = isShortage,
        shortageRemaining = shortageRemaining,
        shortageFromStock = shortageFromStock,
        shortageManual = shortageManual,
        shortageManualPurchase = shortageManualPurchase,
        shortageManualSell = shortageManualSell
    )

    private fun project(laborCost: Double) = Project(id = 1, customerName = "عميل", laborCost = laborCost)

    @Test
    fun `billable quantity equals quantity when not shortage`() {
        assertEquals(10, item().billableQuantity)
    }

    @Test
    fun `billable quantity subtracts shortage remaining`() {
        assertEquals(6, item(quantity = 10, isShortage = true, shortageRemaining = 4).billableQuantity)
    }

    @Test
    fun `billable quantity never goes negative`() {
        assertEquals(0, item(quantity = 10, isShortage = true, shortageRemaining = 30).billableQuantity)
    }

    @Test
    fun `purchase total uses last known purchase for normal item`() {
        assertEquals(600.0, item().billablePurchaseTotal, 0.001)
    }

    @Test
    fun `purchase total blends stock and manual purchases for shortage`() {
        val it = item(
            isShortage = true,
            quantity = 10,
            shortageRemaining = 1,
            shortageFromStock = 3,
            shortageManual = 6,
            shortageManualPurchase = 400.0
        )
        assertEquals(60.0 * 3 + 400.0, it.billablePurchaseTotal, 0.001)
    }

    @Test
    fun `sell total blends stock and manual sells for shortage`() {
        val it = item(
            isShortage = true,
            quantity = 10,
            shortageRemaining = 1,
            shortageFromStock = 3,
            shortageManual = 6,
            shortageManualSell = 600.0
        )
        assertEquals(100.0 * 3 + 600.0, it.billableSellTotal, 0.001)
    }

    @Test
    fun `manual unit price is the average actually paid`() {
        val it = item(shortageManual = 4, shortageManualPurchase = 200.0, shortageManualSell = 320.0)
        assertEquals(50.0, it.shortageManualPurchaseUnit, 0.001)
        assertEquals(80.0, it.shortageManualSellUnit, 0.001)
    }

    @Test
    fun `manual unit price is zero when nothing was covered manually`() {
        val it = item()
        assertEquals(0.0, it.shortageManualPurchaseUnit, 0.001)
        assertEquals(0.0, it.shortageManualSellUnit, 0.001)
    }

    @Test
    fun `project totals sum billable quantities and add labor to sell`() {
        val items = listOf(
            item(quantity = 10),
            item(
                quantity = 10,
                isShortage = true,
                shortageRemaining = 4,
                shortageFromStock = 3,
                shortageManual = 3,
                shortageManualSell = 300.0
            )
        )
        assertEquals(16, projectTotalQuantity(items))
        assertEquals(2, projectItemCount(items))
        assertEquals(1000.0 + 600.0 + 2500.0, projectTotalCost(project(2500.0), items), 0.001)
    }
}
