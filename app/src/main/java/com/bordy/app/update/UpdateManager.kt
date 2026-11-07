package com.bordy.app.update

import android.app.NotificationManager
import android.content.Context
import com.bordy.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface UpdateUi {
    data object None : UpdateUi

    /**
     * تحديث لازم (أقدم من الحد الأدنى). `apk` غير قابل لـ null عمداً: لا تُعرض
     * الشاشة قبل اكتمال التنزيل والتحقق في الخفاء، فزر «تثبيت» يفعّل فوراً.
     */
    data class Forced(val version: String, val message: String, val apk: File) : UpdateUi

    /** الإدارة رجّعت «أحدث إصدار» إلى نسخة أقدم — يلزم حذف وإعادة تثبيت. */
    data class RollbackRequired(val version: String, val notes: String, val copiedFile: String?) : UpdateUi
}

/**
 * طلب المالك المباشر («أطلق التنبيه الآن»): اختياري = نافذة بزر «لاحقاً»،
 * إجباري = شاشة حاجزة. لا يظهر إلا بعد تنزيل الملف والتحقق من توقيعه، فلا يرى
 * المستخدم أي انتظار. للمتأخرين عن أحدث إصدار فقط، ولا يمس الجدول الدوري.
 */
data class UpdatePrompt(
    val version: String,
    val message: String,
    val forced: Boolean,
    val triggerId: Long,
    val apk: File,
)

/** أقل عمر للكاش يبرّر جلباً جديداً عند كل فتح — يمنع استطلاعاً بلا طائل. */
private const val FOREGROUND_REFRESH_MS = 60_000L

/** معرّف قناة الإشعارات التي أنشأها بناء 1.2 في مسار التحديث القديم. */
private const val LEGACY_UPDATE_CHANNEL = "bordy_update"

/**
 * حالة البوابة تُشتق من الكاش المحلي فوراً (بلا شبكة) وتتحدث عند كل تحقق خلفي.
 */
object UpdateManager {
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.None)
    val state: StateFlow<UpdateUi> = _state

    private val _prompt = MutableStateFlow<UpdatePrompt?>(null)
    val prompt: StateFlow<UpdatePrompt?> = _prompt

    private var app: Context? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        app = appContext
        clearLegacyNotificationChannel(appContext)
        evaluate()
    }

    /** قناة «تحديث التطبيق» من بناء 1.2: التحديث صار صامتاً فلا موضع لها في الإعدادات. */
    private fun clearLegacyNotificationChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.deleteNotificationChannel(LEGACY_UPDATE_CHANNEL)
    }

    /**
     * يُستدعى في onResume: يعيد قراءة الكاش فوراً، ثم يجلب الإعدادات إن كانت
     * قديمة — لأن رفع «أقل إصدار مسموح» يجب أن يظهر عند أول فتح لا بعد ساعات
     * من انتظار العامل الدوري. إعادة التسليح هنا حارس لا تصفير: الوتيرة المسجَّلة
     * تُحترم، ولا يُمسّ عداد الفحص الدوري ما لم تكن قديمة.
     */
    fun onAppForeground(context: Context) {
        val appContext = context.applicationContext
        evaluate()
        // حارس للجدولة: النظام يلغي أعمال التطبيق (تحديث من المتجر، force stop،
        // توفير البطارية) ولا شيء يعيد التسليح غير فتح التطبيق. لا يلمس السلسلة
        // إن كانت مسلّحة بالوتيرة نفسها.
        UpdateScheduler.ensurePeriodic(appContext)
        val cacheAge = System.currentTimeMillis() - UpdateStore.lastFetchAt(appContext)
        if (cacheAge > FOREGROUND_REFRESH_MS) UpdateScheduler.nudge(appContext)
    }

    /**
     * يستدعيه العامل الخلفي بعد جلب الإعدادات. `rearm=false` في تشغيلات السلسلة:
     * إعادة التسليح من داخل التشغيلة يلغيها وهي قائمة، فتضيع الدورة الحالية؛
     * `afterRun` يتولى الانتقال إلى الوتيرة الجديدة بعد الجلب مباشرة.
     */
    fun onConfigFetched(context: Context, config: UpdateConfig, rearm: Boolean = true) {
        val appContext = context.applicationContext
        val previous = UpdateStore.loadConfig(appContext)
        UpdateStore.saveConfig(appContext, config, System.currentTimeMillis())
        // تغيّر الفترة من اللوحة وحده يستدعي إعادة التسليح — لا شيء هنا يُلغي الفحص الدوري.
        if (rearm && previous.checkIntervalSeconds != config.checkIntervalSeconds) {
            UpdateScheduler.ensurePeriodic(appContext, force = true)
        }
        evaluate()
    }

    /**
     * التنبيه يصل مع استطلاع الترخيص، فيستجيب التطبيق خلال ثوانٍ بدل انتظار
     * الفحص الدوري. إن كان الملف غير مُحمَّل يُطلق تنزيلاً صامتاً مرة واحدة لكل
     * طلقة؛ التنبيه نفسه لا يظهر قبل اكتماله.
     */
    fun onSyncTrigger(
        context: Context,
        mode: String,
        id: Long,
        message: String,
        latestVersion: String,
    ) {
        val appContext = context.applicationContext
        val config = UpdateStore.loadConfig(appContext)

        if (mode == UpdateConfig.TRIGGER_OFF) {
            if (!config.triggerActive()) return
            UpdateStore.saveTrigger(appContext, UpdateConfig.TRIGGER_OFF, config.triggerId, "")
            _prompt.value = null
            return
        }

        val saneMode = if (mode == UpdateConfig.TRIGGER_FORCED || mode == UpdateConfig.TRIGGER_OPTIONAL) {
            mode
        } else {
            UpdateConfig.TRIGGER_OFF
        }
        // خذ الأحدث بين ما نعرفه: تنبيه لا يجب أن يُنزل «أحدث إصدار» المخزّن.
        val latest = if (latestVersion.isNotBlank() && compareVersions(latestVersion, config.latestVersion) > 0) {
            latestVersion
        } else {
            config.latestVersion
        }

        UpdateStore.saveTrigger(appContext, saneMode, id, message)
        if (latest != config.latestVersion) UpdateStore.saveLatest(appContext, latest)

        val behind = compareVersions(BuildConfig.VERSION_NAME, latest) < 0
        if (behind) {
            if (UpdateStore.readyApk(appContext, latest) == null &&
                UpdateStore.lastNudgedTrigger(appContext) != id
            ) {
                UpdateStore.markTriggerNudged(appContext, id)
                UpdateScheduler.nudge(appContext)
            }
        } else if (latestVersion.isBlank()) {
            // تنبيه بلا رقم إصدار والكاش يقول «أنت الأحدث» = كاش قديم؛ اسأل الخادم
            // بدل الصمت، وإلا يمرّ طلب المالك بلا أي شاشة.
            UpdateScheduler.nudge(appContext)
        }
        // evaluate() يقرر التنبيه بعد اكتمال التنزيل.
        evaluate()
    }

    /** «لاحقاً» في التنبيه الاختياري: لا يُعيد الظهور حتى يطلق المالك تنبيهًا جديداً. */
    fun dismissPrompt() {
        val prompt = _prompt.value ?: return
        if (prompt.forced) return
        app?.let { UpdateStore.markTriggerSeen(it, prompt.triggerId) }
        _prompt.value = null
    }

    private fun refreshTrigger(context: Context, config: UpdateConfig) {
        val current = BuildConfig.VERSION_NAME
        val apk = UpdateStore.readyApk(context, config.latestVersion)
        val active = config.triggerActive() && config.behindLatest(current) && apk != null
        // شاشة التحديث الإجباري أو الرجوع أعلى مرتبةً من التنبيه.
        if (!active || _state.value != UpdateUi.None) {
            _prompt.value = null
            return
        }
        // الاختياري المُجاب عنه «لاحقاً» لا يعود حتى تأتي طلقة جديدة بطابع جديد.
        if (!config.triggerForced() && UpdateStore.seenTriggerId(context) == config.triggerId) {
            _prompt.value = null
            return
        }
        _prompt.value = UpdatePrompt(
            version = config.latestVersion,
            message = config.triggerMessage,
            forced = config.triggerForced(),
            triggerId = config.triggerId,
            apk = apk,
        )
    }

    fun evaluate() {
        val context = app ?: return
        val config = UpdateStore.loadConfig(context)
        val current = BuildConfig.VERSION_NAME
        val ready = UpdateStore.readyApk(context, config.latestVersion)

        _state.value = when {
            config.updateRequired(current) && ready != null -> UpdateUi.Forced(
                config.latestVersion,
                config.notes.ifBlank {
                    "يجب تحديث التطبيق إلى الإصدار ${config.latestVersion} لمتابعة العمل."
                },
                ready,
            )

            config.rollbackRequired(current) -> UpdateUi.RollbackRequired(
                config.latestVersion,
                config.notes,
                UpdateStore.rollbackCopy(context)?.second,
            )

            else -> UpdateUi.None
        }

        // الملف المحمَّل ليس «بقايا» ما دام الجهاز يحتاج الإصدار الأحدث (تنزيل صامت)
        UpdateStore.pruneStaleApks(context, if (config.prefetchNeeded(current)) config.latestVersion else null)
        refreshTrigger(context, config)
    }
}
