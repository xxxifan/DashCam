package com.xxxifan.dashcam.data

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StabilizationMode {
    Off,
    Standard,
    Enhanced,
}

enum class BitratePreset {
    SpaceSaver,
    Standard,
    HighQuality,
}

enum class AudioProcessingMode {
    Camcorder,
}

enum class FocusMode {
    Auto,
    Farthest,
}

const val DEFAULT_CROP_ZOOM_RATIO = 1f
const val MIN_CROP_ZOOM_RATIO = 1f
const val MAX_CROP_ZOOM_RATIO = 2f

val recordingCropZoomRatios = listOf(1f, 1.2f, 1.4f, 1.6f, 2f)

fun Float.coerceCropZoomRatio(): Float =
    takeIf { it.isFinite() }?.coerceIn(MIN_CROP_ZOOM_RATIO, MAX_CROP_ZOOM_RATIO)
        ?: DEFAULT_CROP_ZOOM_RATIO

data class RecordingSettings(
    val segmentMinutes: Int = 2,
    val audioEnabled: Boolean = true,
    val audioProcessingMode: AudioProcessingMode = AudioProcessingMode.Camcorder,
    val resolution: String = "720p",
    val frameRate: Int = 30,
    val codec: String = "h265",
    val bitratePreset: BitratePreset = BitratePreset.Standard,
    val autoQualityEnabled: Boolean = false,
    val dynamicRange: String = "sdr",
    val stabilizationMode: StabilizationMode = StabilizationMode.Standard,
    val cameraId: String = "",
    val cameraLabel: String = "1X 主镜头",
    val cropZoomRatio: Float = DEFAULT_CROP_ZOOM_RATIO,
    val focusMode: FocusMode = FocusMode.Auto,
    val autoDowngradeEnabled: Boolean = true,
    val reservePercent: Int = 10,
    val loopQuotaBytes: Long? = null,
)

data class RecordingEntry(
    val id: String,
    val filePath: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val sizeBytes: Long,
    val resolution: String,
    val frameRate: Int,
    val codec: String,
    val bitratePreset: BitratePreset = BitratePreset.Standard,
    val dynamicRange: String = "sdr",
    val audioEnabled: Boolean,
    val audioProcessingMode: AudioProcessingMode = AudioProcessingMode.Camcorder,
    val stabilizationMode: StabilizationMode,
    val cameraId: String = "",
    val cameraLabel: String = "1X 主镜头",
    val cropZoomRatio: Float = DEFAULT_CROP_ZOOM_RATIO,
    val exported: Boolean = false,
    val thumbnailPath: String? = null,
) {
    val file: File get() = File(filePath)

    val durationMillis: Long
        get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0L)

    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("filePath", filePath)
        .put("startedAtMillis", startedAtMillis)
        .put("endedAtMillis", endedAtMillis)
        .put("sizeBytes", sizeBytes)
        .put("resolution", resolution)
        .put("frameRate", frameRate)
        .put("codec", codec)
        .put("bitratePreset", bitratePreset.name)
        .put("dynamicRange", dynamicRange)
        .put("audioEnabled", audioEnabled)
        .put("audioProcessingMode", audioProcessingMode.name)
        .put("stabilizationMode", stabilizationMode.name)
        .put("cameraId", cameraId)
        .put("cameraLabel", cameraLabel)
        .put("cropZoomRatio", cropZoomRatio)
        .put("exported", exported)
        .put("thumbnailPath", thumbnailPath)
        .toString()

    companion object {
        fun fromJson(value: String): RecordingEntry {
            val json = JSONObject(value)
            return RecordingEntry(
                id = json.getString("id"),
                filePath = json.getString("filePath"),
                startedAtMillis = json.getLong("startedAtMillis"),
                endedAtMillis = json.optLong("endedAtMillis", json.getLong("startedAtMillis")),
                sizeBytes = json.optLong("sizeBytes", 0L),
                resolution = json.optString("resolution", "1080p"),
                frameRate = json.optInt("frameRate", 30),
                codec = json.optString("codec", "h265"),
                bitratePreset = runCatching {
                    BitratePreset.valueOf(json.optString("bitratePreset", BitratePreset.Standard.name))
                }.getOrDefault(BitratePreset.Standard),
                dynamicRange = json.optString("dynamicRange", "sdr"),
                audioEnabled = json.optBoolean("audioEnabled", true),
                audioProcessingMode = runCatching {
                    AudioProcessingMode.valueOf(
                        json.optString("audioProcessingMode", AudioProcessingMode.Camcorder.name),
                    )
                }.getOrDefault(AudioProcessingMode.Camcorder),
                stabilizationMode = runCatching {
                    StabilizationMode.valueOf(json.optString("stabilizationMode", StabilizationMode.Standard.name))
                }.getOrDefault(StabilizationMode.Standard),
                cameraId = json.optString("cameraId", ""),
                cameraLabel = json.optString("cameraLabel", "1X 主镜头"),
                cropZoomRatio = json.optDouble("cropZoomRatio", DEFAULT_CROP_ZOOM_RATIO.toDouble())
                    .toFloat()
                    .coerceCropZoomRatio(),
                exported = json.optBoolean("exported", false),
                thumbnailPath = json.optString("thumbnailPath").takeIf { it.isNotBlank() },
            )
        }

        fun fromFile(file: File): RecordingEntry {
            val parsed = parseDashCamFileName(file.name)
            return RecordingEntry(
                id = "file_${file.nameWithoutExtension}",
                filePath = file.absolutePath,
                startedAtMillis = parsed?.first ?: file.lastModified(),
                endedAtMillis = file.lastModified().coerceAtLeast(parsed?.first ?: file.lastModified()),
                sizeBytes = file.length(),
                resolution = parsed?.second ?: "1080p",
                frameRate = parsed?.third ?: 30,
                codec = parsed?.fourth ?: "h265",
                bitratePreset = BitratePreset.Standard,
                dynamicRange = "sdr",
                audioEnabled = true,
                audioProcessingMode = AudioProcessingMode.Camcorder,
                stabilizationMode = StabilizationMode.Standard,
            )
        }

        private fun parseDashCamFileName(name: String): ParsedFileName? {
            val match = Regex("""dashcam_(\d{8})_(\d{6})_([A-Za-z0-9]+)(\d+)_(.+)\.mp4""")
                .matchEntire(name) ?: return null
            val date = match.groupValues[1]
            val time = match.groupValues[2]
            val instant = runCatching {
                LocalDateTime.parse(
                    "${date}_${time}",
                    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"),
                ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: return null
            return ParsedFileName(
                first = instant,
                second = match.groupValues[3],
                third = match.groupValues[4].toIntOrNull() ?: 30,
                fourth = match.groupValues[5],
            )
        }
    }
}

private data class ParsedFileName(
    val first: Long,
    val second: String,
    val third: Int,
    val fourth: String,
)

private val dateFormatter = DateTimeFormatter
    .ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)
    .withZone(ZoneId.systemDefault())

private val timeFormatter = DateTimeFormatter
    .ofPattern("HH:mm:ss", Locale.CHINA)
    .withZone(ZoneId.systemDefault())

fun RecordingEntry.dateHeader(): String = dateFormatter.format(Instant.ofEpochMilli(startedAtMillis))

fun RecordingEntry.timeLabel(): String = timeFormatter.format(Instant.ofEpochMilli(startedAtMillis))

fun Long.formatBytes(): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return if (index == 0) {
        "${value.toLong()} ${units[index]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[index])
    }
}

fun Long.formatDuration(): String {
    val totalSeconds = this / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
