package com.xxxifan.dashcam.device.remote

import android.net.Uri
import android.util.Xml
import androidx.core.net.toUri
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis

class Legacy254DeviceDriver : DeviceProtocolDriver {
    override val definition = DeviceModel.Dc5.definition

    override suspend fun probe(route: DeviceNetworkRoute): DeviceProbeResult {
        val client = DeviceHttpClient(route)
        var response: DeviceHttpResponse? = null
        val latency = runCatching {
            measureTimeMillis {
                response = client.get("$BASE_URL/?custom=1&cmd=3029")
            }
        }
        return latency.fold(
            onSuccess = { elapsed ->
                val value = response
                if (value != null && value.statusCode in 200..299 && value.body.isNotBlank()) {
                    DeviceProbeResult(
                        device = definition,
                        status = DeviceProbeStatus.Supported,
                        latencyMillis = elapsed,
                        detail = "SSID/XML 控制接口可访问",
                        detectedName = parseNamedXmlValues(value.body)["SSID"],
                    )
                } else {
                    DeviceProbeResult(
                        device = definition,
                        status = DeviceProbeStatus.Unsupported,
                        latencyMillis = elapsed,
                        detail = "控制接口响应不符合预期，HTTP ${value?.statusCode}",
                    )
                }
            },
            onFailure = { error ->
                DeviceProbeResult(
                    device = definition,
                    status = DeviceProbeStatus.Unreachable,
                    detail = error.message ?: error.javaClass.simpleName,
                )
            },
        )
    }

    override fun createSession(
        route: DeviceNetworkRoute,
        diagnostics: DeviceDiagnosticsLogger,
    ): DeviceSession = Legacy254Session(definition, route, diagnostics)

    private class Legacy254Session(
        override val device: DeviceDefinition,
        route: DeviceNetworkRoute,
        private val diagnostics: DeviceDiagnosticsLogger,
    ) : DeviceSession {
        private val client = DeviceHttpClient(route)
        private var previewPrepared = false

        override val supportedCategories = RemoteMediaCategory.entries.toSet()

        override suspend fun preparePreview(): DevicePlaybackSource {
            diagnostics.log("device_preview_prepare_started", mapOf("deviceId" to device.id))
            client.get("$BASE_URL/?custom=1&cmd=3001&par=1").requireSuccess("切换录像模式")
            client.get("$BASE_URL/?custom=1&cmd=2015&par=1").requireSuccess("启动实时流")
            client.get("$BASE_URL/?custom=1&cmd=2001&par=1").requireSuccess("启动设备录像")
            previewPrepared = true
            return DevicePlaybackSource.Rtsp(
                uri = "rtsp://$HOST".toUri(),
                forceTcp = false,
                timeoutMillis = 5_000L,
            ).also {
                diagnostics.log(
                    "device_preview_ready",
                    mapOf("deviceId" to device.id, "uri" to it.uri, "forceTcp" to it.forceTcp),
                )
            }
        }

        override suspend fun releasePreview() {
            if (!previewPrepared) {
                return
            }
            runCatching {
                client.get("$BASE_URL/?custom=1&cmd=2015&par=0").requireSuccess("停止实时流")
            }.onFailure { error ->
                diagnostics.log(
                    "device_preview_release_failed",
                    mapOf("deviceId" to device.id, "error" to error.message),
                )
            }
            previewPrepared = false
        }

        override suspend fun loadRemoteMedia(category: RemoteMediaCategory): List<RemoteDeviceMedia> {
            diagnostics.log(
                "device_media_list_started",
                mapOf("deviceId" to device.id, "category" to category.name),
            )
            client.get("$BASE_URL/?custom=1&cmd=3001&par=2").requireSuccess("切换回放模式")
            client.get("$BASE_URL/?custom=1&cmd=2001&par=0").requireSuccess("停止设备录像")
            client.get("$BASE_URL/?custom=1&cmd=2015&par=0").requireSuccess("停止实时流")
            previewPrepared = false
            val response = client.get("$BASE_URL/?custom=1&cmd=3015").requireSuccess("读取文件列表")
            val media = parseFileList(response.body)
                .filter { it.category == category }
            diagnostics.log(
                "device_media_list_completed",
                mapOf("deviceId" to device.id, "category" to category.name, "count" to media.size),
            )
            return media
        }

        override suspend fun download(
            media: RemoteDeviceMedia,
            destinationDirectory: File,
            onProgress: (DeviceDownloadProgress) -> Unit,
        ): DownloadedDeviceMedia {
            val source = media.playbackSource as? DevicePlaybackSource.Http
                ?: throw DeviceProtocolException("该媒体不是 HTTP 下载源")
            val destination = File(
                destinationDirectory,
                media.name.safeFileName("device-${UUID.randomUUID()}.${media.format.defaultExtension()}"),
            )
            diagnostics.log(
                "device_download_started",
                mapOf("deviceId" to device.id, "mediaId" to media.id, "target" to destination),
            )
            client.download(source.uri.toString(), destination, media, onProgress)
            return DownloadedDeviceMedia(
                source = media,
                file = destination,
                outputFormat = media.format,
                postProcessingDescription = when (media.format) {
                    RemoteMediaFormat.TransportStream -> "设备策略：保留原始 TS 容器，避免通用层错误转码"
                    else -> "设备策略：保留设备原始文件"
                },
            ).also { result ->
                diagnostics.log(
                    "device_download_completed",
                    mapOf(
                        "deviceId" to device.id,
                        "mediaId" to media.id,
                        "file" to result.file,
                        "postProcessing" to result.postProcessingDescription,
                    ),
                )
            }
        }

        override suspend fun close() {
            releasePreview()
            diagnostics.log("device_session_closed", mapOf("deviceId" to device.id))
        }

        private fun parseFileList(xml: String): List<RemoteDeviceMedia> {
            val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
            val records = mutableListOf<Map<String, String>>()
            var current = mutableMapOf<String, String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.uppercase()
                        if (tag in FILE_TAGS) {
                            current[tag] = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name.equals("ALLFile", ignoreCase = true)) {
                        records += current.toMap()
                        current = mutableMapOf()
                    }
                }
                parser.next()
            }
            return records.mapNotNull(::toRemoteMedia).reversed()
        }

        private fun toRemoteMedia(values: Map<String, String>): RemoteDeviceMedia? {
            val rawPath = values["FPATH"].orEmpty()
            val name = values["NAME"].orEmpty().ifBlank {
                rawPath.substringAfterLast('\\').substringAfterLast('/')
            }
            if (rawPath.isBlank() || name.isBlank()) {
                return null
            }
            val normalizedPath = rawPath.replace('\\', '/')
            val category = when {
                normalizedPath.contains("/Movie/", ignoreCase = true) -> RemoteMediaCategory.NormalVideo
                normalizedPath.contains("/SOS/", ignoreCase = true) -> RemoteMediaCategory.EmergencyVideo
                normalizedPath.contains("/Photo/", ignoreCase = true) -> RemoteMediaCategory.Photo
                else -> return null
            }
            val parent = normalizedPath.substringBeforeLast('/').substringAfterLast(':').substringAfterLast('/')
            val fileUri = Uri.Builder()
                .scheme("http")
                .encodedAuthority(HOST)
                .appendPath("Novatek")
                .appendPath(parent)
                .appendPath(name)
                .build()
            return RemoteDeviceMedia(
                id = "$rawPath:${values["TIME"].orEmpty()}",
                name = name,
                category = category,
                format = name.toRemoteMediaFormat(),
                sizeBytes = values["SIZE"]?.toLongOrNull(),
                durationMillis = null,
                createdAtMillis = null,
                playbackSource = DevicePlaybackSource.Http(fileUri),
                thumbnailUri = fileUri.buildUpon()
                    .encodedQuery("custom=1&cmd=4002")
                    .build(),
                protocolData = values + ("rawPath" to rawPath),
            )
        }
    }

    companion object {
        private val HOST = DeviceModel.Dc5.host
        private val BASE_URL = "http://$HOST"
        private val FILE_TAGS = setOf("NAME", "FPATH", "SIZE", "TIMECODE", "TIME", "ATTR")

        private fun parseNamedXmlValues(xml: String): Map<String, String> {
            val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
            val result = mutableMapOf<String, String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name.uppercase() in setOf("SSID", "PASSPHRASE")) {
                    result[parser.name.uppercase()] = parser.nextText()
                }
                parser.next()
            }
            return result
        }
    }
}

private fun RemoteMediaFormat.defaultExtension(): String = when (this) {
    RemoteMediaFormat.Mp4 -> "mp4"
    RemoteMediaFormat.Mov -> "mov"
    RemoteMediaFormat.TransportStream -> "ts"
    RemoteMediaFormat.Jpeg -> "jpg"
    RemoteMediaFormat.Unknown -> "bin"
}
