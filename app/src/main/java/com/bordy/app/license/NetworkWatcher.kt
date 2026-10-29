package com.bordy.app.license

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.Collections

/**
 * Reacts the moment a usable network shows up again instead of waiting for the next poll.
 *
 * A network becomes the default before it is validated, so syncing on onAvailable alone can fire
 * into a dead socket. We therefore kick once per network when it reports VALIDATED, and once as a
 * fallback if it is already validated when it appears.
 *
 * The callback lives only while the process does, so it costs nothing when the app is killed;
 * the periodic WorkManager job (which has a CONNECTED constraint) covers that case.
 */
object NetworkWatcher {

    private const val KICK_DEBOUNCE_MS = 5_000L

    @Volatile
    private var registered = false

    private var lastKickAt = 0L

    private val validated = Collections.synchronizedSet(HashSet<Network>())

    fun start(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Usually unvalidated at this point; onCapabilitiesChanged kicks when it is usable.
                validated.remove(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (usable && validated.add(network)) kick(app)
            }

            override fun onLost(network: Network) {
                validated.remove(network)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Exception) {
            // Registration is a best-effort nicety; the scheduled poll still works.
        }
    }

    private fun kick(app: Context) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastKickAt < KICK_DEBOUNCE_MS) return
            lastKickAt = now
        }
        LicenseManager.syncOnce(app, force = true)
    }
}
