package com.bordy.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bordy.app.ui.components.BrandButton
import com.bordy.app.ui.components.EmptyState
import com.bordy.app.ui.theme.BordyTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * لقطات شاشة لمكوّنات نظام التصميم بالعربية RTL.
 * الإنشاء: ./gradlew :app:recordRoborazziDebug
 * التحقق: ./gradlew :app:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-rIQ-w411dp-h891dp-xhdpi", application = Application::class)
class DesignSystemScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    // رسم الخطوط يختلف قليلاً بين Windows وLinux؛ العتبة تسمح بذلك وتمسك التغيّر البنيوي
    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.03f),
    )

    @Test
    fun brandButton_states() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                BordyTheme(darkTheme = false) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        BrandButton(text = "إضافة قطعة", onClick = {}, icon = Icons.Filled.Add)
                        BrandButton(text = "غير متاح", onClick = {}, enabled = false)
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/brand_button.png", roborazziOptions = options)
    }

    @Test
    fun emptyState_arabic() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                BordyTheme(darkTheme = false) {
                    EmptyState(
                        icon = Icons.Filled.Inventory2,
                        message = "لا توجد قطع في هذا البورد بعد",
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/empty_state.png", roborazziOptions = options)
    }
}
