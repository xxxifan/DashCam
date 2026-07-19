package com.xxxifan.dashcam.device.remote

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class DeviceWifiScanResult(
    val networks: List<DeviceWifiNetwork>,
    val message: String,
    val rawNetworkCount: Int,
    val scanRequested: Boolean,
    val failureType: String? = null,
)

class DeviceWifiScanner(
    context: Context,
) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    suspend fun scan(): DeviceWifiScanResult = withContext(Dispatchers.IO) {
        if (!wifiManager.isWifiEnabled) {
            return@withContext DeviceWifiScanResult(
                networks = emptyList(),
                message = "请先开启 Wi-Fi",
                rawNetworkCount = 0,
                scanRequested = false,
                failureType = "WifiDisabled",
            )
        }
        val scanRequest = runCatching { wifiManager.startScan() }
        val scanRequested = scanRequest.getOrDefault(false)
        val scanRequestFailureType = scanRequest.exceptionOrNull()?.javaClass?.name
        if (scanRequested) {
            delay(SCAN_SETTLE_MILLIS)
        }
        val currentWifi = runCatching { wifiManager.connectionInfo }.getOrNull()
        val scanResults = runCatching { wifiManager.scanResults }
            .getOrElse { error ->
                return@withContext DeviceWifiScanResult(
                    networks = emptyList(),
                    message = when (error) {
                        is SecurityException -> "需要附近 Wi-Fi 和位置权限才能发现记录仪"
                        else -> "扫描失败：${error.message ?: error.javaClass.simpleName}"
                    },
                    rawNetworkCount = 0,
                    scanRequested = scanRequested,
                    failureType = error.javaClass.name,
                )
            }
        val networks = scanResults
                .asSequence()
                .mapNotNull { result ->
                    val ssid = result.SSID.trim()
                    val model = ssid.deviceModelOrNull() ?: return@mapNotNull null
                    DeviceWifiNetwork(
                        ssid = ssid,
                        bssid = result.BSSID.orEmpty(),
                        signalLevel = result.level.coerceIn(MIN_RSSI, MAX_RSSI),
                        model = model,
                        security = result.capabilities.deviceWifiSecurity(),
                        isCurrent = currentWifi.matches(result.BSSID, ssid),
                    )
                }
                .distinctBy { it.bssid.ifBlank { it.ssid } }
                .sortedWith(
                    compareByDescending<DeviceWifiNetwork> { it.isCurrent }
                        .thenByDescending { it.signalLevel },
                )
                .toList()
        DeviceWifiScanResult(
            networks = networks,
            message = when {
                networks.isNotEmpty() -> "发现 ${networks.size} 台记录仪"
                !scanRequested -> "系统暂未允许主动扫描，请稍后重试"
                else -> "附近没有发现支持的记录仪"
            },
            rawNetworkCount = scanResults.size,
            scanRequested = scanRequested,
            failureType = scanRequestFailureType,
        )
    }

    @Suppress("DEPRECATION")
    fun currentSsid(): String? = runCatching {
        wifiManager.connectionInfo.ssid
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    }.getOrNull()

    private fun WifiInfo?.matches(bssid: String?, ssid: String): Boolean {
        if (this == null) {
            return false
        }
        if (!bssid.isNullOrBlank() && this.bssid.equals(bssid, ignoreCase = true)) {
            return true
        }
        return this.ssid?.removeSurrounding("\"") == ssid
    }

    private companion object {
        const val SCAN_SETTLE_MILLIS = 1_200L
        const val MIN_RSSI = -100
        const val MAX_RSSI = -20
    }
}

private fun String.deviceWifiSecurity(): DeviceWifiSecurity {
    val normalized = uppercase()
    return when {
        "SAE" in normalized -> DeviceWifiSecurity.Wpa3
        "WPA" in normalized || "WEP" in normalized -> DeviceWifiSecurity.Wpa2
        else -> DeviceWifiSecurity.Open
    }
}
