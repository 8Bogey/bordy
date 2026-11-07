package com.bordy.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * جدولة التحقق الدوري من التحديثات بالوتيرة التي يختارها المالك (ساعات/دقائق/ثوانٍ).
 *
 * WorkManager لا يقبل فترات دورية أقل من 15 دقيقة، لذلك:
 *  - الفترة >= 15 دقيقة: عمل دوري حقيقي يديره النظام في نوافذ الصيانة (أخف على البطارية).
 *  - الفترة < 15 دقيقة: سلسلة عملٍ فردي يعيد تسجيل نفسه بعد كل تشغيل — للاختبار
 *    وللحالات التي يريد فيها المالك استجابة سريعة، مع تنبيه صريح في اللوحة.
 * الجدولة لا تنتقل من مسار إلى الآخر بمفردها: كل تسليح يُلغي العمل الآخر في
 * المسار الذي لم يعد مستخدماً، وإلا بقي الفحص القديم يعمل بالوتيرة القديمة إلى
 * جانب الجديد.
 *
 * في سلسلة الفواصل القصيرة يبقى دائماً تشغيلة واحدة معلّقة فقط (REPLACE): أي
 * تسليح أو تمدّد يُلغي ما هو معلَّق ويستبدله، فلا تتكاثر السلاسل ولا يتضاعف
 * استهلاك البيانات.
 */
object UpdateScheduler {
    private const val PERIODIC_WORK = "bordy.update.periodic"
    private const val LOOP_WORK = "bordy.update.loop"
    private const val NUDGE_WORK = "bordy.update.nudge"

    private const val PREFS = "bordy_update_sched"
    private const val KEY_ARMED_SECONDS = "armed_seconds"

    /** حد WorkManager الأدنى للعمل الدوري. */
    private const val MIN_PERIODIC_SECONDS = 15L * 60
    private const val MIN_INTERVAL_SECONDS = 5L
    private const val MAX_INTERVAL_SECONDS = 7L * 24 * 3600

    internal const val KEY_LOOP_TICK = "loop_tick"

    /** تشغيلة فورية بطلب من المالك: وحدها التي تستحق `Result.retry()` على الفشل. */
    internal const val KEY_IMMEDIATE = "immediate"

    private val networkOnly = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun armedSeconds(context: Context): Long = prefs(context).getLong(KEY_ARMED_SECONDS, 0L)

    fun intervalSeconds(context: Context): Long =
        UpdateStore.loadConfig(context).checkIntervalSeconds
            .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

    /** يعيد التسليح عند فتح التطبيق أو تغيّر الفترة؛ آمن التكرار. */
    fun ensurePeriodic(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val seconds = intervalSeconds(app)
        val wm = WorkManager.getInstance(app)
        // الفترة المخزّنة لا تطابق المسجَّل: تغيير اللوحة وصل أثناء انقطاع أو
        // الجدولة أُلغيت، فلا يكفي KEEP وإلا بقي الفحص بوتيرة قديمة.
        val replace = force || armedSeconds(app) != seconds

        if (seconds >= MIN_PERIODIC_SECONDS) {
            wm.cancelUniqueWork(LOOP_WORK)
            val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, policy, periodicRequest(seconds))
        } else {
            wm.cancelUniqueWork(PERIODIC_WORK)
            // KEEP يكفي عند فتح التطبيق والسلسلة قائمة بوتيرتها الصحيحة؛
            // الاستبدال يترك تشغيلة معلّقة واحدة لا ذيلين متراكمين.
            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            wm.enqueueUniqueWork(LOOP_WORK, policy, loopRequest(seconds))
        }
        prefs(app).edit().putLong(KEY_ARMED_SECONDS, seconds).apply()
    }

    /**
     * يُستدعى في نهاية تشغيلٍ من سلسلة الفواصل القصيرة فقط: تمدّ نفسها،
     * أو تسلّم الجدولة للعمل الدوري إذا غيّر المالك الفترة إلى 15 دقيقة فأكثر.
     * التشغيلات الفورية (زر «تنزيل الآن» أو التنبيه) لا تستدعيها حتى لا تُصفَّر
     * ساعة العدّاد الدوري.
     *
     * REPLACE — لا APPEND: إلحاق تشغيلة بذيل السلسلة يبقي ذيلين معلّقين متى
     * أعاد النظام تشغيل العمل أو متى تسليح متزامن من فتح التطبيق، فتتضاعف
     * السلاسل مع كل دورة. الاستبدال يترك تشغيلة واحدة دائماً.
     */
    fun afterRun(context: Context) {
        val app = context.applicationContext
        val seconds = intervalSeconds(app)
        val wm = WorkManager.getInstance(app)

        if (seconds >= MIN_PERIODIC_SECONDS) {
            wm.cancelUniqueWork(LOOP_WORK)
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(seconds))
        } else {
            wm.cancelUniqueWork(PERIODIC_WORK)
            wm.enqueueUniqueWork(LOOP_WORK, ExistingWorkPolicy.REPLACE, loopRequest(seconds))
        }
        prefs(app).edit().putLong(KEY_ARMED_SECONDS, seconds).apply()
    }

    /**
     * تحقق فوري واحد (فتح التطبيق أو طلب المالك). بلا setExpedited: التشغيل
     * المستعجل يعتمد خدمة في المقدمة بإشعار تقدم، والمطلوب تنزيل صامت تماماً.
     * APPEND_OR_REPLACE بدل REPLACE حتى لا يُلغي تنزيلاً جارياً عند تكرار الطلب.
     */
    fun nudge(context: Context) {
        val app = context.applicationContext
        WorkManager.getInstance(app).enqueueUniqueWork(
            NUDGE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(networkOnly)
                .setInputData(workDataOf(KEY_IMMEDIATE to true))
                .build(),
        )
    }

    /** لا حاجة لمراقبة التحديث (لا ترخيص على الجهاز) — يوفّر البطارية. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork(PERIODIC_WORK)
        wm.cancelUniqueWork(LOOP_WORK)
        wm.cancelUniqueWork(NUDGE_WORK)
        prefs(app).edit().remove(KEY_ARMED_SECONDS).apply()
    }

    private fun periodicRequest(seconds: Long) =
        PeriodicWorkRequestBuilder<UpdateWorker>(seconds, TimeUnit.SECONDS)
            .setConstraints(networkOnly)
            .build()

    /**
     * بلا قيد شبكة: القيد يمرّ التشغيلة عاملاً «تتبّع قيود» أولاً، وإن لم تكن
     * الشبكة متاحة لحظة الفتح أعاد الجدولة بنافذة تتبّع لا تقل عن 15 دقيقة —
     * وهو ما يُلغي معنى فترة الثواني/الدقائق التي طلبها المالك. الجلب نفسه يتحمّل
     * انقطاع الشبكة ويعيد المحاولة في التشغيلة التالية.
     */
    private fun loopRequest(seconds: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInitialDelay(seconds, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_LOOP_TICK to true))
            .build()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
