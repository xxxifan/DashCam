package com.xxxifan.dashcam.recording

import com.xxxifan.dashcam.camera.CameraCapabilities
import com.xxxifan.dashcam.data.BitratePreset
import com.xxxifan.dashcam.data.RecordingSettings
import com.xxxifan.dashcam.data.StabilizationMode
import com.xxxifan.dashcam.storage.RecordingStorageEstimator

object RecordingStartupFallbackPolicy {
    fun fallbackCandidates(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<RecordingSettings> {
        val resolutions = fallbackResolutions(requested, capabilities)
        val frameRates = fallbackFrameRates(requested, capabilities)
        val dynamicRanges = fallbackDynamicRanges(requested, capabilities)
        val stabilizationModes = fallbackStabilizationModes(requested, capabilities)
        val codecs = fallbackCodecs(requested, capabilities)
        val bitratePresets = fallbackBitratePresets(requested)

        return buildList {
            resolutions.forEach { resolution ->
                frameRates.forEach { frameRate ->
                    dynamicRanges.forEach { dynamicRange ->
                        stabilizationModes.forEach { stabilizationMode ->
                            codecs.forEach { codec ->
                                bitratePresets.forEach { bitratePreset ->
                                    add(
                                        requested.copy(
                                            resolution = resolution,
                                            frameRate = frameRate,
                                            codec = codec,
                                            bitratePreset = bitratePreset,
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
            .filter { it != requested }
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
            .sortedWith(
                compareBy<RecordingSettings> { RecordingStorageEstimator.estimateSegmentBytes(it) }
                    .thenBy { resolutionRank(it.resolution) }
                    .thenBy { if (it.frameRate <= 30) 0 else 1 }
                    .thenBy { if (it.dynamicRange == "sdr") 0 else 1 }
                    .thenBy { stabilizationRank(it.stabilizationMode) },
            )
    }

    private fun fallbackResolutions(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<String> {
        val requestedRank = resolutionRank(requested.resolution)
        val supported = capabilities.resolutionOptions
            .filter { resolutionRank(it) <= requestedRank }
            .sortedBy { resolutionRank(it) }
        return supported.ifEmpty {
            capabilities.resolutionOptions.sortedBy { resolutionRank(it) }
        }
    }

    private fun fallbackFrameRates(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<Int> {
        val supported = capabilities.frameRateOptions
        val stableLowerFrameRate = when {
            requested.frameRate > 30 && 30 in supported -> 30
            requested.frameRate > 30 -> supported.filter { it < requested.frameRate }.maxOrNull()
            else -> null
        }
        return buildList {
            stableLowerFrameRate?.let { add(it) }
            if (requested.frameRate in supported) {
                add(requested.frameRate)
            }
            if (isEmpty()) {
                supported.minOrNull()?.let { add(it) }
            }
        }.distinct()
    }

    private fun fallbackDynamicRanges(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<String> {
        val supported = capabilities.dynamicRangeOptions.map { it.id }.toSet()
        return buildList {
            if ("sdr" in supported) {
                add("sdr")
            }
            if (requested.dynamicRange in supported) {
                add(requested.dynamicRange)
            }
        }.distinct()
    }

    private fun fallbackStabilizationModes(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<StabilizationMode> {
        val supported = capabilities.stabilizationModes
        return buildList {
            if (requested.stabilizationMode == StabilizationMode.Enhanced) {
                if (StabilizationMode.Standard in supported) {
                    add(StabilizationMode.Standard)
                }
                if (StabilizationMode.Off in supported) {
                    add(StabilizationMode.Off)
                }
            }
            if (requested.stabilizationMode in supported) {
                add(requested.stabilizationMode)
            }
            if (isEmpty()) {
                supported.firstOrNull()?.let { add(it) }
            }
        }.distinct()
    }

    private fun fallbackCodecs(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
    ): List<String> {
        val supported = capabilities.codecOptions.map { it.id }.toSet()
        return buildList {
            if (requested.codec in supported) {
                add(requested.codec)
            }
            if (requested.codec == "h265" && "h264" in supported) {
                add("h264")
            }
            if (isEmpty()) {
                supported.firstOrNull()?.let { add(it) }
            }
        }.distinct()
    }

    private fun fallbackBitratePresets(requested: RecordingSettings): List<BitratePreset> =
        buildList {
            when (requested.bitratePreset) {
                BitratePreset.HighQuality -> {
                    add(BitratePreset.Standard)
                    add(BitratePreset.SpaceSaver)
                }
                BitratePreset.Standard -> add(BitratePreset.SpaceSaver)
                BitratePreset.SpaceSaver,
                -> Unit
            }
            add(requested.bitratePreset)
        }.ifEmpty {
            listOf(BitratePreset.SpaceSaver)
        }.distinct()

    private fun resolutionRank(resolution: String): Int = when (resolution) {
        "720p" -> 0
        "1080p" -> 1
        "4K" -> 2
        else -> 1
    }

    private fun stabilizationRank(mode: StabilizationMode): Int = when (mode) {
        StabilizationMode.Standard -> 0
        StabilizationMode.Off -> 1
        StabilizationMode.Enhanced -> 2
    }
}
