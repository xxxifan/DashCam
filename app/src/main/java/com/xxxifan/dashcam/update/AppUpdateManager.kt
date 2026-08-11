package com.xxxifan.dashcam.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apk: ReleaseAsset?,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateCheckResult {
    data class Available(val release: AppRelease) : UpdateCheckResult

    data class UpToDate(val latestVersion: String) : UpdateCheckResult
}

class AppUpdateManager(
    private val context: Context,
    private val repository: String,
) {
    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        require(REPOSITORY_PATTERN.matches(repository)) { "更新源配置无效" }
        val connection = URL("https://api.github.com/repos/$repository/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "DashCam-Android-Update-Checker")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val message = when (responseCode) {
                    HttpURLConnection.HTTP_NOT_FOUND -> "暂未找到公开发布版本"
                    403 -> "更新服务请求受限，请稍后再试"
                    else -> "更新服务返回错误 $responseCode"
                }
                throw IllegalStateException(message)
            }

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = reader.readText()
                require(text.length <= MAX_RESPONSE_CHARS) { "版本信息过大" }
                text
            }
            val release = parseRelease(response)
            if (VersionComparator.isNewer(release.tagName, currentVersion)) {
                UpdateCheckResult.Available(release)
            } else {
                UpdateCheckResult.UpToDate(release.tagName)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun download(release: AppRelease): Long {
        val asset = requireNotNull(release.apk) { "此版本没有可下载的 APK" }
        require(isTrustedGitHubUrl(asset.downloadUrl)) { "APK 下载地址不受信任" }
        val downloadManager = requireNotNull(
            context.getSystemService(DownloadManager::class.java),
        ) { "系统下载服务不可用" }
        val fileName = asset.name
            .replace(UNSAFE_FILE_NAME_CHARS, "_")
            .ifBlank { "DashCam-${release.tagName}.apk" }
        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
            .setTitle("DashCam ${release.tagName}")
            .setDescription("正在下载应用更新")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        return downloadManager.enqueue(request)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val MAX_RESPONSE_CHARS = 1_000_000
        private const val MAX_NOTES_CHARS = 1_200
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        private val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

        internal fun parseRelease(json: String): AppRelease {
            val root = JSONObject(json)
            val tagName = root.getString("tag_name").trim()
            require(tagName.isNotEmpty()) { "发布版本缺少版本号" }
            val pageUrl = root.getString("html_url")
            require(isTrustedGitHubUrl(pageUrl)) { "发布页面地址无效" }
            val assets = root.optJSONArray("assets")
            val apkAssets = buildList {
                if (assets != null) {
                    repeat(assets.length()) { index ->
                        val item = assets.optJSONObject(index) ?: return@repeat
                        val name = item.optString("name").trim()
                        val downloadUrl = item.optString("browser_download_url").trim()
                        if (name.endsWith(".apk", ignoreCase = true) &&
                            !name.contains("debug", ignoreCase = true) &&
                            isTrustedGitHubUrl(downloadUrl)
                        ) {
                            add(
                                ReleaseAsset(
                                    name = name,
                                    downloadUrl = downloadUrl,
                                    sizeBytes = item.optLong("size").coerceAtLeast(0L),
                                ),
                            )
                        }
                    }
                }
            }
            val apk = apkAssets.minByOrNull { asset ->
                when {
                    asset.name.contains("universal", ignoreCase = true) -> 0
                    asset.name.contains("release", ignoreCase = true) -> 1
                    else -> 2
                }
            }
            return AppRelease(
                tagName = tagName,
                title = root.optString("name").trim().ifEmpty { tagName },
                notes = root.optString("body").trim().take(MAX_NOTES_CHARS),
                pageUrl = pageUrl,
                apk = apk,
            )
        }

        private fun isTrustedGitHubUrl(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                (host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"))
        }
    }
}

object VersionComparator {
    fun isNewer(candidate: String, current: String): Boolean {
        val candidateVersion = ParsedVersion.parse(candidate) ?: return false
        val currentVersion = ParsedVersion.parse(current) ?: return false
        return candidateVersion > currentVersion
    }

    private data class ParsedVersion(
        val numbers: List<Long>,
        val preRelease: List<String>,
    ) : Comparable<ParsedVersion> {
        override fun compareTo(other: ParsedVersion): Int {
            repeat(maxOf(numbers.size, other.numbers.size)) { index ->
                val comparison = numbers.getOrElse(index) { 0L }
                    .compareTo(other.numbers.getOrElse(index) { 0L })
                if (comparison != 0) return comparison
            }
            if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
            if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
            repeat(maxOf(preRelease.size, other.preRelease.size)) { index ->
                val left = preRelease.getOrNull(index) ?: return -1
                val right = other.preRelease.getOrNull(index) ?: return 1
                val leftNumber = left.toLongOrNull()
                val rightNumber = right.toLongOrNull()
                val comparison = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right, ignoreCase = true)
                }
                if (comparison != 0) return comparison
            }
            return 0
        }

        companion object {
            fun parse(value: String): ParsedVersion? {
                val match = VERSION_PATTERN.matchEntire(value.trim()) ?: return null
                val numbers = match.groupValues[1].split('.').mapNotNull(String::toLongOrNull)
                if (numbers.isEmpty()) return null
                val preRelease = match.groupValues[2]
                    .takeIf(String::isNotEmpty)
                    ?.split('.', '-')
                    .orEmpty()
                return ParsedVersion(numbers, preRelease)
            }

            private val VERSION_PATTERN = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
        }
    }
}
