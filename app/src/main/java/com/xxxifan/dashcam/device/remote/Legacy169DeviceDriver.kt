package com.xxxifan.dashcam.device.remote

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis

class Legacy169DeviceDriver : DeviceProtocolDriver {
    override val definition = DeviceDefinition(
        id = "legacy-192-168-169-1",
        displayName = "旧款记录仪 192.168.169.1",
        protocolName = "JSON / HTTP / RTSP TCP",
        host = HOST,
    )

    override suspend fun probe(route: DeviceNetworkRoute): DeviceProbeResult {
        val client = DeviceHttpClient(route)
        var response: DeviceHttpResponse? = null
        val latency = runCatching {
            measureTimeMillis {
                response = client.get("$BASE_URL/app/getdeviceattr")
            }
        }
        return latency.fold(
            onSuccess = { elapsed ->
                val value = response
                val json = value?.body?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (value != null && value.statusCode in 200..299 && json != null) {
                    DeviceProbeResult(
                        device = definition,
                        status = DeviceProbeStatus.Supported,
                        latencyMillis = elapsed,
                        detail = "设备属性 JSON 接口可访问",
                        detectedName = json.optJSONObject("info")?.optString("ssid")?.takeIf { it.isNotBlank() },
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
    ): DeviceSession = Legacy169Session(definition, route, diagnostics)

    private class Legacy169Session(
        override val device: DeviceDefinition,
        route: DeviceNetworkRoute,
        private val diagnostics: DeviceDiagnosticsLogger,
    ) : DeviceSession {
        private val client = DeviceHttpClient(route)
        private val sessionJob = SupervisorJob()
        private val sessionScope = CoroutineScope(sessionJob + Dispatchers.IO)
        private val heartbeatJob: Job = sessionScope.launch { runHeartbeatLoop() }
        private var previewPrepared = false
        private var closed = false

        override val supportedCategories = RemoteMediaCategory.entries.toSet()

        override suspend fun preparePreview(): DevicePlaybackSource {
            diagnostics.log("device_preview_prepare_started", mapOf("deviceId" to device.id))
            client.postEmpty("$BASE_URL/app/enterrecorder").requireSuccess("进入录像模式")
            client.postEmpty("$BASE_URL/app/setparamvalue?param=rec&value=1").requireSuccess("启动设备录像")
            previewPrepared = true
            return DevicePlaybackSource.Rtsp(
                uri = "rtsp://$HOST".toUri(),
                forceTcp = true,
                timeoutMillis = 5_000L,
            ).also {
                diagnostics.log(
                    "device_preview_ready",
                    mapOf("deviceId" to device.id, "uri" to it.uri, "forceTcp" to it.forceTcp),
                )
            }
        }

        override suspend fun releasePreview() {
            previewPrepared = false
        }

        override suspend fun loadStorageInfo(): DeviceStorageInfo {
            val response = client.get("$BASE_URL/app/getsdinfo").requireSuccess("读取存储容量")
            val root = JSONObject(response.body)
            if (root.optInt("result", -1) != 0) {
                throw DeviceProtocolException("设备返回存储容量错误：result=${root.optInt("result", -1)}")
            }
            val info = root.optJSONObject("info")
                ?: throw DeviceProtocolException("设备存储容量响应缺少 info")
            val totalBytes = legacy169StorageMiBToBytes(info.optLong("total", -1L))
                ?: throw DeviceProtocolException("设备返回的总容量无效")
            val freeBytes = legacy169StorageMiBToBytes(info.optLong("free", -1L))
                ?.takeIf { it <= totalBytes }
                ?: throw DeviceProtocolException("设备返回的剩余容量无效")
            return DeviceStorageInfo(
                totalBytes = totalBytes,
                freeBytes = freeBytes,
            ).also { storage ->
                diagnostics.log(
                    "device_storage_info_loaded",
                    mapOf(
                        "deviceId" to device.id,
                        "totalBytes" to storage.totalBytes,
                        "freeBytes" to storage.freeBytes,
                        "usedBytes" to storage.usedBytes,
                    ),
                )
            }
        }

        override suspend fun loadRemoteMedia(category: RemoteMediaCategory): List<RemoteDeviceMedia> {
            diagnostics.log(
                "device_media_list_started",
                mapOf("deviceId" to device.id, "category" to category.name),
            )
            client.postEmpty("$BASE_URL/app/setparamvalue?param=rec&value=0").requireSuccess("停止设备录像")
            client.postEmpty("$BASE_URL/app/playback?param=enter").requireSuccess("进入回放模式")
            previewPrepared = false
            val requestUrl = when (category) {
                RemoteMediaCategory.NormalVideo -> "$BASE_URL/app/getfilelist?folder=loop&start=0&end=99999"
                RemoteMediaCategory.EmergencyVideo -> "$BASE_URL/app/getfilelist?folder=emr&start=0&end=99999"
                RemoteMediaCategory.Photo -> "$BASE_URL/app/getfilelist?folder=event&start=0&end=99999"
            }
            val response = client.get(requestUrl).requireSuccess("读取文件列表")
            val media = parseFileList(response.body, category)
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
                media.name.safeFileName("device-${UUID.randomUUID()}.${media.format.defaultExtension169()}"),
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
                    RemoteMediaFormat.TransportStream -> "设备策略：保留原始 TS 容器，后续可为该驱动接入专用转封装器"
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
            if (closed) {
                return
            }
            closed = true
            heartbeatJob.cancelAndJoin()
            sessionJob.cancel()
            releasePreview()
            diagnostics.log("device_heartbeat_stopped", mapOf("deviceId" to device.id))
            diagnostics.log("device_session_closed", mapOf("deviceId" to device.id))
        }

        private suspend fun runHeartbeatLoop() {
            diagnostics.log(
                "device_heartbeat_started",
                mapOf(
                    "deviceId" to device.id,
                    "intervalMillis" to HEARTBEAT_INTERVAL_MILLIS,
                    "url" to HEARTBEAT_URL,
                ),
            )
            var consecutiveFailures = 0
            while (sessionScope.isActive) {
                try {
                    client.postEmpty(HEARTBEAT_URL).requireSuccess("设备心跳")
                    if (consecutiveFailures > 0) {
                        diagnostics.log(
                            "device_heartbeat_recovered",
                            mapOf(
                                "deviceId" to device.id,
                                "previousConsecutiveFailures" to consecutiveFailures,
                            ),
                        )
                    }
                    consecutiveFailures = 0
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    consecutiveFailures++
                    if (shouldLogHeartbeatFailure(consecutiveFailures)) {
                        diagnostics.log(
                            "device_heartbeat_failed",
                            mapOf(
                                "deviceId" to device.id,
                                "consecutiveFailures" to consecutiveFailures,
                                "error" to (error.message ?: error.javaClass.simpleName),
                            ),
                        )
                    }
                }
                delay(HEARTBEAT_INTERVAL_MILLIS)
            }
        }

        private fun parseFileList(
            body: String,
            requestedCategory: RemoteMediaCategory,
        ): List<RemoteDeviceMedia> {
            val root = JSONObject(body)
            if (root.optInt("result", -1) != 0) {
                throw DeviceProtocolException("设备返回文件列表错误：result=${root.optInt("result", -1)}")
            }
            val info = root.optJSONArray("info") ?: return emptyList()
            val result = mutableListOf<RemoteDeviceMedia>()
            for (infoIndex in 0 until info.length()) {
                val group = info.optJSONObject(infoIndex) ?: continue
                val folder = group.optString("folder")
                val category = folder.toCategory() ?: continue
                if (category != requestedCategory) {
                    continue
                }
                val files = group.optJSONArray("files") ?: continue
                for (fileIndex in 0 until files.length()) {
                    val item = files.optJSONObject(fileIndex) ?: continue
                    val rawPath = item.optString("name")
                    if (rawPath.isBlank()) {
                        continue
                    }
                    val fileUri = "$BASE_URL/${rawPath.trimStart('/')}".toUri()
                    val name = rawPath.substringAfterLast('/').ifBlank { "remote-$fileIndex" }
                    result += RemoteDeviceMedia(
                        id = "$folder:$rawPath:${item.optLong("createtime", 0L)}",
                        name = name,
                        category = category,
                        format = name.toRemoteMediaFormat(),
                        sizeBytes = legacy169SizeKiBToBytes(item.optLong("size", -1L)),
                        durationMillis = item.optLong("duration", -1L)
                            .takeIf { it >= 0L }
                            ?.times(1_000L),
                        createdAtMillis = item.optLong("createtime", -1L).takeIf { it > 0L },
                        playbackSource = DevicePlaybackSource.Http(fileUri),
                        thumbnailUri = "$BASE_URL/app/getthumbnail?file=${Uri.encode(rawPath)}".toUri(),
                        protocolData = mapOf(
                            "folder" to folder,
                            "rawPath" to rawPath,
                            "type" to item.optInt("type", -1).toString(),
                        ),
                    )
                }
            }
            return result.sortedByDescending { it.name }
        }

        private fun String.toCategory(): RemoteMediaCategory? = when (lowercase()) {
            "loop" -> RemoteMediaCategory.NormalVideo
            "emr" -> RemoteMediaCategory.EmergencyVideo
            "event" -> RemoteMediaCategory.Photo
            else -> null
        }
    }

    companion object {
        private const val HOST = "192.168.169.1"
        private const val BASE_URL = "http://$HOST"
        private const val HEARTBEAT_URL = "$BASE_URL/app/getparamvalue?param=rec"
        private const val HEARTBEAT_INTERVAL_MILLIS = 4_000L
    }
}

internal fun shouldLogHeartbeatFailure(consecutiveFailures: Int): Boolean =
    consecutiveFailures == 1 || consecutiveFailures % 15 == 0

internal fun legacy169SizeKiBToBytes(sizeKiB: Long): Long? {
    if (sizeKiB < 0L) {
        return null
    }
    return runCatching { Math.multiplyExact(sizeKiB, 1_024L) }.getOrNull()
}

internal fun legacy169StorageMiBToBytes(sizeMiB: Long): Long? {
    if (sizeMiB < 0L) {
        return null
    }
    return runCatching { Math.multiplyExact(sizeMiB, 1_024L * 1_024L) }.getOrNull()
}

private fun RemoteMediaFormat.defaultExtension169(): String = when (this) {
    RemoteMediaFormat.Mp4 -> "mp4"
    RemoteMediaFormat.Mov -> "mov"
    RemoteMediaFormat.TransportStream -> "ts"
    RemoteMediaFormat.Jpeg -> "jpg"
    RemoteMediaFormat.Unknown -> "bin"
}
