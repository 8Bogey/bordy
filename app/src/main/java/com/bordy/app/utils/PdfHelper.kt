package com.bordy.app.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import com.bordy.app.R
import com.bordy.app.data.*
import java.io.File
import java.io.FileOutputStream

object PdfHelper {

    fun generateProjectPdf(
        context: Context,
        project: Project,
        items: List<ProjectItem>,
        variants: List<ComponentVariant>,
        components: List<Component>,
        suppliers: List<Supplier>,
        settings: AppSettings = AppSettings()
    ): File {
        if (!com.bordy.app.license.IntegrityChecks.checkDataIntegrity()) {
            throw IllegalStateException("License inactive")
        }
        val pdfDocument = PdfDocument()
        val marginRight = 550f
        val marginLeft = 45f
        val bottomLimit = 812f

        val paint = Paint()
        val titlePaint = Paint()
        val boldPaint = Paint()

        val typeface = ResourcesCompat.getFont(context, R.font.lantx) ?: Typeface.DEFAULT
        paint.typeface = typeface
        paint.textSize = 12f
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.RIGHT

        titlePaint.typeface = Typeface.create(typeface, Typeface.BOLD)
        titlePaint.textSize = 20f
        titlePaint.color = Color.parseColor("#2A9251")
        titlePaint.textAlign = Paint.Align.CENTER

        boldPaint.typeface = Typeface.create(typeface, Typeface.BOLD)
        boldPaint.textSize = 14f
        boldPaint.color = Color.BLACK
        boldPaint.textAlign = Paint.Align.RIGHT

        var pageNumber = 0
        lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = 0f

        fun startPage() {
            pageNumber++
            page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            canvas = page.canvas
            y = 50f
        }

        // أسطر الفاتورة قد تتجاوز صفحة واحدة: تُفتح صفحة جديدة قبل تجاوز الهامش السفلي
        fun ensureSpace(needed: Float) {
            if (y + needed > bottomLimit) {
                pdfDocument.finishPage(page)
                startPage()
            }
        }

        fun ellipsize(text: String, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
            return text.substring(0, end) + "…"
        }

        startPage()

        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(settings.invoiceTitle(), 297f, y, titlePaint)
        y += 30f
        canvas.drawText(settings.invoiceTagline(), 297f, y, paint)
        if (settings.businessPhone.isNotBlank()) {
            y += 18f
            canvas.drawText("هاتف: \u202A${settings.businessPhone.trim()}\u202C", 297f, y, paint)
        }
        paint.textAlign = Paint.Align.RIGHT
        y += 40f

        canvas.drawText("اسم البورد: ${project.customerName}", marginRight, y, boldPaint)
        y += 20f
        // الصيغة نفسها المعزولة LTR في الواجهة حتى لا ينعكس ترتيب التاريخ داخل الفاتورة
        val dateStr = formatShortDateUi(project.createdAt)
        canvas.drawText("التاريخ: $dateStr", marginRight, y, paint)
        y += 30f

        canvas.drawLine(marginLeft, y, marginRight, y, paint)
        y += 20f
        canvas.drawText("القطعة", marginRight - 20f, y, boldPaint)
        canvas.drawText("الكمية", 350f, y, boldPaint)
        canvas.drawText("السعر", 200f, y, boldPaint)
        canvas.drawText("الإجمالي", 100f, y, boldPaint)
        y += 10f
        canvas.drawLine(marginLeft, y, marginRight, y, paint)
        y += 25f

        var totalSell = 0.0
        items.forEach { item ->
            val billableQty = item.billableQuantity
            if (billableQty <= 0) return@forEach
            val displayName = if (item.isManual) {
                "${item.manualName} (يدوي)"
            } else {
                val variant = variants.find { it.id == item.variantId }
                val comp = components.find { it.id == variant?.componentId }
                componentLabel(comp?.name, variant, "")
            }
            val total = item.billableSellTotal
            val unitPrice = total / billableQty
            totalSell += total

            ensureSpace(20f)
            canvas.drawText(ellipsize(displayName, 170f), marginRight - 20f, y, paint)
            canvas.drawText(billableQty.toString(), 350f, y, paint)
            canvas.drawText(formatCurrency(unitPrice), 200f, y, paint)
            canvas.drawText(formatCurrency(total), 100f, y, paint)
            y += 20f
        }

        y += 10f
        ensureSpace(25f)
        canvas.drawLine(marginLeft, y, marginRight, y, paint)
        y += 25f

        ensureSpace(20f)
        canvas.drawText("إجمالي المواد: ${formatCurrency(totalSell)}", marginRight, y, boldPaint)
        if (project.laborCost > 0.0) {
            y += 20f
            ensureSpace(20f)
            canvas.drawText("أجور العمل: ${formatCurrency(project.laborCost)}", marginRight, y, boldPaint)
        }
        y += 20f
        ensureSpace(20f)
        val grandTotal = totalSell + project.laborCost
        canvas.drawText("إجمالي الفاتورة: ${formatCurrency(grandTotal)}", marginRight, y, boldPaint)

        val totalMaterial = items.sumOf { it.billablePurchaseTotal }
        if (settings.showPurchaseInPdf) {
            y += 20f
            ensureSpace(20f)
            canvas.drawText("كلفة المواد عليّ: ${formatCurrency(totalMaterial)}", marginRight, y, boldPaint)
        }
        if (settings.showProfitInPdf) {
            y += 20f
            ensureSpace(20f)
            canvas.drawText("الربح بعد الأجور: ${formatCurrency(grandTotal - totalMaterial)}", marginRight, y, boldPaint)
        }
        if (settings.invoiceFooter.isNotBlank()) {
            y += 26f
            for (line in settings.invoiceFooter.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                ensureSpace(18f)
                canvas.drawText(line, marginRight, y, paint)
                y += 18f
            }
        }

        pdfDocument.finishPage(page)

        val file = File(context.cacheDir, "Invoice_${project.id}.pdf")
        runCatching {
            FileOutputStream(file).use { pdfDocument.writeTo(it) }
        }.onFailure {
            it.printStackTrace()
            file.delete()
        }
        pdfDocument.close()
        return file
    }
}