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
import android.os.IBinder
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
import com.xxxifan.dashcam.camera.CameraSelectionId
import com.xxxifan.dashcam.camera.toCameraXDynamicRange
import com.xxxifan.dashcam.camera.toVideoMimeType
import com.xxxifan.dashcam.data.RecordingEntry
import com.xxxifan.dashcam.data.RecordingRepository
import com.xxxifan.dashcam.data.RecordingSettings
import com.xxxifan.dashcam.data.RecordingSettingsStore
import com.xxxifan.dashcam.data.StabilizationMode
import com.xxxifan.dashcam.storage.LoopStorageManager
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
    private val recordingRepository by lazy { RecordingRepository() }
    private val storageManager by lazy { LoopStorageManager(this, recordingRepository) }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var segmentJob: Job? = null
    private var currentSegment: SegmentContext? = null
    private var serviceStarted = false
    private var stopRequested = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopRecordingAndSelf()
            ACTION_START, null -> {
                if (!serviceStarted) {
                    serviceStarted = true
                    stopRequested = false
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
        if (!stopRequested) {
            activeRecording?.stop()
        }
        activeRecording = null
        RecordingStateBus.update(RecordingUiState(message = "录制已停止"))
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
            stopWithMessage("缺少相机权限")
            return
        }
        val settings = settingsStore.get()
        if (settings.audioEnabled && !hasAudioPermission()) {
            stopWithMessage("缺少麦克风权限")
            return
        }
        if (!storageManager.ensureSpaceForNextSegment(settings)) {
            stopWithMessage(getString(R.string.storage_insufficient_body))
            return
        }

        val provider = ProcessCameraProvider.getInstance(this).await()
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
        startNewSegment(settings)
    }

    private fun startNewSegment(settings: RecordingSettings) {
        val capture = videoCapture ?: return
        if (!storageManager.ensureSpaceForNextSegment(settings)) {
            stopWithMessage(getString(R.string.storage_cleanup_failed_body))
            return
        }

        val context = createSegmentContext(settings)
        currentSegment = context

        val outputOptions = FileOutputOptions.Builder(context.file).build()
        val pending = capture.output.prepareRecording(this, outputOptions)
        activeRecording = if (settings.audioEnabled && hasAudioPermission()) {
            pending.withAudioEnabled()
        } else {
            pending
        }.start(ContextCompat.getMainExecutor(this)) { event ->
            handleRecordEvent(event)
        }

        RecordingStateBus.update(
            RecordingUiState(
                isRecording = true,
                message = "正在录制 ${context.file.name}",
                startedAtMillis = context.startedAtMillis,
                currentSegmentPath = context.file.absolutePath,
            ),
        )
        updateNotification("正在录制，锁屏后将尽量继续")
        scheduleSegmentRotation(settings)
    }

    private fun scheduleSegmentRotation(settings: RecordingSettings) {
        segmentJob?.cancel()
        segmentJob = lifecycleScope.launch {
            delay(settings.segmentMinutes * 60_000L)
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
        RecordingStateBus.update(
            RecordingUiState(
                isRecording = true,
                message = "正在录制 ${segment.file.name}",
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
            recordingRepository.add(
                RecordingEntry(
                    id = segment.id,
                    filePath = segment.file.absolutePath,
                    startedAtMillis = segment.startedAtMillis,
                    endedAtMillis = endedAt,
                    sizeBytes = segment.file.length(),
                    resolution = segment.settings.resolution,
                    frameRate = segment.settings.frameRate,
                    codec = segment.settings.codec,
                    dynamicRange = segment.settings.dynamicRange,
                    audioEnabled = segment.settings.audioEnabled,
                    stabilizationMode = segment.settings.stabilizationMode,
                    cameraId = segment.settings.cameraId,
                    cameraLabel = segment.settings.cameraLabel,
                ),
            )
            if (serviceStarted && !stopRequested) {
                startNewSegment(settingsStore.get())
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            Log.w(TAG, "Recording finalized with error ${event.error}: ${event.cause?.message}")
            if (segment.file.length() == 0L) {
                segment.file.delete()
            }
            stopWithMessage("录制失败：${event.error}")
        }
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
        segmentJob?.cancel()
        val recording = activeRecording
        if (recording != null) {
            recording.stop()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopWithMessage(message: String) {
        serviceStarted = false
        stopRequested = true
        RecordingStateBus.update(RecordingUiState(message = message))
        updateNotification(message)
        segmentJob?.cancel()
        val recording = activeRecording
        if (recording != null) {
            recording.stop()
        } else {
            stopSelf()
        }
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
    }

    private fun applyRecorderOptions(
        builder: Recorder.Builder,
        settings: RecordingSettings,
    ) {
        val mimeType = settings.codec.toVideoMimeType() ?: return
        builder.setVideoMimeType(mimeType)
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

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "dashcam_recording"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.xxxifan.dashcam.action.START"
        private const val ACTION_STOP = "com.xxxifan.dashcam.action.STOP"

        private val fileTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        fun startIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
