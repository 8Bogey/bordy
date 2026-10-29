package com.bordy.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bordy.app.R
import com.bordy.app.ui.animations.bouncyClickable
import com.bordy.app.ui.animations.bouncyHoldToRepeat
import com.bordy.app.utils.DangerRed
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.contentColorOn

val DialogShape = RoundedCornerShape(26.dp)
val CardShape = RoundedCornerShape(22.dp)

// ─────────────────────────────────────────────────
// ▎ زر أساسي صلب بضغطة فيزيائية مرنة
// ─────────────────────────────────────────────────
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = SellGreen,
    contentColor: Color = Color.White,
    icon: ImageVector? = null
) {
    val resolvedContent = if (enabled) contentColor else Color.White
    Row(
        modifier = modifier
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(if (enabled) containerColor else Color(0xFF8B95A1))
            .bouncyClickable(enabled = enabled, scaleDownTo = 0.94f) { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedContent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            color = resolvedContent,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

// ─────────────────────────────────────────────────
// ▎ زر عائم صلب
// ─────────────────────────────────────────────────
@Composable
fun BrandFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    containerColor: Color = SellGreen,
    contentColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(containerColor)
            .bouncyClickable(scaleDownTo = 0.9f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

// ─────────────────────────────────────────────────
// ▎ زر دائري صلب (حذف / تعديل / إضافة ...)
// ─────────────────────────────────────────────────
@Composable
fun TintedCircleButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = 17.dp,
    enabled: Boolean = true,
    contentColor: Color = contentColorOn(tint)
) {
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.32f)
            .clip(CircleShape)
            .background(tint)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

// ─────────────────────────────────────────────────
// ▎ زر زيادة / نقصان موحّد: ضغط زنبركي + تكرار متسارع عند الضغط المطوّل
// ─────────────────────────────────────────────────
@Composable
fun StepButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = 17.dp,
    enabled: Boolean = true
) {
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.32f)
            .clip(CircleShape)
            .background(containerColor)
            .bouncyHoldToRepeat(enabled = enabled) { currentOnClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColorOn(containerColor),
            modifier = Modifier.size(iconSize)
        )
    }
}

// ─────────────────────────────────────────────────
// ▎ رأس شاشة ثانوية نحيف وموحّد
// ─────────────────────────────────────────────────
@Composable
fun SlimScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TintedCircleButton(
                onClick = onBack,
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "رجوع",
                tint = MaterialTheme.colorScheme.primary,
                size = 40.dp,
                iconSize = 20.dp
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

// ─────────────────────────────────────────────────
// ▎ خلية موحّدة للعرض الشبكي: صورة دائرية + اسم + ما يُضاف تحتها
// ─────────────────────────────────────────────────
@Composable
fun PieceGridTile(
    imageUri: String,
    title: String,
    modifier: Modifier = Modifier,
    circleSize: Dp = 48.dp,
    circleScale: Float = 1f,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color? = null,
    fallbackIcon: ImageVector? = null,
    fallbackIconTint: Color = DangerRed,
    below: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(circleSize)
                .scale(circleScale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (borderColor != null) Modifier.border(2.dp, borderColor, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri.isNotEmpty()) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (fallbackIcon != null) {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    tint = fallbackIconTint
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.clover_placeholder),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            maxLines = 2,
            minLines = 2,
            textAlign = TextAlign.Center
        )
        below()
    }
}

// ─────────────────────────────────────────────────
// ▎ حالة فارغة أنيقة موحّدة
// ─────────────────────────────────────────────────
@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}
