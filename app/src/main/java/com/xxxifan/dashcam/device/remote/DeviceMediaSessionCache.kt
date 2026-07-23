package com.xxxifan.dashcam.device.remote

import android.graphics.Bitmap
import android.util.LruCache
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

internal class DeviceMediaSessionCache {
    private val timestampCache = mutableMapOf<String, Long?>()
    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Synchronized
    fun prepare(media: List<RemoteDeviceMedia>): List<RemoteDeviceMedia> = media
        .map { item ->
            val timestamp = if (timestampCache.containsKey(item.id)) {
                timestampCache[item.id]
            } else {
                (item.createdAtMillis?.let(RemoteMediaTimestampParser::normalize)
                    ?: RemoteMediaTimestampParser.parse(item)).also {
                    timestampCache[item.id] = it
                }
            }
            if (timestamp != null && timestamp != item.createdAtMillis) {
                item.copy(createdAtMillis = timestamp)
            } else {
                item
            }
        }
        .sortedWith(
            compareByDescending<RemoteDeviceMedia> { it.createdAtMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.name },
        )

    fun thumbnail(mediaId: String): Bitmap? = thumbnailCache.get(mediaId)

    fun putThumbnail(mediaId: String, bitmap: Bitmap) {
        thumbnailCache.put(mediaId, bitmap)
    }

    @Synchronized
    fun clear() {
        timestampCache.clear()
        thumbnailCache.evictAll()
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024
    }
}

internal object RemoteMediaTimestampParser {
    private val compactTimestamp = Regex("(?<!\\d)(20\\d{2}[01]\\d[0-3]\\d[_-]?[0-2]\\d[0-5]\\d[0-5]\\d)(?!\\d)")
    private val separatedTimestamp = Regex(
        "(?<!\\d)(20\\d{2})[-_]?([01]\\d)[-_]?([0-3]\\d)[T _-]([0-2]\\d)[-_.:]?([0-5]\\d)[-_.:]?([0-5]\\d)(?!\\d)",
    )
    private val compactFormatters = listOf(
        DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT),
    )

    fun parse(media: RemoteDeviceMedia): Long? {
        return parse(media.name, media.protocolData)
    }

    fun parse(name: String, protocolData: Map<String, String> = emptyMap()): Long? {
        val candidates = buildList {
            add(name.substringBeforeLast('.'))
            protocolData["TIME"]?.let(::add)
            protocolData["TIMECODE"]?.let(::add)
            protocolData["rawPath"]?.let(::add)
        }
        return candidates.firstNotNullOfOrNull(::parseCandidate)
    }

    fun normalize(raw: Long): Long? = when {
        raw in EPOCH_SECONDS_RANGE -> raw * 1_000L
        raw in EPOCH_MILLIS_RANGE -> raw
        else -> null
    }

    private fun parseCandidate(value: String): Long? {
        value.trim().toLongOrNull()?.let { raw ->
            normalize(raw)?.let { return it }
        }
        compactTimestamp.find(value)?.groupValues?.get(1)?.let { compact ->
            compactFormatters.forEach { formatter ->
                parseDateTime(compact, formatter)?.let { return it }
            }
        }
        separatedTimestamp.find(value)?.destructured?.let { (year, month, day, hour, minute, second) ->
            return runCatching {
                LocalDateTime.of(
                    year.toInt(),
                    month.toInt(),
                    day.toInt(),
                    hour.toInt(),
                    minute.toInt(),
                    second.toInt(),
                ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }
        return null
    }

    private fun parseDateTime(value: String, formatter: DateTimeFormatter): Long? = try {
        LocalDateTime.parse(value, formatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private val EPOCH_SECONDS_RANGE = 946_684_800L..4_102_444_799L
    private val EPOCH_MILLIS_RANGE = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()..
        Instant.parse("2100-01-01T00:00:00Z").toEpochMilli()
}

private val standardMediaTimestampFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
private val mediaTimelineDateFormatter = DateTimeFormatter.ofPattern("MM/dd")
private val mediaTimelineTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun Long.formatRemoteMediaTimestamp(): String = standardMediaTimestampFormatter.format(
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()),
)

internal fun Long.formatRemoteTimelineDate(): String = mediaTimelineDateFormatter.format(
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()),
)

internal fun Long.formatRemoteTimelineTime(): String = mediaTimelineTimeFormatter.format(
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()),
)

internal fun Long.formatRemoteDuration(): String {
    val totalSeconds = (this / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
