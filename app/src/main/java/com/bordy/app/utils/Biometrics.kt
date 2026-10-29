package com.bordy.app.utils

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

// واجهة BiometricPrompt في النظام (API 30+) تعمل مع كل مصنّعي الأجهزة دون مكتبة إضافية
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun Context.biometricManager(): BiometricManager? =
    getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager

// بعض الـ ROM (هواوي/إنفينكس) ترمي SecurityException أو تفشل بصمت — الانهيار هنا يُغلق التطبيق
private fun Context.statusFor(mask: Int): Int =
    try {
        biometricManager()?.canAuthenticate(mask) ?: BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    } catch (e: Throwable) {
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }

private val FINGERPRINT_MASK = BiometricManager.Authenticators.BIOMETRIC_WEAK
private val ANY_MASK = FINGERPRINT_MASK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

private fun canUseFingerprint(context: Context): Boolean =
    context.statusFor(FINGERPRINT_MASK) == BiometricManager.BIOMETRIC_SUCCESS

private fun hasDeviceCredential(context: Context): Boolean =
    try {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    } catch (e: Throwable) {
        false
    }

// لا نعرض للضغط مصادقةً لا يمكن إتمامها: بصمة، ثم رقم سري، ثم لا شيء
fun privacyAuthMode(context: Context): PrivacyAuth = when {
    canUseFingerprint(context) -> PrivacyAuth.Fingerprint
    hasDeviceCredential(context) &&
        context.statusFor(ANY_MASK) == BiometricManager.BIOMETRIC_SUCCESS -> PrivacyAuth.Pin
    else -> PrivacyAuth.None
}

enum class PrivacyAuth { Fingerprint, Pin, None }

// نجاح المصادقة وحده يبدّل وضع الأسعار، وفشلها أو إلغاؤها يترك الحالة كما هي
fun requestPrivacyAuth(
    context: Context,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit
) {
    val activity = context.findActivity() ?: return onFailure("تعذّر فتح نافذة المصادقة")
    val mode = privacyAuthMode(activity)
    if (mode == PrivacyAuth.None) return onFailure("لا توجد بصمة ولا رقم سري على هذا الجهاز")

    // البصمة وحدها حين تتوفر، وإلا يُسمح برقم فتح الجهاز — بلا ذلك تفشل النافذة ولا تُفتح
    val mask = if (mode == PrivacyAuth.Fingerprint) FINGERPRINT_MASK else ANY_MASK
    val executor = ContextCompat.getMainExecutor(activity)
    try {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton("إلغاء", executor) { _, _ -> }
            .setAllowedAuthenticators(mask)
            .build()

        prompt.authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    // 5 إلغاء من النظام، 10 إلغاء المستخدم، 13 زر الإلغاء
                    if (errorCode == 5 || errorCode == 10 || errorCode == 13) return
                    val message = when (errorCode) {
                        7 -> "محاولات كثيرة خاطئة — انتظر 30 ثانية ثم حاول"
                        9 -> "المصادقة مقفلة مؤقتاً — أعد فتح الجهاز أولاً"
                        11, 12 -> "لا توجد وسيلة مصادقة متاحة على هذا الجهاز"
                        else -> "لم تكتمل المصادقة — حاول مرة أخرى"
                    }
                    onFailure(message)
                }
            }
        )
    } catch (e: Throwable) {
        onFailure("تعذّر فتح المصادقة على هذا الجهاز")
    }
}
