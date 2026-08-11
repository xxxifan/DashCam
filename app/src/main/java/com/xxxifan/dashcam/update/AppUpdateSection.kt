package com.xxxifan.dashcam.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxxifan.dashcam.BuildConfig
import kotlinx.coroutines.launch

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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val currentState = state) {
                UpdateUiState.Idle -> Text(
                    "检查是否有新的正式版本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateUiState.Checking -> Text("正在检查新版本…")
                is UpdateUiState.UpToDate -> Text("已是最新版本（${currentState.latestVersion}）")
                is UpdateUiState.Error -> Text(
                    currentState.message,
                    color = MaterialTheme.colorScheme.error,
                )
                is UpdateUiState.Available -> ReleaseDetails(
                    release = currentState.release,
                    downloadQueued = currentState.downloadQueued,
                    onDownload = {
                        state = runCatching { manager.download(currentState.release) }
                            .fold(
                                onSuccess = {
                                    currentState.copy(downloadQueued = true)
                                },
                                onFailure = { error ->
                                    UpdateUiState.Error(error.message ?: "无法开始下载")
                                },
                            )
                    },
                    onOpenRelease = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(currentState.release.pageUrl)),
                        )
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = ::checkForUpdate,
                enabled = state !is UpdateUiState.Checking,
            ) {
                Text(if (state is UpdateUiState.Checking) "检查中" else "检查更新")
            }
        }
    }
}

@Composable
private fun ReleaseDetails(
    release: AppRelease,
    downloadQueued: Boolean,
    onDownload: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    Text("发现新版本 ${release.tagName}", fontWeight = FontWeight.SemiBold)
    if (release.title != release.tagName) {
        Text(release.title)
    }
    if (release.notes.isNotBlank()) {
        Text(
            release.notes,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (downloadQueued) {
        Text(
            "已加入系统下载队列，可在下载通知中查看进度。下载完成后点击 APK 安装。",
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val apk = release.apk
        if (apk != null && !downloadQueued) {
            Button(onClick = onDownload) {
                Text("下载 APK${apk.sizeBytes.sizeSuffix()}")
            }
        }
        OutlinedButton(onClick = onOpenRelease) {
            Text(if (apk == null) "打开发布页" else "查看详情")
        }
    }
    if (release.apk == null) {
        Text(
            "此版本没有可用的正式 APK，请打开发布页查看其他文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun Long.sizeSuffix(): String = when {
    this <= 0L -> ""
    this >= 1024L * 1024L -> "（${this / (1024L * 1024L)} MB）"
    this >= 1024L -> "（${this / 1024L} KB）"
    else -> "（$this B）"
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data class UpToDate(val latestVersion: String) : UpdateUiState

    data class Available(
        val release: AppRelease,
        val downloadQueued: Boolean = false,
    ) : UpdateUiState

    data class Error(val message: String) : UpdateUiState
}
