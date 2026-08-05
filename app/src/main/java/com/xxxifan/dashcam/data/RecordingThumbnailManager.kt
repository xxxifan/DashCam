package com.xxxifan.dashcam.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.LruCache
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
    private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun cachedThumbnail(entry: RecordingEntry): Bitmap? {
        val path = entry.thumbnailPath ?: return null
        val matchesCurrentSize = File(path).name.contains("_${entry.sizeBytes}_")
        return if (matchesCurrentSize) bitmapCache.get(path) else null
    }

    suspend fun loadCachedThumbnail(entry: RecordingEntry): Bitmap? {
        cachedThumbnail(entry)?.let { return it }
        return withContext(Dispatchers.IO) {
            val canonicalFile = thumbnailFile(entry)
            loadThumbnailFile(canonicalFile)
        }
    }

    suspend fun ensureThumbnail(entry: RecordingEntry): String? = coroutineScope {
        if (!withContext(Dispatchers.IO) { entry.file.exists() }) {
            return@coroutineScope null
        }

        val targetFile = withContext(Dispatchers.IO) { thumbnailFile(entry) }
        if (withContext(Dispatchers.IO) { targetFile.isFile }) {
            withContext(Dispatchers.IO) { touchDiskEntry(targetFile) }
            return@coroutineScope targetFile.absolutePath
        }

        val inFlightKey = targetFile.absolutePath
        val job = inFlightMutex.withLock {
            inFlight[inFlightKey] ?: async {
                generateAndStore(entry, targetFile)
            }.also { deferred ->
                inFlight[inFlightKey] = deferred
            }
        }
        try {
            job.await()
        } finally {
            inFlightMutex.withLock {
                if (inFlight[inFlightKey] == job) {
                    inFlight.remove(inFlightKey)
                }
            }
        }
        withContext(Dispatchers.IO) {
            targetFile.takeIf { it.isFile }?.absolutePath
        }
    }

    suspend fun cleanOrphans(entries: List<RecordingEntry>) {
        withContext(Dispatchers.IO) {
            val validFiles = entries
                .filter { it.file.exists() }
                .map { thumbnailFile(it).absolutePath }
                .toSet()
            val directory = thumbnailDirectory()
            directory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.absolutePath !in validFiles }
                .forEach { it.delete() }
            trimDiskCache(directory)
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
        if (withContext(Dispatchers.IO) { targetFile.exists() }) {
            withContext(Dispatchers.IO) { touchDiskEntry(targetFile) }
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
                    touchDiskEntry(targetFile)
                    trimDiskCache(targetFile.parentFile ?: thumbnailDirectory())
                    targetFile
                }.getOrNull()
            }
        } ?: return
        withContext(Dispatchers.IO) {
            recordingRepository.update(entry.copy(thumbnailPath = generated.absolutePath))
        }
    }

    private suspend fun loadThumbnailFile(file: File): Bitmap? {
        if (!file.isFile) {
            return null
        }
        val path = file.absolutePath
        bitmapCache.get(path)?.let {
            touchDiskEntry(file)
            return it
        }
        return decodeSemaphore.withPermit {
            bitmapCache.get(path) ?: BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )?.also { bitmap ->
                bitmapCache.put(path, bitmap)
                touchDiskEntry(file)
            }
        }
    }

    private fun touchDiskEntry(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() >= DISK_CACHE_TOUCH_INTERVAL_MILLIS) {
            file.setLastModified(now)
        }
    }

    private fun trimDiskCache(directory: File) {
        val files = directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
        var retainedBytes = 0L
        files.forEachIndexed { index, file ->
            val fileBytes = file.length()
            if (index >= DISK_CACHE_MAX_FILES || retainedBytes + fileBytes > DISK_CACHE_MAX_BYTES) {
                bitmapCache.remove(file.absolutePath)
                file.delete()
            } else {
                retainedBytes += fileBytes
            }
        }
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
        const val BITMAP_CACHE_BYTES = 16 * 1024 * 1024
        const val DISK_CACHE_MAX_FILES = 256
        const val DISK_CACHE_MAX_BYTES = 16L * 1024L * 1024L
        const val DISK_CACHE_TOUCH_INTERVAL_MILLIS = 60L * 60L * 1000L
    }
}
