package com.xxxifan.dashcam.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecordingEventLogger private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var lastRetentionDate: LocalDate? = null

    fun log(
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val capturedAtMillis = System.currentTimeMillis()
        scope.launch {
            writeMutex.withLock {
                runCatching {
                    applyRetentionIfNeeded()
                    val line = JSONObject()
                        .put("timestampMillis", capturedAtMillis)
                        .put("timestamp", Instant.ofEpochMilli(capturedAtMillis).toString())
                        .put("event", event)
                        .put("fields", fields.toJsonObject())
                        .toString()
                    currentLogFile().appendText("$line\n", Charsets.UTF_8)
                }
            }
        }
    }

    fun logRecordingSettings(
        event: String,
        settings: RecordingSettings,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        log(
            event = event,
            fields = fields + mapOf(
                "segmentMinutes" to settings.segmentMinutes,
                "audioEnabled" to settings.audioEnabled,
                "resolution" to settings.resolution,
                "frameRate" to settings.frameRate,
                "codec" to settings.codec,
                "bitratePreset" to settings.bitratePreset.name,
                "dynamicRange" to settings.dynamicRange,
                "stabilizationMode" to settings.stabilizationMode.name,
                "cameraId" to settings.cameraId,
                "cameraLabel" to settings.cameraLabel,
                "autoDowngradeEnabled" to settings.autoDowngradeEnabled,
                "reservePercent" to settings.reservePercent,
                "loopQuotaBytes" to settings.loopQuotaBytes,
            ),
        )
    }

    private fun currentLogFile(): File {
        val date = LocalDate.now(ZoneId.systemDefault()).format(fileDateFormatter)
        return File(logDirectory(), "recording-events-$date.log")
    }

    private fun logDirectory(): File =
        File(appContext.filesDir, "recording_logs").also { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }

    private fun applyRetentionIfNeeded() {
        val today = LocalDate.now(ZoneId.systemDefault())
        if (lastRetentionDate == today) {
            return
        }
        lastRetentionDate = today
        val cutoffMillis = System.currentTimeMillis() - LOG_RETENTION_MILLIS
        val logFiles = logDirectory()
            .listFiles { file -> file.isFile && file.name.startsWith(LOG_FILE_PREFIX) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
        logFiles
            .filterIndexed { index, file ->
                index >= MAX_LOG_FILES || file.lastModified() < cutoffMillis
            }
            .forEach { it.delete() }
    }

    private fun Map<String, Any?>.toJsonObject(): JSONObject {
        val json = JSONObject()
        forEach { (key, value) ->
            json.put(key, value.toJsonValue())
        }
        return json
    }

    private fun Any?.toJsonValue(): Any =
        when (this) {
            null -> JSONObject.NULL
            is String, is Number, is Boolean -> this
            is Enum<*> -> name
            is Map<*, *> -> JSONObject().also { json ->
                forEach { (key, value) ->
                    if (key != null) {
                        json.put(key.toString(), value.toJsonValue())
                    }
                }
            }
            is Iterable<*> -> JSONArray().also { array ->
                forEach { array.put(it.toJsonValue()) }
            }
            is Array<*> -> JSONArray().also { array ->
                forEach { array.put(it.toJsonValue()) }
            }
            else -> toString()
        }

    companion object {
        private const val LOG_FILE_PREFIX = "recording-events-"
        private const val MAX_LOG_FILES = 30
        private const val LOG_RETENTION_MILLIS = 14L * 24L * 60L * 60L * 1000L
        private val fileDateFormatter = DateTimeFormatter.BASIC_ISO_DATE

        @Volatile
        private var instance: RecordingEventLogger? = null

        fun get(context: Context): RecordingEventLogger =
            instance ?: synchronized(this) {
                instance ?: RecordingEventLogger(context).also { instance = it }
            }
    }
}
