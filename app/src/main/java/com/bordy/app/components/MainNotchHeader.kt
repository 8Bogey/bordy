package com.bordy.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bordy.app.R
import com.bordy.app.ui.animations.bouncyClickable
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.OnBrandYellow
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.SellGreenDark
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────
// ▎ تعريف التبويبات الرئيسية — أيقونات مملوءة موحّدة
//   نجمة مقعّرة للبوردات، علامة مرجعية مملوءة للقوالب،
//   ثلاثة أعمدة أوسطها أطول للمخزون، وزائد غليظ مدوّر للحاسبة
// ─────────────────────────────────────────────────
data class MainNavTab(
    val label: String,
    val icon: ImageVector
)

@Composable
fun appNavTabs(): List<MainNavTab> = listOf(
    MainNavTab("بوردات", ImageVector.vectorResource(R.drawable.ic_nav_star)),
    MainNavTab("قوالب", ImageVector.vectorResource(R.drawable.ic_nav_bookmark)),
    MainNavTab("المخزن", ImageVector.vectorResource(R.drawable.ic_nav_lines)),
    MainNavTab("حاسبة", ImageVector.vectorResource(R.drawable.ic_nav_plus))
)

// ─────────────────────────────────────────────────
// ▎ شريط التنقل العلوي النحيف (Slim Top Capsule)
//   ● كبسولة عائمة في المنتصف، أنحف بـ15% وأيقونات أكبر قليلاً
//   ● شريط أخضر صلب ومؤشر أصفر صلب ينساب فيزيائياً مع السحب
//   ● الإعدادات والبصمة جهة البداية، والسجل والشعار جهة النهاية،
//     وكلها دوائر متساوية العيار: قطر 36، بينها 6، وأيقونات معايَرة بصرياً
//   ● السجل يبدو خافتاً حين يكون مقفلاً أثناء إخفاء الأسعار
// ─────────────────────────────────────────────────
@Composable
fun MainNotchHeader(
    pagePosition: Float,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    onOpenActivityLog: (() -> Unit)? = null,
    activityLogLocked: Boolean = false,
    pricesHidden: Boolean = false,
    onTogglePrivacy: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val tabs = appNavTabs()
    val totalTabs = tabs.size
    val activeIndex = pagePosition.roundToInt().coerceIn(0, totalTabs - 1)

    // اتجاه RTL داخلي ليطابق الترتيب البصري لـ HorizontalPager:
    // التبويب الأول (بوردات) يظهر في أقصى اليمين والحاسبة في أقصى اليسار
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            val sideSize = 36.dp
            val gap = 6.dp
            // عيار بصري موحّد: الأشكال الممتلئة تُصغّر والخطوط الرفيعة تُكبَّر فتتقارب أوزانها
            val glyphFull = 23.dp
            val glyphThin = 24.5.dp
            val brandGlyph = 26.dp
            val controlsWidth = sideSize * 2 + gap
            // الكبسولة 50% ما لم تزاحم الدوائر على الشاشات الضيقة — الجانبان يتّسعان لدائرتين
            val pillWidth = minOf(maxWidth * 0.5f, maxWidth - (controlsWidth + 10.dp) * 2)

            // ══════════ الإعدادات + البصمة — جهة البداية ══════════
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onOpenSettings != null) {
                    Box(
                        modifier = Modifier
                            .size(sideSize)
                            .shadow(4.dp, CircleShape)
                            .clip(CircleShape)
                            .background(BrandYellow)
                            .bouncyClickable(scaleDownTo = 0.88f) { onOpenSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "الإعدادات",
                            tint = SellGreenDark,
                            modifier = Modifier.size(glyphFull)
                        )
                    }
                }
                if (onTogglePrivacy != null) {
                    if (onOpenSettings != null) Spacer(Modifier.width(gap))
                    Box(
                        modifier = Modifier
                            .size(sideSize)
                            .shadow(4.dp, CircleShape)
                            .clip(CircleShape)
                            .background(if (pricesHidden) BrandYellow else SellGreen)
                            .bouncyClickable(scaleDownTo = 0.88f) { onTogglePrivacy() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fingerprint,
                            contentDescription = if (pricesHidden) "إظهار الأسعار بالبصمة" else "إخفاء الأسعار بالبصمة",
                            tint = if (pricesHidden) OnBrandYellow else Color.White,
                            modifier = Modifier.size(glyphThin)
                        )
                    }
                }
            }

            // ══════════ السجل + شعار الهوية — جهة النهاية ══════════
            // في RTL يسبق السجلُ الشعارَ فيقع بينه وبين الكبسولة كما في جهة البداية
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onOpenActivityLog != null) {
                    // الخافت كناية القفل: قرص باهت بغير ظل وعلامة شبه شفافة
                    Box(
                        modifier = Modifier
                            .size(sideSize)
                            .shadow(if (activityLogLocked) 0.dp else 4.dp, CircleShape)
                            .clip(CircleShape)
                            .background(BrandYellow.copy(alpha = if (activityLogLocked) 0.30f else 1f))
                            .bouncyClickable(scaleDownTo = 0.88f) { onOpenActivityLog() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_nav_history),
                            contentDescription = if (activityLogLocked) "سجل العمليات مقفل" else "سجل العمليات",
                            tint = SellGreenDark.copy(alpha = if (activityLogLocked) 0.45f else 1f),
                            modifier = Modifier.size(glyphFull)
                        )
                    }
                    Spacer(Modifier.width(gap))
                }
                Box(
                    modifier = Modifier
                        .size(sideSize)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(BrandYellow),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_clover),
                        contentDescription = "بوردي",
                        modifier = Modifier.size(brandGlyph)
                    )
                }
            }

            // ══════════ الكبسولة الرئيسية ══════════
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(pillWidth)
                    .height(sideSize)
                    .shadow(4.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.12f)),
                shape = CircleShape,
                color = SellGreen,
                border = null
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                ) {
                    val tabWidth = maxWidth / totalTabs
                    val clamped = pagePosition.coerceIn(0f, (totalTabs - 1).toFloat())

                    // المؤشر الأصفر الصلب — يتبع الإصبع أثناء السحب
                    Box(
                        modifier = Modifier
                            .offset(x = tabWidth * clamped)
                            .width(tabWidth)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(BrandYellow)
                    )

                    Row(modifier = Modifier.fillMaxSize()) {
                        tabs.forEachIndexed { index, tab ->
                            val selected = activeIndex == index

                            val iconScale by animateFloatAsState(
                                targetValue = if (selected) 1.1f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "nav_icon_scale_$index"
                            )

                            val tint by animateColorAsState(
                                targetValue = if (selected) OnBrandYellow else Color.White,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "nav_icon_tint_$index"
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (activeIndex != index) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSelectTab(index)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = tint,
                                    modifier = Modifier
                                        .size(24.2.dp)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
