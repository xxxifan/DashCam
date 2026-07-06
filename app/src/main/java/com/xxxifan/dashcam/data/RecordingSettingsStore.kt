package com.xxxifan.dashcam.data

import com.tencent.mmkv.MMKV

class RecordingSettingsStore(
    private val mmkv: MMKV = MMKV.mmkvWithID("settings"),
) {
    fun get(): RecordingSettings {
        val rawBitratePreset = mmkv.decodeString(KEY_BITRATE_PRESET, BitratePreset.Standard.name)
        val legacyAutoQuality = rawBitratePreset == LEGACY_AUTO_PRESET
        return RecordingSettings(
            segmentMinutes = mmkv.decodeInt(KEY_SEGMENT_MINUTES, 2),
            audioEnabled = mmkv.decodeBool(KEY_AUDIO_ENABLED, true),
            audioProcessingMode = decodeAudioProcessingMode(),
            resolution = mmkv.decodeString(KEY_RESOLUTION, "720p") ?: "720p",
            frameRate = mmkv.decodeInt(KEY_FRAME_RATE, 30),
            codec = decodeCodec(),
            bitratePreset = decodeBitratePreset(rawBitratePreset),
            autoQualityEnabled = mmkv.decodeBool(KEY_AUTO_QUALITY_ENABLED, legacyAutoQuality),
            dynamicRange = mmkv.decodeString(KEY_DYNAMIC_RANGE, "sdr") ?: "sdr",
            stabilizationMode = decodeStabilization(),
            cameraId = mmkv.decodeString(KEY_CAMERA_ID, "") ?: "",
            cameraLabel = mmkv.decodeString(KEY_CAMERA_LABEL, "1X 主镜头") ?: "1X 主镜头",
            cropZoomRatio = mmkv.decodeFloat(KEY_CROP_ZOOM_RATIO, DEFAULT_CROP_ZOOM_RATIO)
                .coerceCropZoomRatio(),
            focusMode = decodeFocusMode(),
            autoDowngradeEnabled = mmkv.decodeBool(KEY_AUTO_DOWNGRADE, true),
            reservePercent = mmkv.decodeInt(KEY_RESERVE_PERCENT, 10),
            loopQuotaBytes = mmkv.decodeLong(KEY_LOOP_QUOTA_BYTES, 0L).takeIf { it > 0L },
        )
    }

    fun update(update: (RecordingSettings) -> RecordingSettings): RecordingSettings {
        val next = update(get())
        save(next)
        return next
    }

    fun save(settings: RecordingSettings) {
        mmkv.encode(KEY_SEGMENT_MINUTES, settings.segmentMinutes)
        mmkv.encode(KEY_AUDIO_ENABLED, settings.audioEnabled)
        mmkv.encode(KEY_AUDIO_PROCESSING_MODE, settings.audioProcessingMode.name)
        mmkv.encode(KEY_RESOLUTION, settings.resolution)
        mmkv.encode(KEY_FRAME_RATE, settings.frameRate)
        mmkv.encode(KEY_CODEC, settings.codec)
        mmkv.encode(KEY_BITRATE_PRESET, settings.bitratePreset.name)
        mmkv.encode(KEY_AUTO_QUALITY_ENABLED, settings.autoQualityEnabled)
        mmkv.encode(KEY_DYNAMIC_RANGE, settings.dynamicRange)
        mmkv.encode(KEY_STABILIZATION, settings.stabilizationMode.name)
        mmkv.encode(KEY_CAMERA_ID, settings.cameraId)
        mmkv.encode(KEY_CAMERA_LABEL, settings.cameraLabel)
        mmkv.encode(KEY_CROP_ZOOM_RATIO, settings.cropZoomRatio.coerceCropZoomRatio())
        mmkv.encode(KEY_FOCUS_MODE, settings.focusMode.name)
        mmkv.encode(KEY_AUTO_DOWNGRADE, settings.autoDowngradeEnabled)
        mmkv.encode(KEY_RESERVE_PERCENT, settings.reservePercent)
        mmkv.encode(KEY_LOOP_QUOTA_BYTES, settings.loopQuotaBytes ?: 0L)
    }

    private fun decodeStabilization(): StabilizationMode {
        val name = mmkv.decodeString(KEY_STABILIZATION, StabilizationMode.Standard.name)
        return runCatching {
            StabilizationMode.valueOf(name ?: StabilizationMode.Standard.name)
        }.getOrDefault(StabilizationMode.Standard)
    }

    private fun decodeFocusMode(): FocusMode {
        val name = mmkv.decodeString(KEY_FOCUS_MODE, FocusMode.Auto.name)
        return runCatching {
            FocusMode.valueOf(name ?: FocusMode.Auto.name)
        }.getOrDefault(FocusMode.Auto)
    }

    private fun decodeAudioProcessingMode(): AudioProcessingMode {
        val name = mmkv.decodeString(KEY_AUDIO_PROCESSING_MODE, AudioProcessingMode.Camcorder.name)
        return runCatching {
            AudioProcessingMode.valueOf(name ?: AudioProcessingMode.Camcorder.name)
        }.getOrDefault(AudioProcessingMode.Camcorder)
    }

    private fun decodeCodec(): String {
        val value = mmkv.decodeString(KEY_CODEC, DEFAULT_CODEC) ?: DEFAULT_CODEC
        return if (value == LEGACY_AUTO_CODEC) DEFAULT_CODEC else value
    }

    private fun decodeBitratePreset(name: String?): BitratePreset {
        if (name == LEGACY_AUTO_PRESET) {
            return BitratePreset.Standard
        }
        return runCatching {
            BitratePreset.valueOf(name ?: BitratePreset.Standard.name)
        }.getOrDefault(BitratePreset.Standard)
    }

    private companion object {
        const val KEY_SEGMENT_MINUTES = "segment_minutes"
        const val KEY_AUDIO_ENABLED = "audio_enabled"
        const val KEY_AUDIO_PROCESSING_MODE = "audio_processing_mode"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FRAME_RATE = "frame_rate"
        const val KEY_CODEC = "codec"
        const val KEY_BITRATE_PRESET = "bitrate_preset"
        const val KEY_AUTO_QUALITY_ENABLED = "auto_quality_enabled"
        const val KEY_DYNAMIC_RANGE = "dynamic_range"
        const val KEY_STABILIZATION = "stabilization"
        const val KEY_CAMERA_ID = "camera_id"
        const val KEY_CAMERA_LABEL = "camera_label"
        const val KEY_CROP_ZOOM_RATIO = "crop_zoom_ratio"
        const val KEY_FOCUS_MODE = "focus_mode"
        const val KEY_AUTO_DOWNGRADE = "auto_downgrade"
        const val KEY_RESERVE_PERCENT = "reserve_percent"
        const val KEY_LOOP_QUOTA_BYTES = "loop_quota_bytes"
        const val DEFAULT_CODEC = "h265"
        const val LEGACY_AUTO_CODEC = "auto"
        const val LEGACY_AUTO_PRESET = "Auto"
    }
}
