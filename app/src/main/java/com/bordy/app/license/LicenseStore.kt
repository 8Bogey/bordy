package com.bordy.app.license

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted license storage in noBackupFilesDir (excluded from Google Backup).
 * Uses Android Keystore AES-GCM encryption.
 * Format: [12-byte IV][ciphertext+tag]
 * Stored JSON: {"t":"<token>","ta":true,"tv":1}
 */
data class StoredLicense(
    val token: String,
    val tosAccepted: Boolean,
    val tosVersion: Int
)

object LicenseStore {

    private const val KEYSTORE_ALIAS = "bordy_license_key"
    private const val FILE_NAME = "license.enc"
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            return (ks.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val kg = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        kg.init(spec)
        kg.generateKey()
        return (ks.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun licenseFile(context: Context): File =
        File(context.noBackupFilesDir, FILE_NAME)

    fun save(context: Context, token: String, tosAccepted: Boolean, tosVersion: Int) {
        try {
            val json = JSONObject().apply {
                put("t", token)
                put("ta", tosAccepted)
                put("tv", tosVersion)
            }.toString()
            val plaintext = json.toByteArray(Charsets.UTF_8)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            val out = iv + ciphertext
            licenseFile(context).writeBytes(out)
        } catch (_: Exception) {
            // Silently fail — next open will retry via /activate
        }
    }

    fun load(context: Context): StoredLicense? {
        return try {
            val file = licenseFile(context)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            if (bytes.size <= IV_SIZE) return null

            val iv = bytes.sliceArray(0 until IV_SIZE)
            val ciphertext = bytes.sliceArray(IV_SIZE until bytes.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)

            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            StoredLicense(
                token = json.getString("t"),
                tosAccepted = json.optBoolean("ta", false),
                tosVersion = json.optInt("tv", 0)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            licenseFile(context).delete()
        } catch (_: Exception) {}
    }

    // ── Pending Order Cache ──────────────────────────────────────────────────

    private const val PREFS_NAME = "bordy_license_prefs"
    private const val KEY_PENDING_ORDER_ID = "pending_order_id"
    private const val KEY_PENDING_PLAN_ID = "pending_plan_id"
    private const val KEY_PENDING_PLAN_TITLE = "pending_plan_title"
    private const val KEY_PENDING_AMOUNT = "pending_amount"
    private const val KEY_PENDING_METHOD = "pending_method"
    private const val KEY_PENDING_TXN = "pending_txn"
    private const val KEY_PENDING_SUBMITTED = "pending_submitted"
    private const val KEY_SYNC_INTERVAL = "sync_interval_seconds"
    private const val KEY_FOREGROUND_SYNC = "foreground_sync_seconds"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_BLOCKED_REASON = "blocked_reason"

    private const val SYNC_INTERVAL_DEFAULT = 60
    private const val SYNC_INTERVAL_MIN = 15
    private const val SYNC_INTERVAL_MAX = 3600

    private const val FOREGROUND_SYNC_DEFAULT = 10
    private const val FOREGROUND_SYNC_MIN = 5
    private const val FOREGROUND_SYNC_MAX = 120

    /** Polling cadence last pushed by the server (admin setting), clamped to sane bounds. */
    fun getSyncInterval(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SYNC_INTERVAL, SYNC_INTERVAL_DEFAULT)
            .coerceIn(SYNC_INTERVAL_MIN, SYNC_INTERVAL_MAX)

    fun saveSyncInterval(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SYNC_INTERVAL, seconds.coerceIn(SYNC_INTERVAL_MIN, SYNC_INTERVAL_MAX))
            .apply()
    }

    /** How often an open app asks the server — this is what makes panel changes land in seconds. */
    fun getForegroundSync(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FOREGROUND_SYNC, FOREGROUND_SYNC_DEFAULT)
            .coerceIn(FOREGROUND_SYNC_MIN, FOREGROUND_SYNC_MAX)

    fun saveForegroundSync(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FOREGROUND_SYNC, seconds.coerceIn(FOREGROUND_SYNC_MIN, FOREGROUND_SYNC_MAX))
            .apply()
    }

    /** Wall-clock time of the last successful round-trip, so the UI stays honest across restarts. */
    fun getLastSyncAt(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_AT, 0L)

    fun saveLastSyncAt(context: Context, at: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SYNC_AT, at)
            .apply()
    }

    /**
     * A block is a server verdict that must survive restarts — otherwise killing the app
     * offline would silently drop it. Cleared only by a sync that comes back clean.
     */
    fun getBlockedReason(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_BLOCKED_REASON)) prefs.getString(KEY_BLOCKED_REASON, "") ?: "" else null
    }

    fun saveBlocked(context: Context, reason: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_BLOCKED_REASON, reason ?: "")
            .apply()
    }

    fun clearBlocked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_BLOCKED_REASON)
            .apply()
    }

    fun isBlocked(context: Context): Boolean = getBlockedReason(context) != null

    data class PendingOrder(
        val id: String,
        val planId: String,
        val planTitle: String,
        val amount: Int,
        val method: String?,
        val txnRef: String?,
        val submitted: Boolean
    )

    fun savePendingOrder(
        context: Context,
        orderId: String,
        planId: String,
        planTitle: String,
        amount: Int,
        method: String? = null,
        txnRef: String? = null,
        submitted: Boolean = false
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_ORDER_ID, orderId)
            .putString(KEY_PENDING_PLAN_ID, planId)
            .putString(KEY_PENDING_PLAN_TITLE, planTitle)
            .putInt(KEY_PENDING_AMOUNT, amount)
            .putString(KEY_PENDING_METHOD, method)
            .putString(KEY_PENDING_TXN, txnRef)
            .putBoolean(KEY_PENDING_SUBMITTED, submitted)
            .apply()
    }

    /** Remember the wallet/transfer reference so a failed submission can be retried later. */
    fun updatePendingProof(context: Context, method: String?, txnRef: String?, submitted: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PENDING_ORDER_ID, null).isNullOrBlank()) return
        prefs.edit()
            .putString(KEY_PENDING_METHOD, method)
            .putString(KEY_PENDING_TXN, txnRef)
            .putBoolean(KEY_PENDING_SUBMITTED, submitted)
            .apply()
    }

    fun getPendingOrderId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_ORDER_ID, null)
    }

    fun getPendingOrder(context: Context): PendingOrder? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_PENDING_ORDER_ID, null) ?: return null
        if (id.isBlank()) return null
        return PendingOrder(
            id = id,
            planId = prefs.getString(KEY_PENDING_PLAN_ID, "") ?: "",
            planTitle = prefs.getString(KEY_PENDING_PLAN_TITLE, "") ?: "",
            amount = prefs.getInt(KEY_PENDING_AMOUNT, 0),
            method = prefs.getString(KEY_PENDING_METHOD, null),
            txnRef = prefs.getString(KEY_PENDING_TXN, null),
            submitted = prefs.getBoolean(KEY_PENDING_SUBMITTED, false)
        )
    }

    fun clearPendingOrder(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_ORDER_ID)
            .remove(KEY_PENDING_PLAN_ID)
            .remove(KEY_PENDING_PLAN_TITLE)
            .remove(KEY_PENDING_AMOUNT)
            .remove(KEY_PENDING_METHOD)
            .remove(KEY_PENDING_TXN)
            .remove(KEY_PENDING_SUBMITTED)
            .apply()
        clearReceipts(context)
    }

    // ── Receipt image attached to the pending order ───────────────────────────

    private const val RECEIPT_PREFIX = "receipt_"

    private fun receiptFile(context: Context, orderId: String): File {
        val safe = orderId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(context.noBackupFilesDir, RECEIPT_PREFIX + safe + ".bin")
    }

    /** Keep the receipt on device so a failed upload can be retried without asking the user again. */
    fun saveReceipt(context: Context, orderId: String, bytes: ByteArray): Boolean {
        if (orderId.isBlank() || bytes.isEmpty()) return false
        return try {
            clearReceipts(context)
            receiptFile(context, orderId).writeBytes(bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getReceiptBase64(context: Context, orderId: String): String? {
        if (orderId.isBlank()) return null
        val file = receiptFile(context, orderId)
        if (!file.exists()) return null
        return try {
            android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun hasReceipt(context: Context, orderId: String): Boolean =
        orderId.isNotBlank() && receiptFile(context, orderId).exists()

    fun clearReceipts(context: Context) {
        try {
            context.noBackupFilesDir.listFiles { f -> f.name.startsWith(RECEIPT_PREFIX) }
                ?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
