package com.xxxifan.dashcam.device.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL

internal data class DeviceHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal class DeviceProtocolException(message: String) : IOException(message)

internal class DeviceHttpClient(
    private val route: DeviceNetworkRoute,
    private val connectTimeoutMillis: Int = 2_500,
    private val readTimeoutMillis: Int = 6_000,
) {
    suspend fun get(url: String): DeviceHttpResponse = request(url = url, method = "GET")

    suspend fun postEmpty(url: String): DeviceHttpResponse = request(url = url, method = "POST")

    private suspend fun request(
        url: String,
        method: String,
    ): DeviceHttpResponse = withContext(Dispatchers.IO) {
        val connection = route.openHttpConnection(URL(url))
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "*/*")
            if (method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            DeviceHttpResponse(
                statusCode = statusCode,
                body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        url: String,
        destination: File,
        media: RemoteDeviceMedia,
        onProgress: (DeviceDownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val existingLength = destination.takeIf { it.exists() }?.length() ?: 0L
        val connection = route.openHttpConnection(URL(url))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            if (existingLength > 0L) {
                connection.setRequestProperty("Range", "bytes=$existingLength-")
            }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw DeviceProtocolException("下载失败：HTTP $statusCode")
            }
            val append = existingLength > 0L && statusCode == 206
            val startingLength = if (append) existingLength else 0L
            val totalLength = connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?: connection.contentLengthLong
                    .takeIf { it >= 0L }
                    ?.plus(startingLength)
            RandomAccessFile(destination, "rw").use { output ->
                if (append) {
                    output.seek(startingLength)
                } else {
                    output.setLength(0L)
                }
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = startingLength
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(
                            DeviceDownloadProgress(
                                mediaId = media.id,
                                fileName = destination.name,
                                downloadedBytes = downloaded,
                                totalBytes = totalLength,
                            ),
                        )
                    }
                }
            }
            destination
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000
    }
}

internal fun DeviceHttpResponse.requireSuccess(operation: String): DeviceHttpResponse {
    if (statusCode !in 200..299) {
        throw DeviceProtocolException("$operation 失败：HTTP $statusCode")
    }
    return this
}

internal fun String.safeFileName(fallback: String): String {
    val normalized = substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
    return normalized.ifBlank { fallback }
}
