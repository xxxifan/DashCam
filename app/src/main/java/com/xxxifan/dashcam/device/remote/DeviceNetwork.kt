package com.xxxifan.dashcam.device.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import javax.net.SocketFactory

data class DeviceNetworkRoute(
    val network: Network,
    val interfaceName: String?,
    val description: String,
) {
    val socketFactory: SocketFactory
        get() = network.socketFactory

    fun openHttpConnection(url: URL): HttpURLConnection =
        network.openConnection(url) as HttpURLConnection
}

class DeviceNetworkResolver(
    context: Context,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    fun findWifiRoute(): DeviceNetworkRoute? =
        connectivityManager.allNetworks
            .asSequence()
            .mapNotNull { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return@mapNotNull null
                }
                val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(network)
                DeviceNetworkRoute(
                    network = network,
                    interfaceName = linkProperties?.interfaceName,
                    description = buildString {
                        append("network=")
                        append(network)
                        append(", interface=")
                        append(linkProperties?.interfaceName ?: "unknown")
                        append(", internet=")
                        append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        append(", validated=")
                        append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    },
                )
            }
            .firstOrNull()
}
