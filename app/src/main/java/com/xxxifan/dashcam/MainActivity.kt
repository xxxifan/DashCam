package com.xxxifan.dashcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xxxifan.dashcam.camera.CameraCapabilities
import com.xxxifan.dashcam.camera.CameraCapabilitiesRepository
import com.xxxifan.dashcam.camera.PreviewController
import com.xxxifan.dashcam.camera.codecLabel
import com.xxxifan.dashcam.camera.dynamicRangeLabel
import com.xxxifan.dashcam.data.BitratePreset
import com.xxxifan.dashcam.data.RecordingEntry
import com.xxxifan.dashcam.data.RecordingRepository
import com.xxxifan.dashcam.data.RecordingSettings
import com.xxxifan.dashcam.data.RecordingSettingsStore
import com.xxxifan.dashcam.data.StabilizationMode
import com.xxxifan.dashcam.data.dateHeader
import com.xxxifan.dashcam.data.formatBytes
import com.xxxifan.dashcam.data.formatDuration
import com.xxxifan.dashcam.data.timeLabel
import com.xxxifan.dashcam.recording.RecordingService
import com.xxxifan.dashcam.recording.RecordingDowngradeReason
import com.xxxifan.dashcam.recording.RecordingDowngradeState
import com.xxxifan.dashcam.recording.RecordingStateBus
import com.xxxifan.dashcam.storage.LoopStorageManager
import com.xxxifan.dashcam.storage.RecordingStorageEstimate
import com.xxxifan.dashcam.storage.RecordingStorageEstimator
import java.io.File
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingStartAfterPermission = true
    }

    private var pendingStartAfterPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DashCamApp(
                requestPermissions = { requestRecordingPermissions() },
                consumePermissionResult = {
                    val result = pendingStartAfterPermission
                    pendingStartAfterPermission = false
                    result
                },
            )
        }
    }

    private fun requestRecordingPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }
}

@Composable
private fun DashCamApp(
    requestPermissions: () -> Unit,
    consumePermissionResult: () -> Boolean,
) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF0EA5E9),
            secondary = Color(0xFF10B981),
            tertiary = Color(0xFFF59E0B),
            background = Color(0xFFF8FAFC),
            surface = Color.White,
        ),
    ) {
        val context = LocalContext.current
        val settingsStore = remember { RecordingSettingsStore() }
        val recordingRepository = remember { RecordingRepository() }
        val cameraCapabilities = remember { CameraCapabilitiesRepository(context).capabilities() }
        val uiState by RecordingStateBus.state.collectAsStateWithLifecycle()
        val entries by recordingRepository.entries.collectAsStateWithLifecycle()
        var settings by remember { mutableStateOf(settingsStore.get()) }
        var storageEstimate by remember {
            mutableStateOf(
                RecordingStorageEstimator.estimate(
                    context = context,
                    settings = settings,
                    entries = entries,
                    liveRecordedBytes = uiState.recordedBytes,
                    liveRecordedDurationNanos = uiState.recordedDurationNanos,
                ),
            )
        }
        var selectedTab by remember { mutableIntStateOf(0) }
        var showConfirm by remember { mutableStateOf(false) }
        val shouldShowConfirmAfterPermission = consumePermissionResult()

        LaunchedEffect(cameraCapabilities) {
            settings = settingsStore.update {
                it.coerceToCapabilities(cameraCapabilities)
                    .coerceToStorage(context)
            }
        }

        LaunchedEffect(Unit) {
            recordingRepository.refreshFromDirectory(LoopStorageManager.recordingDirectory(context))
        }

        LaunchedEffect(selectedTab) {
            if (selectedTab == 1) {
                recordingRepository.refreshFromDirectory(LoopStorageManager.recordingDirectory(context))
            }
        }

        LaunchedEffect(
            settings,
            uiState.activeSettings,
            entries,
            selectedTab,
            uiState.isRecording,
            uiState.recordedBytes,
            uiState.recordedDurationNanos,
        ) {
            val estimateSettings = uiState.activeSettings ?: settings
            storageEstimate = RecordingStorageEstimator.estimate(
                context = context,
                settings = estimateSettings,
                entries = entries,
                liveRecordedBytes = uiState.recordedBytes,
                liveRecordedDurationNanos = uiState.recordedDurationNanos,
            )
        }

        LaunchedEffect(shouldShowConfirmAfterPermission) {
            if (shouldShowConfirmAfterPermission) {
                showConfirm = true
            }
        }

        if (showConfirm) {
            StartRecordingDialog(
                onDismiss = { showConfirm = false },
                onConfirm = {
                    showConfirm = false
                    context.startRecordingService()
                },
            )
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    isRecording = uiState.isRecording,
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Filled.Movie, contentDescription = null) },
                        label = { Text("录制") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("视频") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") },
                    )
                }
            },
        ) { padding ->
            val recordingSettings = uiState.activeSettings ?: settings
            when (selectedTab) {
                0 -> RecordingHome(
                    padding = padding,
                    stateMessage = uiState.message,
                    fallbackGuidance = uiState.fallbackGuidance,
                    isRecording = uiState.isRecording,
                    settings = recordingSettings,
                    storageEstimate = storageEstimate,
                    onStart = {
                        if (context.hasRecordingPermissions()) {
                            showConfirm = true
                        } else {
                            requestPermissions()
                        }
                    },
                    onStop = { context.stopRecordingService() },
                    onOpenSettings = { selectedTab = 2 },
                )
                1 -> LibraryScreen(
                    padding = padding,
                    entries = entries,
                    onDelete = recordingRepository::delete,
                    onShare = { context.shareRecording(it) },
                    onExport = { context.exportRecording(it) },
                )
                2 -> SettingsScreen(
                    padding = padding,
                    settings = settings,
                    isRecording = uiState.isRecording,
                    downgradeState = uiState.downgradeState,
                    capabilities = cameraCapabilities,
                    storageEstimate = storageEstimate,
                    onSettingsChange = { update ->
                        settings = settingsStore.update { current ->
                            update(current).coerceToCapabilities(cameraCapabilities)
                                .coerceToStorage(context)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    isRecording: Boolean,
) {
    TopAppBar(
        title = {
            Column {
                Text("DashCam", fontWeight = FontWeight.SemiBold)
                Text(
                    if (isRecording) "前台服务录制中" else "Pixel 10a 行车记录仪",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun RecordingHome(
    padding: PaddingValues,
    stateMessage: String,
    fallbackGuidance: String?,
    isRecording: Boolean,
    settings: RecordingSettings,
    storageEstimate: RecordingStorageEstimate,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CameraPreviewCard(
            isRecording = isRecording,
            settings = settings,
        )
        RecordingStatusCard(
            isRecording = isRecording,
            stateMessage = stateMessage,
            fallbackGuidance = fallbackGuidance,
            settings = settings,
            storageEstimate = storageEstimate,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = if (isRecording) onStop else onStart,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    if (isRecording) Icons.Filled.Stop else Icons.Filled.Movie,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isRecording) "停止录制" else "开始录制")
            }
        }
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRecording,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("录制设置")
        }
        Text(
            "开始录制后会显示常驻通知，之后你可以按电源键熄屏。建议在系统电池设置里允许 DashCam 后台运行。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordingStatusCard(
    isRecording: Boolean,
    stateMessage: String,
    fallbackGuidance: String?,
    settings: RecordingSettings,
    storageEstimate: RecordingStorageEstimate,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) Color(0xFFEFF6FF) else Color.White,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (isRecording) "正在录制" else "准备就绪",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(stateMessage, style = MaterialTheme.typography.bodyMedium)
            if (isRecording && storageEstimate.remainingBytes <= 0L) {
                Text(
                    "当前安全可写空间不足，后续片段会继续尝试自动降档或停止录制。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!fallbackGuidance.isNullOrBlank()) {
                Text(
                    fallbackGuidance,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AssistChip(onClick = {}, label = { Text("${settings.resolution}${settings.frameRate}fps") })
                AssistChip(onClick = {}, label = { Text(settings.codec.codecLabel()) })
                AssistChip(onClick = {}, label = { Text(settings.bitratePreset.label()) })
                AssistChip(onClick = {}, label = { Text(settings.dynamicRange.dynamicRangeLabel()) })
                AssistChip(onClick = {}, label = { Text("分段 ${settings.segmentMinutes} 分钟") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("音频 ${if (settings.audioEnabled) "开" else "关"}") })
                AssistChip(onClick = {}, label = { Text("防抖 ${settings.stabilizationMode.label()}") })
                AssistChip(onClick = {}, label = { Text(settings.cameraLabel) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("剩余 ${storageEstimate.remainingBytes.formatBytes()}") },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("预计 ${storageEstimate.estimatedRecordableSeconds.formatRecordableTime()}") },
                )
            }
        }
    }
}

@Composable
private fun CameraPreviewCard(
    isRecording: Boolean,
    settings: RecordingSettings,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "录制中，预览已暂停以保证锁屏录制稳定",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Card
        }
        LaunchedEffect(previewView, settings) {
            previewView?.let {
                PreviewController.bind(context, lifecycleOwner, it, settings)
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                PreviewController.unbind(context)
            }
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            factory = {
                PreviewView(it).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    settings: RecordingSettings,
    isRecording: Boolean,
    downgradeState: RecordingDowngradeState?,
    capabilities: CameraCapabilities,
    storageEstimate: RecordingStorageEstimate,
    onSettingsChange: ((RecordingSettings) -> RecordingSettings) -> Unit,
) {
    val context = LocalContext.current
    val maxLoopQuotaBytes = remember(
        settings.reservePercent,
        storageEstimate.totalBytes,
        storageEstimate.usableBytes,
        storageEstimate.recordingBytes,
    ) {
        RecordingStorageEstimator.maxQuotaBytes(context, settings.reservePercent)
    }
    val canCustomizeLoopQuota = maxLoopQuotaBytes >= RecordingStorageEstimator.MIN_LOOP_QUOTA_BYTES
    val minLoopQuotaGb = RecordingStorageEstimator.MIN_LOOP_QUOTA_BYTES.toQuotaGb()
    val maxLoopQuotaGb = maxLoopQuotaBytes.toQuotaGb()
    val loopQuotaValueBytes = settings.loopQuotaBytes
        ?: maxLoopQuotaBytes.coerceAtLeast(RecordingStorageEstimator.MIN_LOOP_QUOTA_BYTES)
    val downgradeText = downgradeState?.settingsLockMessage()
    val qualityControlsEnabled = !isRecording && downgradeState == null

    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isRecording) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "录制中不能修改录制设置，请先停止录制。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (capabilities.cameraOptions.size > 1) {
            item {
                SettingsSection("镜头") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        capabilities.cameraOptions.forEach { option ->
                            FilterChip(
                                enabled = !isRecording,
                                selected = settings.cameraId == option.id,
                                onClick = {
                                    onSettingsChange {
                                        it.copy(cameraId = option.id, cameraLabel = option.label)
                                    }
                                },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsSection("画质") {
                if (!downgradeText.isNullOrBlank()) {
                    Text(
                        downgradeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("分辨率")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    capabilities.resolutionOptions.forEach { option ->
                        FilterChip(
                            enabled = qualityControlsEnabled,
                            selected = (downgradeState?.activeSettings?.resolution ?: settings.resolution) == option,
                            onClick = { onSettingsChange { it.copy(resolution = option) } },
                            label = { Text(option) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("帧率")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    capabilities.frameRateOptions.forEach { option ->
                        FilterChip(
                            enabled = qualityControlsEnabled,
                            selected = (downgradeState?.activeSettings?.frameRate ?: settings.frameRate) == option,
                            onClick = { onSettingsChange { it.copy(frameRate = option) } },
                            label = { Text("${option}fps") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("编码器")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    capabilities.codecOptions.forEach { option ->
                        FilterChip(
                            enabled = qualityControlsEnabled,
                            selected = (downgradeState?.activeSettings?.codec ?: settings.codec) == option.id,
                            onClick = { onSettingsChange { it.copy(codec = option.id) } },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("质量档位")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        BitratePreset.Auto,
                        BitratePreset.SpaceSaver,
                        BitratePreset.Standard,
                        BitratePreset.HighQuality,
                    ).forEach { option ->
                        FilterChip(
                            enabled = qualityControlsEnabled,
                            selected = (downgradeState?.activeSettings?.bitratePreset ?: settings.bitratePreset) == option,
                            onClick = { onSettingsChange { it.copy(bitratePreset = option) } },
                            label = { Text(option.label()) },
                        )
                    }
                }
                if (capabilities.dynamicRangeOptions.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text("HDR")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        capabilities.dynamicRangeOptions.forEach { option ->
                            FilterChip(
                                enabled = qualityControlsEnabled,
                                selected = (downgradeState?.activeSettings?.dynamicRange ?: settings.dynamicRange) == option.id,
                                onClick = { onSettingsChange { it.copy(dynamicRange = option.id) } },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsSection("分段时长") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 5, 10).forEach { option ->
                        FilterChip(
                            enabled = !isRecording,
                            selected = settings.segmentMinutes == option,
                            onClick = { onSettingsChange { it.copy(segmentMinutes = option) } },
                            label = { Text("${option}分") },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection("防抖") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    capabilities.stabilizationModes.forEach { mode ->
                        FilterChip(
                            enabled = qualityControlsEnabled,
                            selected = (downgradeState?.activeSettings?.stabilizationMode ?: settings.stabilizationMode) == mode,
                            onClick = { onSettingsChange { it.copy(stabilizationMode = mode) } },
                            label = { Text(mode.label()) },
                        )
                    }
                }
                if (!downgradeText.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        downgradeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            SettingsSection("录制安全") {
                Text("循环录制空间")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        enabled = !isRecording,
                        selected = settings.loopQuotaBytes == null,
                        onClick = { onSettingsChange { it.copy(loopQuotaBytes = null) } },
                        label = { Text("最大可用") },
                    )
                    FilterChip(
                        enabled = !isRecording && canCustomizeLoopQuota,
                        selected = settings.loopQuotaBytes != null,
                        onClick = {
                            if (canCustomizeLoopQuota) {
                                onSettingsChange {
                                    it.copy(loopQuotaBytes = loopQuotaValueBytes.coerceToLoopQuota(maxLoopQuotaBytes))
                                }
                            }
                        },
                        label = { Text("自定义") },
                    )
                }
                if (canCustomizeLoopQuota) {
                    Text(
                        "空间上限 ${
                            if (settings.loopQuotaBytes == null) {
                                "最大可用 ${maxLoopQuotaBytes.formatBytes()}"
                            } else {
                                loopQuotaValueBytes.formatBytes()
                            }
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (maxLoopQuotaGb > minLoopQuotaGb) {
                        Slider(
                            value = loopQuotaValueBytes
                                .coerceToLoopQuota(maxLoopQuotaBytes)
                                .toQuotaGb(),
                            onValueChange = { value ->
                                onSettingsChange {
                                    it.copy(loopQuotaBytes = value.toLoopQuotaBytes(maxLoopQuotaBytes))
                                }
                            },
                            valueRange = minLoopQuotaGb..maxLoopQuotaGb,
                            enabled = !isRecording,
                        )
                    }
                } else {
                    Text(
                        "当前可用空间不足 ${RecordingStorageEstimator.MIN_LOOP_QUOTA_BYTES.formatBytes()}，无法设置自定义循环空间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "当前录制目录 ${storageEstimate.recordingBytes.formatBytes()}，可写剩余 ${storageEstimate.remainingBytes.formatBytes()}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingSwitchRow(
                    title = "录制音频",
                    checked = settings.audioEnabled,
                    enabled = !isRecording,
                    onCheckedChange = { enabled -> onSettingsChange { it.copy(audioEnabled = enabled) } },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingSwitchRow(
                    title = "资源紧张时自动降低视频质量",
                    checked = settings.autoDowngradeEnabled,
                    enabled = !isRecording,
                    onCheckedChange = { enabled -> onSettingsChange { it.copy(autoDowngradeEnabled = enabled) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "默认至少保留 10% 系统存储空间。达到循环录制空间上限或系统空间不足时，会先删除最旧片段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    padding: PaddingValues,
    entries: List<RecordingEntry>,
    onDelete: (RecordingEntry) -> Unit,
    onShare: (RecordingEntry) -> Unit,
    onExport: (RecordingEntry) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    var previewEntry by remember { mutableStateOf<RecordingEntry?>(null) }

    pendingDelete?.let { entry ->
        DeleteRecordingDialog(
            entry = entry,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(entry)
            },
        )
    }

    previewEntry?.let { entry ->
        VideoPreviewDialog(
            entry = entry,
            onDismiss = { previewEntry = null },
            onShare = { onShare(entry) },
            onExport = { onExport(entry) },
        )
    }

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("还没有录制视频")
        }
        return
    }

    val grouped = entries.groupBy { it.dateHeader() }
    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        grouped.forEach { (date, dayEntries) ->
            stickyHeader {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                ) {
                    Text(
                        date,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            items(dayEntries, key = { it.id }) { entry ->
                RecordingListItem(
                    entry = entry,
                    onClick = {
                        previewEntry = entry
                    },
                    onDelete = { pendingDelete = entry },
                    onShare = { onShare(entry) },
                    onExport = { onExport(entry) },
                )
            }
        }
    }
}

@Composable
private fun RecordingListItem(
    entry: RecordingEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${entry.timeLabel()} · ${entry.durationMillis.formatDuration()} · ${entry.sizeBytes.formatBytes()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${entry.resolution}${entry.frameRate}fps · ${entry.codec.codecLabel()} · ${entry.dynamicRange.dynamicRangeLabel()} · ${entry.cameraLabel} · 防抖 ${entry.stabilizationMode.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "分享")
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Filled.FileUpload, contentDescription = "导出")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun VideoPreviewDialog(
    entry: RecordingEntry,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(entry.filePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(context.fileProviderUri(entry.file)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                entry.file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    factory = {
                        PlayerView(it).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    update = {
                        it.player = player
                    },
                )
                Text(
                    "${entry.timeLabel()} · ${entry.durationMillis.formatDuration()} · ${entry.sizeBytes.formatBytes()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onExport) {
                Text("导出")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShare) {
                    Text("分享")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}

@Composable
private fun DeleteRecordingDialog(
    entry: RecordingEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除视频？") },
        text = {
            Text("将删除 ${entry.file.name}，大小 ${entry.sizeBytes.formatBytes()}。此操作无法撤销。")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun StartRecordingDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recording_confirm_title)) },
        text = { Text(stringResource(R.string.recording_confirm_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.recording_confirm_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.recording_confirm_cancel))
            }
        },
    )
}

private fun Context.hasRecordingPermissions(): Boolean {
    val permissions = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    return permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun Context.startRecordingService() {
    ContextCompat.startForegroundService(this, RecordingService.startIntent(this))
}

private fun Context.stopRecordingService() {
    startService(RecordingService.stopIntent(this))
}

private fun Context.shareRecording(entry: RecordingEntry) {
    val uri = fileProviderUri(entry.file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "分享视频"))
}

private fun Context.exportRecording(entry: RecordingEntry) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(fileProviderUri(entry.file), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "打开或导出视频"))
}

private fun Context.fileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

private fun RecordingSettings.coerceToCapabilities(capabilities: CameraCapabilities): RecordingSettings {
    val camera = capabilities.cameraOptions.firstOrNull { it.id == cameraId }
        ?: capabilities.cameraOptions.firstOrNull()
    val resolution = resolution.takeIf { it in capabilities.resolutionOptions }
        ?: capabilities.resolutionOptions.firstOrNull { it == "720p" }
        ?: capabilities.resolutionOptions.firstOrNull { it == "1080p" }
        ?: capabilities.resolutionOptions.firstOrNull()
        ?: "720p"
    val frameRate = frameRate.takeIf { it in capabilities.frameRateOptions }
        ?: capabilities.frameRateOptions.firstOrNull { it == 30 }
        ?: capabilities.frameRateOptions.firstOrNull()
        ?: 30
    val codec = codec.takeIf { saved -> capabilities.codecOptions.any { it.id == saved } }
        ?: capabilities.codecOptions.firstOrNull { it.id == "auto" }?.id
        ?: "auto"
    val dynamicRange = dynamicRange.takeIf { saved -> capabilities.dynamicRangeOptions.any { it.id == saved } }
        ?: "sdr"
    val stabilizationMode = stabilizationMode.takeIf { it in capabilities.stabilizationModes }
        ?: capabilities.stabilizationModes.firstOrNull { it == StabilizationMode.Standard }
        ?: capabilities.stabilizationModes.firstOrNull()
        ?: StabilizationMode.Off

    return copy(
        cameraId = camera?.id.orEmpty(),
        cameraLabel = camera?.label ?: "1X 主镜头",
        resolution = resolution,
        frameRate = frameRate,
        codec = codec,
        dynamicRange = dynamicRange,
        stabilizationMode = stabilizationMode,
    )
}

private fun RecordingSettings.coerceToStorage(context: Context): RecordingSettings {
    val maxQuota = RecordingStorageEstimator.maxQuotaBytes(context, reservePercent)
    val quota = loopQuotaBytes
        ?.takeIf { it >= RecordingStorageEstimator.MIN_LOOP_QUOTA_BYTES }
        ?.takeIf { it <= maxQuota }
    return copy(loopQuotaBytes = quota)
}

private fun Long.coerceToLoopQuota(maxQuotaBytes: Long): Long =
    coerceIn(RecordingStorageEstimator.MIN_LOOP_QUOTA_BYTES, maxQuotaBytes)

private fun Long.toQuotaGb(): Float =
    (toDouble() / RecordingStorageEstimator.GB).toFloat()

private fun Float.toLoopQuotaBytes(maxQuotaBytes: Long): Long {
    val roundedGb = roundToLong().coerceAtLeast(2L)
    return (roundedGb * RecordingStorageEstimator.GB).coerceToLoopQuota(maxQuotaBytes)
}

private fun Long?.formatRecordableTime(): String {
    val seconds = this ?: return "无法预估"
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    return when {
        hours > 0L -> "${hours}小时${minutes}分"
        minutes > 0L -> "${minutes}分钟"
        else -> "不足1分钟"
    }
}

private fun StabilizationMode.label(): String = when (this) {
    StabilizationMode.Off -> "关"
    StabilizationMode.Standard -> "标准"
    StabilizationMode.Enhanced -> "增强"
}

private fun BitratePreset.label(): String = when (this) {
    BitratePreset.Auto -> "自动"
    BitratePreset.SpaceSaver -> "节省空间"
    BitratePreset.Standard -> "标准"
    BitratePreset.HighQuality -> "高画质"
}

private fun RecordingDowngradeState.settingsLockMessage(): String {
    val reasonsText = reasons.sortedBy { it.ordinal }.joinToString("、") { it.label() }
    return "本次录制已因$reasonsText 临时降档，画质相关设置已锁定为 ${activeSettings.resolution}${activeSettings.frameRate}fps ${activeSettings.bitratePreset.label()}。停止录制后可恢复修改。"
}

private fun RecordingDowngradeReason.label(): String = when (this) {
    RecordingDowngradeReason.StartupStorage -> "存储空间不足"
    RecordingDowngradeReason.Thermal -> "设备发热"
    RecordingDowngradeReason.Battery -> "电量偏低"
}
