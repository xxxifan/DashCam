package com.xxxifan.dashcam.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xxxifan.dashcam.MainActivity
import com.xxxifan.dashcam.R
import com.xxxifan.dashcam.camera.CameraCapabilities
import com.xxxifan.dashcam.camera.CameraCapabilitiesRepository
import com.xxxifan.dashcam.camera.CameraSelectionId
import com.xxxifan.dashcam.camera.codecLabel
import com.xxxifan.dashcam.camera.coerceToSupportedCombination
import com.xxxifan.dashcam.camera.isHdrDynamicRange
import com.xxxifan.dashcam.camera.toCameraXDynamicRange
import com.xxxifan.dashcam.camera.toVideoMimeType
import com.xxxifan.dashcam.data.BitratePreset
import com.xxxifan.dashcam.data.RecordingAlertStore
import com.xxxifan.dashcam.data.RecordingEntry
import com.xxxifan.dashcam.data.RecordingEventLogger
import com.xxxifan.dashcam.data.RecordingRepository
import com.xxxifan.dashcam.data.RecordingSettings
import com.xxxifan.dashcam.data.RecordingSettingsStore
import com.xxxifan.dashcam.data.RecordingThumbnailManager
import com.xxxifan.dashcam.data.StabilizationMode
import com.xxxifan.dashcam.data.coerceCropZoomRatio
import com.xxxifan.dashcam.safety.DefaultRecordingSafetyPolicy
import com.xxxifan.dashcam.safety.RecordingHealthSnapshot
import com.xxxifan.dashcam.safety.RecordingSafetyDecision
import com.xxxifan.dashcam.safety.SafetyAction
import com.xxxifan.dashcam.safety.SafetyLevel
import com.xxxifan.dashcam.safety.SafetyReason
import com.xxxifan.dashcam.storage.LoopStorageManager
import com.xxxifan.dashcam.storage.RecordingStorageEstimator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class RecordingService : LifecycleService() {
    private val settingsStore by lazy { RecordingSettingsStore() }
    private val alertStore by lazy { RecordingAlertStore() }
    private val recordingRepository by lazy { RecordingRepository() }
    private val storageManager by lazy { LoopStorageManager(this, recordingRepository) }
    private val thumbnailManager by lazy { RecordingThumbnailManager(this, recordingRepository) }
    private val eventLogger by lazy { RecordingEventLogger.get(this) }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var segmentJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var currentSegment: SegmentContext? = null
    private var requestedSessionSettings: RecordingSettings? = null
    private var activeSessionSettings: RecordingSettings? = null
    private var sessionCapabilities: CameraCapabilities? = null
    private var downgradeState: RecordingDowngradeState? = null
    private var latestSafetyDecision: RecordingSafetyDecision? = null
    private var pipelineFailureCount = 0
    private var cameraInterruptionCount = 0
    private var serviceStarted = false
    private var stopRequested = false
    private var pendingStopAlert: StopAlert? = null
    private var lastStatsUiUpdateMillis = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopRecordingAndSelf()
            ACTION_START, null -> {
                if (!serviceStarted) {
                    serviceStarted = true
                    stopRequested = false
                    pendingStopAlert = null
                    lastStatsUiUpdateMillis = 0L
                    alertStore.clearLastStopAlert()
                    eventLogger.log("session_start_requested")
                    startForegroundNotification("正在准备录制")
                    lifecycleScope.launch {
                        startCameraAndRecording()
                    }
                }
            }
        }
        return Service.START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        segmentJob?.cancel()
        healthMonitorJob?.cancel()
        if (!stopRequested) {
            val message = "录制服务被系统中断，录制已停止。"
            pendingStopAlert = StopAlert(
                message = message,
                fallbackGuidance = null,
                reason = RecordingStopReason.SystemInterrupted,
            )
            alertStore.saveStopAlert(
                message = message,
                fallbackGuidance = null,
                reason = RecordingStopReason.SystemInterrupted.name,
            )
            eventLogger.log(
                event = "service_destroyed_unexpectedly",
                fields = mapOf(
                    "reason" to RecordingStopReason.SystemInterrupted.name,
                    "currentSegmentPath" to currentSegment?.file?.absolutePath,
                ),
            )
            activeRecording?.stop()
        }
        activeRecording = null
        requestedSessionSettings = null
        activeSessionSettings = null
        sessionCapabilities = null
        downgradeState = null
        latestSafetyDecision = null
        val stopAlert = pendingStopAlert
        RecordingStateBus.update(
            if (stopAlert != null) {
                RecordingUiState(
                    message = stopAlert.message,
                    fallbackGuidance = stopAlert.fallbackGuidance,
                    stopReason = stopAlert.reason,
                )
            } else {
                RecordingUiState(message = "录制已停止")
            },
        )
        super.onDestroy()
    }

    private fun startForegroundNotification(message: String) {
        ensureNotificationChannel()
        val notification = buildNotification(message)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private suspend fun startCameraAndRecording() {
        if (!hasCameraPermission()) {
            stopWithMessage(
                message = "缺少相机权限",
                reason = RecordingStopReason.PermissionMissing,
            )
            return
        }
        val requestedSettings = settingsStore.get()
        eventLogger.logRecordingSettings("session_settings_requested", requestedSettings)
        if (requestedSettings.audioEnabled && !hasAudioPermission()) {
            stopWithMessage(
                message = "缺少麦克风权限",
                reason = RecordingStopReason.PermissionMissing,
            )
            return
        }
        val provider = ProcessCameraProvider.getInstance(this).await()
        val capabilities = CameraCapabilitiesRepository(this)
            .capabilities(provider.cameraInfoFor(requestedSettings))
        sessionCapabilities = capabilities
        val supportedRequestedSettings = requestedSettings.coerceToCapabilities(capabilities)
        val resolvedSettings = RecordingQualityResolver.resolveAutoQuality(
            context = this,
            requested = supportedRequestedSettings,
            capabilities = capabilities,
        )
        val startupSettings = resolveStartupSettings(
            requested = resolvedSettings,
            capabilities = capabilities,
            autoResolved = requestedSettings.autoQualityEnabled,
        )
        if (startupSettings == null) {
            stopWithMessage(
                message = getString(R.string.storage_insufficient_body),
                fallbackGuidance = storageGuidance(startupStorage = true),
                reason = RecordingStopReason.AppSafetyStorage,
            )
            return
        }
        val settings = startupSettings.settings
        requestedSessionSettings = supportedRequestedSettings
        activeSessionSettings = settings
        downgradeState = startupSettings.downgradeState
        eventLogger.logRecordingSettings(
            event = "session_settings_active",
            settings = settings,
            fields = mapOf(
                "startupMessage" to startupSettings.message,
                "downgradeReasons" to startupSettings.downgradeState?.reasons?.map { it.name },
            ),
        )
        val recorderBuilder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    qualityFor(settings.resolution),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
        applyRecorderOptions(recorderBuilder, settings)
        val recorder = recorderBuilder.build()
        recorder.setVideoEncodingFrameRate(settings.frameRate)
        val captureBuilder = VideoCapture.Builder(recorder)
            .setTargetFrameRate(Range(settings.frameRate, settings.frameRate))
            .setDynamicRange(settings.dynamicRange.toCameraXDynamicRange())
            .setVideoStabilizationEnabled(settings.stabilizationMode != StabilizationMode.Off)
        applyCamera2Options(captureBuilder, settings)
        val capture = captureBuilder.build()

        provider.unbindAll()
        val selector = cameraSelectorFor(settings)
        provider.bindToLifecycle(
            this,
            selector,
            capture,
        )
        videoCapture = capture
        startNewSegment(settings, startupSettings.message)
        startHealthMonitoring()
    }

    private fun resolveStartupSettings(
        requested: RecordingSettings,
        capabilities: CameraCapabilities,
        autoResolved: Boolean,
    ): StartupSettings? {
        val autoMessage = if (autoResolved) {
            "自动画质已选择 ${requested.resolution}${requested.frameRate}fps ${requested.codec.codecLabel()} ${requested.bitratePreset.label()}。"
        } else {
            null
        }
        if (storageManager.ensureSpaceForNextSegment(requested)) {
            return StartupSettings(requested, autoMessage)
        }
        if (!requested.autoDowngradeEnabled) {
            return null
        }
        val fallback = RecordingStartupFallbackPolicy
            .fallbackCandidates(requested, capabilities)
            .firstOrNull { storageManager.ensureSpaceForNextSegment(it) }
            ?: return null
        val downgradeState = RecordingDowngradeState(
            reasons = setOf(RecordingDowngradeReason.StartupStorage),
            requestedSettings = requested,
            activeSettings = fallback,
            message = storageDowngradeMessage(fallback),
        )
        return StartupSettings(
            settings = fallback,
            message = downgradeState.message,
            downgradeState = downgradeState,
        )
    }

    private fun startNewSegment(
        settings: RecordingSettings,
        startupMessage: String? = null,
    ) {
        val capture = videoCapture ?: return
        if (!storageManager.ensureSpaceForNextSegment(settings)) {
            eventLogger.logRecordingSettings(
                event = "segment_space_check_failed",
                settings = settings,
            )
            stopWithMessage(
                message = getString(R.string.storage_cleanup_failed_body),
                fallbackGuidance = storageGuidance(startupStorage = false),
                reason = RecordingStopReason.AppSafetyStorage,
            )
            return
        }

        val context = createSegmentContext(settings)
        currentSegment = context
        lastStatsUiUpdateMillis = 0L
        eventLogger.logRecordingSettings(
            event = "segment_start",
            settings = settings,
            fields = mapOf(
                "segmentId" to context.id,
                "filePath" to context.file.absolutePath,
                "startedAtMillis" to context.startedAtMillis,
            ),
        )

        val outputOptions = FileOutputOptions.Builder(context.file).build()
        val pending = capture.output.prepareRecording(this, outputOptions)
        activeRecording = if (settings.audioEnabled && hasAudioPermission()) {
            pending.withAudioEnabled()
        } else {
            pending
        }.start(ContextCompat.getMainExecutor(this)) { event ->
            handleRecordEvent(event)
        }

        val recordingMessage = startupMessage ?: "正在录制 ${context.file.name}"
        RecordingStateBus.update(
            RecordingUiState(
                isRecording = true,
                message = recordingMessage,
                activeSettings = settings,
                downgradeState = downgradeState,
                safetyDecision = latestSafetyDecision,
                fallbackGuidance = null,
                startedAtMillis = context.startedAtMillis,
                currentSegmentPath = context.file.absolutePath,
            ),
        )
        updateNotification(startupMessage ?: "正在录制，锁屏后将尽量继续")
        scheduleSegmentRotation(settings)
    }

    private fun scheduleSegmentRotation(settings: RecordingSettings) {
        segmentJob?.cancel()
        segmentJob = lifecycleScope.launch {
            delay(settings.segmentMinutes * 60_000L)
            eventLogger.logRecordingSettings(
                event = "segment_rotation_requested",
                settings = settings,
                fields = mapOf(
                    "currentSegmentPath" to currentSegment?.file?.absolutePath,
                ),
            )
            activeRecording?.stop()
        }
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> updateNotification("正在录制，锁屏后将尽量继续")
            is VideoRecordEvent.Status -> updateRecordingStats(event)
            is VideoRecordEvent.Finalize -> finalizeSegment(event)
            else -> Unit
        }
    }

    private fun updateRecordingStats(event: VideoRecordEvent) {
        val segment = currentSegment ?: return
        val now = System.currentTimeMillis()
        if (now - lastStatsUiUpdateMillis < STATS_UI_UPDATE_INTERVAL_MILLIS) {
            return
        }
        lastStatsUiUpdateMillis = now
        RecordingStateBus.update(
            RecordingUiState(
                isRecording = true,
                message = "正在录制 ${segment.file.name}",
                activeSettings = segment.settings,
                downgradeState = downgradeState,
                safetyDecision = latestSafetyDecision,
                fallbackGuidance = null,
                startedAtMillis = segment.startedAtMillis,
                currentSegmentPath = segment.file.absolutePath,
                recordedBytes = event.recordingStats.numBytesRecorded,
                recordedDurationNanos = event.recordingStats.recordedDurationNanos,
            ),
        )
    }

    private fun finalizeSegment(event: VideoRecordEvent.Finalize) {
        val segment = currentSegment ?: return
        segmentJob?.cancel()
        val endedAt = System.currentTimeMillis()
        activeRecording = null

        if (!event.hasError() && segment.file.exists() && segment.file.length() > 0L) {
            cameraInterruptionCount = 0
            pipelineFailureCount = 0
            val entry = RecordingEntry(
                id = segment.id,
                filePath = segment.file.absolutePath,
                startedAtMillis = segment.startedAtMillis,
                endedAtMillis = endedAt,
                sizeBytes = segment.file.length(),
                resolution = segment.settings.resolution,
                frameRate = segment.settings.frameRate,
                codec = segment.settings.codec,
                bitratePreset = segment.settings.bitratePreset,
                dynamicRange = segment.settings.dynamicRange,
                audioEnabled = segment.settings.audioEnabled,
                audioProcessingMode = segment.settings.audioProcessingMode,
                stabilizationMode = segment.settings.stabilizationMode,
                cameraId = segment.settings.cameraId,
                cameraLabel = segment.settings.cameraLabel,
                cropZoomRatio = segment.settings.cropZoomRatio,
            )
            recordingRepository.add(entry)
            eventLogger.logRecordingSettings(
                event = "segment_finalize_success",
                settings = segment.settings,
                fields = mapOf(
                    "segmentId" to segment.id,
                    "filePath" to segment.file.absolutePath,
                    "sizeBytes" to entry.sizeBytes,
                    "startedAtMillis" to entry.startedAtMillis,
                    "endedAtMillis" to entry.endedAtMillis,
                    "durationMillis" to entry.durationMillis,
                    "stopRequested" to stopRequested,
                ),
            )
            lifecycleScope.launch {
                thumbnailManager.ensureThumbnail(entry)
            }
            if (serviceStarted && !stopRequested) {
                startNewSegment(activeSessionSettings ?: settingsStore.get())
            } else {
                val stopReason = pendingStopAlert?.reason ?: RecordingStopReason.Manual
                eventLogger.log(
                    event = "session_stop_completed",
                    fields = mapOf(
                        "reason" to stopReason.name,
                        "lastSegmentId" to segment.id,
                    ),
                )
                healthMonitorJob?.cancel()
                requestedSessionSettings = null
                activeSessionSettings = null
                downgradeState = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            pipelineFailureCount += 1
            Log.w(TAG, "Recording finalized with error ${event.error}: ${event.cause?.message}")
            eventLogger.logRecordingSettings(
                event = "segment_finalize_error",
                settings = segment.settings,
                fields = mapOf(
                    "segmentId" to segment.id,
                    "filePath" to segment.file.absolutePath,
                    "errorCode" to event.error,
                    "errorName" to event.recordingErrorName(),
                    "cause" to event.cause?.message,
                    "fileSizeBytes" to segment.file.length(),
                    "pipelineFailureCount" to pipelineFailureCount,
                ),
            )
            if (segment.file.length() == 0L) {
                segment.file.delete()
            }
            stopWithMessage(
                message = recordingErrorMessage(event),
                fallbackGuidance = fallbackGuidanceFor(event),
                reason = stopReasonFor(event),
            )
        }
    }

    private fun recordingErrorMessage(event: VideoRecordEvent.Finalize): String {
        return when (event.error) {
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> {
                cameraInterruptionCount += 1
                if (cameraInterruptionCount >= CAMERA_INTERRUPTION_WARNING_THRESHOLD) {
                    getString(R.string.recording_lock_screen_unstable_body)
                } else {
                    "录制被系统相机中断，请重新开始录制。"
                }
            }
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> getString(R.string.storage_cleanup_failed_body)
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR,
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
            -> getString(R.string.recording_pipeline_unstable_body)
            else -> "录制失败：${event.error}"
        }
    }

    private fun fallbackGuidanceFor(event: VideoRecordEvent.Finalize): String? {
        return when (event.error) {
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> getString(R.string.recording_lock_screen_fallback_guidance)
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR,
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
            -> getString(R.string.recording_pipeline_fallback_guidance)
            else -> null
        }
    }

    private fun stopReasonFor(event: VideoRecordEvent.Finalize): RecordingStopReason {
        return when (event.error) {
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> RecordingStopReason.SourceInactive
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> RecordingStopReason.AppSafetyStorage
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR,
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
            -> RecordingStopReason.CameraXError
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> RecordingStopReason.AppSafetyStorage
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> RecordingStopReason.Unknown
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> RecordingStopReason.SystemInterrupted
            else -> RecordingStopReason.SystemInterrupted
        }
    }

    private fun VideoRecordEvent.Finalize.recordingErrorName(): String =
        when (error) {
            VideoRecordEvent.Finalize.ERROR_NONE -> "ERROR_NONE"
            VideoRecordEvent.Finalize.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "ERROR_FILE_SIZE_LIMIT_REACHED"
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "ERROR_INSUFFICIENT_STORAGE"
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "ERROR_SOURCE_INACTIVE"
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "ERROR_INVALID_OUTPUT_OPTIONS"
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "ERROR_ENCODING_FAILED"
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "ERROR_RECORDER_ERROR"
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "ERROR_NO_VALID_DATA"
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "ERROR_DURATION_LIMIT_REACHED"
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "ERROR_RECORDING_GARBAGE_COLLECTED"
            else -> "ERROR_$error"
        }

    private fun createSegmentContext(settings: RecordingSettings): SegmentContext {
        val timestamp = fileTimestampFormatter.format(LocalDateTime.now())
        val file = File(
            storageManager.recordingDirectory,
            "dashcam_${timestamp}_${settings.resolution}${settings.frameRate}_${settings.codec}.mp4",
        )
        return SegmentContext(
            id = UUID.randomUUID().toString(),
            file = file,
            startedAtMillis = System.currentTimeMillis(),
            settings = settings,
        )
    }

    private fun stopRecordingAndSelf() {
        serviceStarted = false
        stopRequested = true
        healthMonitorJob?.cancel()
        requestedSessionSettings = null
        activeSessionSettings = null
        sessionCapabilities = null
        downgradeState = null
        latestSafetyDecision = null
        pendingStopAlert = null
        alertStore.clearLastStopAlert()
        eventLogger.log(
            event = "session_stop_requested",
            fields = mapOf(
                "reason" to RecordingStopReason.Manual.name,
                "currentSegmentPath" to currentSegment?.file?.absolutePath,
            ),
        )
        segmentJob?.cancel()
        val recording = activeRecording
        if (recording != null) {
            recording.stop()
        } else {
            eventLogger.log(
                event = "session_stop_completed",
                fields = mapOf("reason" to RecordingStopReason.Manual.name),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopWithMessage(
        message: String,
        fallbackGuidance: String? = null,
        reason: RecordingStopReason = RecordingStopReason.Unknown,
    ) {
        serviceStarted = false
        stopRequested = true
        healthMonitorJob?.cancel()
        requestedSessionSettings = null
        activeSessionSettings = null
        sessionCapabilities = null
        downgradeState = null
        latestSafetyDecision = null
        pendingStopAlert = StopAlert(message, fallbackGuidance, reason)
        alertStore.saveStopAlert(message, fallbackGuidance, reason.name)
        Log.w(TAG, "Stopping recording with message: $message")
        eventLogger.log(
            event = "session_stop_requested",
            fields = mapOf(
                "reason" to reason.name,
                "message" to message,
                "fallbackGuidance" to fallbackGuidance,
                "currentSegmentPath" to currentSegment?.file?.absolutePath,
            ),
        )
        RecordingStateBus.update(
            RecordingUiState(
                message = message,
                fallbackGuidance = fallbackGuidance,
                stopReason = reason,
            ),
        )
        updateNotification(message)
        segmentJob?.cancel()
        val recording = activeRecording
        if (recording != null) {
            recording.stop()
        } else {
            eventLogger.log(
                event = "session_stop_completed",
                fields = mapOf("reason" to reason.name),
            )
            stopSelf()
        }
    }

    private fun startHealthMonitoring() {
        healthMonitorJob?.cancel()
        healthMonitorJob = lifecycleScope.launch {
            while (serviceStarted && !stopRequested) {
                delay(HEALTH_CHECK_INTERVAL_MILLIS)
                evaluateResourcePressure()
            }
        }
    }

    private fun evaluateResourcePressure() {
        val requested = requestedSessionSettings ?: return
        val active = activeSessionSettings ?: return
        val snapshot = buildHealthSnapshot(active)
        val decision = DefaultRecordingSafetyPolicy(requested.autoDowngradeEnabled).evaluate(snapshot)
        publishSafetyDecision(decision)
        if (decision.actions.contains(SafetyAction.StopRecording)) {
            val isStorageStop = decision.reasons.contains(SafetyReason.Storage)
            stopWithMessage(
                message = if (isStorageStop) {
                    getString(R.string.storage_cleanup_failed_body)
                } else {
                    decision.message
                },
                fallbackGuidance = if (isStorageStop) {
                    storageGuidance(startupStorage = false)
                } else {
                    getString(R.string.recording_emergency_stop_body)
                },
                reason = decision.stopReason(),
            )
            return
        }
        if (!decision.actions.contains(SafetyAction.DowngradeQuality)) {
            return
        }
        val reasons = decision.reasons.toDowngradeReasons()
        if (reasons.isEmpty()) {
            return
        }

        val capabilities = sessionCapabilities ?: CameraCapabilitiesRepository(this).capabilities()
        val fallback = RecordingStartupFallbackPolicy
            .fallbackCandidates(active, capabilities)
            .firstOrNull { storageManager.ensureSpaceForNextSegment(it) }
            ?: active.copy(
                bitratePreset = BitratePreset.SpaceSaver,
                dynamicRange = "sdr",
                stabilizationMode = StabilizationMode.Off,
                frameRate = minOf(active.frameRate, 30),
                resolution = if (active.resolution == "4K") "1080p" else "720p",
            )
        if (fallback == active && downgradeState?.reasons?.containsAll(reasons) == true) {
            return
        }

        val nextState = RecordingDowngradeState(
            reasons = (downgradeState?.reasons.orEmpty() + reasons),
            requestedSettings = requested,
            activeSettings = fallback,
            message = resourceDowngradeMessage(reasons, fallback),
        )
        activeSessionSettings = fallback
        downgradeState = nextState
        eventLogger.logRecordingSettings(
            event = "quality_downgrade",
            settings = fallback,
            fields = mapOf(
                "reasons" to reasons.map { it.name },
                "message" to nextState.message,
                "requestedResolution" to requested.resolution,
                "requestedFrameRate" to requested.frameRate,
                "requestedBitratePreset" to requested.bitratePreset.name,
                "requestedAutoQualityEnabled" to requested.autoQualityEnabled,
                "requestedDynamicRange" to requested.dynamicRange,
                "requestedStabilizationMode" to requested.stabilizationMode.name,
            ),
        )
        RecordingStateBus.update(
            RecordingUiState(
                isRecording = true,
                message = nextState.message,
                activeSettings = fallback,
                downgradeState = nextState,
                safetyDecision = decision,
                startedAtMillis = currentSegment?.startedAtMillis,
                currentSegmentPath = currentSegment?.file?.absolutePath,
            ),
        )
        updateNotification(nextState.message)
    }

    private fun buildHealthSnapshot(activeSettings: RecordingSettings): RecordingHealthSnapshot =
        RecordingHealthSnapshot(
            thermalLevel = thermalLevel(),
            storageLevel = storageLevel(activeSettings),
            batteryLevel = batteryLevel(),
            pipelineLevel = pipelineLevel(),
        )

    private fun RecordingSafetyDecision.stopReason(): RecordingStopReason =
        when {
            reasons.contains(SafetyReason.Storage) -> RecordingStopReason.AppSafetyStorage
            reasons.contains(SafetyReason.Thermal) -> RecordingStopReason.AppSafetyThermal
            reasons.contains(SafetyReason.Battery) -> RecordingStopReason.AppSafetyBattery
            reasons.contains(SafetyReason.RecordingPipeline) -> RecordingStopReason.AppSafetyPipeline
            else -> RecordingStopReason.Unknown
        }

    private fun publishSafetyDecision(decision: RecordingSafetyDecision) {
        if (decision.level == SafetyLevel.Normal) {
            latestSafetyDecision = null
            return
        }
        latestSafetyDecision = decision
        Log.w(TAG, "Safety decision: $decision")
        eventLogger.log(
            event = "safety_decision",
            fields = mapOf(
                "level" to decision.level.name,
                "actions" to decision.actions.map { it.name },
                "reasons" to decision.reasons.map { it.name },
                "message" to decision.message,
                "shouldNotifyUser" to decision.shouldNotifyUser,
            ),
        )
        if (decision.shouldNotifyUser) {
            updateNotification(decision.message)
        }
    }

    private fun Set<SafetyReason>.toDowngradeReasons(): Set<RecordingDowngradeReason> =
        mapNotNull { reason ->
            when (reason) {
                SafetyReason.Thermal -> RecordingDowngradeReason.Thermal
                SafetyReason.Storage -> RecordingDowngradeReason.StartupStorage
                SafetyReason.Battery -> RecordingDowngradeReason.Battery
                SafetyReason.RecordingPipeline -> null
            }
        }.toSet()

    private fun thermalLevel(): SafetyLevel {
        val powerManager = getSystemService<PowerManager>() ?: return SafetyLevel.Normal
        return when (powerManager.currentThermalStatus) {
            in PowerManager.THERMAL_STATUS_CRITICAL..Int.MAX_VALUE -> SafetyLevel.Emergency
            PowerManager.THERMAL_STATUS_SEVERE -> SafetyLevel.Pressure
            PowerManager.THERMAL_STATUS_MODERATE -> SafetyLevel.Pressure
            PowerManager.THERMAL_STATUS_LIGHT -> SafetyLevel.Notice
            else -> SafetyLevel.Normal
        }
    }

    private fun storageLevel(settings: RecordingSettings): SafetyLevel =
        if (storageManager.ensureSpaceForNextSegment(settings)) {
            SafetyLevel.Normal
        } else {
            SafetyLevel.Emergency
        }

    private fun batteryLevel(): SafetyLevel {
        val batteryManager = getSystemService<BatteryManager>() ?: return SafetyLevel.Normal
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryPercent < 0 || batteryManager.isCharging()) {
            return SafetyLevel.Normal
        }
        return when {
            batteryPercent < BATTERY_EMERGENCY_PERCENT -> SafetyLevel.Emergency
            batteryPercent < BATTERY_DOWNGRADE_PERCENT -> SafetyLevel.Pressure
            batteryPercent < BATTERY_NOTICE_PERCENT -> SafetyLevel.Notice
            else -> SafetyLevel.Normal
        }
    }

    private fun pipelineLevel(): SafetyLevel = when {
        pipelineFailureCount >= PIPELINE_EMERGENCY_FAILURES -> SafetyLevel.Emergency
        pipelineFailureCount >= PIPELINE_PRESSURE_FAILURES -> SafetyLevel.Pressure
        else -> SafetyLevel.Normal
    }

    private fun storageDowngradeMessage(settings: RecordingSettings): String =
        "${getString(R.string.recording_auto_downgrade_body)} 存储空间不足，已临时切到 ${settings.resolution}${settings.frameRate}fps ${settings.bitratePreset.label()}。"

    private fun storageGuidance(startupStorage: Boolean): String {
        val prefix = if (startupStorage) {
            "系统仍可能显示有剩余空间，但 DashCam 会先保留安全空间再开始录制。"
        } else {
            "系统仍可能显示有剩余空间，但 DashCam 已达到录制安全阈值。"
        }
        return "$prefix 请释放空间、降低画质，或调大循环录制空间后重试。"
    }

    private fun resourceDowngradeMessage(
        reasons: Set<RecordingDowngradeReason>,
        settings: RecordingSettings,
    ): String {
        val reasonText = reasons.sortedBy { it.ordinal }.joinToString("、") { it.label() }
        return "$reasonText 触发保护，下一段起临时切到 ${settings.resolution}${settings.frameRate}fps ${settings.bitratePreset.label()}。"
    }

    private fun cameraSelectorFor(settings: RecordingSettings): CameraSelector {
        val logicalCameraId = CameraSelectionId.logicalCameraId(settings.cameraId)
        if (logicalCameraId.isBlank()) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { info -> cameraIdOf(info) == logicalCameraId }.ifEmpty { infos }
            }
            .build()
    }

    private fun applyCamera2Options(
        builder: VideoCapture.Builder<Recorder>,
        settings: RecordingSettings,
    ) {
        val extender = Camera2Interop.Extender(builder)
        val physicalCameraId = CameraSelectionId.physicalCameraId(settings.cameraId)
        if (!physicalCameraId.isNullOrBlank()) {
            extender.setPhysicalCameraId(physicalCameraId)
        }
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(settings.frameRate, settings.frameRate),
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            settings.stabilizationMode.toCaptureRequestValue(),
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_ZOOM_RATIO,
            settings.cropZoomRatio.coerceCropZoomRatio(),
        )
    }

    private fun applyRecorderOptions(
        builder: Recorder.Builder,
        settings: RecordingSettings,
    ) {
        // HDR lets CameraX pick the matching Main10 profile; forcing the MIME can hit buggy 8-bit paths.
        if (!settings.dynamicRange.isHdrDynamicRange()) {
            settings.codec.toVideoMimeType()?.let { builder.setVideoMimeType(it) }
        }
        builder.setTargetVideoEncodingBitRate(RecordingStorageEstimator.targetVideoBitrate(settings))
        RecordingStorageEstimator.audioBitrate(settings)
            .takeIf { it > 0 }
            ?.let { builder.setTargetAudioEncodingBitRate(it) }
    }

    private fun qualityFor(resolution: String): Quality = when (resolution) {
        "720p" -> Quality.HD
        "4K" -> Quality.UHD
        else -> Quality.FHD
    }

    private fun StabilizationMode.toCaptureRequestValue(): Int = when (this) {
        StabilizationMode.Off -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        StabilizationMode.Standard -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        StabilizationMode.Enhanced -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
    }

    private fun cameraIdOf(info: CameraInfo): String =
        Camera2CameraInfo.from(info).cameraId

    private fun ProcessCameraProvider.cameraInfoFor(settings: RecordingSettings): CameraInfo? =
        runCatching {
            getCameraInfo(cameraSelectorFor(settings))
        }.getOrNull() ?: runCatching {
            getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
        }.getOrNull()

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

    private fun BitratePreset.label(): String = when (this) {
        BitratePreset.SpaceSaver -> "节省空间"
        BitratePreset.Standard -> "标准"
        BitratePreset.HighQuality -> "高画质"
    }

    private fun RecordingDowngradeReason.label(): String = when (this) {
        RecordingDowngradeReason.StartupStorage -> "存储空间不足"
        RecordingDowngradeReason.Thermal -> "设备发热"
        RecordingDowngradeReason.Battery -> "电量偏低"
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("DashCam 正在录制")
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "DashCam 录制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "DashCam 前台录制状态"
            },
        )
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private data class SegmentContext(
        val id: String,
        val file: File,
        val startedAtMillis: Long,
        val settings: RecordingSettings,
    )

    private data class StartupSettings(
        val settings: RecordingSettings,
        val message: String? = null,
        val downgradeState: RecordingDowngradeState? = null,
    )

    private data class StopAlert(
        val message: String,
        val fallbackGuidance: String?,
        val reason: RecordingStopReason,
    )

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "dashcam_recording"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.xxxifan.dashcam.action.START"
        private const val ACTION_STOP = "com.xxxifan.dashcam.action.STOP"
        private const val CAMERA_INTERRUPTION_WARNING_THRESHOLD = 2
        private const val HEALTH_CHECK_INTERVAL_MILLIS = 30_000L
        private const val BATTERY_NOTICE_PERCENT = 20
        private const val BATTERY_DOWNGRADE_PERCENT = 10
        private const val BATTERY_EMERGENCY_PERCENT = 5
        private const val PIPELINE_PRESSURE_FAILURES = 1
        private const val PIPELINE_EMERGENCY_FAILURES = 3
        private const val STATS_UI_UPDATE_INTERVAL_MILLIS = 2_000L

        private val fileTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        fun startIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
