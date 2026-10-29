package com.bordy.app.license

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Silent licence watching.
 *
 * One periodic poll (WorkManager refuses anything under 15 minutes, and batches it into the
 * system's maintenance windows) plus short one-shot nudges for boot, app updates and clock
 * changes. Nothing here holds a wake lock, runs a foreground service or posts a notification:
 * when there is no network the work simply waits for the CONNECTED constraint to be met.
 */
object LicenseSyncScheduler {

    private const val PERIODIC_WORK = "bordy.license.periodic"
    private const val NUDGE_WORK = "bordy.license.nudge"
    private const val PREFS = "bordy_license_sync"
    private const val KEY_ARMED_MINUTES = "armed_minutes"

    /** WorkManager's own floor; the owner's cadence can be higher but never lower. */
    private const val OS_MIN_MINUTES = 15L

    private val networkOnly = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun intervalMinutes(context: Context): Long =
        (LicenseStore.getSyncInterval(context) / 60L).coerceAtLeast(OS_MIN_MINUTES)

    /**
     * Idempotent. Re-arms with the owner's cadence when it changed, otherwise keeps the existing
     * schedule (KEEP also heals a schedule the system dropped after a force-stop).
     */
    fun ensurePeriodic(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val minutes = intervalMinutes(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val armed = prefs.getLong(KEY_ARMED_MINUTES, 0L)

        val changed = force || armed != minutes
        val request = PeriodicWorkRequestBuilder<LicenseSyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(networkOnly)
            // A failed attempt must not turn into a retry storm on a metered connection.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val wm = WorkManager.getInstance(app)
        if (changed) {
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            prefs.edit().putLong(KEY_ARMED_MINUTES, minutes).apply()
        } else {
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    /** Immediate single check; safe to call repeatedly (REPLACE collapses duplicates). */
    fun nudge(context: Context, expedited: Boolean = false) {
        val app = context.applicationContext
        val builder = OneTimeWorkRequestBuilder<LicenseSyncWorker>()
            .setConstraints(networkOnly)
        if (expedited) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(app)
            .enqueueUniqueWork(NUDGE_WORK, ExistingWorkPolicy.REPLACE, builder.build())
    }

    /** No licence on this device (or TOS not accepted) — polling would only burn battery. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork(PERIODIC_WORK)
        wm.cancelUniqueWork(NUDGE_WORK)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ARMED_MINUTES)
            .apply()
    }
}

/**
 * One bounded round-trip. The periodic schedule already guarantees the next attempt, so this
 * worker only asks for a retry when the attempt failed for a reason that may pass shortly.
 */
class LicenseSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val stored = LicenseStore.load(context) ?: return Result.success()
        if (!stored.tosAccepted || stored.token.isBlank() || stored.token == "DEV_TEST_TOKEN") {
            return Result.success()
        }
        if (!LicenseApi.isOnline(context)) return Result.success()

        if (LicenseManager.syncFromBackground(context)) return Result.success()
        // The periodic slot will cover a slowly recovering network; retry once for a blip.
        return if (runAttemptCount < 1) Result.retry() else Result.success()
    }
}
