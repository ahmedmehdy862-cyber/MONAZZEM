package com.focusstudy.poc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Offline-first helpers. The local SQLite database and cached preferences are always the source of
 * truth; the network is an optional enhancement for updates, team messages and backup. A missing
 * connection must never block a screen, spin forever, or surface an error to the person.
 */
internal object OfflineFirst {

    /** True only when a validated internet connection is available right now. */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Pure decision helper used by network callers: a manual refresh is allowed to try once even
     * offline (it will fail silently and use the cache), while background checks skip entirely.
     */
    fun shouldAttempt(online: Boolean, manual: Boolean): Boolean = online || manual
}
