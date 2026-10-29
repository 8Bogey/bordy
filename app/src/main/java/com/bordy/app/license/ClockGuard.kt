package com.bordy.app.license

import android.content.Context
import java.io.File

/**
 * Guards against manual clock rollback.
 * Stores the max seen time in noBackupFilesDir/clock.dat.
 * If current time is more than 10 minutes behind max seen → TAMPER detected.
 */
object ClockGuard {

    private const val FILE_NAME = "clock.dat"
    private const val ALLOWED_DRIFT_MS = 10 * 60 * 1000L // 10 minutes

    enum class Result { OK, TAMPER }

    private fun clockFile(context: Context): File =
        File(context.noBackupFilesDir, FILE_NAME)

    fun check(context: Context): Result {
        val now = System.currentTimeMillis()
        val file = clockFile(context)

        val maxSeen = try {
            if (file.exists()) file.readText().trim().toLong() else 0L
        } catch (_: Exception) {
            0L
        }

        return if (now < maxSeen - ALLOWED_DRIFT_MS) {
            Result.TAMPER
        } else {
            val newMax = maxOf(maxSeen, now)
            try {
                file.writeText(newMax.toString())
            } catch (_: Exception) {}
            Result.OK
        }
    }
}
