package com.bordy.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bordy.app.BuildConfig
import java.io.File

/**
 * دورة واحدة صامتة بالكامل: جلب إعدادات التحديث ثم تنزيل الـ APK في الخلفية بلا
 * إشعار ولا شريط تقدم ولا خدمة في المقدمة. لا تظهر أي شاشة تحديث إلا بعد اكتمال
 * التنزيل والتحقق من التوقيع، فلا ينتظر المستخدم أبداً.
 * تشغيلات السلسلة القصيرة (أقل من 15 دقيقة) لا تعيد المحاولة: التشغيلة التالية
 * كفيلة بذلك، فلا تتراكم الأعمال ولا تُستهلك البيانات بلا داعٍ.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val chainedTick: Boolean
        get() = inputData.getBoolean(UpdateScheduler.KEY_LOOP_TICK, false)

    override suspend fun doWork(): Result {
        val result = runOnce()
        // سلسلة الفواصل القصيرة تمدّ نفسها فقط؛ التشغيلات الفورية (تنبيه/زر) لا تلمس الجدولة.
        if (chainedTick) UpdateScheduler.afterRun(applicationContext)
        return result
    }

    private suspend fun runOnce(): Result {
        val context = applicationContext
        val config = UpdateApi.fetchConfig()
            ?: return if (!chainedTick && runAttemptCount < 2) Result.retry() else Result.success()

        UpdateManager.onConfigFetched(context, config)
        val current = BuildConfig.VERSION_NAME

        // كل متأخر عن «أحدث إصدار» يحصل على ملفه في الخفاء فوراً، فيأتي التنبيه
        // (أو الحد الأدنى) والملف جاهز أصلاً لضغطة واحدة.
        if (!config.prefetchNeeded(current)) {
            UpdateStore.clearReadyApk(context)
            UpdateManager.evaluate()
            return Result.success()
        }

        val target = config.latestVersion
        val existing = UpdateStore.readyApk(context, target)
        if (existing != null) {
            if (config.rollbackRequired(current)) ensureRollbackCopy(context, target, existing)
            UpdateManager.evaluate()
            return Result.success()
        }

        val url = config.downloadUrl
        if (url.isBlank()) {
            // الإدارة لم ترفع ملفاً بعد — لا شاشة ولا إعادة محاولة عنيفة.
            UpdateManager.evaluate()
            return Result.success()
        }

        // احترام باقة المستخدم: التنزيل على Wi‑Fi فقط ما لم تعطّلها الإدارة.
        if (config.wifiOnly && !ApkInstaller.onWifiOrEthernet(context)) {
            UpdateManager.evaluate()
            return Result.success()
        }

        val dest = File(UpdateStore.updatesDir(context), "bordy-$target.apk")
        if (!UpdateApi.downloadApk(url, dest)) {
            UpdateManager.evaluate()
            return if (!chainedTick && runAttemptCount < 2) Result.retry() else Result.success()
        }

        // ملف بتوقيع غير توقيعنا أو بغير الإصدار المطلوب = تنزيل مغشوش: يُحذف ولا
        // يُعرض كتثبيت أبداً، ولا يُعاد معه جدولة عدّاء.
        if (!ApkIntegrity.verify(context, dest, target)) {
            dest.delete()
            UpdateManager.evaluate()
            return Result.success()
        }

        UpdateStore.saveReadyApk(context, target, dest)
        if (config.rollbackRequired(current)) ensureRollbackCopy(context, target, dest)
        UpdateManager.evaluate()
        return Result.success()
    }

    /**
     * نسخة الرجوع يجب أن تبقى بعد حذف التطبيق، لذا تُنسخ إلى مجلد التنزيلات العام
     * قبل أن يضغط المستخدم «حذف».
     */
    private fun ensureRollbackCopy(context: Context, version: String, apk: File) {
        val saved = UpdateStore.rollbackCopy(context)
        if (saved != null && saved.first == version) return
        val displayName = "bordy-$version.apk"
        if (ApkInstaller.copyToPublicDownloads(context, apk, displayName)) {
            UpdateStore.saveRollbackCopy(context, version, displayName)
        }
    }
}
