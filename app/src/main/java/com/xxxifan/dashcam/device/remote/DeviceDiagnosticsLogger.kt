package com.xxxifan.dashcam.device.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DeviceDiagnosticsLogger(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val writeMutex = Mutex()
    private val _records = MutableStateFlow<List<DeviceDiagnosticRecord>>(emptyList())

    val records: StateFlow<List<DeviceDiagnosticRecord>> = _records.asStateFlow()

    suspend fun log(
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val timestampMillis = System.currentTimeMillis()
        val stringFields = fields.mapValues { (_, value) -> value?.toString() ?: "null" }
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                applyRetention()
                val line = JSONObject()
                    .put("timestampMillis", timestampMillis)
                    .put("timestamp", Instant.ofEpochMilli(timestampMillis).toString())
                    .put("event", event)
                    .put("fields", JSONObject(stringFields))
                    .toString()
                currentFile().appendText("$line\n", Charsets.UTF_8)
                _records.value = (
                    listOf(
                        DeviceDiagnosticRecord(
                            timestampMillis = timestampMillis,
                            event = event,
                            fields = stringFields,
                        ),
                    ) + _records.value
                    ).take(MAX_IN_MEMORY_RECORDS)
            }
        }
    }

    fun currentFile(): File {
        val date = LocalDate.now(ZoneId.systemDefault()).format(FILE_DATE_FORMATTER)
        return File(directory(), "device-events-$date.log")
    }

    private fun directory(): File =
        File(appContext.filesDir, DIRECTORY_NAME).also { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }

    private fun applyRetention() {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        directory()
            .listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .filterIndexed { index, file -> index >= MAX_LOG_FILES || file.lastModified() < cutoff }
            .forEach { it.delete() }
    }

    companion object {
        const val DIRECTORY_NAME = "device_logs"
        private const val FILE_PREFIX = "device-events-"
        private const val MAX_IN_MEMORY_RECORDS = 40
        private const val MAX_LOG_FILES = 30
        private const val RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private val FILE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE
    }
}
