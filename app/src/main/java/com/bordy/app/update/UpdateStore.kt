package com.bordy.app.update

import android.content.Context
import java.io.File

/** أبعد عمر لملف تنزيل مؤقت (.part) يبقى بعده «بقايا» تستحق الحذف. */
private const val PART_MAX_AGE_MS = 6L * 60 * 60 * 1000

/**
 * كاش محلي لإعدادات التحديث القادمة من الخادم — تُقرأ فوراً عند فتح التطبيق
 * دون انتظار شبكة، فيقرر البوابة هل يظهر زر تثبيت جاهز أم لا.
 */
object UpdateStore {
    private const val PREFS = "bordy_update"

    private const val KEY_MIN = "min_version"
    private const val KEY_LATEST = "latest_version"
    private const val KEY_URL = "download_url"
    private const val KEY_NOTES = "notes"
    private const val KEY_HOURS = "check_hours"
    private const val KEY_INTERVAL_SECONDS = "check_interval_seconds"
    private const val KEY_WIFI = "wifi_only"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_APK_VERSION = "apk_version"
    private const val KEY_APK_PATH = "apk_path"
    private const val KEY_ROLLBACK_VERSION = "rollback_version"
    private const val KEY_ROLLBACK_FILE = "rollback_file"
    private const val KEY_TRIGGER_MODE = "trigger_mode"
    private const val KEY_TRIGGER_ID = "trigger_id"
    private const val KEY_TRIGGER_MSG = "trigger_message"
    private const val KEY_SEEN_TRIGGER = "seen_trigger_id"
    private const val KEY_NUDGED_TRIGGER = "nudged_trigger_id"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): UpdateConfig {
        val p = prefs(context)
        val storedInterval = p.getLong(KEY_INTERVAL_SECONDS, 0L)
        val intervalSeconds = if (storedInterval >= 5L) {
            storedInterval
        } else {
            // Upgrading from a build that only stored whole hours.
            p.getInt(KEY_HOURS, 12).coerceIn(1, 168) * 3600L
        }
        return UpdateConfig(
            minVersion = p.getString(KEY_MIN, "1.0") ?: "1.0",
            latestVersion = p.getString(KEY_LATEST, "1.0") ?: "1.0",
            downloadUrl = p.getString(KEY_URL, "") ?: "",
            notes = p.getString(KEY_NOTES, "") ?: "",
            checkIntervalSeconds = intervalSeconds.coerceIn(5L, 7L * 24 * 3600),
            wifiOnly = p.getBoolean(KEY_WIFI, true),
            triggerMode = p.getString(KEY_TRIGGER_MODE, UpdateConfig.TRIGGER_OFF) ?: UpdateConfig.TRIGGER_OFF,
            triggerId = p.getLong(KEY_TRIGGER_ID, 0L),
            triggerMessage = p.getString(KEY_TRIGGER_MSG, "") ?: "",
        )
    }

    fun saveConfig(context: Context, config: UpdateConfig, fetchedAt: Long) {
        prefs(context).edit()
            .putString(KEY_MIN, config.minVersion)
            .putString(KEY_LATEST, config.latestVersion)
            .putString(KEY_URL, config.downloadUrl)
            .putString(KEY_NOTES, config.notes)
            .putLong(KEY_INTERVAL_SECONDS, config.checkIntervalSeconds)
            .putBoolean(KEY_WIFI, config.wifiOnly)
            .putString(KEY_TRIGGER_MODE, config.triggerMode)
            .putLong(KEY_TRIGGER_ID, config.triggerId)
            .putString(KEY_TRIGGER_MSG, config.triggerMessage)
            .putLong(KEY_FETCHED_AT, fetchedAt)
            .apply()
    }

    /**
     * التنبيه قد يصل مع إعدادات كاملة أو مع استطلاع الترخيص، لذا يُكتب وحده
     * دون العبث ببقية الكاش.
     */
    fun saveTrigger(context: Context, mode: String, id: Long, message: String) {
        prefs(context).edit()
            .putString(KEY_TRIGGER_MODE, mode)
            .putLong(KEY_TRIGGER_ID, id)
            .putString(KEY_TRIGGER_MSG, message)
            .apply()
    }

    fun saveLatest(context: Context, version: String) {
        prefs(context).edit().putString(KEY_LATEST, version).apply()
    }

    fun seenTriggerId(context: Context): Long = prefs(context).getLong(KEY_SEEN_TRIGGER, 0L)

    fun markTriggerSeen(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_SEEN_TRIGGER, id).apply()
    }

    fun lastNudgedTrigger(context: Context): Long = prefs(context).getLong(KEY_NUDGED_TRIGGER, 0L)

    fun markTriggerNudged(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_NUDGED_TRIGGER, id).apply()
    }

    fun lastFetchAt(context: Context): Long = prefs(context).getLong(KEY_FETCHED_AT, 0L)

    // ── ملف APK المحمَّل مسبقاً ────────────────────────────────────────────────

    /**
     * ملفات التحديث في filesDir لا cacheDir: النظام يفرغ الكاش بصمت عند الضغط،
     * فيختفي الملف الذي يُفترض أن يُثبَّت بضغطة واحدة لحظة الضغط عليه.
     */
    fun updatesDir(context: Context): File = File(context.filesDir, "updates").apply { mkdirs() }

    /** ملف الإصدار إن وُجد وسليم (غير صفري)، وإلا null. */
    fun readyApk(context: Context, version: String): File? {
        val p = prefs(context)
        val savedVersion = p.getString(KEY_APK_VERSION, "") ?: ""
        val path = p.getString(KEY_APK_PATH, "") ?: ""
        if (savedVersion != version || path.isEmpty()) return null
        val file = File(path)
        // مسار من إصدار التطبيق السابق (مجلد الكاش) لا يُعد جاهزاً: لم يتحقق أحد من توقيعه
        if (file.parentFile != updatesDir(context)) return null
        return if (file.isFile && file.length() > 0L) file else null
    }

    fun saveReadyApk(context: Context, version: String, file: File) {
        prefs(context).edit()
            .putString(KEY_APK_VERSION, version)
            .putString(KEY_APK_PATH, file.absolutePath)
            .apply()
    }

    fun clearReadyApk(context: Context) {
        prefs(context).edit().remove(KEY_APK_VERSION).remove(KEY_APK_PATH).apply()
        updatesDir(context).listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    /** يحذف ملفات الإصدارات القديمة التي لم تعد مطلوبة. */
    fun pruneStaleApks(context: Context, keepVersion: String?) {
        // بقايا 1.2 وأقدم كانت داخل cacheDir وتُركت بلا حذف بعد نقل الملفات
        File(context.cacheDir, "updates").deleteRecursively()
        val keepPath = keepVersion?.let { readyApk(context, it)?.absolutePath }
        val now = System.currentTimeMillis()
        updatesDir(context).listFiles()?.forEach { f ->
            when {
                f.absolutePath == keepPath -> Unit
                // تنزيل جارٍ في الخلفية: لا تقطعه، واحذف ما تعفّن من بقاياه
                f.name.endsWith(".part") -> if (now - f.lastModified() > PART_MAX_AGE_MS) f.delete()
                else -> f.delete()
            }
        }
    }

    // ── نسخة الرجوع (rollback) — تبقى في مجلد التنزيلات العام حتى بعد الحذف ─────

    fun saveRollbackCopy(context: Context, version: String, displayName: String) {
        prefs(context).edit()
            .putString(KEY_ROLLBACK_VERSION, version)
            .putString(KEY_ROLLBACK_FILE, displayName)
            .apply()
    }

    fun rollbackCopy(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val v = p.getString(KEY_ROLLBACK_VERSION, "") ?: ""
        val f = p.getString(KEY_ROLLBACK_FILE, "") ?: ""
        return if (v.isNotEmpty() && f.isNotEmpty()) v to f else null
    }

    fun clearRollbackCopy(context: Context) {
        prefs(context).edit().remove(KEY_ROLLBACK_VERSION).remove(KEY_ROLLBACK_FILE).apply()
    }
}
