package com.xxxifan.dashcam.device.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal class DeviceWifiConnector(
    context: Context,
) {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val connectionMutex = Mutex()
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    suspend fun connect(
        device: DeviceWifiNetwork,
        passphrase: String?,
    ): Result<Network> = connectionMutex.withLock {
        releaseLocked()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(device.ssid)
            .apply {
                runCatching { MacAddress.fromString(device.bssid) }
                    .getOrNull()
                    ?.let(::setBssid)
                when (device.security) {
                    DeviceWifiSecurity.Open -> Unit
                    DeviceWifiSecurity.Wpa2 -> setWpa2Passphrase(passphrase.orEmpty())
                    DeviceWifiSecurity.Wpa3 -> setWpa3Passphrase(passphrase.orEmpty())
                }
            }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        val result = CompletableDeferred<Result<Network>>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                result.complete(Result.success(network))
            }

            override fun onUnavailable() {
                result.complete(Result.failure(IOException("未能连接所选记录仪 Wi-Fi")))
            }
        }
        activeCallback = callback
        runCatching {
            connectivityManager.requestNetwork(request, callback, CONNECTION_TIMEOUT_MILLIS.toInt())
        }.onFailure { error ->
            activeCallback = null
            return@withLock Result.failure<Network>(error)
        }
        withTimeoutOrNull(CONNECTION_TIMEOUT_MILLIS + CALLBACK_GRACE_MILLIS) {
            result.await()
        } ?: Result.failure<Network>(IOException("连接记录仪 Wi-Fi 超时")).also {
            releaseLocked()
        }
    }

    fun release() {
        releaseLocked()
    }

    private fun releaseLocked() {
        activeCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        activeCallback = null
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 30_000L
        const val CALLBACK_GRACE_MILLIS = 2_000L
    }
}
