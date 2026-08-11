package com.xxxifan.dashcam.update

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xxxifan.dashcam.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) {
        AppUpdateManager(
            context = context.applicationContext,
            repository = BuildConfig.GITHUB_REPOSITORY,
        )
    }
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadRecord by remember(manager) {
        mutableStateOf(
            manager.savedDownload()?.takeIf { record ->
                VersionComparator.isNewer(record.tagName, BuildConfig.VERSION_NAME)
            }.also { validRecord ->
                if (validRecord == null) manager.clearSavedDownload()
            },
        )
    }
    var downloadStatus by remember { mutableStateOf<UpdateDownloadStatus?>(null) }

    fun startInstaller(record: UpdateDownloadRecord) {
        runCatching { context.startActivity(manager.installationIntent(record)) }
            .onFailure { error ->
                state = UpdateUiState.Error(error.message ?: "无法打开系统安装界面")
            }
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val record = downloadRecord ?: return@rememberLauncherForActivityResult
        if (manager.canRequestPackageInstalls()) {
            startInstaller(record)
        } else {
            state = UpdateUiState.Error("需要先允许 DashCam 安装未知应用")
        }
    }

    LaunchedEffect(downloadRecord?.id) {
        val record = downloadRecord ?: run {
            downloadStatus = null
            return@LaunchedEffect
        }
        while (true) {
            val status = withContext(Dispatchers.IO) { manager.queryDownload(record) }
            downloadStatus = status
            if (status.state in TERMINAL_DOWNLOAD_STATES) break
            delay(DOWNLOAD_STATUS_REFRESH_MILLIS)
        }
    }

    fun checkForUpdate() {
        if (state is UpdateUiState.Checking) return
        state = UpdateUiState.Checking
        scope.launch {
            state = runCatching { manager.check(BuildConfig.VERSION_NAME) }
                .fold(
                    onSuccess = { result ->
                        when (result) {
                            is UpdateCheckResult.Available -> UpdateUiState.Available(result.release)
                            is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.latestVersion)
                        }
                    },
                    onFailure = { error ->
                        UpdateUiState.Error(error.message ?: "检查更新失败")
                    },
                )
        }
    }

    fun startDownload(release: AppRelease) {
        state = runCatching {
            downloadRecord?.let(manager::removeDownload)
            manager.download(release)
        }.fold(
            onSuccess = { record ->
                downloadRecord = record
                downloadStatus = UpdateDownloadStatus(UpdateDownloadState.Pending)
                UpdateUiState.Available(release)
            },
            onFailure = { error ->
                UpdateUiState.Error(error.message ?: "无法开始下载")
            },
        )
    }

    fun removeDownload() {
        downloadRecord?.let(manager::removeDownload)
        downloadRecord = null
        downloadStatus = null
    }

    fun installUpdate() {
        val record = downloadRecord ?: return
        if (manager.canRequestPackageInstalls()) {
            startInstaller(record)
        } else {
            installPermissionLauncher.launch(manager.installationPermissionIntent())
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("应用更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                compactDownloadStatus(downloadStatus)?.let { summary ->
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    showUpdateDialog = true
                    if (downloadRecord == null && state !is UpdateUiState.Available) {
                        checkForUpdate()
                    }
                },
            ) {
                Text(if (downloadRecord == null) "检查更新" else "查看下载")
            }
        }
    }

    if (showUpdateDialog) {
        val release = (state as? UpdateUiState.Available)?.release
        val status = downloadStatus
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text(updateDialogTitle(state, status)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (val currentState = state) {
                        UpdateUiState.Idle -> Text("准备检查新版本。")
                        UpdateUiState.Checking -> {
                            Text("正在检查新版本…")
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is UpdateUiState.UpToDate -> Text("已是最新版本（${currentState.latestVersion}）")
                        is UpdateUiState.Error -> Text(
                            currentState.message,
                            color = MaterialTheme.colorScheme.error,
                        )
                        is UpdateUiState.Available -> ReleaseDetails(
                            release = currentState.release,
                            onOpenRelease = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(currentState.release.pageUrl)),
                                )
                            },
                        )
                    }
                    val record = downloadRecord
                    if (record != null) {
                        DownloadDetails(
                            record = record,
                            status = status,
                            canInstall = manager.canRequestPackageInstalls(),
                        )
                    }
                }
            },
            confirmButton = {
                when {
                    status?.state == UpdateDownloadState.Successful -> Button(onClick = ::installUpdate) {
                        Text("安装更新")
                    }
                    status?.state in setOf(UpdateDownloadState.Failed, UpdateDownloadState.Missing) &&
                        release?.apk != null -> Button(onClick = {
                            removeDownload()
                            startDownload(release)
                        }) {
                            Text("重新下载")
                        }
                    release?.apk != null && downloadRecord?.tagName != release.tagName ->
                        Button(onClick = { startDownload(release) }) {
                            Text("下载 APK${release.apk.sizeBytes.sizeSuffix()}")
                        }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status?.state == UpdateDownloadState.Successful) {
                        TextButton(onClick = ::removeDownload) {
                            Text("删除安装包")
                        }
                    }
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("关闭")
                    }
                }
            },
        )
    }
}

@Composable
private fun ReleaseDetails(
    release: AppRelease,
    onOpenRelease: () -> Unit,
) {
    Text("新版本 ${release.tagName}", fontWeight = FontWeight.SemiBold)
    if (release.title != release.tagName) {
        Text(release.title)
    }
    if (release.notes.isNotBlank()) {
        Text(
            release.notes,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedButton(onClick = onOpenRelease) {
        Text(if (release.apk == null) "打开发布页" else "查看完整说明")
    }
    if (release.apk == null) {
        Text(
            "此版本没有可用的正式 APK，请打开发布页查看其他文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DownloadDetails(
    record: UpdateDownloadRecord,
    status: UpdateDownloadStatus?,
    canInstall: Boolean,
) {
    when (status?.state) {
        null -> Text("正在读取下载状态…")
        UpdateDownloadState.Pending -> Text("等待系统开始下载…")
        UpdateDownloadState.Running -> {
            val totalBytes = status.totalBytes.takeIf { it > 0L }
            val progress = totalBytes?.let {
                (status.downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f)
            }
            Text(
                if (totalBytes == null) {
                    "正在下载 ${status.downloadedBytes.readableSize()}"
                } else {
                    "正在下载 ${status.downloadedBytes.readableSize()} / ${totalBytes.readableSize()}"
                },
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        UpdateDownloadState.Paused -> Text(
            status.message ?: "下载已暂停",
            color = MaterialTheme.colorScheme.tertiary,
        )
        UpdateDownloadState.Successful -> {
            Text(
                "安装包已保存到系统“下载”目录：${record.fileName}",
                color = MaterialTheme.colorScheme.primary,
            )
            if (!canInstall) {
                Text(
                    "首次安装需要允许 DashCam 安装未知应用，点击安装后会打开系统授权页面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        UpdateDownloadState.Failed,
        UpdateDownloadState.Missing,
        -> {
            Text(
                status.message ?: "下载失败",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun compactDownloadStatus(status: UpdateDownloadStatus?): String? = when (status?.state) {
    UpdateDownloadState.Pending -> "等待下载"
    UpdateDownloadState.Running -> {
        val total = status.totalBytes
        if (total > 0L) "下载中 ${(status.downloadedBytes * 100L / total).coerceIn(0L, 100L)}%" else "正在下载"
    }
    UpdateDownloadState.Paused -> "下载已暂停"
    UpdateDownloadState.Successful -> "安装包已下载"
    UpdateDownloadState.Failed -> "下载失败"
    UpdateDownloadState.Missing -> "下载记录失效"
    null -> null
}

private fun updateDialogTitle(
    state: UpdateUiState,
    status: UpdateDownloadStatus?,
): String = when {
    status?.state == UpdateDownloadState.Successful -> "更新已下载"
    status?.state in setOf(UpdateDownloadState.Pending, UpdateDownloadState.Running, UpdateDownloadState.Paused) ->
        "正在下载更新"
    state is UpdateUiState.Available -> "发现新版本"
    state is UpdateUiState.Error -> "更新失败"
    else -> "应用更新"
}

private fun Long.sizeSuffix(): String = when {
    this <= 0L -> ""
    this >= 1024L * 1024L -> "（${this / (1024L * 1024L)} MB）"
    this >= 1024L -> "（${this / 1024L} KB）"
    else -> "（$this B）"
}

private fun Long.readableSize(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this.toDouble() / (1024L * 1024L))
    this >= 1024L -> "%.1f KB".format(this.toDouble() / 1024L)
    else -> "$this B"
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data class UpToDate(val latestVersion: String) : UpdateUiState

    data class Available(val release: AppRelease) : UpdateUiState

    data class Error(val message: String) : UpdateUiState
}

private const val DOWNLOAD_STATUS_REFRESH_MILLIS = 1_000L
private val TERMINAL_DOWNLOAD_STATES = setOf(
    UpdateDownloadState.Successful,
    UpdateDownloadState.Failed,
    UpdateDownloadState.Missing,
)
