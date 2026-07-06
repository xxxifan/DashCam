package com.xxxifan.dashcam.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.media.EncoderProfiles
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapabilities
import androidx.camera.video.VideoCapture
import androidx.core.content.getSystemService
import com.xxxifan.dashcam.data.StabilizationMode
import kotlin.math.roundToInt

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
        val officialHighSpeedFrameRateOptionsByResolution = cameraInfo
            ?.officialHighSpeedFrameRateOptionsByResolution(resolutionOptions)
            .orEmpty()
        val frameRateOptionsByResolution = characteristics
            ?.frameRateOptionsByResolution(
                cameraId = logicalBackCamera.orEmpty(),
                cameraInfo = cameraInfo,
                resolutionOptions = resolutionOptions,
                officialHighSpeedFrameRateOptionsByResolution = officialHighSpeedFrameRateOptionsByResolution,
            )
            .orDefaultFrameRatesByResolution(resolutionOptions)
        val frameRateOptions = frameRateOptionsByResolution
            .values
            .flatten()
            .distinct()
            .sorted()
            .orDefaultFrameRates()
        val codecOptions = codecOptions()
        val camera2DynamicRangeOptions = characteristics?.camera2DynamicRangeOptions().orDefaultDynamicRanges()
        val cameraXProbe = cameraInfo?.cameraXRecordingDynamicRangeProbe()
        val dynamicRangeOptions = recordingDynamicRangeOptions(
            camera2Options = camera2DynamicRangeOptions,
            cameraXProbe = cameraXProbe,
        )
        val stabilizationModes = characteristics?.stabilizationModes().orDefaultStabilizationModes()
        val combinations = recordingCombinations(
            cameraInfo = cameraInfo,
            resolutionOptions = resolutionOptions,
            frameRateOptionsByResolution = frameRateOptionsByResolution,
            highSpeedFrameRateOptionsByResolution = officialHighSpeedFrameRateOptionsByResolution,
            codecOptions = codecOptions,
            dynamicRangeOptions = dynamicRangeOptions,
            stabilizationModes = stabilizationModes,
        )
        val hdrDiagnostics = characteristics?.hdrDiagnostics(
            cameraId = logicalBackCamera.orEmpty(),
            cameraXProbe = cameraXProbe,
            hdrExtensionDiagnostics = manager.hdrExtensionDiagnostics(logicalBackCamera),
        ) ?: CameraHdrDiagnostics()
        logHdrDiagnostics(hdrDiagnostics)

        return CameraCapabilities(
            cameraOptions = cameraOptions,
            resolutionOptions = resolutionOptions,
            frameRateOptions = frameRateOptions,
            frameRateOptionsByResolution = frameRateOptionsByResolution,
            highSpeedFrameRateOptionsByResolution = officialHighSpeedFrameRateOptionsByResolution,
            codecOptions = codecOptions,
            dynamicRangeOptions = dynamicRangeOptions,
            stabilizationModes = stabilizationModes,
            supportedRecordingCombinations = combinations,
            hdrDiagnostics = hdrDiagnostics,
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

    private fun CameraCharacteristics.frameRateOptionsByResolution(
        cameraId: String,
        cameraInfo: CameraInfo?,
        resolutionOptions: List<String>,
        officialHighSpeedFrameRateOptionsByResolution: Map<String, List<Int>>,
    ): Map<String, List<Int>> {
        val streamMap = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val recorderSizes = streamMap
            ?.getOutputSizes(MediaRecorder::class.java)
            ?.toList()
            .orEmpty()
        val camera2Ranges = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
            .toList()
        val cameraXRanges = cameraInfo
            ?.let { runCatching { it.supportedFrameRateRanges.toList() }.getOrDefault(emptyList()) }
            .orEmpty()
        val profileFrameRateOptionsByResolution = encoderProfileFrameRateOptionsByResolution(
            cameraId = cameraId,
            resolutionOptions = resolutionOptions,
        )
        val highSpeedFrameRateOptionsByResolution = streamMap.highSpeedFrameRateOptionsByResolution(
            resolutionOptions = resolutionOptions,
        )
        val samsungVendorRanges = samsungEffectAeFrameRateRanges()
        val samsungVendorFrameRateOptionsByResolution = samsungVendorRanges
            .samsungFrameRateOptionsByResolution(resolutionOptions)
        val fallbackRanges = cameraXRanges.ifEmpty { camera2Ranges }
        val result = resolutionOptions.associateWith { resolution ->
            val matchingSizes = recorderSizes.exactSizesForResolution(resolution)
            val maxFps = matchingSizes.maxOfOrNull { size ->
                runCatching {
                    streamMap?.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                        ?.takeIf { it > 0L }
                        ?.toMaxFps()
                        ?: 0
                }.getOrDefault(0)
            } ?: 0
            val sizeLimitedOptions = candidateFrameRates
                .filter { fps -> maxFps >= fps }
            val profileOptions = profileFrameRateOptionsByResolution[resolution].orEmpty()
            val highSpeedOptions = highSpeedFrameRateOptionsByResolution[resolution].orEmpty()
            val samsungVendorOptions = samsungVendorFrameRateOptionsByResolution[resolution].orEmpty()
            val recordingOptions = (
                sizeLimitedOptions +
                    profileOptions +
                    highSpeedOptions +
                    samsungVendorOptions
                )
                .distinct()
                .sorted()
            val fallbackOptions = candidateFrameRates
                .filter { fps -> fallbackRanges.supportsFps(fps) }
            recordingOptions
                .ifEmpty { fallbackOptions }
                .ifEmpty { listOf(30) }
        }
        logFpsDiagnostics(
            cameraId = cameraId,
            camera2Ranges = camera2Ranges,
            cameraXRanges = cameraXRanges,
            recorderSizes = recorderSizes,
            profileFrameRateOptionsByResolution = profileFrameRateOptionsByResolution,
            highSpeedFrameRateOptionsByResolution = highSpeedFrameRateOptionsByResolution,
            officialHighSpeedFrameRateOptionsByResolution = officialHighSpeedFrameRateOptionsByResolution,
            samsungVendorFrameRateOptionsByResolution = samsungVendorFrameRateOptionsByResolution,
            samsungVendorRanges = samsungVendorRanges,
            frameRateOptionsByResolution = result,
        )
        return result
    }

    private fun CameraCharacteristics.camera2DynamicRangeOptions(): List<DynamicRangeOption> {
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
            if (profiles.any { it in tenBitDolbyVisionProfiles }) {
                add(DynamicRangeOption("dolby_vision", "Dolby Vision"))
            }
        }.distinctBy { it.id }
    }

    private fun CameraInfo.cameraXRecordingDynamicRangeProbe(): CameraXDynamicRangeProbe {
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
        return CameraXDynamicRangeProbe(
            recorderRanges = recorderRanges,
            queriedRanges = queriedRanges,
            resolvedRanges = cameraXRanges,
        )
    }

    private fun recordingDynamicRangeOptions(
        camera2Options: List<DynamicRangeOption>,
        cameraXProbe: CameraXDynamicRangeProbe?,
    ): List<DynamicRangeOption> {
        val cameraXOptions = cameraXProbe
            ?.resolvedRanges
            ?.takeIf { it.isNotEmpty() }
            ?.toDynamicRangeOptions(allowDefault = false)
        val confirmedIds = if (cameraXOptions != null) {
            camera2Options.map { it.id }.toSet().intersect(cameraXOptions.map { it.id }.toSet())
        } else {
            camera2Options
                .map { it.id }
                .toSet()
        }
        return camera2Options
            .filter { it.id == "sdr" || it.id in confirmedIds }
            .orDefaultDynamicRanges()
    }

    private fun recordingCombinations(
        cameraInfo: CameraInfo?,
        resolutionOptions: List<String>,
        frameRateOptionsByResolution: Map<String, List<Int>>,
        highSpeedFrameRateOptionsByResolution: Map<String, List<Int>>,
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
                        val frameRateOptions = frameRateOptionsByResolution[resolution]
                            .orDefaultFrameRates()
                        frameRateOptions.forEach { frameRate ->
                            if (
                                frameRate > MAX_REGULAR_FRAME_RATE &&
                                frameRate !in highSpeedFrameRateOptionsByResolution[resolution].orEmpty()
                            ) {
                                return@forEach
                            }
                            if (!codec.supportsResolutionFrameRate(resolution, frameRate)) {
                                return@forEach
                            }
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

    private fun Set<DynamicRange>.toDynamicRangeOptions(
        allowDefault: Boolean = true,
    ): List<DynamicRangeOption> {
        val ranges = this
        val options = buildList {
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
        }
        return if (options.isEmpty() && allowDefault) {
            listOf(DynamicRangeOption("sdr", "SDR"))
        } else {
            options
        }
    }

    private fun CameraCharacteristics.hdrDiagnostics(
        cameraId: String,
        cameraXProbe: CameraXDynamicRangeProbe?,
        hdrExtensionDiagnostics: HdrExtensionDiagnostics,
    ): CameraHdrDiagnostics {
        val profiles = get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        val supportedProfiles = profiles?.supportedProfiles.orEmpty()
        val tenBitProfiles = supportedProfiles.filter { it.isTenBitDynamicRangeProfile() }
        val recommendedProfile = get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE)
        return CameraHdrDiagnostics(
            cameraId = cameraId,
            hasTenBitDynamicRangeCapability = capabilities()
                .contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT),
            camera2SupportedProfiles = supportedProfiles.map { it.dynamicRangeProfileName() },
            camera2TenBitProfiles = tenBitProfiles.map { it.dynamicRangeProfileName() },
            camera2RecommendedProfile = recommendedProfile?.dynamicRangeProfileName(),
            cameraXRecorderDynamicRanges = cameraXProbe?.recorderRanges
                ?.map { it.dynamicRangeName() }
                .orEmpty(),
            cameraXQueriedDynamicRanges = cameraXProbe?.queriedRanges
                ?.map { it.dynamicRangeName() }
                .orEmpty(),
            cameraXResolvedDynamicRanges = cameraXProbe?.resolvedRanges
                ?.map { it.dynamicRangeName() }
                .orEmpty(),
            hdrExtensionDiagnostics = hdrExtensionDiagnostics,
        )
    }

    private fun CameraManager.hdrExtensionDiagnostics(cameraId: String): HdrExtensionDiagnostics {
        return runCatching {
            val characteristics = getCameraExtensionCharacteristics(cameraId)
            val supportedExtensions = characteristics.supportedExtensions
            val hdrExtensionSupported =
                CameraExtensionCharacteristics.EXTENSION_HDR in supportedExtensions
            if (!hdrExtensionSupported) {
                return HdrExtensionDiagnostics(
                    cameraId = cameraId,
                    supportedExtensions = supportedExtensions.map { it.extensionName() },
                    hdrExtensionSupported = false,
                )
            }
            val extensionProfiles = runCatching {
                characteristics.get(
                    CameraExtensionCharacteristics.EXTENSION_HDR,
                    CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES,
                )?.supportedProfiles.orEmpty()
            }.getOrDefault(emptySet())
            val extensionRecommendedProfile = runCatching {
                characteristics.get(
                    CameraExtensionCharacteristics.EXTENSION_HDR,
                    CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE,
                )
            }.getOrNull()
            val previewSizes = runCatching {
                characteristics.getExtensionSupportedSizes(
                    CameraExtensionCharacteristics.EXTENSION_HDR,
                    SurfaceTexture::class.java,
                )
            }.getOrDefault(emptyList())
            val captureRequestKeys = runCatching {
                characteristics.getAvailableCaptureRequestKeys(CameraExtensionCharacteristics.EXTENSION_HDR)
            }.getOrDefault(emptySet())
            HdrExtensionDiagnostics(
                cameraId = cameraId,
                supportedExtensions = supportedExtensions.map { it.extensionName() },
                hdrExtensionSupported = true,
                hdrExtensionDynamicRangeProfiles = extensionProfiles
                    .filter { it.isTenBitDynamicRangeProfile() }
                    .map { it.dynamicRangeProfileName() },
                hdrExtensionRecommendedProfile = extensionRecommendedProfile?.dynamicRangeProfileName(),
                hdrExtensionPreviewSizes = previewSizes.map { "${it.width}x${it.height}" },
                hdrExtensionCaptureRequestKeys = captureRequestKeys.map { it.name }.sorted(),
            )
        }.getOrElse { error ->
            HdrExtensionDiagnostics(
                cameraId = cameraId,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun logHdrDiagnostics(diagnostics: CameraHdrDiagnostics) {
        if (diagnostics.cameraId.isBlank()) {
            return
        }
        Log.d(
            HDR_CHECK_TAG,
            "camera=${diagnostics.cameraId}, 10bitCapability=${diagnostics.hasTenBitDynamicRangeCapability}",
        )
        Log.d(
            HDR_CHECK_TAG,
            "Camera2 profiles=${diagnostics.camera2SupportedProfiles}, " +
                "10bitProfiles=${diagnostics.camera2TenBitProfiles}, " +
                "recommended=${diagnostics.camera2RecommendedProfile}",
        )
        Log.d(
            HDR_CHECK_TAG,
            "CameraX recorder=${diagnostics.cameraXRecorderDynamicRanges}, " +
                "queried=${diagnostics.cameraXQueriedDynamicRanges}, " +
                "resolved=${diagnostics.cameraXResolvedDynamicRanges}",
        )
        Log.d(
            HDR_CHECK_TAG,
            "HDR extension supported=${diagnostics.hdrExtensionDiagnostics.hdrExtensionSupported}, " +
                "profiles=${diagnostics.hdrExtensionDiagnostics.hdrExtensionDynamicRangeProfiles}, " +
                "previewSizes=${diagnostics.hdrExtensionDiagnostics.hdrExtensionPreviewSizes}, " +
                "error=${diagnostics.hdrExtensionDiagnostics.error}",
        )
    }

    private fun logFpsDiagnostics(
        cameraId: String,
        camera2Ranges: List<Range<Int>>,
        cameraXRanges: List<Range<Int>>,
        recorderSizes: List<Size>,
        profileFrameRateOptionsByResolution: Map<String, List<Int>>,
        highSpeedFrameRateOptionsByResolution: Map<String, List<Int>>,
        officialHighSpeedFrameRateOptionsByResolution: Map<String, List<Int>>,
        samsungVendorFrameRateOptionsByResolution: Map<String, List<Int>>,
        samsungVendorRanges: List<Range<Int>>,
        frameRateOptionsByResolution: Map<String, List<Int>>,
    ) {
        Log.d(
            FPS_CHECK_TAG,
            "camera=$cameraId, Camera2 fps=${camera2Ranges.rangeLabels()}, " +
                "CameraX fps=${cameraXRanges.rangeLabels()}, " +
                "profiles=$profileFrameRateOptionsByResolution, " +
                "highSpeed=$highSpeedFrameRateOptionsByResolution, " +
                "officialHighSpeed=$officialHighSpeedFrameRateOptionsByResolution, " +
                "Samsung fps=${samsungVendorRanges.rangeLabels()}, " +
                "Samsung resolved=$samsungVendorFrameRateOptionsByResolution, " +
                "MediaRecorder sizes=${recorderSizes.sizeLabels()}, " +
                "resolved=$frameRateOptionsByResolution",
        )
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

    private fun VideoCodecOption.supportsResolutionFrameRate(
        resolution: String,
        frameRate: Int,
    ): Boolean {
        val mimeType = mimeType ?: return true
        val (width, height) = resolution.toTargetSize() ?: return true
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { codec -> codec.isEncoder }
            .filter { codec -> codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
            .any { codec ->
                runCatching {
                    val capabilities = codec.getCapabilitiesForType(mimeType).videoCapabilities
                        ?: return@runCatching false
                    capabilities.areSizeAndRateSupported(width, height, frameRate.toDouble()) ||
                        capabilities.areSizeAndRateSupported(height, width, frameRate.toDouble())
                }.getOrDefault(false)
            }
    }

    private fun List<Size>.supportsAtLeast(width: Int, height: Int): Boolean =
        any { size ->
            (size.width >= width && size.height >= height) ||
                (size.width >= height && size.height >= width)
        }

    private fun List<Size>.exactSizesForResolution(resolution: String): List<Size> {
        val (width, height) = resolution.toTargetSize() ?: return emptyList()
        return filter { it.matchesDimensions(width, height) }
    }

    @Suppress("DEPRECATION")
    private fun encoderProfileFrameRateOptionsByResolution(
        cameraId: String,
        resolutionOptions: List<String>,
    ): Map<String, List<Int>> =
        resolutionOptions.associateWith { resolution ->
            val (width, height) = resolution.toTargetSize() ?: return@associateWith emptyList()
            resolution.profileQualities().flatMap { quality ->
                runCatching {
                    CamcorderProfile.getAll(cameraId, quality)
                }.getOrNull()
                    ?.videoProfiles
                    ?.filter { profile -> profile.matchesDimensions(width, height) }
                    ?.flatMap { profile ->
                        candidateFrameRates.filter { fps -> profile.frameRate >= fps }
                    }
                    .orEmpty()
            }
                .distinct()
                .sorted()
        }

    private fun CameraInfo.officialHighSpeedFrameRateOptionsByResolution(
        resolutionOptions: List<String>,
    ): Map<String, List<Int>> {
        val highSpeedCapabilities = runCatching {
            Recorder.getHighSpeedVideoCapabilities(this)
        }.onFailure { error ->
            Log.w(FPS_CHECK_TAG, "Failed to query CameraX official high-speed capabilities.", error)
        }.getOrNull() ?: return emptyMap()
        val supportedQualities = runCatching {
            highSpeedCapabilities.getSupportedQualities(DynamicRange.SDR)
        }.onFailure { error ->
            Log.w(FPS_CHECK_TAG, "Failed to query CameraX official high-speed qualities.", error)
        }.getOrDefault(emptyList())
        return resolutionOptions.associateWith { resolution ->
            val quality = resolution.toQuality()
                ?: return@associateWith emptyList()
            if (quality !in supportedQualities) {
                return@associateWith emptyList()
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .build()
            val capture = VideoCapture.withOutput(recorder)
            val sessionConfig = HighSpeedVideoSessionConfig(capture)
            runCatching {
                getSupportedFrameRateRanges(sessionConfig)
                    .filter { range -> range.lower == range.upper }
                    .map { range -> range.upper }
                    .filter { fps -> fps in candidateFrameRates }
                    .distinct()
                    .sorted()
            }.onFailure { error ->
                Log.w(
                    FPS_CHECK_TAG,
                    "Failed to query CameraX official high-speed fps for $resolution.",
                    error,
                )
            }.getOrDefault(emptyList())
        }
    }

    private fun StreamConfigurationMap?.highSpeedFrameRateOptionsByResolution(
        resolutionOptions: List<String>,
    ): Map<String, List<Int>> =
        resolutionOptions.associateWith { resolution ->
            val (width, height) = resolution.toTargetSize() ?: return@associateWith emptyList()
            val size = Size(width, height)
            val ranges = this?.let { streamMap ->
                runCatching {
                    streamMap.getHighSpeedVideoFpsRangesFor(size).toList()
                }.getOrDefault(emptyList())
            }.orEmpty()
            candidateFrameRates
                .filter { fps -> ranges.supportsCaptureFps(fps) }
                .distinct()
                .sorted()
        }

    private fun CameraCharacteristics.samsungEffectAeFrameRateRanges(): List<Range<Int>> {
        val key = CameraCharacteristics.Key(
            "samsung.android.control.effectAeAvailableTargetFpsRanges",
            IntArray::class.java,
        )
        val values = runCatching { get(key) }.getOrNull() ?: return emptyList()
        return values
            .toList()
            .chunked(2)
            .mapNotNull { range ->
                if (range.size != 2) {
                    null
                } else {
                    Range(
                        range[0].coerceAtMost(range[1]),
                        range[0].coerceAtLeast(range[1]),
                    )
                }
            }
    }

    private fun List<Range<Int>>.samsungFrameRateOptionsByResolution(
        resolutionOptions: List<String>,
    ): Map<String, List<Int>> {
        if (isEmpty()) {
            return emptyMap()
        }
        return resolutionOptions.associateWith { resolution ->
            if (resolution == "4K") {
                emptyList()
            } else {
                candidateFrameRates
                    .filter { fps -> supportsFps(fps) }
                    .distinct()
                    .sorted()
            }
        }
    }

    private fun String.toTargetSize(): Pair<Int, Int>? = when (this) {
        "720p" -> 1280 to 720
        "1080p" -> 1920 to 1080
        "4K" -> 3840 to 2160
        else -> null
    }

    private fun String.toQuality(): Quality? = when (this) {
        "720p" -> Quality.HD
        "1080p" -> Quality.FHD
        "4K" -> Quality.UHD
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun String.profileQualities(): List<Int> = when (this) {
        "720p" -> listOf(
            CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_HIGH_SPEED_720P,
        )
        "1080p" -> listOf(
            CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_HIGH_SPEED_1080P,
        )
        "4K" -> listOf(
            CamcorderProfile.QUALITY_2160P,
            CamcorderProfile.QUALITY_HIGH_SPEED_2160P,
        )
        else -> emptyList()
    }

    private fun EncoderProfiles.VideoProfile.matchesDimensions(width: Int, height: Int): Boolean =
        (this.width == width && this.height == height) ||
            (this.width == height && this.height == width)

    private fun Size.matchesDimensions(width: Int, height: Int): Boolean =
        (this.width == width && this.height == height) ||
            (this.width == height && this.height == width)

    private fun Long.toMaxFps(): Int =
        (NANOS_PER_SECOND.toDouble() / this.toDouble()).roundToInt()

    private fun List<Range<Int>>.supportsFps(fps: Int): Boolean =
        any { range -> range.lower <= fps && range.upper >= fps }

    private fun List<Range<Int>>.supportsCaptureFps(fps: Int): Boolean =
        any { range -> range.lower <= fps && range.upper == fps }

    private fun List<Range<Int>>.rangeLabels(): List<String> =
        map { "${it.lower}-${it.upper}" }

    private fun List<Size>.sizeLabels(): List<String> =
        map { "${it.width}x${it.height}" }

    private fun List<String>?.orDefaultResolutions(): List<String> =
        this?.takeIf { it.isNotEmpty() } ?: listOf("720p", "1080p")

    private fun List<Int>?.orDefaultFrameRates(): List<Int> =
        this?.takeIf { it.isNotEmpty() } ?: listOf(30)

    private fun Map<String, List<Int>>?.orDefaultFrameRatesByResolution(
        resolutionOptions: List<String>,
    ): Map<String, List<Int>> =
        this?.takeIf { it.isNotEmpty() }
            ?: resolutionOptions.associateWith { listOf(30) }

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

    private fun Long.dynamicRangeProfileName(): String = when (this) {
        DynamicRangeProfiles.STANDARD -> "STANDARD"
        DynamicRangeProfiles.HLG10 -> "HLG10"
        DynamicRangeProfiles.HDR10 -> "HDR10"
        DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_HDR_REF"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO -> "DOLBY_VISION_10B_HDR_REF_PO"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "DOLBY_VISION_10B_HDR_OEM"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO -> "DOLBY_VISION_10B_HDR_OEM_PO"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "DOLBY_VISION_8B_HDR_REF"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO -> "DOLBY_VISION_8B_HDR_REF_PO"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM -> "DOLBY_VISION_8B_HDR_OEM"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO -> "DOLBY_VISION_8B_HDR_OEM_PO"
        else -> "UNKNOWN_$this"
    }

    private fun Long.isTenBitDynamicRangeProfile(): Boolean =
        this == DynamicRangeProfiles.HLG10 ||
            this == DynamicRangeProfiles.HDR10 ||
            this == DynamicRangeProfiles.HDR10_PLUS ||
            this in tenBitDolbyVisionProfiles

    private fun DynamicRange.dynamicRangeName(): String = when (this) {
        DynamicRange.SDR -> "SDR"
        DynamicRange.HLG_10_BIT -> "HLG10"
        DynamicRange.HDR10_10_BIT -> "HDR10"
        DynamicRange.HDR10_PLUS_10_BIT -> "HDR10_PLUS"
        DynamicRange.DOLBY_VISION_10_BIT -> "DOLBY_VISION_10_BIT"
        DynamicRange.DOLBY_VISION_8_BIT -> "DOLBY_VISION_8_BIT"
        DynamicRange.HDR_UNSPECIFIED_10_BIT -> "HDR_UNSPECIFIED_10_BIT"
        DynamicRange.UNSPECIFIED -> "UNSPECIFIED"
        else -> toString()
    }

    private fun Int.extensionName(): String = when (this) {
        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTOMATIC"
        CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
        CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
        CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
        CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
        else -> "VENDOR_$this"
    }

    private data class CameraXDynamicRangeProbe(
        val recorderRanges: Set<DynamicRange>,
        val queriedRanges: Set<DynamicRange>,
        val resolvedRanges: Set<DynamicRange>,
    )

    private companion object {
        const val HDR_CHECK_TAG = "HDR_CHECK"
        const val FPS_CHECK_TAG = "FPS_CHECK"
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MAX_REGULAR_FRAME_RATE = 30

        val candidateFrameRates = listOf(24, 30, 60)

        val cameraXDynamicRangeCandidates = setOf(
            DynamicRange.SDR,
            DynamicRange.HLG_10_BIT,
            DynamicRange.HDR10_10_BIT,
            DynamicRange.HDR10_PLUS_10_BIT,
            DynamicRange.DOLBY_VISION_10_BIT,
        )

        val tenBitDolbyVisionProfiles = setOf(
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM,
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO,
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF,
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO,
        )
    }
}
