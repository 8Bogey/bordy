package com.bordy.app.sync

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

data class AccountSession(
    val accountId: String = "",
    val username: String = "",
    val password: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val accessExpiresAt: Long = 0,
    val refreshExpiresAt: Long = 0,
    val serverDeviceId: String = "",
    val deviceStatus: String = "",
    val accountEpoch: Long = 0,
    val deviceEpoch: Long = 0,
    val installId: String = "",
    val pendingTransferId: String? = null,
    val lastError: String? = null,
    val suspendedReason: String? = null
) {
    val isLoggedIn: Boolean get() = accountId.isNotBlank() && refreshToken.isNotBlank()
    val canWrite: Boolean get() = deviceStatus == "active"
}

/**
 * جلسة الحساب مشفّرة بمفتاح Keystore وخارج مجلد النسخ الاحتياطي.
 * كلمة المرور محفوظة لأن مسح الجهاز القديم بعد نقل الملكية يطلبها دون تدخّل المستخدم.
 */
object AccountStore {

    private const val ALIAS = "bordy_account_key"
    private const val FILE_NAME = "account.enc"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) {
            return (ks.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(spec) }.generateKey()
        return (ks.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    fun load(context: Context): AccountSession? = try {
        val f = file(context)
        if (!f.exists()) null else {
            val bytes = f.readBytes()
            if (bytes.size <= IV_SIZE) null else {
                val iv = bytes.copyOfRange(0, IV_SIZE)
                val body = bytes.copyOfRange(IV_SIZE, bytes.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
                val json = JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))
                AccountSession(
                    accountId = json.optString("aid"),
                    username = json.optString("u"),
                    password = json.optString("p"),
                    accessToken = json.optString("at"),
                    refreshToken = json.optString("rt"),
                    accessExpiresAt = json.optLong("ae"),
                    refreshExpiresAt = json.optLong("re"),
                    serverDeviceId = json.optString("did"),
                    deviceStatus = json.optString("ds"),
                    accountEpoch = json.optLong("acc"),
                    deviceEpoch = json.optLong("dec"),
                    installId = json.optString("ins"),
                    pendingTransferId = json.optString("tid").ifBlank { null },
                    lastError = json.optString("err").ifBlank { null },
                    suspendedReason = json.optString("sus").ifBlank { null }
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    fun save(context: Context, session: AccountSession) {
        try {
            val json = JSONObject()
                .put("aid", session.accountId)
                .put("u", session.username)
                .put("p", session.password)
                .put("at", session.accessToken)
                .put("rt", session.refreshToken)
                .put("ae", session.accessExpiresAt)
                .put("re", session.refreshExpiresAt)
                .put("did", session.serverDeviceId)
                .put("ds", session.deviceStatus)
                .put("acc", session.accountEpoch)
                .put("dec", session.deviceEpoch)
                .put("ins", session.installId)
                .put("tid", session.pendingTransferId ?: "")
                .put("err", session.lastError ?: "")
                .put("sus", session.suspendedReason ?: "")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val out = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            file(context).writeBytes(out)
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
    }

    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences("bordy_account_prefs", Context.MODE_PRIVATE)
        prefs.getString("install_id", null)?.let { if (it.isNotBlank()) return it }
        val fresh = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("install_id", fresh).apply()
        return fresh
    }

    fun setPendingTransfer(context: Context, transferId: String?) {
        val current = load(context) ?: return
        save(context, current.copy(pendingTransferId = transferId))
    }
}
