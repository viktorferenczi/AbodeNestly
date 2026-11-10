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
            val monitor = NetworkMonitor(manager.hasValidatedNetwork())
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = monitor.setOnline(true)
                override fun onLost(network: Network) = monitor.setOnline(false)
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    monitor.setOnline(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            })
            return monitor
        }

        private fun ConnectivityManager.hasValidatedNetwork(): Boolean =
            getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
