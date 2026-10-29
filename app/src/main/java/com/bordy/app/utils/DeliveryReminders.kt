package com.bordy.app.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bordy.app.MainActivity
import com.bordy.app.R
import com.bordy.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// تنبيهان لكل بورد قيد العمل: قبل التسليم بأيام وقبل التسليم بساعات، بلا صلاحية منظّم دقيق
object DeliveryReminders {
    const val CHANNEL_ID = "delivery_reminders"
    private const val PREFS = "delivery_reminders"
    private const val KEY_CODES = "scheduled_codes"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HOUR_MS = 60L * 60 * 1000
    private const val EXTRA_PROJECT_ID = "projectId"
    private const val EXTRA_KIND = "kind"
    private const val KIND_DAYS = 1
    private const val KIND_HOURS = 2

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "تذكير بمواعيد التسليم",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "تنبيه قبل موعد تسليم البوردات"
            }
        )
    }

    suspend fun reschedule(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        ensureChannel(app)
        val db = AppDatabase.get(app)
        val settings = db.settingsDao().getOnce()
        val projects = db.projectDao().getAllOnce()
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_CODES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val scheduled = mutableSetOf<String>()

        if (alarmManager != null && settings != null) {
            val now = System.currentTimeMillis()
            for (p in projects) {
                val due = p.dueDate ?: continue
                if (p.status != "ACTIVE" || p.completedAt != null) continue

                if (settings.reminderDaysBefore > 0) {
                    val code = requestCode(p.id, KIND_DAYS)
                    schedule(app, alarmManager, p.id, KIND_DAYS, code, due - settings.reminderDaysBefore * DAY_MS, now)
                    scheduled.add(code.toString())
                }
                if (settings.reminderHoursBefore > 0) {
                    val code = requestCode(p.id, KIND_HOURS)
                    schedule(app, alarmManager, p.id, KIND_HOURS, code, due - settings.reminderHoursBefore * HOUR_MS, now)
                    scheduled.add(code.toString())
                }
            }
        }

        // ألغِ ما لم يعد له بورد قيد العمل أو تغيّر إعداد التنبيه
        (previous - scheduled).forEach { raw ->
            raw.toIntOrNull()?.let { code ->
                val intent = Intent(app, DeliveryReminderReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    app, code, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pending != null) {
                    alarmManager?.cancel(pending)
                    pending.cancel()
                }
            }
        }
        prefs.edit().putStringSet(KEY_CODES, scheduled).apply()
    }

    private fun requestCode(projectId: Long, kind: Int): Int =
        ((projectId % 100_000_000L) * 10L + kind).toInt()

    private fun schedule(
        context: Context,
        alarmManager: AlarmManager,
        projectId: Long,
        kind: Int,
        code: Int,
        triggerAt: Long,
        now: Long
    ) {
        val intent = Intent(context, DeliveryReminderReceiver::class.java).apply {
            putExtra(EXTRA_PROJECT_ID, projectId)
            putExtra(EXTRA_KIND, kind)
        }
        val pending = PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // الموعد الفائت لا يُنبَّه عليه؛ البورد المتأخر يبقى ظاهراً بالأحمر في الواجهة
        if (triggerAt <= now) {
            alarmManager.cancel(pending)
            return
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    internal fun notify(context: Context, projectId: Long, customer: String, dueDate: Long, kind: Int) {
        ensureChannel(context)
        val kindLabel = if (kind == KIND_DAYS) "بأيام" else "بساعات"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PROJECT_ID, projectId)
        }
        val open = PendingIntent.getActivity(
            context, projectId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_bookmark)
            .setContentTitle("موعد تسليم اقترب — $customer")
            .setContentText("التسليم: ${formatDateTimeWithDay(dueDate)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("تنبيه $kindLabel · التسليم: ${formatDateTimeWithDay(dueDate)}"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify((projectId * 10 + kind).toInt(), notification)
        } catch (_: SecurityException) {
            // صلاحية الإشعارات غير ممنوحة — لا شيء نفعله هنا
        }
    }
}

class DeliveryReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val projectId = intent.getLongExtra("projectId", -1L)
        val kind = intent.getIntExtra("kind", 0)
        if (projectId <= 0L || kind == 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.get(context)
                val project = db.projectDao().getByIdOnce(projectId) ?: return@launch
                val due = project.dueDate ?: return@launch
                if (project.status != "ACTIVE" || project.completedAt != null) return@launch
                // ألغِ التنبيه إن تأخر الجهاز عن الموعد أكثر من يوم
                if (due < System.currentTimeMillis() - 24L * 60 * 60 * 1000) return@launch
                DeliveryReminders.notify(context, project.id, project.customerName, due, kind)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DeliveryReminders.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
