package com.xxxifan.dashcam.recording

import android.content.Context
import com.xxxifan.dashcam.camera.CameraCapabilities
import com.xxxifan.dashcam.camera.frameRateOptionsForResolution
import com.xxxifan.dashcam.camera.isRecordingCombinationSupported
import com.xxxifan.dashcam.data.BitratePreset
import com.xxxifan.dashcam.data.RecordingSettings
import com.xxxifan.dashcam.data.StabilizationMode
import com.xxxifan.dashcam.storage.RecordingStorageEstimator

object RecordingQualityResolver {
    private const val AUTO_TARGET_SECONDS = 8L * 60L * 60L

    fun resolveAutoQuality(
        context: Context,
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): RecordingSettings {
        if (!requested.autoQualityEnabled) {
            return requested
        }

        val candidates = autoCandidates(requested, capabilities)
        if (candidates.isEmpty()) {
            return requested.copy(bitratePreset = BitratePreset.Standard, autoQualityEnabled = true)
        }

        val safeBytes = RecordingStorageEstimator.safeRecordingCapacityBytes(context, requested)
        val viable = candidates.filter { candidate ->
            RecordingStorageEstimator.estimateBytesPerSecond(candidate) * AUTO_TARGET_SECONDS <= safeBytes
        }
        return viable.maxWithOrNull(autoQualityComparator)
            ?: candidates.minBy { RecordingStorageEstimator.estimateBytesPerSecond(it) }
    }

    private fun autoCandidates(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<RecordingSettings> {
        val resolutions = capabilities.resolutionOptions.sortedBy { resolutionRank(it) }
        val codecs = autoCodecs(requested, capabilities)
        val dynamicRanges = autoDynamicRanges(requested, capabilities)
        val stabilizationModes = autoStabilizationModes(requested, capabilities)

        return buildList {
            resolutions.forEach { resolution ->
                val frameRates = capabilities.frameRateOptionsForResolution(resolution).sorted()
                frameRates.forEach { frameRate ->
                    listOf(BitratePreset.SpaceSaver, BitratePreset.Standard, BitratePreset.HighQuality).forEach { preset ->
                        codecs.forEach { codec ->
                            dynamicRanges.forEach { dynamicRange ->
                                stabilizationModes.forEach { stabilizationMode ->
                                    add(
                                        requested.copy(
                                            resolution = resolution,
                                            frameRate = frameRate,
                                            codec = codec,
                                            bitratePreset = preset,
                                            autoQualityEnabled = true,
                                            dynamicRange = dynamicRange,
                                            stabilizationMode = stabilizationMode,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
            .filter { capabilities.isRecordingCombinationSupported(it) }
            .distinctBy {
                listOf(
                    it.resolution,
                    it.frameRate.toString(),
                    it.codec,
                    it.bitratePreset.name,
                    it.dynamicRange,
                    it.stabilizationMode.name,
                )
            }
    }

    private fun autoCodecs(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<String> {
        val supported = capabilities.codecOptions.map { it.id }.toSet()
        return buildList {
            if ("h265" in supported) {
                add("h265")
            }
            if ("h264" in supported) {
                add("h264")
            }
            if (requested.codec in supported) {
                add(requested.codec)
            }
        }.distinct().ifEmpty { listOf(requested.codec.takeIf { it != "auto" } ?: "h265") }
    }

    private fun autoDynamicRanges(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<String> {
        val supported = capabilities.dynamicRangeOptions.map { it.id }.toSet()
        return buildList {
            if ("sdr" in supported) {
                add("sdr")
            }
            if (requested.dynamicRange != "sdr" && requested.dynamicRange in supported) {
                add(requested.dynamicRange)
            }
        }.ifEmpty { listOf("sdr") }
    }

    private fun autoStabilizationModes(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<StabilizationMode> {
        val supported = capabilities.stabilizationModes
        return buildList {
            if (requested.stabilizationMode in supported) {
                add(requested.stabilizationMode)
            }
            if (requested.stabilizationMode == StabilizationMode.Enhanced && StabilizationMode.Standard in supported) {
                add(StabilizationMode.Standard)
            }
            if (requested.stabilizationMode != StabilizationMode.Off && StabilizationMode.Off in supported) {
                add(StabilizationMode.Off)
            }
        }.ifEmpty {
            supported.ifEmpty { listOf(StabilizationMode.Off) }
        }.distinct()
    }

    private val autoQualityComparator = compareBy<RecordingSettings> { resolutionRank(it.resolution) }
        .thenBy { it.frameRate }
        .thenBy { bitratePresetRank(it.bitratePreset) }
        .thenBy { codecRank(it.codec) }
        .thenBy { if (it.dynamicRange == "sdr") 0 else 1 }
        .thenBy { stabilizationRank(it.stabilizationMode) }

    private fun resolutionRank(resolution: String): Int = when (resolution) {
        "720p" -> 0
        "1080p" -> 1
        "4K" -> 2
        else -> 1
    }

    private fun bitratePresetRank(preset: BitratePreset): Int = when (preset) {
        BitratePreset.SpaceSaver -> 0
        BitratePreset.Standard -> 1
        BitratePreset.HighQuality -> 2
    }

    private fun codecRank(codec: String): Int = when (codec) {
        "h264" -> 0
        "h265" -> 1
        else -> 0
    }

    private fun stabilizationRank(mode: StabilizationMode): Int = when (mode) {
        StabilizationMode.Off -> 0
        StabilizationMode.Standard -> 1
        StabilizationMode.Enhanced -> 2
    }
}
