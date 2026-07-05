package com.xxxifan.dashcam.data

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class RecordingThumbnailManager(
    context: Context,
    private val recordingRepository: RecordingRepository,
) {
    private val appContext = context.applicationContext
    private val inFlightMutex = Mutex()
    private val decodeSemaphore = Semaphore(1)
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Unit>>()

    suspend fun ensureThumbnail(entry: RecordingEntry) = coroutineScope {
        if (!entry.file.exists()) {
            return@coroutineScope
        }

        val targetFile = thumbnailFile(entry)
        if (entry.thumbnailPath == targetFile.absolutePath && targetFile.exists()) {
            return@coroutineScope
        }

        val job = inFlightMutex.withLock {
            inFlight[entry.id] ?: async {
                generateAndStore(entry, targetFile)
            }.also { deferred ->
                inFlight[entry.id] = deferred
            }
        }
        try {
            job.await()
        } finally {
            inFlightMutex.withLock {
                if (inFlight[entry.id] == job) {
                    inFlight.remove(entry.id)
                }
            }
        }
    }

    suspend fun cleanOrphans(entries: List<RecordingEntry>) {
        val validFiles = entries
            .filter { it.file.exists() }
            .map { thumbnailFile(it).absolutePath }
            .toSet()
        withContext(Dispatchers.IO) {
            thumbnailDirectory().listFiles()
                .orEmpty()
                .filter { it.isFile && it.absolutePath !in validFiles }
                .forEach { it.delete() }
        }
    }

    suspend fun backfill(entries: List<RecordingEntry>) = coroutineScope {
        entries.map { entry ->
            async {
                ensureThumbnail(entry)
            }
        }.awaitAll()
    }

    private suspend fun generateAndStore(
        entry: RecordingEntry,
        targetFile: File,
    ) {
        if (targetFile.exists()) {
            recordingRepository.update(entry.copy(thumbnailPath = targetFile.absolutePath))
            return
        }
        val generated = withContext(Dispatchers.IO) {
            decodeSemaphore.withPermit {
                runCatching {
                    val bitmap = ThumbnailUtils.createVideoThumbnail(
                        entry.file,
                        Size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT),
                        null,
                    )
                    targetFile.outputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                    }
                    targetFile
                }.getOrNull()
            }
        } ?: return
        recordingRepository.update(entry.copy(thumbnailPath = generated.absolutePath))
    }

    private fun thumbnailFile(entry: RecordingEntry): File {
        val fingerprint = "${entry.file.length()}_${entry.file.lastModified()}"
        return File(thumbnailDirectory(), "${entry.id}_v${SCHEMA_VERSION}_$fingerprint.jpg")
    }

    private fun thumbnailDirectory(): File {
        return File(appContext.cacheDir, "recording_thumbnails").also { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val THUMBNAIL_WIDTH = 320
        const val THUMBNAIL_HEIGHT = 180
        const val JPEG_QUALITY = 82
    }
}
