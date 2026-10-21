package com.switchon.customer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Whether the device has internet. Abstracted so the view model can be unit-tested without Android. */
interface NetworkMonitor {
    val online: Boolean
    fun close() {}
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    override var online by mutableStateOf(
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    )
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        override fun onLost(network: Network) { online = false }
    }

    init { runCatching { connectivity.registerDefaultNetworkCallback(callback) } }

    override fun close() { runCatching { connectivity.unregisterNetworkCallback(callback) } }
}
