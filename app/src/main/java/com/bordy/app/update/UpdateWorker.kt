package com.bordy.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bordy.app.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * دورة واحدة صامتة بالكامل: جلب إعدادات التحديث ثم تنزيل الـ APK في الخلفية بلا
 * إشعار ولا شريط تقدم ولا خدمة في المقدمة. لا تظهر أي شاشة تحديث إلا بعد اكتمال
 * التنزيل والتحقق من التوقيع، فلا ينتظر المستخدم أبداً.
 *
 * التشغيلات المجدولة (الدورية وسلسلة الفواصل القصيرة) لا تعيد المحاولة أبداً:
 * `Result.retry()` يستسلم لسياسة backoff فيؤجّل الفحص التالي ساعات بدل أن
 * ينتظر دوره الطبيعي، والتشغيلة القادمة كفيلة بالعمل. إعادة المحاولة فقط
 * لتشغيلة المالك الفورية (nudge) لأنها لا تملك دوراً تالياً.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val chainedTick: Boolean
        get() = inputData.getBoolean(UpdateScheduler.KEY_LOOP_TICK, false)

    private val immediate: Boolean
        get() = inputData.getBoolean(UpdateScheduler.KEY_IMMEDIATE, false)

    private val retryAllowed: Boolean
        get() = immediate && runAttemptCount < 2

    override suspend fun doWork(): Result {
        // إن شذّت أي خطوة فالسلسلة يجب أن تتمّد رغم ذلك، وإلا مات الفحص الدوري
        // نهائياً ولا شيء يعيد تسليحه غير فتح التطبيق.
        val result = try {
            runOnce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.success()
        }
        // سلسلة الفواصل القصيرة تمدّ نفسها فقط؛ التشغيلات الفورية (تنبيه/زر) لا تلمس الجدولة.
        if (chainedTick && !isStopped) UpdateScheduler.afterRun(applicationContext)
        return result
    }

    private suspend fun runOnce(): Result {
        if (isStopped) return Result.success()
        val context = applicationContext
        val config = UpdateApi.fetchConfig()
            ?: return if (retryAllowed) Result.retry() else Result.success()

        UpdateManager.onConfigFetched(context, config, rearm = !chainedTick)
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
            return if (retryAllowed) Result.retry() else Result.success()
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
