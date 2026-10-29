package com.bordy.app.license

sealed class LicenseState {
    object Loading : LicenseState()
    object NeedTos : LicenseState()                                    // لم يوافق على الشروط
    object NoLicense : LicenseState()                                  // لا رمز + لا إنترنت
    data class Trial(
        val daysLeft: Int,
        val showBanner: Boolean,
        val msLeft: Long = daysLeft * 86_400_000L
    ) : LicenseState()
    data class Active(val daysLeft: Int, val showReminder: Boolean) : LicenseState()
    data class Grace(
        val daysLeft: Int,
        val msLeft: Long = daysLeft * 86_400_000L
    ) : LicenseState()
    object Expired : LicenseState()                                    // قفل كامل
    data class Blocked(val reason: String?) : LicenseState()
    object ClockTamper : LicenseState()
    data class NeedUpdate(val minVersion: Int) : LicenseState()
}

/** Trials and grace periods can now be set in seconds, so days alone would round them to zero. */
fun formatRemaining(ms: Long): String {
    if (ms <= 0) return "0"
    val days = ms / 86_400_000L
    if (days >= 1) return "$days يوم"
    val hours = ms / 3_600_000L
    if (hours >= 1) return "$hours ساعة"
    val minutes = ms / 60_000L
    if (minutes >= 1) return "$minutes دقيقة"
    return "أقل من دقيقة"
}
