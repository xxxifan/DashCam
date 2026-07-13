package com.xxxifan.dashcam.audio

import org.json.JSONObject

const val CURRENT_AUDIO_DENOISE_VERSION = 2

enum class AudioDenoiseStatus {
    Pending,
    Analyzing,
    Processing,
    Completed,
    SkippedClean,
    SkippedUncertain,
    Paused,
    Failed,
}

data class AudioDenoiseTask(
    val recordingId: String,
    val status: AudioDenoiseStatus,
    val manuallyRequested: Boolean = false,
    val forceProcessing: Boolean = false,
    val attemptCount: Int = 0,
    val processorVersion: Int = 0,
    val progress: Int? = null,
    val detail: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject()
        .put("recordingId", recordingId)
        .put("status", status.name)
        .put("manuallyRequested", manuallyRequested)
        .put("forceProcessing", forceProcessing)
        .put("attemptCount", attemptCount)
        .put("processorVersion", processorVersion)
        .put("progress", progress)
        .put("detail", detail)
        .put("updatedAtMillis", updatedAtMillis)
        .toString()

    companion object {
        fun fromJson(value: String): AudioDenoiseTask {
            val json = JSONObject(value)
            return AudioDenoiseTask(
                recordingId = json.getString("recordingId"),
                status = AudioDenoiseStatus.valueOf(json.getString("status")),
                manuallyRequested = json.optBoolean("manuallyRequested", false),
                forceProcessing = json.optBoolean("forceProcessing", false),
                attemptCount = json.optInt("attemptCount", 0),
                processorVersion = json.optInt("processorVersion", 0),
                progress = if (json.isNull("progress")) null else json.optInt("progress"),
                detail = json.optString("detail").takeIf { it.isNotBlank() },
                updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
            )
        }
    }
}

enum class AudioNoiseClassification {
    Clean,
    Noise,
    Uncertain,
}

data class AudioNoiseAnalysis(
    val classification: AudioNoiseClassification,
    val noiseFloorDbfs: Double,
    val dynamicRangeDb: Double,
    val lowVsVoiceDb: Double,
    val highVsVoiceDb: Double,
    val spectralFlatness: Double,
    val lowSuppressionDb: Double,
    val midSuppressionDb: Double,
    val highSuppressionDb: Double,
    val harshNoiseEvents: List<HarshNoiseEvent> = emptyList(),
    val reason: String,
)

data class HarshNoiseEvent(
    val startSeconds: Double,
    val endSeconds: Double,
    val lowFrequencyHz: Double,
    val highFrequencyHz: Double,
    val attenuationDb: Double,
    val confidence: Double,
)

fun AudioDenoiseTask?.statusLabel(): String = when (this?.status) {
    null -> "未降噪"
    AudioDenoiseStatus.Pending -> "等待降噪"
    AudioDenoiseStatus.Analyzing -> "正在分析噪声"
    AudioDenoiseStatus.Processing -> progress?.let { "正在降噪 $it%" } ?: "正在降噪"
    AudioDenoiseStatus.Completed -> if (processorVersion < CURRENT_AUDIO_DENOISE_VERSION) {
        "已降噪 · 可升级新版"
    } else {
        "已降噪"
    }
    AudioDenoiseStatus.SkippedClean -> "背景安静，无需降噪"
    AudioDenoiseStatus.SkippedUncertain -> "声音不确定，已保留原声"
    AudioDenoiseStatus.Paused -> "降噪已暂停"
    AudioDenoiseStatus.Failed -> "降噪失败"
}

object AudioNoiseClassifier {
    private const val CLEAN_NOISE_FLOOR_DBFS = -35.0
    private const val CONFIRMED_NOISE_FLOOR_DBFS = -33.5
    private const val LOUD_NOISE_FLOOR_DBFS = -28.0
    private const val LOW_NOISE_RATIO_DB = -3.0
    private const val HIGH_NOISE_RATIO_DB = -10.0

    fun classify(
        noiseFloorDbfs: Double,
        dynamicRangeDb: Double,
        lowVsVoiceDb: Double,
        highVsVoiceDb: Double,
        spectralFlatness: Double,
    ): AudioNoiseAnalysis {
        val classification = when {
            noiseFloorDbfs <= CLEAN_NOISE_FLOOR_DBFS -> AudioNoiseClassification.Clean
            noiseFloorDbfs >= CONFIRMED_NOISE_FLOOR_DBFS &&
                (
                    noiseFloorDbfs >= LOUD_NOISE_FLOOR_DBFS ||
                        lowVsVoiceDb >= LOW_NOISE_RATIO_DB ||
                        highVsVoiceDb >= HIGH_NOISE_RATIO_DB
                    ) -> AudioNoiseClassification.Noise
            else -> AudioNoiseClassification.Uncertain
        }
        val lowSuppression = if (lowVsVoiceDb >= LOW_NOISE_RATIO_DB) {
            interpolate(lowVsVoiceDb, LOW_NOISE_RATIO_DB, 5.0, 3.0, 10.0)
        } else if (classification == AudioNoiseClassification.Noise) {
            2.5
        } else {
            0.0
        }
        val highSuppression = if (highVsVoiceDb >= -14.0) {
            interpolate(highVsVoiceDb, -14.0, -2.0, 4.0, 12.0)
        } else {
            0.0
        }
        val midSuppression = if (classification == AudioNoiseClassification.Noise) {
            interpolate(noiseFloorDbfs, CONFIRMED_NOISE_FLOOR_DBFS, -16.0, 2.0, 5.0)
        } else {
            0.0
        }
        val reason = when (classification) {
            AudioNoiseClassification.Clean -> "低能量窗口达到 %.1f dBFS，背景安静".format(noiseFloorDbfs)
            AudioNoiseClassification.Noise -> "持续噪声底 %.1f dBFS，低频比 %.1f dB，高频比 %.1f dB".format(
                noiseFloorDbfs,
                lowVsVoiceDb,
                highVsVoiceDb,
            )
            AudioNoiseClassification.Uncertain -> "噪声特征处于保守区间，避免误伤人声和场景声"
        }
        return AudioNoiseAnalysis(
            classification = classification,
            noiseFloorDbfs = noiseFloorDbfs,
            dynamicRangeDb = dynamicRangeDb,
            lowVsVoiceDb = lowVsVoiceDb,
            highVsVoiceDb = highVsVoiceDb,
            spectralFlatness = spectralFlatness,
            lowSuppressionDb = lowSuppression,
            midSuppressionDb = midSuppression,
            highSuppressionDb = highSuppression,
            reason = reason,
        )
    }

    private fun interpolate(
        value: Double,
        start: Double,
        end: Double,
        outputStart: Double,
        outputEnd: Double,
    ): Double {
        val fraction = ((value - start) / (end - start)).coerceIn(0.0, 1.0)
        return outputStart + (outputEnd - outputStart) * fraction
    }
}
