package com.bordy.app.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.bordy.app.data.AppSettings

// ========== لونا الهوية الصلبان (نفس ألوان الحاسبة) ==========
// أخضر الحاسبة = أخضر البيع، وأصفر الحاسبة = أصفر الهوية
// أخضر الهوية مستخرج من أيقونة التطبيق نفسها (حشوة البرسيم لا الحواف الزيتونية)
val SellGreen = Color(0xFF2A9251)
val SellGreenDark = Color(0xFF1B6B3A)
val BrandGreen = SellGreen
val BrandYellow = Color(0xFFFBBF24)
val OnBrandYellow = Color(0xFF3B2A00)

// ========== ألوان الأسعار (ثابتة — لا تتغير) ==========
// الأصفر الذهبي (سعر الشراء) هو نفسه أصفر الهوية المستعمل في عموم التطبيق
val BuyAmber = BrandYellow
val BuyAmberDark = Color(0xFFF59E0B)

// لون التحذير / المصروف من عندي — نفس أحمر زر الحذف في الحاسبة
val DangerRed = Color(0xFFEF4444)

// لون المحتوى المناسب فوق خلفية صلبة
fun contentColorOn(background: Color): Color =
    if (background.luminance() > 0.42f) OnBrandYellow else Color.White

// ========== لوحة ألوان الوسوم ==========
// ألوان مختارة لتبقى مقروءة على الداكن، كل وسم يُلون بستاذه لتمييزه بصرياً في أي شاشة
val TagPalette = listOf(
    SellGreen, // أخضر الهوية
    Color(0xFFFBBF24), // أصفر الهوية
    Color(0xFF38BDF8), // سماوي
    Color(0xFFA78BFA), // بنفسجي فاتح
    Color(0xFFF472B6), // وردي
    Color(0xFFFB923C), // برتقالي
    Color(0xFF2DD4BF), // فيروزي
    Color(0xFFE879F9)  // أرجواني
)

fun tagColorFor(colorIndex: Int): Color = TagPalette[colorIndex.mod(TagPalette.size)]

// لون مبدئي ثابت للاسم قبل إنشاء الوسم في القاعدة — نفس الاسم = نفس اللون دائماً
fun tagColorIndexForName(name: String): Int {
    var hash = 0
    for (ch in name.trim().lowercase()) hash = (hash * 31 + ch.code) % 100003
    return hash.mod(TagPalette.size)
}

// ========== تدرّج ألوان المخزون المعتمد في التطبيق ==========
// تدرّج smooth: أحمر عند الصفر ← أصفر عند الحد الأوسط ← أخضر عند الحد الأعلى فأكثر
fun stockColorFor(count: Int, settings: AppSettings?): Color {
    val high = (settings?.stockHighThreshold ?: 10).coerceAtLeast(2)
    val medium = (settings?.stockMediumThreshold ?: 5).coerceIn(1, high - 1)
    val safe = count.coerceAtLeast(0)
    return when {
        safe >= high -> SellGreen
        safe >= medium -> lerp(BrandYellow, SellGreen, (safe - medium).toFloat() / (high - medium))
        else -> lerp(DangerRed, BrandYellow, safe.toFloat() / medium)
    }
}
