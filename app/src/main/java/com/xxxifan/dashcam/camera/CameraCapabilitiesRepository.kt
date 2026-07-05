package com.xxxifan.dashcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import androidx.core.content.getSystemService
import com.xxxifan.dashcam.data.StabilizationMode

class CameraCapabilitiesRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun capabilities(): CameraCapabilities {
        val manager = appContext.getSystemService<CameraManager>()
        val logicalBackCamera = manager?.logicalBackCamera()
        val characteristics = logicalBackCamera?.let { runCatching { manager.getCameraCharacteristics(it) }.getOrNull() }
        val cameraOptions = manager?.let { backCameraOptions(it) } ?: fallbackCameraOptions()

        return CameraCapabilities(
            cameraOptions = cameraOptions,
            resolutionOptions = characteristics?.resolutionOptions().orDefaultResolutions(),
            frameRateOptions = characteristics?.frameRateOptions().orDefaultFrameRates(),
            codecOptions = codecOptions(),
            dynamicRangeOptions = characteristics?.dynamicRangeOptions().orDefaultDynamicRanges(),
            stabilizationModes = characteristics?.stabilizationModes().orDefaultStabilizationModes(),
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
        return buildList {
            add(DynamicRangeOption("sdr", "SDR"))
            if (profiles.contains(DynamicRangeProfiles.HLG10)) {
                add(DynamicRangeOption("hlg10", "HLG10 HDR"))
            }
            if (profiles.contains(DynamicRangeProfiles.HDR10)) {
                add(DynamicRangeOption("hdr10", "HDR10"))
            }
            if (profiles.contains(DynamicRangeProfiles.HDR10_PLUS)) {
                add(DynamicRangeOption("hdr10_plus", "HDR10+"))
            }
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
}
