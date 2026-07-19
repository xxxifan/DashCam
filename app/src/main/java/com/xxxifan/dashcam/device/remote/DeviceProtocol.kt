package com.xxxifan.dashcam.device.remote

import java.io.File

interface DeviceProtocolDriver {
    val definition: DeviceDefinition

    suspend fun probe(route: DeviceNetworkRoute): DeviceProbeResult

    fun createSession(
        route: DeviceNetworkRoute,
        diagnostics: DeviceDiagnosticsLogger,
    ): DeviceSession
}

interface DeviceSession {
    val device: DeviceDefinition
    val supportedCategories: Set<RemoteMediaCategory>

    suspend fun preparePreview(): DevicePlaybackSource

    suspend fun releasePreview()

    suspend fun loadStorageInfo(): DeviceStorageInfo? = null

    suspend fun loadRemoteMedia(category: RemoteMediaCategory): List<RemoteDeviceMedia>

    suspend fun download(
        media: RemoteDeviceMedia,
        destinationDirectory: File,
        onProgress: (DeviceDownloadProgress) -> Unit,
    ): DownloadedDeviceMedia

    suspend fun close()
}

interface NewDeviceSdkProbe {
    val integrationAvailable: Boolean

    suspend fun probe(route: DeviceNetworkRoute?): NewSdkSupportStatus
}

object UnavailableNewDeviceSdkProbe : NewDeviceSdkProbe {
    override val integrationAvailable: Boolean = false

    override suspend fun probe(route: DeviceNetworkRoute?): NewSdkSupportStatus =
        NewSdkSupportStatus(
            integrationAvailable = false,
            deviceSupport = DeviceProbeStatus.NotIntegrated,
            detail = if (route == null) {
                "iCatch SDK 未集成，且当前没有可用 Wi-Fi 网络"
            } else {
                "iCatch SDK 未集成；已记录 Wi-Fi 网络，但不能声称设备支持或不支持新款协议"
            },
        )
}
