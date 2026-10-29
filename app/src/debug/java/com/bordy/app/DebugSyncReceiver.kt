package com.bordy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bordy.app.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** أداة اختبار للنسخة التجريبية فقط: تشغيل الدخول والمزامنة عبر adb دون لمس الواجهة. */
class DebugSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_LOGIN -> {
                        val username = intent.getStringExtra("username").orEmpty()
                        val password = intent.getStringExtra("password").orEmpty()
                        Log.d("SYNCDBG", "debug login -> " + SyncEngine.login(username, password))
                    }
                    else -> Log.d("SYNCDBG", "debug sync -> " + SyncEngine.syncNow())
                }
            } catch (e: Exception) {
                Log.d("SYNCDBG", "debug receiver failed: " + e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOGIN = "com.bordy.app.DEBUG_LOGIN"
        const val ACTION_SYNC = "com.bordy.app.DEBUG_SYNC"
    }
}
