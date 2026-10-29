package com.bordy.app.license

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceId {
    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val raw = "$androidId${context.packageName}bordy_salt_2026"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } // 32 hex chars
    }

    fun getShortId(context: Context): String = get(context).take(8)

    fun getDisplayId(context: Context): String {
        val full = get(context)
        return full.chunked(4).joinToString("-")
    }
}
