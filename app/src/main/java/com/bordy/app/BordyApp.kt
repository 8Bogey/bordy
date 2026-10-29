package com.bordy.app

import android.app.Application
import com.bordy.app.data.AppDatabase
import com.bordy.app.license.LicenseManager
import com.bordy.app.license.NetworkWatcher
import com.bordy.app.sync.SyncEngine
import com.bordy.app.sync.SyncScheduler

class BordyApp : Application() {
    val db by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        // Load the licence before any UI exists: WorkManager can start this process headless.
        LicenseManager.init(this)
        NetworkWatcher.start(this)
        SyncEngine.init(this)
        SyncScheduler.start(this)
    }
}
