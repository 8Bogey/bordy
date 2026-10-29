package com.bordy.app.utils

import com.bordy.app.data.ComponentVariant
import com.bordy.app.data.Template
import com.bordy.app.data.TemplateItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateMathTest {

    private val variant = ComponentVariant(
        id = 5,
        componentId = 1,
        specName = "6mm",
        defaultPurchasePrice = 40.0,
        defaultSellPrice = 90.0
    )

    private fun templateItem(
        variantId: Long? = 5,
        quantity: Int = 1,
        purchasePrice: Double = 0.0,
        sellPrice: Double = 0.0
    ) = TemplateItem(
        templateId = 1,
        variantId = variantId,
        quantity = quantity,
        purchasePrice = purchasePrice,
        sellPrice = sellPrice
    )

    @Test
    fun `stock variant price overrides stored item price`() {
        val it = templateItem(sellPrice = 70.0, purchasePrice = 30.0)
        assertEquals(90.0, templateItemSellPrice(it, listOf(variant)), 0.001)
        assertEquals(40.0, templateItemPurchasePrice(it, listOf(variant)), 0.001)
    }

    @Test
    fun `manual item keeps its stored price`() {
        val it = templateItem(variantId = null, sellPrice = 75.0, purchasePrice = 35.0)
        assertEquals(75.0, templateItemSellPrice(it, listOf(variant)), 0.001)
        assertEquals(35.0, templateItemPurchasePrice(it, listOf(variant)), 0.001)
    }

    @Test
    fun `detached variant falls back to stored price`() {
        val it = templateItem(variantId = 99, sellPrice = 55.0)
        assertEquals(55.0, templateItemSellPrice(it, listOf(variant)), 0.001)
    }

    @Test
    fun `summary sums quantity and sell value and adds labor`() {
        val items = listOf(
            templateItem(variantId = 5, quantity = 3),
            templateItem(variantId = null, quantity = 2, sellPrice = 50.0)
        )
        val summary = templateSummary(
            Template(id = 1, name = "قالب", laborCost = 100.0),
            items,
            listOf(variant)
        )
        assertEquals(2, summary.itemCount)
        assertEquals(5, summary.totalQuantity)
        assertEquals(90.0 * 3 + 50.0 * 2, summary.piecesTotal, 0.001)
        assertEquals(100.0, summary.laborCost, 0.001)
        assertEquals(470.0, summary.totalCost, 0.001)
    }

    @Test
    fun `negative labor cost is clamped to zero`() {
        val summary = templateSummary(
            Template(id = 1, name = "قالب", laborCost = -5.0),
            listOf(templateItem(variantId = 5, quantity = 1)),
            listOf(variant)
        )
        assertEquals(0.0, summary.laborCost, 0.001)
        assertEquals(90.0, summary.totalCost, 0.001)
    }
}
