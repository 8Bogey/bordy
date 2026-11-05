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
    data class Active(
        val daysLeft: Int,
        val showReminder: Boolean,
        val msLeft: Long = daysLeft * 86_400_000L,
        val lifetime: Boolean = false
    ) : LicenseState()
    data class Grace(
        val daysLeft: Int,
        val msLeft: Long = daysLeft * 86_400_000L
    ) : LicenseState()
    object Expired : LicenseState()                                    // قفل كامل
    data class Blocked(val reason: String?) : LicenseState()
    object ClockTamper : LicenseState()
    data class NeedUpdate(val minVersion: String) : LicenseState()
}

/**
 * Trials and grace periods can now be set in seconds, so days alone would round them to zero.
 * Whole days alone would also under-report a fresh 7-day trial as "6 أيام", so the leftover
 * hours are shown alongside them.
 */
fun formatRemaining(ms: Long): String {
    if (ms <= 0) return "أقل من دقيقة"
    val days = ms / 86_400_000L
    if (days >= 1) {
        val dayPart = arabicUnit(days, NoticeUnit.DAYS)
        val hourPart = (ms % 86_400_000L) / 3_600_000L
        return if (hourPart >= 1) "$dayPart و${arabicUnit(hourPart, NoticeUnit.HOURS)}" else dayPart
    }
    val hours = ms / 3_600_000L
    if (hours >= 1) return arabicUnit(hours, NoticeUnit.HOURS)
    val minutes = ms / 60_000L
    if (minutes >= 1) return arabicUnit(minutes, NoticeUnit.MINUTES)
    return "أقل من دقيقة"
}

/** The owner picks the reminder granularity from the panel: days, hours or minutes. */
enum class NoticeUnit(val key: String) {
    DAYS("days"),
    HOURS("hours"),
    MINUTES("minutes");

    companion object {
        fun fromKey(raw: String?): NoticeUnit =
            entries.firstOrNull { it.key == raw } ?: DAYS
    }
}

/** What the server pushes on every sync; drives both the banner and the settings row. */
data class ExpiryNoticeConfig(
    val enabled: Boolean = true,
    val unit: NoticeUnit = NoticeUnit.DAYS,
    val value: Int = 3
) {
    /** How far before expiry the banner appears. */
    val thresholdMs: Long
        get() = when (unit) {
            NoticeUnit.DAYS -> value * 86_400_000L
            NoticeUnit.HOURS -> value * 3_600_000L
            NoticeUnit.MINUTES -> value * 60_000L
        }
}

private fun arabicUnit(count: Long, unit: NoticeUnit): String {
    val n = if (count < 1) 1L else count
    return when (unit) {
        NoticeUnit.DAYS -> when {
            n == 1L -> "يوم واحد"
            n == 2L -> "يومان"
            n in 3..10 -> "$n أيام"
            else -> "$n يوماً"
        }
        NoticeUnit.HOURS -> when {
            n == 1L -> "ساعة واحدة"
            n == 2L -> "ساعتان"
            n in 3..10 -> "$n ساعات"
            else -> "$n ساعة"
        }
        NoticeUnit.MINUTES -> when {
            n == 1L -> "دقيقة واحدة"
            n == 2L -> "دقيقتان"
            n in 3..10 -> "$n دقائق"
            else -> "$n دقيقة"
        }
    }
}

/**
 * Remaining time expressed in the unit the owner chose. Rounds up so the user never sees
 * "0 days" while a few hours are actually left.
 */
fun formatNoticeRemaining(msLeft: Long, unit: NoticeUnit): String {
    if (msLeft <= 0) return when (unit) {
        NoticeUnit.DAYS -> "أقل من يوم"
        NoticeUnit.HOURS -> "أقل من ساعة"
        NoticeUnit.MINUTES -> "أقل من دقيقة"
    }
    val count = when (unit) {
        NoticeUnit.DAYS -> (msLeft + 86_399_999L) / 86_400_000L
        NoticeUnit.HOURS -> (msLeft + 3_599_999L) / 3_600_000L
        NoticeUnit.MINUTES -> (msLeft + 59_999L) / 60_000L
    }
    return arabicUnit(count, unit)
}
