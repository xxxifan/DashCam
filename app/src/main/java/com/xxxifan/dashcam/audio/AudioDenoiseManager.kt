@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.xxxifan.dashcam.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.tencent.mmkv.MMKV
import com.xxxifan.dashcam.data.RecordingEntry
import com.xxxifan.dashcam.data.RecordingEventLogger
import com.xxxifan.dashcam.data.RecordingRepository
import com.xxxifan.dashcam.recording.RecordingStateBus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class AudioDenoiseManager private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val repository = RecordingRepository()
    private val analyzer = AudioNoiseAnalyzer()
    private val eventLogger = RecordingEventLogger.get(appContext)
    private val storage = MMKV.mmkvWithID(STORE_ID)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _tasks = MutableStateFlow(loadTasks())
    val tasks: StateFlow<Map<String, AudioDenoiseTask>> = _tasks.asStateFlow()

    private var appVisible = false
    private var recordingActive = false
    private var playbackActive = false
    private var activeJob: Job? = null
    private var activeRecordingId: String? = null
    private var activeTransformer: Transformer? = null
    private var activeTemporaryFile: File? = null

    init {
        cleanupTemporaryFiles()
        normalizeInterruptedTasks()
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        reevaluateQueue()
    }

    fun setRecordingActive(active: Boolean) {
        recordingActive = active
        reevaluateQueue()
    }

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        reevaluateQueue()
    }

    fun enqueueAutomatically(entry: RecordingEntry) {
        if (!entry.audioEnabled || _tasks.value[entry.id] != null) {
            return
        }
        updateTask(
            AudioDenoiseTask(
                recordingId = entry.id,
                status = AudioDenoiseStatus.Pending,
                processorVersion = CURRENT_AUDIO_DENOISE_VERSION,
                detail = "等待 App 可见、屏幕亮起且录制停止后分析",
            ),
        )
        eventLogger.log(
            event = "audio_denoise_enqueued",
            fields = mapOf("entryId" to entry.id, "source" to "automatic"),
        )
        reevaluateQueue()
    }

    fun enqueueExisting(entries: List<RecordingEntry>) {
        entries
            .asSequence()
            .filter { it.audioEnabled }
            .filter { _tasks.value[it.id] == null }
            .sortedBy { it.startedAtMillis }
            .forEach(::enqueueAutomatically)
    }

    fun requestManually(recordingId: String, forceProcessing: Boolean = false) {
        val current = _tasks.value[recordingId]
        if (
            (current?.status == AudioDenoiseStatus.Completed &&
                current.processorVersion >= CURRENT_AUDIO_DENOISE_VERSION) ||
            current?.status == AudioDenoiseStatus.Analyzing ||
            current?.status == AudioDenoiseStatus.Processing ||
            current?.status == AudioDenoiseStatus.Pending
        ) {
            return
        }
        updateTask(
            AudioDenoiseTask(
                recordingId = recordingId,
                status = AudioDenoiseStatus.Pending,
                manuallyRequested = true,
                forceProcessing = forceProcessing,
                attemptCount = 0,
                processorVersion = CURRENT_AUDIO_DENOISE_VERSION,
                detail = if (forceProcessing) "用户要求忽略自动直通结论并重新处理" else "用户手动加入降噪队列",
            ),
        )
        eventLogger.log(
            event = "audio_denoise_enqueued",
            fields = mapOf(
                "entryId" to recordingId,
                "source" to "manual",
                "forceProcessing" to forceProcessing,
            ),
        )
        reevaluateQueue()
    }

    fun remove(recordingId: String) {
        if (activeRecordingId == recordingId) {
            pauseActiveTask()
        }
        val next = _tasks.value.toMutableMap().apply { remove(recordingId) }
        persistTasks(next)
    }

    private fun reevaluateQueue() {
        if (!canRun()) {
            pauseActiveTask()
            return
        }
        if (activeJob != null) {
            return
        }
        recoverOrphanedRunningTasks()
        val next = _tasks.value.values
            .filter { it.status == AudioDenoiseStatus.Pending || it.status == AudioDenoiseStatus.Paused }
            .sortedWith(
                compareBy<AudioDenoiseTask> { task ->
                    if (task.status == AudioDenoiseStatus.Paused) 0 else 1
                }.thenBy { task ->
                    repository.findById(task.recordingId)?.startedAtMillis ?: task.updatedAtMillis
                },
            )
            .firstOrNull()
            ?: return
        activeRecordingId = next.recordingId
        activeJob = scope.launch {
            try {
                process(next)
            } catch (cancelled: CancellationException) {
                val current = _tasks.value[next.recordingId]
                if (current?.status == AudioDenoiseStatus.Analyzing ||
                    current?.status == AudioDenoiseStatus.Processing
                ) {
                    updateTask(
                        current.copy(
                            status = AudioDenoiseStatus.Paused,
                            progress = null,
                            detail = "App 不可见、屏幕关闭、正在录制或正在播放，已暂停",
                            updatedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                val current = _tasks.value[next.recordingId] ?: next
                val shouldRetry = current.attemptCount < MAX_AUTOMATIC_ATTEMPTS
                updateTask(
                    current.copy(
                        status = if (shouldRetry) AudioDenoiseStatus.Pending else AudioDenoiseStatus.Failed,
                        progress = null,
                        detail = if (shouldRetry) {
                            "处理异常，已移到队尾等待第 ${current.attemptCount + 1} 次尝试：" +
                                (error.message ?: error::class.java.simpleName)
                        } else {
                            error.message ?: error::class.java.simpleName
                        },
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                eventLogger.log(
                    event = if (shouldRetry) "audio_denoise_retry_scheduled" else "audio_denoise_failed",
                    fields = mapOf(
                        "entryId" to next.recordingId,
                        "attemptCount" to current.attemptCount,
                        "error" to (error.message ?: error::class.java.simpleName),
                    ),
                )
            } finally {
                activeTransformer = null
                activeTemporaryFile?.delete()
                activeTemporaryFile = null
                activeRecordingId = null
                activeJob = null
                if (canRun()) {
                    reevaluateQueue()
                }
            }
        }
    }

    private suspend fun process(queuedTask: AudioDenoiseTask) {
        val task = queuedTask.copy(attemptCount = queuedTask.attemptCount + 1)
        val entry = repository.findById(task.recordingId)
            ?: error("视频记录不存在或已被删除")
        check(entry.file.exists()) { "视频文件不存在" }
        check(entry.audioEnabled) { "该视频没有录制音频" }
        updateTask(
            task.copy(
                status = AudioDenoiseStatus.Analyzing,
                progress = null,
                detail = "正在识别安静场景、低频风噪和高频宽带噪声",
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        val analysis = analyzer.analyze(entry.file)
        eventLogger.log(
            event = "audio_denoise_analysis_completed",
            fields = mapOf(
                "entryId" to entry.id,
                "classification" to analysis.classification.name,
                "noiseFloorDbfs" to analysis.noiseFloorDbfs,
                "dynamicRangeDb" to analysis.dynamicRangeDb,
                "lowVsVoiceDb" to analysis.lowVsVoiceDb,
                "highVsVoiceDb" to analysis.highVsVoiceDb,
                "spectralFlatness" to analysis.spectralFlatness,
                "harshNoiseEventCount" to analysis.harshNoiseEvents.size,
                "harshNoiseEvents" to analysis.harshNoiseEvents.map { event ->
                    mapOf(
                        "startSeconds" to event.startSeconds,
                        "endSeconds" to event.endSeconds,
                        "lowFrequencyHz" to event.lowFrequencyHz,
                        "highFrequencyHz" to event.highFrequencyHz,
                        "attenuationDb" to event.attenuationDb,
                        "confidence" to event.confidence,
                    )
                },
                "forceProcessing" to task.forceProcessing,
            ),
        )
        if (!task.forceProcessing) {
            when (analysis.classification) {
                AudioNoiseClassification.Clean -> {
                    updateTask(
                        task.copy(
                            status = AudioDenoiseStatus.SkippedClean,
                            processorVersion = CURRENT_AUDIO_DENOISE_VERSION,
                            detail = analysis.reason,
                            updatedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                    return
                }
                AudioNoiseClassification.Uncertain -> {
                    updateTask(
                        task.copy(
                            status = AudioDenoiseStatus.SkippedUncertain,
                            processorVersion = CURRENT_AUDIO_DENOISE_VERSION,
                            detail = analysis.reason,
                            updatedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                    return
                }
                AudioNoiseClassification.Noise -> Unit
            }
        }
        check(entry.file.parentFile?.usableSpace.orZero() > entry.file.length() + MIN_FREE_SPACE_BYTES) {
            "可用空间不足，无法安全生成降噪临时文件"
        }
        updateTask(
            task.copy(
                status = AudioDenoiseStatus.Processing,
                progress = 0,
                detail = analysis.reason,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        val temporaryFile = createTemporaryFile(entry)
        activeTemporaryFile = temporaryFile
        transform(entry.file, temporaryFile, analysis, entry.id)
        validateOutput(entry.file, temporaryFile)
        replaceAtomically(source = entry.file, replacement = temporaryFile)
        repository.update(entry.copy(sizeBytes = entry.file.length()))
        activeTemporaryFile = null
        updateTask(
            task.copy(
                status = AudioDenoiseStatus.Completed,
                processorVersion = CURRENT_AUDIO_DENOISE_VERSION,
                progress = 100,
                detail = if (analysis.harshNoiseEvents.isEmpty()) {
                    "已完成低频风噪与高频宽带噪声抑制"
                } else {
                    "已完成基础降噪，并抑制 ${analysis.harshNoiseEvents.size} 段重复刺耳噪声"
                },
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        eventLogger.log(
            event = "audio_denoise_completed",
            fields = mapOf(
                "entryId" to entry.id,
                "filePath" to entry.filePath,
                "sizeBytes" to entry.file.length(),
                "lowSuppressionDb" to analysis.lowSuppressionDb,
                "midSuppressionDb" to analysis.midSuppressionDb,
                "highSuppressionDb" to analysis.highSuppressionDb,
            ),
        )
    }

    private suspend fun transform(
        source: File,
        output: File,
        analysis: AudioNoiseAnalysis,
        recordingId: String,
    ) = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
            .setEffects(
                Effects(
                    listOf(DashCamNoiseAudioProcessor(analysis)),
                    emptyList(),
                ),
            )
            .build()
        var progressJob: Job? = null
        val transformer = Transformer.Builder(appContext)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (completed.compareAndSet(false, true)) {
                            progressJob?.cancel()
                            continuation.resume(Unit)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (completed.compareAndSet(false, true)) {
                            progressJob?.cancel()
                            continuation.resumeWithException(exportException)
                        }
                    }
                },
            )
            .build()
        activeTransformer = transformer
        transformer.start(editedMediaItem, output.absolutePath)
        progressJob = scope.launch {
            val holder = ProgressHolder()
            while (isActive && continuation.isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _tasks.value[recordingId]?.let { current ->
                        if (current.status == AudioDenoiseStatus.Processing && current.progress != holder.progress) {
                            updateTask(
                                current.copy(
                                    progress = holder.progress,
                                    updatedAtMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
                delay(PROGRESS_UPDATE_INTERVAL_MILLIS)
            }
        }
        continuation.invokeOnCancellation {
            progressJob.cancel()
            if (completed.compareAndSet(false, true)) {
                transformer.cancel()
            }
            output.delete()
        }
    }

    private suspend fun validateOutput(source: File, output: File) = withContext(Dispatchers.IO) {
        check(output.exists() && output.length() > 0L) { "降噪输出为空" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            var hasAudio = false
            var hasVideo = false
            for (index in 0 until extractor.trackCount) {
                when {
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true -> {
                        hasAudio = true
                    }
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true -> {
                        hasVideo = true
                    }
                }
            }
            check(hasAudio) { "降噪输出缺少音轨" }
            check(hasVideo) { "降噪输出缺少视频轨" }
        } finally {
            extractor.release()
        }
        val sourceDuration = source.readDurationMillis()
        val outputDuration = output.readDurationMillis()
        check(abs(sourceDuration - outputDuration) <= MAX_DURATION_DIFFERENCE_MILLIS) {
            "降噪输出时长异常：原始 ${sourceDuration}ms，输出 ${outputDuration}ms"
        }
    }

    private suspend fun replaceAtomically(source: File, replacement: File) = withContext(Dispatchers.IO) {
        runCatching {
            Files.move(
                replacement.toPath(),
                source.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(
                replacement.toPath(),
                source.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }

    private fun createTemporaryFile(entry: RecordingEntry): File {
        val directory = File(entry.file.parentFile, TEMP_DIRECTORY_NAME).apply { mkdirs() }
        return File(directory, "${entry.file.nameWithoutExtension}-${entry.id}.mp4").apply { delete() }
    }

    private fun canRun(): Boolean {
        val interactive = (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isInteractive == true
        return appVisible &&
            interactive &&
            !recordingActive &&
            !RecordingStateBus.state.value.isRecording &&
            !playbackActive
    }

    private fun pauseActiveTask() {
        activeTransformer?.cancel()
        activeTransformer = null
        activeJob?.cancel()
        activeTemporaryFile?.delete()
    }

    private fun updateTask(task: AudioDenoiseTask) {
        val next = _tasks.value.toMutableMap().apply { put(task.recordingId, task) }
        persistTasks(next)
    }

    private fun recoverOrphanedRunningTasks() {
        val orphaned = _tasks.value.values.filter { task ->
            task.recordingId != activeRecordingId &&
                (task.status == AudioDenoiseStatus.Analyzing || task.status == AudioDenoiseStatus.Processing)
        }
        orphaned.forEach { task ->
            updateTask(
                task.copy(
                    status = AudioDenoiseStatus.Paused,
                    progress = null,
                    detail = "检测到未正常结束的处理状态，已恢复到队列",
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun persistTasks(tasks: Map<String, AudioDenoiseTask>) {
        val oldIds = _tasks.value.keys
        (oldIds - tasks.keys).forEach { storage.removeValueForKey(taskKey(it)) }
        tasks.values.forEach { storage.encode(taskKey(it.recordingId), it.toJson()) }
        storage.encode(KEY_IDS, tasks.keys.joinToString(","))
        _tasks.value = tasks.toMap()
    }

    private fun loadTasks(): Map<String, AudioDenoiseTask> = storage.decodeString(KEY_IDS, "")
        .orEmpty()
        .split(',')
        .filter { it.isNotBlank() }
        .mapNotNull { id ->
            storage.decodeString(taskKey(id))
                ?.let { value -> runCatching { AudioDenoiseTask.fromJson(value) }.getOrNull() }
        }
        .associateBy { it.recordingId }

    private fun normalizeInterruptedTasks() {
        val next = _tasks.value.mapValues { (_, task) ->
            if (task.status == AudioDenoiseStatus.Analyzing || task.status == AudioDenoiseStatus.Processing) {
                task.copy(
                    status = AudioDenoiseStatus.Paused,
                    progress = null,
                    detail = "上次处理被中断，等待恢复",
                    updatedAtMillis = System.currentTimeMillis(),
                )
            } else {
                task
            }
        }
        persistTasks(next)
    }

    private fun cleanupTemporaryFiles() {
        repository.entries.value
            .mapNotNull { it.file.parentFile }
            .distinctBy { it.absolutePath }
            .forEach { parent ->
                File(parent, TEMP_DIRECTORY_NAME).listFiles().orEmpty().forEach(File::delete)
            }
    }

    private fun File.readDurationMillis(): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("无法读取视频时长")
        } finally {
            retriever.release()
        }
    }

    private fun Long?.orZero(): Long = this ?: 0L

    companion object {
        private const val STORE_ID = "audio_denoise_tasks"
        private const val KEY_IDS = "task_ids"
        private const val TEMP_DIRECTORY_NAME = ".denoise"
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024L * 1024L
        private const val MAX_DURATION_DIFFERENCE_MILLIS = 1_500L
        private const val PROGRESS_UPDATE_INTERVAL_MILLIS = 500L
        private const val MAX_AUTOMATIC_ATTEMPTS = 3

        @Volatile
        private var instance: AudioDenoiseManager? = null

        fun get(context: Context): AudioDenoiseManager = instance ?: synchronized(this) {
            instance ?: AudioDenoiseManager(context).also { instance = it }
        }

        private fun taskKey(recordingId: String): String = "task:$recordingId"
    }
}
