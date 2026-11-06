package com.bordy.app.update

/**
 * نسخ الإصدارات تُقارَن رقمياً بالمقاطع: "1" == "1.0" و"1.9" أقدم من "1.10".
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.trim().split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

data class UpdateConfig(
    val minVersion: String = "1.0",
    val latestVersion: String = "1.0",
    val downloadUrl: String = "",
    val notes: String = "",
    /** فترة التحقق بالثواني — يديرها المالك من اللوحة (ساعات/دقائق/ثوانٍ). */
    val checkIntervalSeconds: Long = 12 * 3600L,
    val wifiOnly: Boolean = true,
    /** تنبيه فوري يطلقه المالك: off / optional / forced — لا يمس الفحص الدوري. */
    val triggerMode: String = TRIGGER_OFF,
    /** طابع زمني للطلقتها: يتغير فيعرف الجهاز أنه تنبيه جديد. */
    val triggerId: Long = 0L,
    val triggerMessage: String = "",
) {
    /** أقدم من الحد الأدنى = يجب التحديث فوراً. */
    fun updateRequired(current: String): Boolean = compareVersions(current, minVersion) < 0

    /** أحدث من «الأحدث» المعلن = طلب رجوع لنسخة أقدم (rollback من الإدارة). */
    fun rollbackRequired(current: String): Boolean = compareVersions(current, latestVersion) > 0

    /** أبطأ من أحدث إصدار = يوجد ما يُطلب تحديثه (التنبيه لا يظهر لغير المتأخرين). */
    fun behindLatest(current: String): Boolean = compareVersions(current, latestVersion) < 0

    fun triggerActive(): Boolean = triggerMode != TRIGGER_OFF

    fun triggerForced(): Boolean = triggerMode == TRIGGER_FORCED

    /**
     * من يستحق ملفاً في الخفاء: الحد الأدنى مفروض، أو رجوع مطلوب، أو مجرد التأخر
     * عن أحدث إصدار. أوسع من شروط ظهور التنبيه عمداً — التنبيه لا يظهر إلا وملفه
     * مُحمَّل ومُتحقَّق منه، فلا ينتظر أحد.
     */
    fun prefetchNeeded(current: String): Boolean =
        behindLatest(current) || updateRequired(current) || rollbackRequired(current)

    companion object {
        const val TRIGGER_OFF = "off"
        const val TRIGGER_OPTIONAL = "optional"
        const val TRIGGER_FORCED = "forced"
    }
}
