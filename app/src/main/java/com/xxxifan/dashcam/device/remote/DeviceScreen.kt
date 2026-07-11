package com.xxxifan.dashcam.device.remote

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.xxxifan.dashcam.data.formatBytes
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DeviceScreen(
    padding: PaddingValues,
    manager: DeviceManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by manager.state.collectAsStateWithLifecycle()

    LaunchedEffect(manager) {
        manager.probeAll(trigger = "device_tab_opened")
    }
    DisposableEffect(manager) {
        onDispose { manager.closeActiveAsync() }
    }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DeviceStatusCard(
                state = state,
                onProbe = { scope.launch { manager.probeAll() } },
            )
        }

        item {
            NewSdkStatusCard(state.newSdkSupport)
        }

        items(state.probeResults, key = { it.device.id }) { result ->
            ProbeResultCard(
                result = result,
                selected = state.activeDevice?.id == result.device.id,
                enabled = !state.isBusy && result.status == DeviceProbeStatus.Supported,
                onSelect = { scope.launch { manager.selectDevice(result.device.id) } },
            )
        }

        state.activeDevice?.let { activeDevice ->
            item {
                ActiveDeviceControls(
                    device = activeDevice,
                    state = state,
                    onPreview = { scope.launch { manager.startPreview() } },
                    onLoadMedia = { category -> scope.launch { manager.loadRemoteMedia(category) } },
                    onDisconnect = { scope.launch { manager.closeActive() } },
                )
            }
        }

        val playerSource = state.playbackMedia?.playbackSource ?: state.previewSource
        if (playerSource != null) {
            item {
                DevicePlayerCard(
                    title = state.playbackMedia?.name ?: "实时预览",
                    source = playerSource,
                    showControls = state.playbackMedia != null,
                    onClose = { scope.launch { manager.stopPlayer() } },
                    onError = { manager.reportPlaybackError(playerSource, it) },
                )
            }
        }

        if (state.remoteMedia.isNotEmpty()) {
            item {
                Text(
                    text = "${state.selectedCategory.displayName} · ${state.remoteMedia.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(state.remoteMedia, key = { it.id }) { media ->
                RemoteMediaCard(
                    media = media,
                    isDownloading = state.downloadProgress?.mediaId == media.id,
                    progress = state.downloadProgress?.takeIf { it.mediaId == media.id },
                    enabled = !state.isBusy,
                    onPlay = { manager.play(media) },
                    onDownload = { scope.launch { manager.download(media) } },
                )
            }
        }

        state.downloadedMedia?.let { downloaded ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("下载完成", fontWeight = FontWeight.SemiBold)
                        Text(downloaded.file.absolutePath, style = MaterialTheme.typography.bodySmall)
                        Text(downloaded.postProcessingDescription, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            DiagnosticsCard(
                file = state.diagnosticFile,
                records = state.diagnostics,
                onShare = { file -> context.shareDeviceDiagnostics(file) },
            )
        }
    }
}

@Composable
private fun DeviceStatusCard(
    state: DeviceUiState,
    onProbe: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("设备协议探测", style = MaterialTheme.typography.titleLarge)
                    Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.isProbing) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    OutlinedButton(onClick = onProbe) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("重新探测")
                    }
                }
            }
            Text(
                "请先连接记录仪热点。探测固定走 Wi-Fi Network，避免 Android 把局域网请求发到移动网络。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewSdkStatusCard(status: NewSdkSupportStatus) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (status.integrationAvailable) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("新款 iCatch SDK", fontWeight = FontWeight.SemiBold)
            Text(
                if (status.integrationAvailable) "SDK 已集成" else "SDK 未集成",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(status.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProbeResultCard(
    result: DeviceProbeResult,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.detectedName ?: result.device.displayName, fontWeight = FontWeight.SemiBold)
                    Text(result.device.protocolName, style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(
                    onClick = {},
                    label = { Text(result.status.label()) },
                )
            }
            Text(result.detail, style = MaterialTheme.typography.bodySmall)
            result.latencyMillis?.let { Text("响应 ${it} ms", style = MaterialTheme.typography.labelSmall) }
            if (result.status == DeviceProbeStatus.Supported) {
                Button(onClick = onSelect, enabled = enabled && !selected) {
                    Text(if (selected) "已连接" else "连接设备")
                }
            }
        }
    }
}

@Composable
private fun ActiveDeviceControls(
    device: DeviceDefinition,
    state: DeviceUiState,
    onPreview: () -> Unit,
    onLoadMedia: (RemoteMediaCategory) -> Unit,
    onDisconnect: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onPreview,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("实时预览")
            }
            Text(
                "读取存储会按该设备协议停止录像并切换回放模式。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteMediaCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.selectedCategory == category && state.remoteMedia.isNotEmpty(),
                        onClick = { onLoadMedia(category) },
                        enabled = !state.isBusy,
                        label = { Text(category.displayName) },
                    )
                }
            }
            OutlinedButton(onClick = onDisconnect, enabled = !state.isBusy) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Text("断开")
            }
        }
    }
}

@Composable
private fun RemoteMediaCard(
    media: RemoteDeviceMedia,
    isDownloading: Boolean,
    progress: DeviceDownloadProgress?,
    enabled: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                media.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildString {
                    append(media.format.name)
                    media.sizeBytes?.let { append(" · ${it.formatBytes()}") }
                    media.durationMillis?.let { append(" · ${it / 1_000L}s") }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (isDownloading) {
                progress?.fraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    progress?.downloadedBytes?.formatBytes().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (media.format != RemoteMediaFormat.Jpeg) {
                    OutlinedButton(onClick = onPlay, enabled = enabled) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("播放")
                    }
                }
                Button(onClick = onDownload, enabled = enabled) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("下载")
                }
            }
        }
    }
}

@Composable
private fun DevicePlayerCard(
    title: String,
    source: DevicePlaybackSource,
    showControls: Boolean,
    onClose: () -> Unit,
    onError: (Throwable) -> Unit,
) {
    Card {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClose) { Text("关闭") }
            }
            DevicePlayer(
                source = source,
                showControls = showControls,
                onError = onError,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun DevicePlayer(
    source: DevicePlaybackSource,
    showControls: Boolean,
    onError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(source) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(source.uri)
            when (source) {
                is DevicePlaybackSource.Http -> setMediaItem(mediaItem)
                is DevicePlaybackSource.Rtsp -> {
                    val mediaSource = RtspMediaSource.Factory()
                        .setForceUseRtpTcp(source.forceTcp)
                        .setTimeoutMs(source.timeoutMillis)
                        .createMediaSource(mediaItem)
                    setMediaSource(mediaSource)
                }
            }
            addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onError(error)
                    }
                },
            )
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = showControls
                    this.player = player
                }
            },
            update = { view ->
                view.useController = showControls
                view.player = player
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticsCard(
    file: File?,
    records: List<DeviceDiagnosticRecord>,
    onShare: (File) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("设备诊断文件", style = MaterialTheme.typography.titleMedium)
                file?.let {
                    OutlinedButton(onClick = { onShare(it) }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Text("分享")
                    }
                }
            }
            Text(
                file?.absolutePath ?: "日志文件尚未创建",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "JSON Lines / UTF-8，保留 30 天；下面显示最近记录。",
                style = MaterialTheme.typography.labelSmall,
            )
            records.take(12).forEachIndexed { index, record ->
                if (index > 0) {
                    HorizontalDivider()
                }
                Column {
                    Text(
                        "${record.timestampMillis.diagnosticTime()}  ${record.event}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        record.fields.entries.joinToString(" · ") { "${it.key}=${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (records.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("暂无诊断记录", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun DeviceProbeStatus.label(): String = when (this) {
    DeviceProbeStatus.Supported -> "支持"
    DeviceProbeStatus.Unsupported -> "协议不匹配"
    DeviceProbeStatus.Unreachable -> "不可达"
    DeviceProbeStatus.NotIntegrated -> "未集成"
}

private fun Long.diagnosticTime(): String =
    DIAGNOSTIC_TIME_FORMATTER.format(Instant.ofEpochMilli(this))

private fun Context.shareDeviceDiagnostics(file: File) {
    if (!file.exists()) {
        return
    }
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-ndjson"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "分享设备诊断文件",
        ),
    )
}

private val DIAGNOSTIC_TIME_FORMATTER = DateTimeFormatter
    .ofPattern("MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
