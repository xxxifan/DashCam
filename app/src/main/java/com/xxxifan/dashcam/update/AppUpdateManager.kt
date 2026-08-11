package com.xxxifan.dashcam.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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

data class UpdateDownloadRecord(
    val id: Long,
    val tagName: String,
    val fileName: String,
    val expectedSizeBytes: Long,
)

enum class UpdateDownloadState {
    Pending,
    Running,
    Paused,
    Successful,
    Failed,
    Missing,
}

data class UpdateDownloadStatus(
    val state: UpdateDownloadState,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
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

    fun download(release: AppRelease): UpdateDownloadRecord {
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
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val record = UpdateDownloadRecord(
            id = downloadManager.enqueue(request),
            tagName = release.tagName,
            fileName = fileName,
            expectedSizeBytes = asset.sizeBytes,
        )
        saveDownload(record)
        return record
    }

    fun savedDownload(): UpdateDownloadRecord? {
        val id = preferences.getLong(PREF_DOWNLOAD_ID, -1L)
        if (id < 0L) return null
        val tagName = preferences.getString(PREF_TAG_NAME, null) ?: return null
        val fileName = preferences.getString(PREF_FILE_NAME, null) ?: return null
        return UpdateDownloadRecord(
            id = id,
            tagName = tagName,
            fileName = fileName,
            expectedSizeBytes = preferences.getLong(PREF_EXPECTED_SIZE, 0L),
        )
    }

    fun queryDownload(record: UpdateDownloadRecord): UpdateDownloadStatus {
        val downloadManager = downloadManager()
        downloadManager.query(DownloadManager.Query().setFilterById(record.id)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return UpdateDownloadStatus(
                    state = UpdateDownloadState.Missing,
                    message = "系统中没有找到这次下载记录",
                )
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloadedBytes = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            ).coerceAtLeast(0L)
            val totalBytes = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            ).takeIf { it > 0L } ?: record.expectedSizeBytes
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return UpdateDownloadStatus(
                state = when (status) {
                    DownloadManager.STATUS_PENDING -> UpdateDownloadState.Pending
                    DownloadManager.STATUS_RUNNING -> UpdateDownloadState.Running
                    DownloadManager.STATUS_PAUSED -> UpdateDownloadState.Paused
                    DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadState.Successful
                    DownloadManager.STATUS_FAILED -> UpdateDownloadState.Failed
                    else -> UpdateDownloadState.Missing
                },
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                message = when (status) {
                    DownloadManager.STATUS_PAUSED -> "下载已暂停，系统会自动重试"
                    DownloadManager.STATUS_FAILED -> downloadFailureMessage(reason)
                    else -> null
                },
            )
        }
    }

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installationPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installationIntent(record: UpdateDownloadRecord): Intent {
        check(canRequestPackageInstalls()) { "尚未允许此应用安装更新" }
        val uri = requireNotNull(downloadManager().getUriForDownloadedFile(record.id)) {
            "找不到已下载的安装包"
        }
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun removeDownload(record: UpdateDownloadRecord) {
        downloadManager().remove(record.id)
        clearSavedDownload()
    }

    fun clearSavedDownload() {
        preferences.edit().clear().apply()
    }

    private fun downloadManager(): DownloadManager = requireNotNull(
        context.getSystemService(DownloadManager::class.java),
    ) { "系统下载服务不可用" }

    private fun saveDownload(record: UpdateDownloadRecord) {
        preferences.edit()
            .putLong(PREF_DOWNLOAD_ID, record.id)
            .putString(PREF_TAG_NAME, record.tagName)
            .putString(PREF_FILE_NAME, record.fileName)
            .putLong(PREF_EXPECTED_SIZE, record.expectedSizeBytes)
            .apply()
    }

    private val preferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val MAX_RESPONSE_CHARS = 1_000_000
        private const val MAX_NOTES_CHARS = 1_200
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val PREFS_NAME = "app_update_download"
        private const val PREF_DOWNLOAD_ID = "download_id"
        private const val PREF_TAG_NAME = "tag_name"
        private const val PREF_FILE_NAME = "file_name"
        private const val PREF_EXPECTED_SIZE = "expected_size"
        private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        private val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

        private fun downloadFailureMessage(reason: Int): String = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "下载失败：存储空间不足"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "下载失败：下载目录中已有同名文件"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载失败：下载目录不可用"
            DownloadManager.ERROR_CANNOT_RESUME -> "下载失败：无法继续未完成的下载"
            DownloadManager.ERROR_HTTP_DATA_ERROR,
            DownloadManager.ERROR_TOO_MANY_REDIRECTS,
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
            -> "下载失败：网络响应异常"
            else -> "下载失败，请检查网络后重试（$reason）"
        }

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
