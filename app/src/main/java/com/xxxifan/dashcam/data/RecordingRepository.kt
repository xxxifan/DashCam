package com.xxxifan.dashcam.data

import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class RecordingRepository(
    private val mmkv: MMKV = MMKV.mmkvWithID("recordings"),
) {
    private val _entries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<RecordingEntry>> = _entries.asStateFlow()

    fun add(entry: RecordingEntry) {
        val next = (listOf(entry) + loadEntries().filterNot { it.id == entry.id || it.filePath == entry.filePath })
            .sortedByDescending { it.startedAtMillis }
        persist(next)
    }

    fun update(entry: RecordingEntry) {
        val next = loadEntries().map { existing ->
            if (existing.id == entry.id) {
                entry
            } else {
                existing
            }
        }.sortedByDescending { it.startedAtMillis }
        persist(next)
    }

    fun delete(entry: RecordingEntry) {
        entry.file.delete()
        entry.thumbnailPath?.let { File(it).delete() }
        val next = loadEntries().filterNot { it.id == entry.id }
        mmkv.removeValueForKey(recordingKey(entry.id))
        persist(next)
    }

    fun refresh() {
        val existing = loadEntries().filter { File(it.filePath).exists() }
        persist(existing, notify = false)
    }

    fun refreshFromDirectory(directory: File) {
        val existing = loadEntries().filter { File(it.filePath).exists() }
        val existingPaths = existing.map { it.filePath }.toSet()
        val scanned = directory.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            .orEmpty()
            .filterNot { it.absolutePath in existingPaths }
            .map { RecordingEntry.fromFile(it) }
        persist((existing + scanned).sortedByDescending { it.startedAtMillis }, notify = false)
    }

    private fun loadEntries(): List<RecordingEntry> {
        val ids = mmkv.decodeString(KEY_IDS, "").orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
        return ids.mapNotNull { id ->
            mmkv.decodeString(recordingKey(id))?.let { runCatching { RecordingEntry.fromJson(it) }.getOrNull() }
        }.filter { it.file.exists() }
            .sortedByDescending { it.startedAtMillis }
    }

    private fun persist(entries: List<RecordingEntry>, notify: Boolean = true) {
        entries.forEach { mmkv.encode(recordingKey(it.id), it.toJson()) }
        mmkv.encode(KEY_IDS, entries.joinToString(",") { it.id })
        _entries.value = entries
        if (notify) {
            _changes.tryEmit(Unit)
        }
    }

    companion object {
        private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val changes: SharedFlow<Unit> = _changes

        const val KEY_IDS = "recording_ids"

        fun recordingKey(id: String): String = "recording:$id"
    }
}
