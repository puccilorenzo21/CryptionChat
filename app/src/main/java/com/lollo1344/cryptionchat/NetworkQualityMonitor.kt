package com.lollo1344.cryptionchat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

class NetworkQualityMonitor(
    context: Context,
    private val onStatusChanged: (NetworkStatus) -> Unit
) {

    enum class NetworkStatus {
        GOOD, POOR, DISCONNECTED
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var currentStatus: NetworkStatus = NetworkStatus.DISCONNECTED

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            checkNetworkStatus()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            checkNetworkStatus()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            checkNetworkStatus(networkCapabilities)
        }
    }

    fun register() {
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            checkNetworkStatus()
            Log.d("NetworkQualityMonitor", "Network callback registered.")
        } catch (e: Exception) {
            Log.e("NetworkQualityMonitor", "Error registering callback", e)
        }
    }

    fun unregister() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.d("NetworkQualityMonitor", "Network callback unregistered.")
        } catch (e: Exception) {
            Log.w("NetworkQualityMonitor", "Error unregistering network callback: ${e.message}")
        }
    }

    fun getCurrentStatus(): NetworkStatus {
        return currentStatus
    }

    private fun checkNetworkStatus(capabilities: NetworkCapabilities? = null) {
        val activeNetworkCapabilities = capabilities ?: connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val newStatus: NetworkStatus

        if (activeNetworkCapabilities == null ||
            !activeNetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !activeNetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            newStatus = NetworkStatus.DISCONNECTED
        } else {
            // Logica Raffinata:
            // 1. Controlla bandwidth
            val downstreamKbps = activeNetworkCapabilities.linkDownstreamBandwidthKbps

            // 2. Controlla tipo di connessione (Cellular è spesso più instabile di WiFi anche con banda simile)
            val isCellular = activeNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val isWifi = activeNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

            // Soglie:
            // < 500kbps = POOR (Quasi inutilizzabile per audio HD)
            // < 1500kbps su CELLULAR = POOR (Rischio jitter)

            val isPoorBandwidth = downstreamKbps < 500
            val isMediumBandwidthCellular = isCellular && downstreamKbps < 1500

            if (isPoorBandwidth || isMediumBandwidthCellular) {
                newStatus = NetworkStatus.POOR
            } else {
                newStatus = NetworkStatus.GOOD
            }
        }

        if (newStatus != currentStatus) {
            currentStatus = newStatus
            Log.d("NetworkQualityMonitor", "Network status changed to: $newStatus")
            onStatusChanged(currentStatus)
        }
    }
}