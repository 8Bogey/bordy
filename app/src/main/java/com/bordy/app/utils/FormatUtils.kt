package com.bordy.app.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.bordy.app.data.ComponentVariant
import com.bordy.app.data.Project
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToLong

private val currencyFormatter = DecimalFormat("#,###", DecimalFormatSymbols(Locale.US))

fun formatCurrency(amount: Double): String {
    return currencyFormatter.format(amount.roundToLong())
}

// أسعار العملة تُقارن بدقة النقد لا بمساواة عائمة حرفية
fun pricesEqual(a: Double, b: Double): Boolean = abs(a - b) < 0.005

// فاصل الآلاف: نقطة بيضاء أكبر نسبياً من الأرقام
fun String.withThousandsDot(): AnnotatedString = buildAnnotatedString {
    this@withThousandsDot.forEach { ch ->
        if (ch == ',') withStyle(SpanStyle(color = Color.White, fontSize = 1.5.em)) { append('.') } else append(ch)
    }
}

// اسم الحجم الافتراضي: يظهر دائماً بعد اسم القطعة بوصفه «القطعة - إفتراضي»
const val DEFAULT_VARIANT_NAME = "إفتراضي"

// الحجم الخفي للقطعة الاعتيادية يحمل اسم القطعة ويكون وحيداً — وجوده تفصيل تخزيني لا يُعرض كحجم
fun ComponentVariant.isHiddenBase(componentName: String?): Boolean =
    isBase && namesEqual(specName, componentName ?: "")

// حجم القطعة يظهر «اسم القطعة - اسم الحجم»، والحجم الخفي للقطعة الاعتيادية يحمل اسم القطعة فلا يُكرر
fun componentLabel(componentName: String?, variant: ComponentVariant?, fallback: String = "غير معروف"): String {
    val name = componentName?.trim().orEmpty()
    val spec = variant?.specName?.trim().orEmpty()
    val showSpec = variant != null && spec.isNotEmpty() && !spec.equals(name, ignoreCase = true)
    return when {
        name.isEmpty() && showSpec -> spec
        name.isEmpty() -> if (spec.isNotEmpty()) spec else fallback
        showSpec -> "$name - $spec"
        else -> name
    }
}

// اسم الوسم الوحيد المقبول: بلا فراغات زائدة ولا أرقام وسيطة للاتجاه، وبسقف يمنع الشرائط الطويلة
fun normalizeTagName(raw: String): String {
    val cleaned = raw.replace("\u200E", "").replace("\u200F", "")
        .replace(Regex("[\\u202A-\\u202E]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (cleaned.length <= 24) cleaned else cleaned.substring(0, 24).trim()
}

// اسم قابل للمقارنة: فراغات مضغوطة بلا حواف، ثم مقارنة بلا حساسية للأحرف
fun normalizeItemName(raw: String): String =
    raw.replace("\u200E", "").replace("\u200F", "")
        .replace(Regex("[\\u202A-\\u202E]"), "")
        .replace(Regex("\\s+"), " ").trim()

fun namesEqual(a: String, b: String): Boolean =
    normalizeItemName(a).equals(normalizeItemName(b), ignoreCase = true)

// الشراء أو البيع صفر يعني لم يُدخَل بعد — القاعدة تعمل فقط بعد تحديد السعرين
fun isSellBelowPurchase(sell: Double, purchase: Double): Boolean =
    sell > 0.0 && purchase > 0.0 && sell < purchase

fun sellBelowPurchaseMessage(sell: Double, purchase: Double): String =
    "سعر البيع (${formatCurrency(sell).withThousandsDot()}) أقل من سعر الشراء (${formatCurrency(purchase).withThousandsDot()})"

fun Double.cleanString(): String {
    return try {
        val bd = BigDecimal.valueOf(this).stripTrailingZeros()
        if (bd.scale() <= 0) bd.toBigInteger().toString() else bd.toPlainString()
    } catch (e: Exception) {
        if (this % 1.0 == 0.0) this.toLong().toString() else this.toString()
    }
}

// صيغة الواجهة: شهر على اليسار، السنة بين قوسين في الوسط، يوم على اليمين
private val uiDateFormatter = SimpleDateFormat("MM(yy)dd", Locale.US)

// عزل التاريخ باتجاه LTR حتى لا يعكس ترتيبه داخل واجهة RTL
fun formatShortDateUi(timestamp: Long): String =
    "\u202A${uiDateFormatter.format(Date(timestamp))}\u202C"

private val uiDateTimeFormatter = SimpleDateFormat("MM(yy)dd h:mm a", Locale.US)

fun formatDateTimeUi(timestamp: Long): String =
    "\u202A${uiDateTimeFormatter.format(Date(timestamp))}\u202C"

fun formatDateWithDay(timestamp: Long): String {
    val date = Date(timestamp)
    val cal = Calendar.getInstance()
    cal.time = date
    val dayName = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SATURDAY -> "السبت"
        Calendar.SUNDAY -> "الأحد"
        Calendar.MONDAY -> "الاثنين"
        Calendar.TUESDAY -> "الثلاثاء"
        Calendar.WEDNESDAY -> "الأربعاء"
        Calendar.THURSDAY -> "الخميس"
        Calendar.FRIDAY -> "الجمعة"
        else -> ""
    }
    return "$dayName ${formatShortDateUi(timestamp)}"
}

// ══════════════ وقت التسليم وألوانه ══════════════

private fun startOfLocalDay(timestamp: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestamp
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

fun isSameLocalDay(a: Long, b: Long): Boolean = startOfLocalDay(a) == startOfLocalDay(b)

// منتقي التاريخ يعيد منتصف ليل UTC؛ نأخذ منه اليوم ثم نركّب عليه وقت التسليم المحلي من الإعدادات
fun combinePickedDateWithTime(pickedUtcMidnight: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedUtcMidnight }
    return Calendar.getInstance().apply {
        clear()
        set(
            utc.get(Calendar.YEAR),
            utc.get(Calendar.MONTH),
            utc.get(Calendar.DAY_OF_MONTH),
            hour.coerceIn(0, 23),
            minute.coerceIn(0, 59),
            0
        )
    }.timeInMillis
}

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.US)

fun formatTimeUi(timestamp: Long): String = "\u202A${timeFormatter.format(Date(timestamp))}\u202C"

fun formatDateTimeWithDay(timestamp: Long): String =
    "${formatDateWithDay(timestamp)} · ${formatTimeUi(timestamp)}"

// لون موعد التسليم: فائت أحمر، اليوم أصفر، مستقبلي أخضر — والبورد المكتمل يبقى محايداً
fun dueDateColor(project: Project, now: Long = System.currentTimeMillis()): Color? {
    val due = project.dueDate ?: return null
    if (project.status == "DONE") return null
    val dueDay = startOfLocalDay(due)
    val today = startOfLocalDay(now)
    return when {
        dueDay < today -> DangerRed
        dueDay == today -> BrandYellow
        else -> SellGreen
    }
}