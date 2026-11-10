package com.abodenestly.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device has a validated network. The public constructor is the fixed-value form for tests;
 * [observing] follows the system for the life of the process.
 */
class NetworkMonitor(online: Boolean = true) {
    private val state = MutableStateFlow(online)
    val isOnline: StateFlow<Boolean> = state.asStateFlow()

    /** Test hook: flips connectivity on a fixed monitor. */
    fun setOnline(value: Boolean) {
        state.value = value
    }

    companion object {
        fun observing(context: Context): NetworkMonitor {
            val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            val monitor = NetworkMonitor(manager.getNetworkCapabilities(manager.activeNetwork).isOnline())
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // Validation completes after onAvailable, so read the capabilities instead of assuming online.
                override fun onAvailable(network: Network) = monitor.setOnline(manager.getNetworkCapabilities(network).isOnline())
                override fun onLost(network: Network) = monitor.setOnline(false)
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    monitor.setOnline(capabilities.isOnline())
            })
            return monitor
        }

        /** A captive portal or an unvalidated network has INTERNET but not VALIDATED, and must read as offline. */
        private fun NetworkCapabilities?.isOnline(): Boolean =
            this != null &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
