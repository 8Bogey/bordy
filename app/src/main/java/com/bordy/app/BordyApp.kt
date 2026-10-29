package com.bordy.app

import android.app.Application
import com.bordy.app.data.AppDatabase
import com.bordy.app.license.LicenseManager
import com.bordy.app.license.NetworkWatcher
import com.bordy.app.sync.SyncEngine
import com.bordy.app.sync.SyncScheduler
import io.sentry.android.core.SentryAndroid

class BordyApp : Application() {
    val db by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        initCrashReporting()
        // Load the licence before any UI exists: WorkManager can start this process headless.
        LicenseManager.init(this)
        NetworkWatcher.start(this)
        SyncEngine.init(this)
        SyncScheduler.start(this)
    }

    // لا بيانات شخصية ولا تتبّع أداء؛ يُفعَّل فقط عند وجود DSN
    private fun initCrashReporting() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.isSendDefaultPii = false
            options.tracesSampleRate = 0.0
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
        }
    }
}
