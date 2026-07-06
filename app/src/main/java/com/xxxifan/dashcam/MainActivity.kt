package com.xxxifan.dashcam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xxxifan.dashcam.camera.CameraCapabilities
import com.xxxifan.dashcam.camera.CameraCapabilitiesRepository
import com.xxxifan.dashcam.camera.PreviewController
import com.xxxifan.dashcam.camera.codecLabel
import com.xxxifan.dashcam.camera.coerceToSupportedCombination
import com.xxxifan.dashcam.camera.dynamicRangeLabel
import com.xxxifan.dashcam.camera.frameRateOptionsForResolution
import com.xxxifan.dashcam.camera.isHdrDynamicRange
import com.xxxifan.dashcam.camera.isRecordingCombinationSupported
import com.xxxifan.dashcam.camera.toLogFields
import com.xxxifan.dashcam.data.AppGuidanceStore
import com.xxxifan.dashcam.data.BitratePreset
import com.xxxifan.dashcam.data.FocusMode
import com.xxxifan.dashcam.data.PlaybackPreferencesStore
import com.xxxifan.dashcam.data.RecordingAlertStore
import com.xxxifan.dashcam.data.RecordingEntry
import com.xxxifan.dashcam.data.RecordingEventLogger
import com.xxxifan.dashcam.data.RecordingRepository
import com.xxxifan.dashcam.data.RecordingSettings
import com.xxxifan.dashcam.data.RecordingSettingsStore
import com.xxxifan.dashcam.data.RecordingStopAlert
import com.xxxifan.dashcam.data.RecordingThumbnailManager
import com.xxxifan.dashcam.data.StabilizationMode
import com.xxxifan.dashcam.data.coerceCropZoomRatio
import com.xxxifan.dashcam.data.dateHeader
import com.xxxifan.dashcam.data.formatBytes
import com.xxxifan.dashcam.data.formatDuration
import com.xxxifan.dashcam.data.recordingCropZoomRatios
import com.xxxifan.dashcam.data.timeLabel
import com.xxxifan.dashcam.device.DeviceDisplayNameResolver
import com.xxxifan.dashcam.recording.RecordingService
import com.xxxifan.dashcam.recording.RecordingDowngradeReason
import com.xxxifan.dashcam.recording.RecordingDowngradeState
import com.xxxifan.dashcam.recording.RecordingQualityResolver
import com.xxxifan.dashcam.recording.RecordingStateBus
import com.xxxifan.dashcam.recording.RecordingUiState
import com.xxxifan.dashcam.safety.RecordingSafetyDecision
import com.xxxifan.dashcam.storage.LoopStorageManager
import com.xxxifan.dashcam.storage.RecordingStorageEstimate
import com.xxxifan.dashcam.storage.RecordingStorageEstimator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val activity = remember(context) { context.findActivity() }
        val deviceDisplayName = remember(context) { DeviceDisplayNameResolver.displayName(context) }
        val appScope = rememberCoroutineScope()
        val appGuidanceStore = remember { AppGuidanceStore() }
        val settingsStore = remember { RecordingSettingsStore() }
        val playbackPreferencesStore = remember { PlaybackPreferencesStore() }
        val alertStore = remember { RecordingAlertStore() }
        val recordingRepository = remember { RecordingRepository() }
        val thumbnailManager = remember { RecordingThumbnailManager(context, recordingRepository) }
        val eventLogger = remember { RecordingEventLogger.get(context) }
        val cameraCapabilitiesRepository = remember { CameraCapabilitiesRepository(context) }
        var cameraCapabilities by remember {
            mutableStateOf(cameraCapabilitiesRepository.capabilities())
        }
        val uiState by RecordingStateBus.state.collectAsStateWithLifecycle()
        val entries by recordingRepository.entries.collectAsStateWithLifecycle()
        val activeRecordingPaths = remember(uiState.currentSegmentPath) {
            setOfNotNull(uiState.currentSegmentPath)
        }
        var settings by remember { mutableStateOf(settingsStore.get()) }
        var stopAlert by remember { mutableStateOf(alertStore.getLastStopAlert()) }
        var playbackEntry by remember { mutableStateOf<RecordingEntry?>(null) }
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
        var showBatteryOptimizationPrompt by remember { mutableStateOf(false) }
        val shouldShowConfirmAfterPermission = consumePermissionResult()
        val hdrWindowEnabled = playbackEntry == null &&
            selectedTab == 0 &&
            settings.dynamicRange.isHdrDynamicRange()
        val exportRecording: (RecordingEntry) -> Unit = { entry ->
            appScope.launch {
                val result = context.exportRecordingToMediaStore(entry)
                result
                    .onSuccess {
                        eventLogger.log(
                            event = "export_success",
                            fields = mapOf(
                                "entryId" to entry.id,
                                "filePath" to entry.filePath,
                                "sizeBytes" to entry.sizeBytes,
                                "uri" to it.toString(),
                            ),
                        )
                        recordingRepository.markExported(entry.id)
                        Toast.makeText(context, "已导出到 Movies/DashCam", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        eventLogger.log(
                            event = "export_failure",
                            fields = mapOf(
                                "entryId" to entry.id,
                                "filePath" to entry.filePath,
                                "sizeBytes" to entry.sizeBytes,
                                "error" to it.message,
                            ),
                        )
                        Toast.makeText(context, "导出失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                    }
            }
        }

        DisposableEffect(activity, hdrWindowEnabled) {
            val window = activity?.window
            val previousColorMode = window?.colorMode
            val previousHdrHeadroom = window?.desiredHdrHeadroom
            window?.applyHdrWindowMode(hdrWindowEnabled)
            onDispose {
                if (previousColorMode != null) {
                    window.colorMode = previousColorMode
                }
                if (previousHdrHeadroom != null) {
                    window.desiredHdrHeadroom = previousHdrHeadroom
                }
            }
        }

        LaunchedEffect(cameraCapabilities) {
            settings = settingsStore.update {
                it.coerceToCapabilities(cameraCapabilities)
                    .coerceToStorage(context)
                    .resolveAutoQualityIfNeeded(context, cameraCapabilities)
            }
        }

        LaunchedEffect(cameraCapabilitiesRepository) {
            val provider = ProcessCameraProvider.getInstance(context).await()
            val cameraInfo = runCatching {
                provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            }.getOrNull()
            cameraCapabilities = cameraCapabilitiesRepository.capabilities(cameraInfo).also { capabilities ->
                eventLogger.log(
                    event = "camera_hdr_diagnostics",
                    fields = capabilities.hdrDiagnostics.toLogFields() + mapOf("source" to "main_activity"),
                )
            }
        }

        LaunchedEffect(Unit) {
            recordingRepository.refreshFromDirectory(
                directory = LoopStorageManager.recordingDirectory(context),
                excludedPaths = activeRecordingPaths,
            )
        }

        LaunchedEffect(Unit) {
            showBatteryOptimizationPrompt =
                context.shouldShowBatteryOptimizationPrompt(appGuidanceStore)
        }

        LaunchedEffect(selectedTab, activeRecordingPaths) {
            if (selectedTab == 1) {
                recordingRepository.refreshFromDirectory(
                    directory = LoopStorageManager.recordingDirectory(context),
                    excludedPaths = activeRecordingPaths,
                )
            }
        }

        LaunchedEffect(activeRecordingPaths) {
            if (activeRecordingPaths.isNotEmpty()) {
                recordingRepository.refreshFromDirectory(
                    directory = LoopStorageManager.recordingDirectory(context),
                    excludedPaths = activeRecordingPaths,
                )
            }
        }

        LaunchedEffect(selectedTab, entries) {
            if (selectedTab == 1) {
                thumbnailManager.cleanOrphans(entries)
                thumbnailManager.backfill(entries.take(8))
            }
        }

        LaunchedEffect(
            settings,
            uiState.activeSettings,
            entries,
            selectedTab,
            uiState.isRecording,
        ) {
            val estimateSettings = uiState.activeSettings ?: settings
            while (true) {
                storageEstimate = RecordingStorageEstimator.estimate(
                    context = context,
                    settings = estimateSettings,
                    entries = entries,
                )
                if (!uiState.isRecording) {
                    break
                }
                delay(RECORDING_STORAGE_ESTIMATE_REFRESH_MILLIS)
            }
        }

        LaunchedEffect(uiState.isRecording, uiState.message, uiState.fallbackGuidance) {
            if (!uiState.isRecording) {
                stopAlert = alertStore.getLastStopAlert()
            }
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
                    alertStore.clearLastStopAlert()
                    stopAlert = null
                    context.startRecordingService()
                },
            )
        }

        if (showBatteryOptimizationPrompt) {
            BatteryOptimizationPromptDialog(
                onDismiss = {
                    appGuidanceStore.markBatteryOptimizationPromptShown()
                    showBatteryOptimizationPrompt = false
                },
                onConfirm = {
                    appGuidanceStore.markBatteryOptimizationPromptShown()
                    showBatteryOptimizationPrompt = false
                    if (!context.openBatteryOptimizationSettings()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.battery_optimization_settings_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
        }

        val activePlaybackEntry = playbackEntry
        val shareRecording: (RecordingEntry) -> Unit = { entry ->
            eventLogger.log(
                event = "share_requested",
                fields = mapOf(
                    "entryId" to entry.id,
                    "filePath" to entry.filePath,
                    "sizeBytes" to entry.sizeBytes,
                ),
            )
            context.shareRecording(entry)
                .onFailure {
                    eventLogger.log(
                        event = "share_failure",
                        fields = mapOf(
                            "entryId" to entry.id,
                            "filePath" to entry.filePath,
                            "sizeBytes" to entry.sizeBytes,
                            "error" to it.message,
                        ),
                    )
                    Toast.makeText(
                        context,
                        "分享失败：${it.message ?: "未知错误"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
        if (activePlaybackEntry != null) {
            VideoPlaybackScreen(
                entry = activePlaybackEntry,
                entries = entries,
                onDismiss = { playbackEntry = null },
                onShare = shareRecording,
                onExport = exportRecording,
                playbackPreferencesStore = playbackPreferencesStore,
            )
        } else {
            Scaffold(
                topBar = {
                    AppTopBar(
                        isRecording = uiState.isRecording,
                        deviceDisplayName = deviceDisplayName,
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
                        stopAlert = if (uiState.isRecording) null else stopAlert,
                        safetyDecision = uiState.safetyDecision,
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
                        onStop = {
                            alertStore.clearLastStopAlert()
                            stopAlert = null
                            context.stopRecordingService()
                        },
                        onOpenSettings = { selectedTab = 2 },
                    )
                    1 -> LibraryScreen(
                        padding = padding,
                        entries = entries,
                        recordingState = uiState,
                        onOpenPlayback = { playbackEntry = it },
                        onStopRecording = {
                            alertStore.clearLastStopAlert()
                            stopAlert = null
                            context.stopRecordingService()
                        },
                        onDelete = { entry ->
                            recordingRepository.delete(entry)
                            eventLogger.log(
                                event = "delete_recording",
                                fields = mapOf(
                                    "reason" to "manual_single",
                                    "entryId" to entry.id,
                                    "filePath" to entry.filePath,
                                    "sizeBytes" to entry.sizeBytes,
                                ),
                            )
                        },
                        onDeleteAll = { targets, reason ->
                            val deletedCount = recordingRepository.deleteAll(targets)
                            eventLogger.log(
                                event = "delete_recordings_bulk",
                                fields = mapOf(
                                    "reason" to reason,
                                    "requestedCount" to targets.size,
                                    "deletedCount" to deletedCount,
                                    "totalSizeBytes" to targets.sumOf { it.sizeBytes },
                                ),
                            )
                        },
                        onShare = shareRecording,
                        onExport = exportRecording,
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
                                    .resolveAutoQualityIfNeeded(context, cameraCapabilities)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    isRecording: Boolean,
    deviceDisplayName: String,
) {
    TopAppBar(
        title = {
            Column {
                Text("DashCam", fontWeight = FontWeight.SemiBold)
                Text(
                    if (isRecording) "前台服务录制中" else "${deviceDisplayName}行车记录仪",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    stopAlert: RecordingStopAlert?,
    safetyDecision: RecordingSafetyDecision?,
    fallbackGuidance: String?,
    isRecording: Boolean,
    settings: RecordingSettings,
    storageEstimate: RecordingStorageEstimate,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CameraPreviewCard(
                isRecording = isRecording,
                settings = settings,
            )
        }
        item {
            RecordingStatusCard(
                isRecording = isRecording,
                stateMessage = stateMessage,
                stopAlert = stopAlert,
                safetyDecision = safetyDecision,
                fallbackGuidance = fallbackGuidance,
                settings = settings,
                storageEstimate = storageEstimate,
            )
        }
        item {
            Button(
                onClick = if (isRecording) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (isRecording) Icons.Filled.Stop else Icons.Filled.Movie,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isRecording) "停止录制" else "开始录制")
            }
        }
        item {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRecording,
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("录制设置")
            }
        }
        item {
            Text(
                "开始录制后会显示常驻通知，之后你可以按电源键熄屏。建议在系统电池设置里允许 DashCam 后台运行。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingStatusCard(
    isRecording: Boolean,
    stateMessage: String,
    stopAlert: RecordingStopAlert?,
    safetyDecision: RecordingSafetyDecision?,
    fallbackGuidance: String?,
    settings: RecordingSettings,
    storageEstimate: RecordingStorageEstimate,
) {
    val displayMessage = stopAlert?.let { "上次录制停止：${it.message}" } ?: stateMessage
    val displayGuidance = stopAlert?.fallbackGuidance ?: fallbackGuidance
    val displayStopReason = stopAlert?.reason?.stopReasonLabel()

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
            Text(
                displayMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (stopAlert != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (!displayStopReason.isNullOrBlank()) {
                Text(
                    "停止类型：$displayStopReason",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (safetyDecision != null) {
                Text(
                    safetyDecision.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (isRecording && storageEstimate.remainingBytes <= 0L) {
                Text(
                    "当前安全可写空间不足，后续片段会继续尝试自动降档或停止录制。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!displayGuidance.isNullOrBlank()) {
                Text(
                    displayGuidance,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            RecordingStatusChips(settings = settings, storageEstimate = storageEstimate)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingStatusChips(
    settings: RecordingSettings,
    storageEstimate: RecordingStorageEstimate,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = {}, label = { Text("${settings.resolution}${settings.frameRate}fps") })
        AssistChip(onClick = {}, label = { Text(settings.codec.codecLabel()) })
        AssistChip(onClick = {}, label = { Text(settings.bitratePreset.label()) })
        AssistChip(onClick = {}, label = { Text("画质 ${if (settings.autoQualityEnabled) "自动" else "手动"}") })
        AssistChip(onClick = {}, label = { Text(settings.dynamicRange.dynamicRangeLabel()) })
        AssistChip(onClick = {}, label = { Text("分段 ${settings.segmentMinutes} 分钟") })
        AssistChip(onClick = {}, label = { Text("音频 ${if (settings.audioEnabled) "开" else "关"}") })
        AssistChip(onClick = {}, label = { Text("防抖 ${settings.stabilizationMode.label()}") })
        AssistChip(onClick = {}, label = { Text(settings.cameraLabel) })
        AssistChip(onClick = {}, label = { Text("裁剪 ${settings.cropZoomRatio.zoomRatioLabel()}") })
        AssistChip(onClick = {}, label = { Text("对焦 ${settings.focusMode.label()}") })
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
                it.implementationMode = settings.previewImplementationMode()
                it.applyHdrHeadroom(settings.dynamicRange.isHdrDynamicRange())
                PreviewController.bind(context, lifecycleOwner, it, settings)
                it.applyHdrHeadroomAfterLayout(settings.dynamicRange.isHdrDynamicRange())
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
                    implementationMode = settings.previewImplementationMode()
                    applyHdrHeadroom(settings.dynamicRange.isHdrDynamicRange())
                    previewView = this
                }
            },
            update = {
                it.implementationMode = settings.previewImplementationMode()
                it.applyHdrHeadroomAfterLayout(settings.dynamicRange.isHdrDynamicRange())
            },
        )
    }
}

private fun RecordingSettings.previewImplementationMode(): PreviewView.ImplementationMode =
    if (dynamicRange.isHdrDynamicRange()) {
        PreviewView.ImplementationMode.PERFORMANCE
    } else {
        PreviewView.ImplementationMode.COMPATIBLE
    }

private fun android.view.Window.applyHdrWindowMode(enabled: Boolean) {
    colorMode = if (enabled) {
        ActivityInfo.COLOR_MODE_HDR
    } else {
        ActivityInfo.COLOR_MODE_DEFAULT
    }
    desiredHdrHeadroom = if (enabled) HDR_HEADROOM_RATIO else 0f
}

private fun View.applyHdrHeadroom(enabled: Boolean) {
    if (this is SurfaceView) {
        setDesiredHdrHeadroom(if (enabled) HDR_HEADROOM_RATIO else 0f)
        return
    }
    if (this is ViewGroup) {
        repeat(childCount) { index ->
            getChildAt(index).applyHdrHeadroom(enabled)
        }
    }
}

private fun View.applyHdrHeadroomAfterLayout(enabled: Boolean) {
    applyHdrHeadroom(enabled)
    post { applyHdrHeadroom(enabled) }
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
    val qualitySettings = downgradeState?.activeSettings ?: settings
    fun qualityOptionEnabled(
        update: (RecordingSettings) -> RecordingSettings,
        isKept: (RecordingSettings) -> Boolean,
    ): Boolean {
        if (!qualityControlsEnabled) {
            return false
        }
        val adjusted = update(qualitySettings)
            .coerceToSupportedCombination(capabilities)
        return isKept(adjusted) && capabilities.isRecordingCombinationSupported(adjusted)
    }
    fun onManualQualityChange(update: (RecordingSettings) -> RecordingSettings) {
        onSettingsChange { current ->
            val base = current.copy(
                resolution = qualitySettings.resolution,
                frameRate = qualitySettings.frameRate,
                codec = qualitySettings.codec,
                bitratePreset = qualitySettings.bitratePreset,
                dynamicRange = qualitySettings.dynamicRange,
                stabilizationMode = qualitySettings.stabilizationMode,
                autoQualityEnabled = false,
            )
            update(base).coerceToSupportedCombination(capabilities)
        }
    }

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
            SettingsSection("对焦模式") {
                Text(
                    "自动：持续对焦；最远：关闭自动对焦并锁定到无穷远，适合行车拍摄。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusMode.entries.forEach { mode ->
                        FilterChip(
                            enabled = !isRecording,
                            selected = settings.focusMode == mode,
                            onClick = { onSettingsChange { it.copy(focusMode = mode) } },
                            label = { Text(mode.label()) },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection("画面裁剪放大") {
                Text(
                    "录制时按所选倍率中心裁剪并输出到当前分辨率。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recordingCropZoomRatios.forEach { ratio ->
                        FilterChip(
                            enabled = !isRecording,
                            selected = settings.cropZoomRatio.zoomRatioKey() == ratio.zoomRatioKey(),
                            onClick = { onSettingsChange { it.copy(cropZoomRatio = ratio) } },
                            label = { Text(ratio.zoomRatioLabel()) },
                        )
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
                SettingSwitchRow(
                    title = "自动画质",
                    checked = settings.autoQualityEnabled,
                    enabled = qualityControlsEnabled,
                    onCheckedChange = { enabled ->
                        onSettingsChange {
                            it.copy(autoQualityEnabled = enabled)
                                .coerceToSupportedCombination(capabilities)
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("分辨率")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    capabilities.resolutionOptions.forEach { option ->
                        FilterChip(
                            enabled = qualityOptionEnabled(
                                update = { it.copy(resolution = option) },
                                isKept = { it.resolution == option },
                            ),
                            selected = qualitySettings.resolution == option,
                            onClick = { onManualQualityChange { it.copy(resolution = option) } },
                            label = { Text(option) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("帧率")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val availableFrameRates = capabilities
                        .frameRateOptionsForResolution(qualitySettings.resolution)
                        .toSet()
                    capabilities.frameRateOptions.forEach { option ->
                        FilterChip(
                            enabled = option in availableFrameRates &&
                                qualityOptionEnabled(
                                    update = { it.copy(frameRate = option) },
                                    isKept = { it.frameRate == option },
                                ),
                            selected = qualitySettings.frameRate == option,
                            onClick = { onManualQualityChange { it.copy(frameRate = option) } },
                            label = { Text("${option}fps") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("编码器")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    capabilities.codecOptions.forEach { option ->
                        FilterChip(
                            enabled = qualityOptionEnabled(
                                update = { it.copy(codec = option.id) },
                                isKept = { it.codec == option.id },
                            ),
                            selected = qualitySettings.codec == option.id,
                            onClick = { onManualQualityChange { it.copy(codec = option.id) } },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("质量档位")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        BitratePreset.SpaceSaver,
                        BitratePreset.Standard,
                        BitratePreset.HighQuality,
                    ).forEach { option ->
                        FilterChip(
                            enabled = qualityControlsEnabled,
                            selected = qualitySettings.bitratePreset == option,
                            onClick = { onManualQualityChange { it.copy(bitratePreset = option) } },
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
                                enabled = qualityOptionEnabled(
                                    update = { it.copy(dynamicRange = option.id) },
                                    isKept = { it.dynamicRange == option.id },
                                ),
                                selected = qualitySettings.dynamicRange == option.id,
                                onClick = { onManualQualityChange { it.copy(dynamicRange = option.id) } },
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
                            enabled = qualityOptionEnabled(
                                update = { it.copy(stabilizationMode = mode) },
                                isKept = { it.stabilizationMode == mode },
                            ),
                            selected = qualitySettings.stabilizationMode == mode,
                            onClick = { onManualQualityChange { it.copy(stabilizationMode = mode) } },
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

private data class RecordingCleanupRequest(
    val title: String,
    val description: String,
    val entries: List<RecordingEntry>,
    val reasonKey: String,
) {
    val totalSizeBytes: Long = entries.sumOf { it.sizeBytes }
}

private enum class CleanupRetentionOption(
    val key: String,
    val label: String,
    val millis: Long,
) {
    OneYear("one_year", "一年", 365L * 24L * 60L * 60L * 1000L),
    HalfYear("half_year", "半年", 183L * 24L * 60L * 60L * 1000L),
    OneMonth("one_month", "一月", 30L * 24L * 60L * 60L * 1000L),
    HalfMonth("half_month", "半月", 15L * 24L * 60L * 60L * 1000L),
    OneWeek("one_week", "一周", 7L * 24L * 60L * 60L * 1000L),
    OneDay("one_day", "1天", 24L * 60L * 60L * 1000L),
    SixHours("six_hours", "6小时", 6L * 60L * 60L * 1000L),
    ThreeHours("three_hours", "3小时", 3L * 60L * 60L * 1000L),
    OneHour("one_hour", "1小时", 60L * 60L * 1000L);

    fun cutoffMillis(): Long = System.currentTimeMillis() - millis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    padding: PaddingValues,
    entries: List<RecordingEntry>,
    recordingState: RecordingUiState,
    onOpenPlayback: (RecordingEntry) -> Unit,
    onStopRecording: () -> Unit,
    onDelete: (RecordingEntry) -> Unit,
    onDeleteAll: (List<RecordingEntry>, String) -> Unit,
    onShare: (RecordingEntry) -> Unit,
    onExport: (RecordingEntry) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RecordingEntry?>(null) }
    var pendingCleanup by remember { mutableStateOf<RecordingCleanupRequest?>(null) }
    var showCleanupMenu by remember { mutableStateOf(false) }

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

    pendingCleanup?.let { request ->
        CleanupRecordingsDialog(
            request = request,
            onDismiss = { pendingCleanup = null },
            onConfirm = {
                pendingCleanup = null
                onDeleteAll(request.entries, request.reasonKey)
            },
        )
    }

    if (entries.isEmpty() && !recordingState.isRecording) {
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
        if (recordingState.isRecording) {
            item(key = "active-recording") {
                ActiveRecordingListItem(
                    state = recordingState,
                    onStop = onStopRecording,
                )
            }
        }
        grouped.forEach { (date, dayEntries) ->
            stickyHeader {
                LibraryDateHeader(
                    date = date,
                    canCleanup = entries.isNotEmpty(),
                    showCleanupMenu = showCleanupMenu,
                    onOpenCleanupMenu = { showCleanupMenu = true },
                    onDismissCleanupMenu = { showCleanupMenu = false },
                    onCleanupAll = {
                        showCleanupMenu = false
                        pendingCleanup = RecordingCleanupRequest(
                            title = "全部清除？",
                            description = "将删除视频库中的全部 ${entries.size} 个视频。",
                            entries = entries,
                            reasonKey = "all",
                        )
                    },
                    onCleanupRetention = { option ->
                        showCleanupMenu = false
                        val targets = entries.filter {
                            it.startedAtMillis < option.cutoffMillis()
                        }
                        pendingCleanup = RecordingCleanupRequest(
                            title = "按时间清理？",
                            description = "将删除早于${option.label}的视频，保留最近${option.label}。",
                            entries = targets,
                            reasonKey = "retention_${option.key}",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    showActions = date == grouped.keys.first(),
                )
            }
            items(dayEntries, key = { it.id }) { entry ->
                RecordingListItem(
                    entry = entry,
                    onClick = { onOpenPlayback(entry) },
                    onDelete = { pendingDelete = entry },
                    onShare = { onShare(entry) },
                    onExport = { onExport(entry) },
                )
            }
        }
    }
}

@Composable
private fun LibraryDateHeader(
    date: String,
    canCleanup: Boolean,
    showCleanupMenu: Boolean,
    onOpenCleanupMenu: () -> Unit,
    onDismissCleanupMenu: () -> Unit,
    onCleanupAll: () -> Unit,
    onCleanupRetention: (CleanupRetentionOption) -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (showActions && canCleanup) {
                Box {
                    IconButton(onClick = onOpenCleanupMenu) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清理")
                    }
                    DropdownMenu(
                        expanded = showCleanupMenu,
                        onDismissRequest = onDismissCleanupMenu,
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部清除") },
                            onClick = onCleanupAll,
                        )
                        CleanupRetentionOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("保留${option.label}") },
                                onClick = { onCleanupRetention(option) },
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ActiveRecordingListItem(
    state: RecordingUiState,
    onStop: () -> Unit,
) {
    val fileName = state.currentSegmentPath
        ?.let { File(it).name }
        ?: "当前片段"
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    fileName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "正在写入 · ${state.recordedDurationNanos.formatDurationNanos()} · ${state.recordedBytes.formatCoarseBytes()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "片段完成后才可播放、删除、导出或分享",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("停止")
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
            modifier = Modifier
                .padding(10.dp)
                .height(116.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RecordingThumbnail(entry)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                Column {
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
                        "${entry.resolution}${entry.frameRate}fps · ${entry.codec.codecLabel()} · ${entry.dynamicRange.dynamicRangeLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${entry.cameraLabel} · 裁剪 ${entry.cropZoomRatio.zoomRatioLabel()} · 防抖 ${entry.stabilizationMode.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.exported) {
                        Text(
                            "已导出到 Movies/DashCam",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = onExport, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "导出")
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingThumbnail(
    entry: RecordingEntry,
) {
    var bitmap by remember(entry.thumbnailPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.thumbnailPath) {
        bitmap = withContext(Dispatchers.IO) {
            entry.thumbnailPath
                ?.let { BitmapFactory.decodeFile(it) }
                ?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = Modifier
                .width(152.dp)
                .height(116.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFE2E8F0)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .width(152.dp)
                .height(116.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFE2E8F0)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun VideoPlaybackScreen(
    entry: RecordingEntry,
    entries: List<RecordingEntry>,
    onDismiss: () -> Unit,
    onShare: (RecordingEntry) -> Unit,
    onExport: (RecordingEntry) -> Unit,
    playbackPreferencesStore: PlaybackPreferencesStore,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    var currentEntry by remember(entry.filePath) { mutableStateOf(entry) }
    var metadata by remember(currentEntry.filePath) { mutableStateOf<VideoMetadata?>(null) }
    val isCurrentVideoHdr = currentEntry.dynamicRange.isHdrDynamicRange() || metadata?.isHdr == true
    var continuousPlayEnabled by remember {
        mutableStateOf(playbackPreferencesStore.isContinuousPlayEnabled())
    }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var bufferedPositionMs by remember { mutableStateOf(0L) }
    var durationMs by remember(currentEntry.filePath) { mutableStateOf(currentEntry.durationMillis) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    val nextEntry = remember(currentEntry.id, entries) {
        entries.nextRecordingAfter(currentEntry)
    }

    BackHandler(onBack = onDismiss)

    DisposableEffect(activity, isCurrentVideoHdr) {
        val window = activity?.window
        val previousColorMode = window?.colorMode
        val previousHdrHeadroom = window?.desiredHdrHeadroom
        window?.applyHdrWindowMode(isCurrentVideoHdr)
        onDispose {
            if (previousColorMode != null) {
                window.colorMode = previousColorMode
            }
            if (previousHdrHeadroom != null) {
                window.desiredHdrHeadroom = previousHdrHeadroom
            }
        }
    }

    LaunchedEffect(currentEntry.filePath) {
        metadata = withContext(Dispatchers.IO) {
            currentEntry.file.readVideoMetadata()
        }
    }

    LaunchedEffect(currentEntry.filePath) {
        positionMs = 0L
        bufferedPositionMs = 0L
        durationMs = currentEntry.durationMillis
        player.setMediaItem(MediaItem.fromUri(context.fileProviderUri(currentEntry.file)))
        player.prepare()
        player.play()
    }

    DisposableEffect(activity, metadata?.isLandscape) {
        val previousOrientation = activity?.requestedOrientation
        if (activity != null && metadata?.isLandscape == true) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    DisposableEffect(player, continuousPlayEnabled, nextEntry?.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && continuousPlayEnabled && nextEntry != null) {
                    currentEntry = nextEntry
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            positionMs = player.currentPosition.coerceAtLeast(0L)
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
            if (player.duration > 0L) {
                durationMs = player.duration
            }
            playbackSpeed = player.playbackParameters.speed
            delay(250L)
        }
    }

    Surface(
        color = Color.Black,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        applyHdrHeadroom(isCurrentVideoHdr)
                    }
                },
                update = {
                    it.player = player
                    it.applyHdrHeadroomAfterLayout(isCurrentVideoHdr)
                },
            )
            VideoPlaybackTopBar(
                entry = currentEntry,
                onDismiss = onDismiss,
                onShare = { onShare(currentEntry) },
                onExport = { onExport(currentEntry) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            VideoPlaybackControls(
                isPlaying = isPlaying,
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                continuousPlayEnabled = continuousPlayEnabled,
                hasNextEntry = nextEntry != null,
                onTogglePlayback = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(0L)
                        }
                        player.play()
                    }
                },
                onSeek = { player.seekTo(it) },
                onSpeedChange = { speed ->
                    player.setPlaybackSpeed(speed)
                    playbackSpeed = speed
                },
                onContinuousPlayChange = { enabled ->
                    continuousPlayEnabled = enabled
                    playbackPreferencesStore.setContinuousPlayEnabled(enabled)
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun VideoPlaybackTopBar(
    entry: RecordingEntry,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                entry.file.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${entry.timeLabel()} · ${entry.sizeBytes.formatBytes()}",
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color.White)
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("导出") },
                    leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onExport()
                    },
                )
                DropdownMenuItem(
                    text = { Text("分享") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onShare()
                    },
                )
            }
        }
    }
}

@Composable
private fun VideoPlaybackControls(
    isPlaying: Boolean,
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    continuousPlayEnabled: Boolean,
    hasNextEntry: Boolean,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onContinuousPlayChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    val safeDurationMs = durationMs.coerceAtLeast(1L)
    val displayPositionMs = (pendingSeekMs ?: positionMs).coerceIn(0L, safeDurationMs)
    val bufferedPercent = ((bufferedPositionMs.coerceIn(0L, safeDurationMs) * 100L) / safeDurationMs)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Slider(
            value = displayPositionMs.toFloat(),
            onValueChange = { pendingSeekMs = it.toLong() },
            onValueChangeFinished = {
                pendingSeekMs?.let(onSeek)
                pendingSeekMs = null
            },
            valueRange = 0f..safeDurationMs.toFloat(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                displayPositionMs.formatPlaybackTime(),
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "缓冲 $bufferedPercent% · ${safeDurationMs.formatPlaybackTime()}",
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onTogglePlayback) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { showSpeedMenu = true }) {
                        Text("${playbackSpeed.speedLabel()}x", color = Color.White)
                    }
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false },
                    ) {
                        listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed.speedLabel()}x") },
                                onClick = {
                                    showSpeedMenu = false
                                    onSpeedChange(speed)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Checkbox(
                    checked = continuousPlayEnabled,
                    onCheckedChange = onContinuousPlayChange,
                )
                Text(
                    "连续播放",
                    color = if (continuousPlayEnabled && !hasNextEntry) Color(0xFFCBD5E1) else Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun List<RecordingEntry>.nextRecordingAfter(entry: RecordingEntry): RecordingEntry? =
    filter { candidate ->
        candidate.id != entry.id && candidate.startedAtMillis > entry.startedAtMillis
    }.minByOrNull { it.startedAtMillis }

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
private fun CleanupRecordingsDialog(
    request: RecordingCleanupRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Text(
                if (request.entries.isEmpty()) {
                    "${request.description}\n\n当前没有符合条件的视频。"
                } else {
                    "${request.description}\n\n预计删除 ${request.entries.size} 个视频，释放 ${request.totalSizeBytes.formatBytes()}。此操作无法撤销。"
                },
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = request.entries.isNotEmpty(),
            ) {
                Text("确认清理")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (request.entries.isEmpty()) "知道了" else "取消")
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

@Composable
private fun BatteryOptimizationPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_optimization_prompt_title)) },
        text = { Text(stringResource(R.string.battery_optimization_prompt_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.battery_optimization_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.battery_optimization_prompt_dismiss))
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

private fun Context.shouldShowBatteryOptimizationPrompt(
    appGuidanceStore: AppGuidanceStore,
): Boolean =
    !appGuidanceStore.hasShownBatteryOptimizationPrompt() && !isIgnoringBatteryOptimizations()

private fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(packageName)
}

private fun Context.openBatteryOptimizationSettings(): Boolean {
    val intents = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName")),
    )
    return intents.any { intent ->
        val launchIntent = if (this is Activity) intent else {
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivity(launchIntent)
        }.isSuccess
    }
}

private fun Context.startRecordingService() {
    ContextCompat.startForegroundService(this, RecordingService.startIntent(this))
}

private fun Context.stopRecordingService() {
    startService(RecordingService.stopIntent(this))
}

private fun Context.shareRecording(entry: RecordingEntry): Result<Unit> = runCatching {
    if (!entry.file.exists()) {
        throw IOException("源文件不存在")
    }
    val uri = fileProviderUri(entry.file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "分享视频"))
}

private fun Context.fileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

private suspend fun Context.exportRecordingToMediaStore(entry: RecordingEntry): Result<Uri> = withContext(Dispatchers.IO) {
    runCatching {
        val source = entry.file
        if (!source.exists()) {
            throw IOException("源文件不存在")
        }
        val resolver = contentResolver
        val displayName = source.name
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DashCam")
            put(MediaStore.Video.Media.DATE_TAKEN, entry.startedAtMillis)
            put(MediaStore.Video.Media.DURATION, entry.durationMillis)
            put(MediaStore.Video.Media.SIZE, source.length())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("无法创建媒体库条目")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("无法写入导出文件")
            val completedValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, completedValues, null, null)
            uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}

private data class VideoMetadata(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val colorTransfer: Int?,
    val colorStandard: Int?,
) {
    val isLandscape: Boolean
        get() {
            val rotated = rotation == 90 || rotation == 270
            val displayWidth = if (rotated) height else width
            val displayHeight = if (rotated) width else height
            return displayWidth > displayHeight
        }

    val isHdr: Boolean
        get() = colorTransfer == MediaFormat.COLOR_TRANSFER_HLG ||
            colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            colorStandard == MediaFormat.COLOR_STANDARD_BT2020
}

private fun File.readVideoMetadata(): VideoMetadata? {
    return runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(absolutePath)
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
            val colorTransfer = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
                ?.toIntOrNull()
            val colorStandard = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)
                ?.toIntOrNull()
            VideoMetadata(
                width = width,
                height = height,
                rotation = rotation,
                colorTransfer = colorTransfer,
                colorStandard = colorStandard,
            )
        }
    }.getOrNull()
}

private fun RecordingSettings.coerceToCapabilities(capabilities: CameraCapabilities): RecordingSettings {
    val camera = capabilities.cameraOptions.firstOrNull { it.id == cameraId }
        ?: capabilities.cameraOptions.firstOrNull()
    val resolution = resolution.takeIf { it in capabilities.resolutionOptions }
        ?: capabilities.resolutionOptions.firstOrNull { it == "720p" }
        ?: capabilities.resolutionOptions.firstOrNull { it == "1080p" }
        ?: capabilities.resolutionOptions.firstOrNull()
        ?: "720p"
    val frameRatesForResolution = capabilities.frameRateOptionsForResolution(resolution)
    val frameRate = frameRate.takeIf { it in frameRatesForResolution }
        ?: frameRatesForResolution.firstOrNull { it == 30 }
        ?: frameRatesForResolution.firstOrNull()
        ?: 30
    val codec = codec.takeIf { saved -> capabilities.codecOptions.any { it.id == saved } }
        ?: capabilities.codecOptions.firstOrNull { it.id == "h265" }?.id
        ?: capabilities.codecOptions.firstOrNull { it.id == "h264" }?.id
        ?: capabilities.codecOptions.firstOrNull()?.id
        ?: "h265"
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
        cropZoomRatio = cropZoomRatio.coerceCropZoomRatio(),
    ).coerceToSupportedCombination(capabilities)
}

private fun RecordingSettings.resolveAutoQualityIfNeeded(
    context: Context,
    capabilities: CameraCapabilities,
): RecordingSettings =
    if (autoQualityEnabled) {
        RecordingQualityResolver.resolveAutoQuality(
            context = context,
            requested = this,
            capabilities = capabilities,
        )
    } else {
        this
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

private fun Long.formatDurationNanos(): String =
    (this / 1_000_000L).formatDuration()

private fun Long.formatCoarseBytes(): String {
    val mb = this / (1024L * 1024L)
    return if (mb < 1024L) {
        "$mb MB"
    } else {
        "${mb / 1024L} GB"
    }
}

private fun Long.formatPlaybackTime(): String {
    val totalSeconds = this.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun Float.speedLabel(): String {
    return if (this % 1f == 0f) {
        toInt().toString()
    } else {
        toString().trimEnd('0').trimEnd('.')
    }
}

private fun Float.zoomRatioLabel(): String =
    String.format(java.util.Locale.US, "%.1fx", coerceCropZoomRatio())

private fun Float.zoomRatioKey(): Int =
    (coerceCropZoomRatio() * 10).roundToLong().toInt()

private fun String.stopReasonLabel(): String =
    when (this) {
        "Manual" -> "手动停止"
        "AppSafetyStorage" -> "App 安全空间不足"
        "AppSafetyThermal" -> "设备温度保护"
        "AppSafetyBattery" -> "电量保护"
        "AppSafetyPipeline" -> "录制管线保护"
        "CameraXError" -> "CameraX 录制错误"
        "SourceInactive" -> "相机源中断"
        "PermissionMissing" -> "权限缺失"
        "SystemInterrupted" -> "系统中断"
        "Unknown" -> "未知原因"
        else -> this
    }

private fun FocusMode.label(): String = when (this) {
    FocusMode.Auto -> "自动"
    FocusMode.Farthest -> "最远"
}

private fun StabilizationMode.label(): String = when (this) {
    StabilizationMode.Off -> "关"
    StabilizationMode.Standard -> "标准"
    StabilizationMode.Enhanced -> "增强"
}

private fun BitratePreset.label(): String = when (this) {
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

private const val RECORDING_STORAGE_ESTIMATE_REFRESH_MILLIS = 15_000L
private const val HDR_HEADROOM_RATIO = 2f
