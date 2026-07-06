package com.xxxifan.dashcam.camera

import com.xxxifan.dashcam.data.RecordingSettings
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

data class RecordingCombination(
    val resolution: String,
    val frameRate: Int,
    val codec: String,
    val dynamicRange: String,
    val stabilizationMode: StabilizationMode,
)

data class CameraHdrDiagnostics(
    val cameraId: String = "",
    val hasTenBitDynamicRangeCapability: Boolean = false,
    val camera2SupportedProfiles: List<String> = emptyList(),
    val camera2TenBitProfiles: List<String> = emptyList(),
    val camera2RecommendedProfile: String? = null,
    val cameraXRecorderDynamicRanges: List<String> = emptyList(),
    val cameraXQueriedDynamicRanges: List<String> = emptyList(),
    val cameraXResolvedDynamicRanges: List<String> = emptyList(),
    val hdrExtensionDiagnostics: HdrExtensionDiagnostics = HdrExtensionDiagnostics(),
)

data class HdrExtensionDiagnostics(
    val cameraId: String = "",
    val supportedExtensions: List<String> = emptyList(),
    val hdrExtensionSupported: Boolean = false,
    val hdrExtensionDynamicRangeProfiles: List<String> = emptyList(),
    val hdrExtensionRecommendedProfile: String? = null,
    val hdrExtensionPreviewSizes: List<String> = emptyList(),
    val hdrExtensionCaptureRequestKeys: List<String> = emptyList(),
    val error: String? = null,
)

fun CameraHdrDiagnostics.toLogFields(): Map<String, Any?> = mapOf(
    "cameraId" to cameraId,
    "hasTenBitDynamicRangeCapability" to hasTenBitDynamicRangeCapability,
    "camera2SupportedProfiles" to camera2SupportedProfiles,
    "camera2TenBitProfiles" to camera2TenBitProfiles,
    "camera2RecommendedProfile" to camera2RecommendedProfile,
    "cameraXRecorderDynamicRanges" to cameraXRecorderDynamicRanges,
    "cameraXQueriedDynamicRanges" to cameraXQueriedDynamicRanges,
    "cameraXResolvedDynamicRanges" to cameraXResolvedDynamicRanges,
    "hdrExtension" to hdrExtensionDiagnostics.toLogFields(),
)

private fun HdrExtensionDiagnostics.toLogFields(): Map<String, Any?> = mapOf(
    "cameraId" to cameraId,
    "supportedExtensions" to supportedExtensions,
    "hdrExtensionSupported" to hdrExtensionSupported,
    "hdrExtensionDynamicRangeProfiles" to hdrExtensionDynamicRangeProfiles,
    "hdrExtensionRecommendedProfile" to hdrExtensionRecommendedProfile,
    "hdrExtensionPreviewSizes" to hdrExtensionPreviewSizes,
    "hdrExtensionCaptureRequestKeys" to hdrExtensionCaptureRequestKeys,
    "error" to error,
)

data class CameraCapabilities(
    val cameraOptions: List<CameraOption>,
    val resolutionOptions: List<String>,
    val frameRateOptions: List<Int>,
    val frameRateOptionsByResolution: Map<String, List<Int>> = emptyMap(),
    val highSpeedFrameRateOptionsByResolution: Map<String, List<Int>> = emptyMap(),
    val codecOptions: List<VideoCodecOption>,
    val dynamicRangeOptions: List<DynamicRangeOption>,
    val stabilizationModes: List<StabilizationMode>,
    val supportedRecordingCombinations: List<RecordingCombination> = emptyList(),
    val hdrDiagnostics: CameraHdrDiagnostics = CameraHdrDiagnostics(),
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
    else -> "H.265"
}

fun String.dynamicRangeLabel(): String = when (this) {
    "hlg10" -> "HLG10 HDR"
    "hdr10" -> "HDR10"
    "hdr10_plus" -> "HDR10+"
    "dolby_vision" -> "Dolby Vision"
    else -> "SDR"
}

fun String.toCameraXDynamicRange(): androidx.camera.core.DynamicRange = when (this) {
    "hlg10" -> androidx.camera.core.DynamicRange.HLG_10_BIT
    "hdr10" -> androidx.camera.core.DynamicRange.HDR10_10_BIT
    "hdr10_plus" -> androidx.camera.core.DynamicRange.HDR10_PLUS_10_BIT
    "dolby_vision" -> androidx.camera.core.DynamicRange.DOLBY_VISION_10_BIT
    else -> androidx.camera.core.DynamicRange.SDR
}

fun String.toVideoMimeType(): String? = when (this) {
    "h264" -> android.media.MediaFormat.MIMETYPE_VIDEO_AVC
    "h265" -> android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
    else -> null
}

fun String.isHdrDynamicRange(): Boolean = this != "sdr"

fun String.resolutionRank(): Int = when (this) {
    "720p" -> 0
    "1080p" -> 1
    "4K" -> 2
    else -> 1
}

fun RecordingCombination.isAllowedByDashCamPolicy(): Boolean {
    if (frameRate > 30) {
        return !dynamicRange.isHdrDynamicRange() &&
            stabilizationMode == StabilizationMode.Off
    }
    if (!dynamicRange.isHdrDynamicRange()) {
        return true
    }
    if (codec != "h265" || frameRate > 30 || stabilizationMode != StabilizationMode.Off) {
        return false
    }
    if (resolution.resolutionRank() > "1080p".resolutionRank()) {
        return false
    }
    return true
}

fun CameraCapabilities.isRecordingCombinationSupported(settings: RecordingSettings): Boolean =
    isRecordingCombinationSupported(
        resolution = settings.resolution,
        frameRate = settings.frameRate,
        codec = settings.codec,
        dynamicRange = settings.dynamicRange,
        stabilizationMode = settings.stabilizationMode,
    )

fun CameraCapabilities.isRecordingCombinationSupported(
    resolution: String,
    frameRate: Int,
    codec: String,
    dynamicRange: String,
    stabilizationMode: StabilizationMode,
): Boolean {
    if (supportedRecordingCombinations.isEmpty()) {
        return fallbackCombinationSupported(
            resolution = resolution,
            frameRate = frameRate,
            codec = codec,
            dynamicRange = dynamicRange,
            stabilizationMode = stabilizationMode,
        )
    }
    return supportedRecordingCombinations.any { combination ->
        combination.resolution == resolution &&
            combination.frameRate == frameRate &&
            combination.codec == codec &&
            combination.dynamicRange == dynamicRange &&
            combination.stabilizationMode == stabilizationMode
    }
}

fun CameraCapabilities.frameRateOptionsForResolution(resolution: String): List<Int> =
    frameRateOptionsByResolution[resolution]
        ?.takeIf { it.isNotEmpty() }
        ?: frameRateOptions

fun CameraCapabilities.highSpeedFrameRateOptionsForResolution(resolution: String): List<Int> =
    highSpeedFrameRateOptionsByResolution[resolution]
        ?.takeIf { it.isNotEmpty() }
        ?: emptyList()

fun RecordingSettings.coerceToSupportedCombination(capabilities: CameraCapabilities): RecordingSettings {
    if (capabilities.isRecordingCombinationSupported(this)) {
        return this
    }
    val combination = capabilities.supportedRecordingCombinations
        .ifEmpty { capabilities.fallbackCombinations() }
        .maxByOrNull { it.preferenceScore(this) }
        ?: return this
    return copy(
        resolution = combination.resolution,
        frameRate = combination.frameRate,
        codec = combination.codec,
        dynamicRange = combination.dynamicRange,
        stabilizationMode = combination.stabilizationMode,
    )
}

private fun CameraCapabilities.fallbackCombinationSupported(
    resolution: String,
    frameRate: Int,
    codec: String,
    dynamicRange: String,
    stabilizationMode: StabilizationMode,
): Boolean {
    val combination = RecordingCombination(
        resolution = resolution,
        frameRate = frameRate,
        codec = codec,
        dynamicRange = dynamicRange,
        stabilizationMode = stabilizationMode,
    )
    return resolution in resolutionOptions &&
        frameRate in frameRateOptionsForResolution(resolution) &&
        isFrameRateRecordableForResolution(resolution, frameRate) &&
        codecOptions.any { it.id == codec } &&
        dynamicRangeOptions.any { it.id == dynamicRange } &&
        stabilizationMode in stabilizationModes &&
        combination.isAllowedByDashCamPolicy()
}

private fun CameraCapabilities.fallbackCombinations(): List<RecordingCombination> =
    buildList {
        resolutionOptions.forEach { resolution ->
            frameRateOptionsForResolution(resolution).forEach { frameRate ->
                if (!isFrameRateRecordableForResolution(resolution, frameRate)) {
                    return@forEach
                }
                codecOptions.forEach { codec ->
                    dynamicRangeOptions.forEach { dynamicRange ->
                        stabilizationModes.forEach { stabilizationMode ->
                            RecordingCombination(
                                resolution = resolution,
                                frameRate = frameRate,
                                codec = codec.id,
                                dynamicRange = dynamicRange.id,
                                stabilizationMode = stabilizationMode,
                            ).takeIf { it.isAllowedByDashCamPolicy() }?.let(::add)
                        }
                    }
                }
            }
        }
    }

private fun CameraCapabilities.isFrameRateRecordableForResolution(
    resolution: String,
    frameRate: Int,
): Boolean =
    frameRate <= 30 || frameRate in highSpeedFrameRateOptionsForResolution(resolution)

private fun RecordingCombination.preferenceScore(requested: RecordingSettings): Int {
    var score = 0
    score += when {
        dynamicRange == requested.dynamicRange -> 10_000
        requested.dynamicRange.isHdrDynamicRange() && dynamicRange.isHdrDynamicRange() -> 8_000
        dynamicRange == "sdr" -> 1_000
        else -> 0
    }
    score += when {
        codec == requested.codec -> 2_000
        requested.dynamicRange.isHdrDynamicRange() && codec == "h265" -> 1_200
        else -> 0
    }
    score += rankedPreference(
        currentRank = resolution.resolutionRank(),
        requestedRank = requested.resolution.resolutionRank(),
        exact = resolution == requested.resolution,
        exactScore = 1_000,
        lowerScore = 700,
        higherScore = 300,
    )
    score += numericPreference(
        current = frameRate,
        requested = requested.frameRate,
        exactScore = 600,
        lowerScore = 400,
        higherScore = 150,
    )
    score += when {
        stabilizationMode == requested.stabilizationMode -> 400
        requested.stabilizationMode == StabilizationMode.Enhanced &&
            stabilizationMode == StabilizationMode.Standard -> 350
        stabilizationMode == StabilizationMode.Off -> 360
        else -> 100
    }
    return score
}

private fun rankedPreference(
    currentRank: Int,
    requestedRank: Int,
    exact: Boolean,
    exactScore: Int,
    lowerScore: Int,
    higherScore: Int,
): Int {
    if (exact) {
        return exactScore
    }
    val distance = kotlin.math.abs(currentRank - requestedRank)
    return if (currentRank <= requestedRank) {
        lowerScore - distance * 80
    } else {
        higherScore - distance * 80
    }
}

private fun numericPreference(
    current: Int,
    requested: Int,
    exactScore: Int,
    lowerScore: Int,
    higherScore: Int,
): Int {
    if (current == requested) {
        return exactScore
    }
    val distance = kotlin.math.abs(current - requested).coerceAtMost(120)
    return if (current < requested) {
        lowerScore - distance
    } else {
        higherScore - distance
    }
}
