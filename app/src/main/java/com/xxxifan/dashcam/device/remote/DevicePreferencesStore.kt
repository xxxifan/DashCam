package com.xxxifan.dashcam.device.remote

import com.tencent.mmkv.MMKV

internal class DevicePreferencesStore(
    private val mmkv: MMKV = MMKV.mmkvWithID("device_preferences"),
) {
    fun rememberedDevice(): RememberedDevice? {
        val driverId = mmkv.decodeString(KEY_DRIVER_ID)?.takeIf { it.isNotBlank() } ?: return null
        val method = mmkv.decodeString(KEY_CONNECTION_METHOD)
            ?.let { value -> runCatching { DeviceConnectionMethod.valueOf(value) }.getOrNull() }
            ?: DeviceConnectionMethod.Legacy
        return RememberedDevice(
            ssid = mmkv.decodeString(KEY_SSID)?.takeIf { it.isNotBlank() },
            driverId = driverId,
            connectionMethod = method,
        )
    }

    fun remember(
        ssid: String?,
        driverId: String,
        connectionMethod: DeviceConnectionMethod,
    ): RememberedDevice {
        val savedSsid = ssid?.takeIf { it.isNotBlank() }
            ?: mmkv.decodeString(KEY_SSID)?.takeIf { it.isNotBlank() }
        savedSsid?.let { mmkv.encode(KEY_SSID, it) }
        mmkv.encode(KEY_DRIVER_ID, driverId)
        mmkv.encode(KEY_CONNECTION_METHOD, connectionMethod.name)
        return RememberedDevice(
            ssid = savedSsid,
            driverId = driverId,
            connectionMethod = connectionMethod,
        )
    }

    fun forget() {
        mmkv.removeValuesForKeys(arrayOf(KEY_SSID, KEY_DRIVER_ID, KEY_CONNECTION_METHOD))
    }

    private companion object {
        const val KEY_SSID = "ssid"
        const val KEY_DRIVER_ID = "driver_id"
        const val KEY_CONNECTION_METHOD = "connection_method"
    }
}
