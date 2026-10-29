package com.bordy.app.license

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bordy.app.BuildConfig
import java.security.MessageDigest

/**
 * Distributed integrity checks scattered across the codebase.
 * Each function has a different non-obvious name to make pattern matching harder.
 * All check the license state and return false if the app should be blocked.
 */
object IntegrityChecks {

    /**
     * Checks APK signing certificate matches the expected SHA-256 fingerprint.
     * Called at startup.
     */
    fun checkSigningCert(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val sig = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return false
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                val hex = digest.joinToString(":") { "%02X".format(it) }
                // In debug builds, skip the check (keystore not yet available)
                if (BuildConfig.EXPECTED_CERT_SHA256.startsWith("REPLACE")) return true
                if (BuildConfig.DEBUG) return true
                hex == BuildConfig.EXPECTED_CERT_SHA256
            } else {
                true // Skip on old APIs, rely on other protections
            }
        } catch (_: Exception) {
            true // Don't block on unexpected errors
        }
    }

    private fun isAuthorized(): Boolean {
        // Debug builds bypass licence expiry so local testing of accounting/sync stays possible.
        if (BuildConfig.DEBUG) return true
        return when (LicenseManager.state.value) {
            is LicenseState.Active,
            is LicenseState.Trial,
            is LicenseState.Grace -> true
            else -> false
        }
    }

    /**
     * Used before saving a board. Checks current license state.
     */
    fun validateSession(): Boolean = isAuthorized()

    /**
     * Used before exporting PDF. Checks license state.
     */
    fun checkDataIntegrity(): Boolean = isAuthorized()

    /**
     * Used before exporting backup. Checks license state.
     */
    fun verifyAppState(): Boolean = isAuthorized()

    /**
     * Used when opening project editor. Checks license state.
     */
    fun ensureConsistency(): Boolean = isAuthorized()
}
