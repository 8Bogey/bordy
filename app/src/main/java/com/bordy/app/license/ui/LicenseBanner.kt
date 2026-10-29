package com.bordy.app.license.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordy.app.license.LicenseState
import com.bordy.app.license.formatRemaining
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.DangerRed

@Composable
fun LicenseBanner(
    state: LicenseState,
    onRenewClick: (() -> Unit)? = null
) {
    val (text, bgColor, textColor) = when (state) {
        is LicenseState.Trial -> {
            if (!state.showBanner) return
            Triple(
                "⏳ باقيلك ${formatRemaining(state.msLeft)} بفترة التجربة المجانية — اضغط حتى ترقّي",
                BrandYellow,
                Color(0xFF3B2A00)
            )
        }
        is LicenseState.Active -> {
            if (!state.showReminder) return
            Triple(
                "🔔 باقي ${state.daysLeft} أيام ويخلص اشتراكك — اضغط وجدد هسّه",
                Color(0xFFFB923C), // Amber/Orange
                Color.White
            )
        }
        is LicenseState.Grace -> {
            Triple(
                "⚠️ خلص اشتراكك! عندك فترة سماح ${formatRemaining(state.msLeft)} — جدّد هسّه حتى ما يوكف التطبيق",
                DangerRed,
                Color.White
            )
        }
        else -> return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .then(
                if (onRenewClick != null) Modifier.clickable { onRenewClick() }
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
