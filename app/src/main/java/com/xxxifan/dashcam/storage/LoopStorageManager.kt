package com.xxxifan.dashcam.storage

import android.content.Context
import android.os.StatFs
import com.xxxifan.dashcam.data.RecordingEventLogger
import com.xxxifan.dashcam.data.RecordingRepository
import com.xxxifan.dashcam.data.RecordingSettings
import java.io.File

class LoopStorageManager(
    private val context: Context,
    private val recordingRepository: RecordingRepository,
) {
    private val eventLogger by lazy { RecordingEventLogger.get(context) }

    val recordingDirectory: File
        get() = File(context.getExternalFilesDir("Movies"), "DashCam/records").also { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
            File(directory, ".nomedia").apply {
                if (!exists()) {
                    createNewFile()
                }
            }
        }

    fun ensureSpaceForNextSegment(
        settings: RecordingSettings,
        minBytes: Long = MIN_START_BYTES,
    ): Boolean {
        val directory = recordingDirectory
        val requiredBytes = maxOf(minBytes, RecordingStorageEstimator.estimateSegmentBytes(settings))
        recordingRepository.refresh()
        cleanupToReserve(directory, settings.reservePercent)
        cleanupToQuota(directory, settings, requiredBytes)
        val quotaRemaining = settings.loopQuotaBytes?.let {
            (it - RecordingStorageEstimator.recordingBytes(directory)).coerceAtLeast(0L)
        } ?: Long.MAX_VALUE
        val stat = StatFs(directory.absolutePath)
        val reserveBytes = RecordingStorageEstimator.reserveBytes(stat.totalBytes, settings.reservePercent)
        val systemRemaining = (stat.availableBytes - reserveBytes).coerceAtLeast(0L)
        eventLogger.log(
            event = "space_check",
            fields = mapOf(
                "requiredBytes" to requiredBytes,
                "systemRemainingBytes" to systemRemaining,
                "quotaRemainingBytes" to quotaRemaining.takeIf { it != Long.MAX_VALUE },
                "reserveBytes" to reserveBytes,
                "availableBytes" to stat.availableBytes,
                "totalBytes" to stat.totalBytes,
                "loopQuotaBytes" to settings.loopQuotaBytes,
            ),
        )
        return systemRemaining >= requiredBytes && quotaRemaining >= requiredBytes
    }

    private fun cleanupToReserve(directory: File, reservePercent: Int) {
        val reserveBytes = reserveBytes(directory, reservePercent)
        val usable = directory.usableSpace
        if (usable >= reserveBytes) {
            return
        }

        val entries = recordingRepository.entries.value.sortedBy { it.startedAtMillis }
        for (entry in entries) {
            val sizeBytes = entry.file.length()
            recordingRepository.delete(entry)
            eventLogger.log(
                event = "loop_delete",
                fields = mapOf(
                    "reason" to "reserve",
                    "entryId" to entry.id,
                    "filePath" to entry.filePath,
                    "sizeBytes" to sizeBytes,
                    "usableBytesAfterDelete" to directory.usableSpace,
                    "reserveBytes" to reserveBytes,
                ),
            )
            if (directory.usableSpace >= reserveBytes) {
                break
            }
        }
    }

    private fun cleanupToQuota(
        directory: File,
        settings: RecordingSettings,
        minBytes: Long,
    ) {
        val quotaBytes = settings.loopQuotaBytes ?: return
        if (quotaBytes < RecordingStorageEstimator.MIN_LOOP_QUOTA_BYTES) {
            return
        }
        var recordingBytes = RecordingStorageEstimator.recordingBytes(directory)
        if (recordingBytes + minBytes <= quotaBytes) {
            return
        }
        val entries = recordingRepository.entries.value.sortedBy { it.startedAtMillis }
        for (entry in entries) {
            val sizeBytes = entry.file.length()
            recordingRepository.delete(entry)
            recordingBytes = RecordingStorageEstimator.recordingBytes(directory)
            eventLogger.log(
                event = "loop_delete",
                fields = mapOf(
                    "reason" to "quota",
                    "entryId" to entry.id,
                    "filePath" to entry.filePath,
                    "sizeBytes" to sizeBytes,
                    "recordingBytesAfterDelete" to recordingBytes,
                    "quotaBytes" to quotaBytes,
                    "requiredBytes" to minBytes,
                ),
            )
            if (recordingBytes + minBytes <= quotaBytes) {
                break
            }
        }
    }

    private fun reserveBytes(directory: File, reservePercent: Int): Long {
        val stat = StatFs(directory.absolutePath)
        return RecordingStorageEstimator.reserveBytes(stat.totalBytes, reservePercent)
    }

    companion object {
        fun recordingDirectory(context: Context): File =
            File(context.getExternalFilesDir("Movies"), "DashCam/records").also { directory ->
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                File(directory, ".nomedia").apply {
                    if (!exists()) {
                        createNewFile()
                    }
                }
            }

        const val DEFAULT_RESERVE_PERCENT = 10
        const val MIN_START_BYTES = 512L * 1024L * 1024L
    }
}
