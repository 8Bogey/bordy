package com.bordy.app.license

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the silent watcher after the events that can invalidate our locally cached state:
 * a reboot, an app update (a new signing key or version floor), and a manual clock change
 * (the clock guard must be re-evaluated and the server time re-confirmed right away).
 */
class LicenseWatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                LicenseSyncScheduler.ensurePeriodic(context, force = true)
                LicenseSyncScheduler.nudge(context, expedited = true)
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                LicenseManager.revalidateLocal(context)
                LicenseSyncScheduler.nudge(context, expedited = true)
            }
        }
    }
}
