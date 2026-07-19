package com.xxxifan.dashcam.device.remote

import android.net.Uri
import java.io.File

enum class DeviceProbeStatus {
    Supported,
    Unsupported,
    Unreachable,
    NotIntegrated,
}

enum class DeviceConnectionMethod {
    Legacy,
    VendorSdk,
}

enum class DeviceModel(
    val ssidPrefix: String,
    val driverId: String,
    val displayName: String,
    val protocolName: String,
    val host: String,
    val connectionMethod: DeviceConnectionMethod,
) {
    Dc1(
        ssidPrefix = "DC1",
        driverId = "legacy-192-168-169-1",
        displayName = "DC1 记录仪",
        protocolName = "JSON / HTTP / RTSP TCP",
        host = "192.168.169.1",
        connectionMethod = DeviceConnectionMethod.Legacy,
    ),
    Dc5(
        ssidPrefix = "DC5",
        driverId = "legacy-192-168-1-254",
        displayName = "DC5 记录仪",
        protocolName = "Novatek XML / HTTP / RTSP",
        host = "192.168.1.254",
        connectionMethod = DeviceConnectionMethod.Legacy,
    ),
    ;

    val definition: DeviceDefinition
        get() = DeviceDefinition(this)

    companion object {
        val supported = entries.toList()

        fun fromSsid(ssid: String): DeviceModel? {
            val normalized = ssid.trim().uppercase()
            return supported.firstOrNull { normalized.startsWith(it.ssidPrefix) }
        }
    }
}

data class DeviceDefinition(
    val model: DeviceModel,
) {
    val id: String get() = model.driverId
    val displayName: String get() = model.displayName
    val protocolName: String get() = model.protocolName
    val host: String get() = model.host
    val connectionMethod: DeviceConnectionMethod get() = model.connectionMethod
}

data class RememberedDevice(
    val ssid: String?,
    val driverId: String,
    val connectionMethod: DeviceConnectionMethod,
)

data class DeviceWifiNetwork(
    val ssid: String,
    val bssid: String,
    val signalLevel: Int,
    val model: DeviceModel,
    val security: DeviceWifiSecurity,
    val isCurrent: Boolean,
)

enum class DeviceWifiSecurity {
    Open,
    Wpa2,
    Wpa3,
}

data class DeviceProbeResult(
    val device: DeviceDefinition,
    val status: DeviceProbeStatus,
    val latencyMillis: Long? = null,
    val detail: String,
    val detectedName: String? = null,
)

data class NewSdkSupportStatus(
    val integrationAvailable: Boolean,
    val deviceSupport: DeviceProbeStatus,
    val detail: String,
)

enum class RemoteMediaCategory(val displayName: String) {
    NormalVideo("普通录像"),
    EmergencyVideo("紧急录像"),
    Photo("照片"),
}

enum class RemoteMediaFormat {
    Mp4,
    Mov,
    TransportStream,
    Jpeg,
    Unknown,
}

sealed interface DevicePlaybackSource {
    val uri: Uri

    data class Rtsp(
        override val uri: Uri,
        val forceTcp: Boolean,
        val timeoutMillis: Long,
    ) : DevicePlaybackSource

    data class Http(
        override val uri: Uri,
    ) : DevicePlaybackSource
}

data class RemoteDeviceMedia(
    val id: String,
    val name: String,
    val category: RemoteMediaCategory,
    val format: RemoteMediaFormat,
    val sizeBytes: Long?,
    val durationMillis: Long?,
    val createdAtMillis: Long?,
    val playbackSource: DevicePlaybackSource,
    val thumbnailUri: Uri?,
    val protocolData: Map<String, String>,
)

data class DeviceDownloadProgress(
    val mediaId: String,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { (downloadedBytes.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }
}

data class DownloadedDeviceMedia(
    val source: RemoteDeviceMedia,
    val file: File,
    val outputFormat: RemoteMediaFormat,
    val postProcessingDescription: String,
)

data class DeviceDiagnosticRecord(
    val timestampMillis: Long,
    val event: String,
    val fields: Map<String, String>,
)

data class DeviceStorageInfo(
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long
        get() = (totalBytes - freeBytes).coerceAtLeast(0L)
}

data class DeviceSettingsSupport(
    val canRead: Boolean,
    val canWrite: Boolean,
    val description: String,
) {
    companion object {
        val Unsupported = DeviceSettingsSupport(
            canRead = false,
            canWrite = false,
            description = "当前设备驱动未提供可靠的固件参数读写能力",
        )
    }
}

data class DeviceUiState(
    val isProbing: Boolean = false,
    val probeTrigger: String? = null,
    val probeResults: List<DeviceProbeResult> = emptyList(),
    val newSdkSupport: NewSdkSupportStatus = NewSdkSupportStatus(
        integrationAvailable = false,
        deviceSupport = DeviceProbeStatus.NotIntegrated,
        detail = "应用尚未集成 iCatch SDK，无法判断当前设备是否支持新款协议",
    ),
    val activeDevice: DeviceDefinition? = null,
    val rememberedDevice: RememberedDevice? = null,
    val isBusy: Boolean = false,
    val statusMessage: String = "等待探测设备",
    val previewSource: DevicePlaybackSource? = null,
    val selectedCategory: RemoteMediaCategory = RemoteMediaCategory.NormalVideo,
    val remoteMedia: List<RemoteDeviceMedia> = emptyList(),
    val storageInfo: DeviceStorageInfo? = null,
    val settingsSupport: DeviceSettingsSupport = DeviceSettingsSupport.Unsupported,
    val supportedCategories: Set<RemoteMediaCategory> = emptySet(),
    val playbackMedia: RemoteDeviceMedia? = null,
    val downloadProgress: DeviceDownloadProgress? = null,
    val downloadedMedia: DownloadedDeviceMedia? = null,
    val diagnostics: List<DeviceDiagnosticRecord> = emptyList(),
    val diagnosticFile: File? = null,
)

internal fun String.deviceModelOrNull(): DeviceModel? = DeviceModel.fromSsid(this)

internal fun String.toRemoteMediaFormat(): RemoteMediaFormat =
    when (substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "mp4" -> RemoteMediaFormat.Mp4
        "mov" -> RemoteMediaFormat.Mov
        "ts" -> RemoteMediaFormat.TransportStream
        "jpg", "jpeg" -> RemoteMediaFormat.Jpeg
        else -> RemoteMediaFormat.Unknown
    }
