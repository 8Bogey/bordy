package com.bordy.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SyncEngine.isLoggedIn()) return Result.success()
        return when (SyncEngine.syncNow()) {
            is SyncOutcome.Ok -> Result.success()
            is SyncOutcome.Offline -> Result.retry()
            is SyncOutcome.NeedsLogin -> Result.success()
            else -> Result.success()
        }
    }
}

object SyncScheduler {

    private const val PERIODIC = "bordy-sync-periodic"
    private const val IMMEDIATE = "bordy-sync-now"

    private val online = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun start(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
        )
    }

    fun syncSoon(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .build()
        )
    }
}
