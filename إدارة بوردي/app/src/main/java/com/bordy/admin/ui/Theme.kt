package com.bordy.admin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0F172A)
val Surface = Color(0xFF1E293B)
val SurfaceAlt = Color(0xFF172033)
val Border = Color(0xFF334155)
val Primary = Color(0xFF3B82F6)
val Mint = Color(0xFF6EE7B7)
val Green = Color(0xFF059669)
val Amber = Color(0xFFFBBF24)
val Danger = Color(0xFFDC2626)
val DangerSoft = Color(0xFFFCA5A5)
val TextMain = Color(0xFFE2E8F0)
val TextMuted = Color(0xFF94A3B8)

private val scheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Color.White,
    background = Bg,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = SurfaceAlt,
    onSurfaceVariant = TextMuted,
    outline = Border,
    error = Danger,
    onError = Color.White
)

private val typography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
)

@Composable
fun AdminTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
