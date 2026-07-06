package com.xxxifan.dashcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapabilities
import androidx.core.content.getSystemService
import com.xxxifan.dashcam.data.StabilizationMode

class CameraCapabilitiesRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun capabilities(cameraInfo: CameraInfo? = null): CameraCapabilities {
        val manager = appContext.getSystemService<CameraManager>()
        val logicalBackCamera = manager?.logicalBackCamera()
        val characteristics = logicalBackCamera?.let { runCatching { manager.getCameraCharacteristics(it) }.getOrNull() }
        val cameraOptions = manager?.let { backCameraOptions(it) } ?: fallbackCameraOptions()
        val resolutionOptions = characteristics?.resolutionOptions().orDefaultResolutions()
        val frameRateOptions = characteristics?.frameRateOptions().orDefaultFrameRates()
        val codecOptions = codecOptions()
        val camera2DynamicRangeOptions = characteristics?.dynamicRangeOptions().orDefaultDynamicRanges()
        val dynamicRangeOptions = (
            cameraInfo?.cameraXRecordingDynamicRangeOptions(camera2DynamicRangeOptions)
                ?: camera2DynamicRangeOptions
            ).orDefaultDynamicRanges()
        val stabilizationModes = characteristics?.stabilizationModes().orDefaultStabilizationModes()
        val combinations = recordingCombinations(
            cameraInfo = cameraInfo,
            resolutionOptions = resolutionOptions,
            frameRateOptions = frameRateOptions,
            codecOptions = codecOptions,
            dynamicRangeOptions = dynamicRangeOptions,
            stabilizationModes = stabilizationModes,
        )

        return CameraCapabilities(
            cameraOptions = cameraOptions,
            resolutionOptions = resolutionOptions,
            frameRateOptions = frameRateOptions,
            codecOptions = codecOptions,
            dynamicRangeOptions = dynamicRangeOptions,
            stabilizationModes = stabilizationModes,
            supportedRecordingCombinations = combinations,
        )
    }

    fun backCameraOptions(): List<CameraOption> {
        val manager = appContext.getSystemService<CameraManager>() ?: return fallbackCameraOptions()
        return backCameraOptions(manager)
    }

    private fun backCameraOptions(manager: CameraManager): List<CameraOption> {
        val logicalCameraId = manager.logicalBackCamera()
        if (logicalCameraId == null) {
            return fallbackCameraOptions()
        }

        val logicalCharacteristics = runCatching {
            manager.getCameraCharacteristics(logicalCameraId)
        }.getOrNull() ?: return fallbackCameraOptions()

        val logicalFocalLength = logicalCharacteristics.focalLength()
        val physicalOptions = logicalCharacteristics.getPhysicalCameraIds()
            .mapNotNull { physicalId ->
                val physicalCharacteristics = runCatching {
                    manager.getCameraCharacteristics(physicalId)
                }.getOrNull() ?: return@mapNotNull null
                physicalCharacteristics.toPhysicalOption(
                    logicalCameraId = logicalCameraId,
                    physicalCameraId = physicalId,
                    mainFocalLength = logicalFocalLength,
                )
            }
            .sortedBy { it.zoomRatio ?: Float.MAX_VALUE }

        val main = CameraOption(
            id = CameraSelectionId.encode(logicalCameraId, null),
            label = "1X 主镜头",
            logicalCameraId = logicalCameraId,
            physicalCameraId = null,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            focalLengthMm = logicalFocalLength,
            zoomRatio = 1f,
        )
        val ultraWide = physicalOptions
            .filter { it.isUltraWide() }
            .minByOrNull { it.zoomRatio ?: Float.MAX_VALUE }

        return buildList {
            add(main)
            if (ultraWide != null) {
                add(ultraWide)
            }
        }.ifEmpty {
            fallbackCameraOptions()
        }
    }

    private fun CameraManager.logicalBackCamera(): String? {
        val backCameras = cameraIdList.mapNotNull { id ->
            val characteristics = runCatching { getCameraCharacteristics(id) }.getOrNull()
                ?: return@mapNotNull null
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                id to characteristics
            } else {
                null
            }
        }
        return backCameras.firstOrNull { (_, characteristics) ->
            characteristics.capabilities()
                .contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        }?.first ?: backCameras.firstOrNull()?.first
    }

    private fun CameraCharacteristics.toPhysicalOption(
        logicalCameraId: String,
        physicalCameraId: String,
        mainFocalLength: Float?,
    ): CameraOption {
        val focalLength = focalLength()
        val zoomRatio = if (focalLength != null && mainFocalLength != null && mainFocalLength > 0f) {
            focalLength / mainFocalLength
        } else {
            null
        }
        val zoomLabel = zoomRatio?.let {
            if (it < 0.65f) {
                "0.5X 广角"
            } else if (it < 0.95f) {
                "%.1fX 广角".format(it)
            } else {
                "%.1fX 镜头".format(it)
            }
        } ?: "广角"
        return CameraOption(
            id = CameraSelectionId.encode(logicalCameraId, physicalCameraId),
            label = zoomLabel,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            focalLengthMm = focalLength,
            zoomRatio = zoomRatio,
        )
    }

    private fun CameraCharacteristics.capabilities(): Set<Int> =
        get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()

    private fun CameraCharacteristics.focalLength(): Float? =
        get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()

    private fun CameraCharacteristics.resolutionOptions(): List<String> {
        val sizes = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.media.MediaRecorder::class.java)
            ?.toList()
            .orEmpty()
        return buildList {
            if (sizes.supportsAtLeast(1280, 720)) add("720p")
            if (sizes.supportsAtLeast(1920, 1080)) add("1080p")
            if (sizes.supportsAtLeast(3840, 2160)) add("4K")
        }
    }

    private fun CameraCharacteristics.frameRateOptions(): List<Int> {
        val ranges = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return listOf(24, 30, 60).filter { fps ->
            ranges.any { range -> range.lower <= fps && range.upper >= fps }
        }
    }

    private fun CameraCharacteristics.dynamicRangeOptions(): List<DynamicRangeOption> {
        val profiles = get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?.getSupportedProfiles()
            .orEmpty()
        val recommendedProfile = get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE)
        val hasTenBitDynamicRange = capabilities()
            .contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)
        return buildList {
            add(DynamicRangeOption("sdr", "SDR"))
            if (
                profiles.contains(DynamicRangeProfiles.HLG10) ||
                recommendedProfile == DynamicRangeProfiles.HLG10 ||
                (hasTenBitDynamicRange && profiles.isEmpty())
            ) {
                add(DynamicRangeOption("hlg10", "HLG10 HDR"))
            }
            if (profiles.contains(DynamicRangeProfiles.HDR10) || recommendedProfile == DynamicRangeProfiles.HDR10) {
                add(DynamicRangeOption("hdr10", "HDR10"))
            }
            if (
                profiles.contains(DynamicRangeProfiles.HDR10_PLUS) ||
                recommendedProfile == DynamicRangeProfiles.HDR10_PLUS
            ) {
                add(DynamicRangeOption("hdr10_plus", "HDR10+"))
            }
            if (profiles.any { it in dolbyVisionProfiles }) {
                add(DynamicRangeOption("dolby_vision", "Dolby Vision"))
            }
        }.distinctBy { it.id }
    }

    private fun CameraInfo.cameraXRecordingDynamicRangeOptions(
        camera2Options: List<DynamicRangeOption>,
    ): List<DynamicRangeOption> {
        val recorderRanges: Set<DynamicRange> = runCatching {
            Recorder.getVideoCapabilities(this).supportedDynamicRanges
        }.getOrDefault(emptySet())
        val queriedRanges: Set<DynamicRange> = runCatching {
            querySupportedDynamicRanges(cameraXDynamicRangeCandidates)
        }.getOrDefault(emptySet())
        val cameraXRanges = when {
            recorderRanges.isNotEmpty() && queriedRanges.isNotEmpty() ->
                recorderRanges.intersect(queriedRanges).ifEmpty { recorderRanges }
            recorderRanges.isNotEmpty() -> recorderRanges
            else -> queriedRanges
        }
        return if (cameraXRanges.isNotEmpty()) {
            cameraXRanges.toDynamicRangeOptions()
        } else {
            camera2Options
        }
    }

    private fun recordingCombinations(
        cameraInfo: CameraInfo?,
        resolutionOptions: List<String>,
        frameRateOptions: List<Int>,
        codecOptions: List<VideoCodecOption>,
        dynamicRangeOptions: List<DynamicRangeOption>,
        stabilizationModes: List<StabilizationMode>,
    ): List<RecordingCombination> {
        val dynamicRangeIds = dynamicRangeOptions.map { it.id }.toSet()
        return buildList {
            codecOptions.forEach { codec ->
                val videoCapabilities = cameraInfo?.videoCapabilitiesFor(codec)
                val codecDynamicRanges = if (videoCapabilities != null) {
                    videoCapabilities.supportedDynamicRanges
                        .toDynamicRangeOptions()
                        .filter { it.id in dynamicRangeIds }
                } else {
                    dynamicRangeOptions
                }
                codecDynamicRanges.forEach { dynamicRange ->
                    val resolutions = videoCapabilities
                        ?.supportedResolutionOptions(dynamicRange)
                        ?.let { supported -> resolutionOptions.filter { it in supported } }
                        ?: resolutionOptions
                    resolutions.forEach { resolution ->
                        frameRateOptions.forEach { frameRate ->
                            stabilizationModes
                                .filter { mode -> videoCapabilities.supportsStabilizationMode(mode) }
                                .forEach { stabilizationMode ->
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
        }.distinct()
    }

    private fun CameraInfo.videoCapabilitiesFor(codec: VideoCodecOption): VideoCapabilities? {
        val mimeType = codec.mimeType ?: return runCatching {
            Recorder.getVideoCapabilities(this)
        }.getOrNull()
        return runCatching {
            Recorder.getVideoCapabilities(this, mimeType)
        }.getOrNull()
    }

    private fun VideoCapabilities.supportedResolutionOptions(
        dynamicRange: DynamicRangeOption,
    ): Set<String> {
        val qualities = runCatching {
            getSupportedQualities(dynamicRange.id.toCameraXDynamicRange())
        }.getOrDefault(emptyList())
        return qualities.mapNotNull { it.toResolutionOption() }.toSet()
    }

    private fun VideoCapabilities?.supportsStabilizationMode(mode: StabilizationMode): Boolean =
        mode == StabilizationMode.Off || this?.isStabilizationSupported != false

    private fun Quality.toResolutionOption(): String? = when (this) {
        Quality.HD -> "720p"
        Quality.FHD -> "1080p"
        Quality.UHD -> "4K"
        else -> null
    }

    private fun Set<DynamicRange>.toDynamicRangeOptions(): List<DynamicRangeOption> {
        val ranges = this
        return buildList {
            if (DynamicRange.SDR in ranges) {
                add(DynamicRangeOption("sdr", "SDR"))
            }
            if (DynamicRange.HLG_10_BIT in ranges) {
                add(DynamicRangeOption("hlg10", "HLG10 HDR"))
            }
            if (DynamicRange.HDR10_10_BIT in ranges) {
                add(DynamicRangeOption("hdr10", "HDR10"))
            }
            if (DynamicRange.HDR10_PLUS_10_BIT in ranges) {
                add(DynamicRangeOption("hdr10_plus", "HDR10+"))
            }
            if (DynamicRange.DOLBY_VISION_10_BIT in ranges) {
                add(DynamicRangeOption("dolby_vision", "Dolby Vision"))
            }
        }.ifEmpty {
            listOf(DynamicRangeOption("sdr", "SDR"))
        }
    }

    private fun CameraCharacteristics.stabilizationModes(): List<StabilizationMode> {
        val modes = get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        return buildList {
            add(StabilizationMode.Off)
            if (modes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                add(StabilizationMode.Standard)
            }
            if (modes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) {
                add(StabilizationMode.Enhanced)
            }
        }
    }

    private fun codecOptions(): List<VideoCodecOption> = buildList {
        if (hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            add(VideoCodecOption("h265", "H.265", MediaFormat.MIMETYPE_VIDEO_HEVC))
        }
        if (hasEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)) {
            add(VideoCodecOption("h264", "H.264", MediaFormat.MIMETYPE_VIDEO_AVC))
        }
    }

    private fun hasEncoder(mimeType: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }

    private fun List<Size>.supportsAtLeast(width: Int, height: Int): Boolean =
        any { size ->
            (size.width >= width && size.height >= height) ||
                (size.width >= height && size.height >= width)
        }

    private fun List<String>?.orDefaultResolutions(): List<String> =
        this?.takeIf { it.isNotEmpty() } ?: listOf("720p", "1080p")

    private fun List<Int>?.orDefaultFrameRates(): List<Int> =
        this?.takeIf { it.isNotEmpty() } ?: listOf(30)

    private fun List<DynamicRangeOption>?.orDefaultDynamicRanges(): List<DynamicRangeOption> =
        this?.takeIf { it.isNotEmpty() } ?: listOf(DynamicRangeOption("sdr", "SDR"))

    private fun List<StabilizationMode>?.orDefaultStabilizationModes(): List<StabilizationMode> =
        this?.takeIf { it.isNotEmpty() } ?: listOf(StabilizationMode.Off)

    private fun fallbackCameraOptions(): List<CameraOption> = listOf(
        CameraOption(
            id = "",
            label = "1X 主镜头",
            logicalCameraId = "",
            physicalCameraId = null,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            focalLengthMm = null,
            zoomRatio = 1f,
        ),
    )

    private companion object {
        val cameraXDynamicRangeCandidates = setOf(
            DynamicRange.SDR,
            DynamicRange.HLG_10_BIT,
            DynamicRange.HDR10_10_BIT,
            DynamicRange.HDR10_PLUS_10_BIT,
            DynamicRange.DOLBY_VISION_10_BIT,
        )

        val dolbyVisionProfiles = setOf(
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM,
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO,
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF,
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO,
        )
    }
}
