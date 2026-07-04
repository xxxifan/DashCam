package com.xxxifan.dashcam.camera

import com.xxxifan.dashcam.data.StabilizationMode

data class CameraOption(
    val id: String,
    val label: String,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val lensFacing: Int,
    val focalLengthMm: Float?,
    val zoomRatio: Float?,
)

data class VideoCodecOption(
    val id: String,
    val label: String,
    val mimeType: String?,
)

data class DynamicRangeOption(
    val id: String,
    val label: String,
)

data class CameraCapabilities(
    val cameraOptions: List<CameraOption>,
    val resolutionOptions: List<String>,
    val frameRateOptions: List<Int>,
    val codecOptions: List<VideoCodecOption>,
    val dynamicRangeOptions: List<DynamicRangeOption>,
    val stabilizationModes: List<StabilizationMode>,
)

object CameraSelectionId {
    private const val separator = "|"

    fun encode(logicalCameraId: String, physicalCameraId: String?): String =
        if (physicalCameraId.isNullOrBlank()) {
            logicalCameraId
        } else {
            "$logicalCameraId$separator$physicalCameraId"
        }

    fun logicalCameraId(id: String): String =
        id.substringBefore(separator, id)

    fun physicalCameraId(id: String): String? =
        id.substringAfter(separator, missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
}

fun CameraOption.isUltraWide(): Boolean =
    (zoomRatio ?: 1f) < 0.85f

fun String.codecLabel(): String = when (this) {
    "h264" -> "H.264"
    "h265" -> "H.265"
    else -> "Auto"
}

fun String.dynamicRangeLabel(): String = when (this) {
    "hlg10" -> "HLG10 HDR"
    "hdr10" -> "HDR10"
    "hdr10_plus" -> "HDR10+"
    else -> "SDR"
}

fun String.toCameraXDynamicRange(): androidx.camera.core.DynamicRange = when (this) {
    "hlg10" -> androidx.camera.core.DynamicRange.HLG_10_BIT
    "hdr10" -> androidx.camera.core.DynamicRange.HDR10_10_BIT
    "hdr10_plus" -> androidx.camera.core.DynamicRange.HDR10_PLUS_10_BIT
    else -> androidx.camera.core.DynamicRange.SDR
}

fun String.toVideoMimeType(): String? = when (this) {
    "h264" -> android.media.MediaFormat.MIMETYPE_VIDEO_AVC
    "h265" -> android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
    else -> null
}
