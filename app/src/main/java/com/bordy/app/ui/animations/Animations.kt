package com.bordy.app.ui.animations

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.BuyAmber
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.contentColorOn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * تأثير ضغط فيزيائي زنبركي (Spring Bounce) مع اهتزاز لمسي خفيف
 */
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    scaleDownTo: Float = 0.95f,
    hapticType: HapticFeedbackType = HapticFeedbackType.LongPress,
    onClick: () -> Unit
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    // الحلقة تحت pointerInput تعيش إلى الأبد: بلا هذا تُلتقط أول lambda ولا تُحدَّث أبداً
    val currentOnClick by rememberUpdatedState(onClick)

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .semantics {
            role = Role.Button
            if (enabled) {
                semanticsOnClick {
                    haptic.performHapticFeedback(hapticType)
                    currentOnClick()
                    true
                }
            }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            coroutineScope {
                while (true) {
                    awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false)
                        launch {
                            scale.animateTo(
                                scaleDownTo,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }
                        val up = waitForUpOrCancellation()
                        launch {
                            scale.animateTo(
                                1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                        if (up != null) {
                            haptic.performHapticFeedback(hapticType)
                            currentOnClick()
                        }
                    }
                }
            }
        }
}

/**
 * ضغط زنبركي مع تكرار متسارع: أول خطوة عند النزول، والضغط المطوّل يضيف خطوات
 * تتقارب فترتها حتى حدّ أدنى، وتتوقف برفع الإصبع.
 */
fun Modifier.bouncyHoldToRepeat(
    enabled: Boolean = true,
    scaleDownTo: Float = 0.95f,
    holdDelayMillis: Long = 380,
    startIntervalMillis: Long = 220,
    minIntervalMillis: Long = 60,
    acceleration: Float = 0.82f,
    onStep: () -> Unit
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    val currentOnStep by rememberUpdatedState(onStep)

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            coroutineScope {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    launch {
                        scale.animateTo(
                            scaleDownTo,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                    currentOnStep()
                    var interval = startIntervalMillis
                    var nextAt = SystemClock.uptimeMillis() + holdDelayMillis
                    while (true) {
                        val now = SystemClock.uptimeMillis()
                        if (now >= nextAt) {
                            currentOnStep()
                            interval = (interval * acceleration).toLong().coerceAtLeast(minIntervalMillis)
                            nextAt = now + interval
                            continue
                        }
                        val event = withTimeoutOrNull(nextAt - now) { awaitPointerEvent() }
                            ?: continue
                        if (event.changes.none { it.pressed }) break
                    }
                    launch {
                        scale.animateTo(
                            1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }
                }
            }
        }
}

/**
 * حركة دخول متتالية (Staggered Entry) للبطاقات والقوائم
 */
@Composable
fun StaggeredItemAnimation(
    index: Int,
    modifier: Modifier = Modifier,
    baseDelayMs: Int = 40,
    content: @Composable () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    val animatedAlpha = remember { Animatable(0f) }
    val animatedOffsetY = remember { Animatable(30f) }

    LaunchedEffect(Unit) {
        val delayTime = (index.coerceAtMost(10) * baseDelayMs).toLong()
        kotlinx.coroutines.delay(delayTime)
        isVisible = true
        launch {
            animatedAlpha.animateTo(1f, animationSpec = tween(durationMillis = 280))
        }
        launch {
            animatedOffsetY.animateTo(
                0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            alpha = animatedAlpha.value
            translationY = animatedOffsetY.value
        }
    ) {
        content()
    }
}

/**
 * شارة حالة صلبة وأنيقة (بدون تدرجات) بحواف نقية
 */
@Composable
fun SolidBadge(
    text: String,
    color: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val isBrandColor = color == SellGreen || color == BuyAmber || color == BrandYellow
    val contentColor = if (isBrandColor) contentColorOn(color) else color
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (isBrandColor) color else color.copy(alpha = 0.12f))
            .then(
                if (isBrandColor) Modifier
                else Modifier.border(width = 1.dp, color = color.copy(alpha = 0.28f), shape = CircleShape)
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}
