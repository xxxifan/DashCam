package com.xxxifan.dashcam.storage

import android.content.Context
import android.os.StatFs
import com.xxxifan.dashcam.data.BitratePreset
import com.xxxifan.dashcam.data.RecordingEntry
import com.xxxifan.dashcam.data.RecordingSettings
import java.io.File
import kotlin.math.roundToLong

data class RecordingStorageEstimate(
    val totalBytes: Long,
    val usableBytes: Long,
    val reservedBytes: Long,
    val recordingBytes: Long,
    val quotaBytes: Long?,
    val remainingBytes: Long,
    val estimatedBytesPerSecond: Long,
    val estimatedRecordableSeconds: Long?,
)

object RecordingStorageEstimator {
    const val GB: Long = 1024L * 1024L * 1024L
    const val MIN_LOOP_QUOTA_BYTES: Long = 2L * GB

    fun estimate(
        context: Context,
        settings: RecordingSettings,
        entries: List<RecordingEntry>,
        liveRecordedBytes: Long = 0L,
        liveRecordedDurationNanos: Long = 0L,
    ): RecordingStorageEstimate {
        val directory = LoopStorageManager.recordingDirectory(context)
        val stat = StatFs(directory.absolutePath)
        val totalBytes = stat.totalBytes
        val usableBytes = stat.availableBytes
        val reservedBytes = reserveBytes(totalBytes, settings.reservePercent)
        val recordingBytes = recordingBytes(directory)
        val quotaRemaining = settings.loopQuotaBytes?.let { (it - recordingBytes).coerceAtLeast(0L) }
        val systemRemaining = (usableBytes - reservedBytes).coerceAtLeast(0L)
        val remainingBytes = minOf(systemRemaining, quotaRemaining ?: Long.MAX_VALUE)
        val bytesPerSecond = estimateBytesPerSecond(
            settings = settings,
            entries = entries,
            liveRecordedBytes = liveRecordedBytes,
            liveRecordedDurationNanos = liveRecordedDurationNanos,
        )

        return RecordingStorageEstimate(
            totalBytes = totalBytes,
            usableBytes = usableBytes,
            reservedBytes = reservedBytes,
            recordingBytes = recordingBytes,
            quotaBytes = settings.loopQuotaBytes,
            remainingBytes = remainingBytes,
            estimatedBytesPerSecond = bytesPerSecond,
            estimatedRecordableSeconds = bytesPerSecond.takeIf { it > 0L }?.let { remainingBytes / it },
        )
    }

    fun quotaOptions(context: Context, reservePercent: Int): List<Long> {
        val maxQuota = maxQuotaBytes(context, reservePercent)
        val fixedOptions = listOf(2L, 5L, 10L, 20L, 50L, 100L, 200L, 512L)
            .map { it * GB }
            .filter { it <= maxQuota }
        return buildList {
            addAll(fixedOptions)
            if (maxQuota >= MIN_LOOP_QUOTA_BYTES && fixedOptions.none { it == maxQuota }) {
                add(maxQuota)
            }
        }
    }

    fun maxQuotaBytes(context: Context, reservePercent: Int): Long {
        val directory = LoopStorageManager.recordingDirectory(context)
        val stat = StatFs(directory.absolutePath)
        val protectedBytes = reserveBytes(stat.totalBytes, reservePercent)
        val freeAfterReserve = (stat.availableBytes - protectedBytes).coerceAtLeast(0L)
        return recordingBytes(directory) + freeAfterReserve
    }

    fun recordingBytes(directory: File): Long =
        directory.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            .orEmpty()
            .sumOf { it.length() }

    fun reserveBytes(totalBytes: Long, reservePercent: Int): Long =
        (totalBytes * reservePercent.coerceIn(0, 90)) / 100L

    fun estimateBytesPerSecond(
        settings: RecordingSettings,
        entries: List<RecordingEntry> = emptyList(),
        liveRecordedBytes: Long = 0L,
        liveRecordedDurationNanos: Long = 0L,
    ): Long {
        if (liveRecordedBytes > 0L && liveRecordedDurationNanos >= 5_000_000_000L) {
            return ((liveRecordedBytes * 1_000_000_000.0) / liveRecordedDurationNanos)
                .roundToLong()
                .coerceAtLeast(1L)
        }
        val matchingEntries = entries.filter { entry ->
            entry.durationMillis >= 10_000L &&
                entry.sizeBytes > 0L &&
                entry.resolution == settings.resolution &&
                entry.frameRate == settings.frameRate &&
                (entry.codec == settings.codec || settings.codec == "auto") &&
                entry.bitratePreset == settings.bitratePreset &&
                entry.dynamicRange == settings.dynamicRange
        }
        val matchingDurationMillis = matchingEntries.sumOf { it.durationMillis }
        if (matchingDurationMillis > 0L) {
            val matchingBytes = matchingEntries.sumOf { it.sizeBytes }
            return ((matchingBytes * 1000.0) / matchingDurationMillis).roundToLong().coerceAtLeast(1L)
        }
        return heuristicBytesPerSecond(settings)
    }

    fun estimateSegmentBytes(settings: RecordingSettings): Long {
        val segmentSeconds = settings.segmentMinutes.coerceAtLeast(1) * 60L
        return (estimateBytesPerSecond(settings) * segmentSeconds * SEGMENT_MARGIN).roundToLong()
            .coerceAtLeast(1L)
    }

    private fun heuristicBytesPerSecond(settings: RecordingSettings): Long {
        val targetVideoBitrate = targetVideoBitrate(settings).toLong()
        if (targetVideoBitrate > 0L) {
            return ((targetVideoBitrate + audioBitrate(settings)) / 8L).coerceAtLeast(1L)
        }
        val baseMbps = when (settings.resolution) {
            "720p" -> 12.0
            "4K" -> 80.0
            else -> 25.0
        }
        val fpsMultiplier = when {
            settings.frameRate >= 60 -> 1.7
            settings.frameRate <= 24 -> 0.85
            else -> 1.0
        }
        val codecMultiplier = when (settings.codec) {
            "h265" -> 0.72
            "auto" -> 0.9
            else -> 1.0
        }
        val dynamicRangeMultiplier = if (settings.dynamicRange == "sdr") 1.0 else 1.2
        val audioMbps = if (settings.audioEnabled) 0.128 else 0.0
        val totalMbps = baseMbps * fpsMultiplier * codecMultiplier * dynamicRangeMultiplier + audioMbps
        return ((totalMbps * 1_000_000.0) / 8.0).roundToLong().coerceAtLeast(1L)
    }

    fun targetVideoBitrate(settings: RecordingSettings): Int {
        val preset = if (settings.bitratePreset == BitratePreset.Auto) {
            BitratePreset.Standard
        } else {
            settings.bitratePreset
        }
        val baseMbps = when (preset) {
            BitratePreset.Auto -> 8.0
            BitratePreset.SpaceSaver -> bitrateTable(
                settings = settings,
                p720 = 4.0,
                p1080 = 8.0,
                p1080HighFps = 14.0,
                p4k = 28.0,
            )
            BitratePreset.Standard -> bitrateTable(
                settings = settings,
                p720 = 8.0,
                p1080 = 16.0,
                p1080HighFps = 28.0,
                p4k = 55.0,
            )
            BitratePreset.HighQuality -> bitrateTable(
                settings = settings,
                p720 = 12.0,
                p1080 = 25.0,
                p1080HighFps = 42.0,
                p4k = 80.0,
            )
        }
        return (baseMbps * 1_000_000.0).roundToLong()
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun audioBitrate(settings: RecordingSettings): Int =
        if (settings.audioEnabled) AUDIO_BITRATE else 0

    fun safeRecordingCapacityBytes(
        context: Context,
        settings: RecordingSettings,
    ): Long {
        val maxQuota = maxQuotaBytes(context, settings.reservePercent)
        return minOf(settings.loopQuotaBytes ?: maxQuota, maxQuota).coerceAtLeast(0L)
    }

    private fun bitrateTable(
        settings: RecordingSettings,
        p720: Double,
        p1080: Double,
        p1080HighFps: Double,
        p4k: Double,
    ): Double {
        val baseMbps = when (settings.resolution) {
            "720p" -> p720
            "4K" -> p4k
            else -> if (settings.frameRate >= 60) p1080HighFps else p1080
        }
        return when {
            settings.resolution == "4K" && settings.frameRate >= 60 -> baseMbps * HIGH_FPS_MULTIPLIER
            settings.resolution == "720p" && settings.frameRate >= 60 -> baseMbps * HIGH_FPS_MULTIPLIER
            settings.frameRate <= 24 -> baseMbps * LOW_FPS_MULTIPLIER
            else -> baseMbps
        }
    }

    private const val AUDIO_BITRATE = 128_000
    private const val HIGH_FPS_MULTIPLIER = 1.7
    private const val LOW_FPS_MULTIPLIER = 0.85
    private const val SEGMENT_MARGIN = 1.15
}
