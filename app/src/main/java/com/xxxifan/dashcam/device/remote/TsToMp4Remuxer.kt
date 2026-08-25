package com.xxxifan.dashcam.device.remote

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

internal class TsToMp4Remuxer {
    fun remux(
        source: File,
        output: File,
        expectedDurationMillis: Long?,
        onProgress: (Float?) -> Unit,
    ): RemuxedMediaInfo {
        require(source.isFile && source.length() > 0L) { "TS 源文件不存在或为空" }
        output.parentFile?.mkdirs()
        output.delete()

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            val selectedTracks = selectTracks(extractor)
            val videoTrack = selectedTracks.firstOrNull { it.mimeType.startsWith("video/") }
                ?: throw IOException("TS 中没有可用的视频轨")

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackBySourceTrack = selectedTracks.associate { track ->
                extractor.selectTrack(track.sourceIndex)
                track.sourceIndex to muxer.addTrack(track.format)
            }
            muxer.start()
            muxerStarted = true

            val bufferSize = selectedTracks
                .maxOfOrNull { it.format.maxInputSizeOrNull() ?: 0 }
                ?.coerceAtLeast(DEFAULT_BUFFER_SIZE_BYTES)
                ?: DEFAULT_BUFFER_SIZE_BYTES
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            val durationUs = selectedTracks
                .mapNotNull { it.format.durationUsOrNull() }
                .maxOrNull()
                ?: expectedDurationMillis?.takeIf { it > 0L }?.times(1_000L)
            var firstSampleTimeUs: Long? = null
            var lastPresentationTimeUs = 0L
            var lastReportedProgress = -1f
            onProgress(durationUs?.let { 0f })

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sourceTrackIndex = extractor.sampleTrackIndex
                val muxerTrackIndex = muxerTrackBySourceTrack[sourceTrackIndex]
                if (muxerTrackIndex != null) {
                    val sampleTimeUs = extractor.sampleTime
                    val timeOriginUs = firstSampleTimeUs ?: sampleTimeUs.also { firstSampleTimeUs = it }
                    val presentationTimeUs = (sampleTimeUs - timeOriginUs).coerceAtLeast(0L)
                    bufferInfo.set(
                        0,
                        sampleSize,
                        presentationTimeUs,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        },
                    )
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    lastPresentationTimeUs = maxOf(lastPresentationTimeUs, presentationTimeUs)
                    if (durationUs != null && durationUs > 0L) {
                        val progress = (presentationTimeUs.toDouble() / durationUs.toDouble())
                            .toFloat()
                            .coerceIn(0f, 1f)
                        if (progress - lastReportedProgress >= PROGRESS_REPORT_STEP) {
                            lastReportedProgress = progress
                            onProgress(progress)
                        }
                    }
                }
                extractor.advance()
            }

            if (firstSampleTimeUs == null) {
                throw IOException("TS 中没有可写入的音视频样本")
            }
            muxer.stop()
            muxerStarted = false
            if (!output.isFile || output.length() <= 0L) {
                throw IOException("MP4 输出文件为空")
            }
            onProgress(1f)
            return RemuxedMediaInfo(
                videoMimeType = videoTrack.mimeType,
                audioMimeType = selectedTracks
                    .firstOrNull { it.mimeType.startsWith("audio/") }
                    ?.mimeType,
                ignoredTrackCount = extractor.trackCount - selectedTracks.size,
                durationMillis = lastPresentationTimeUs / 1_000L,
            )
        } catch (error: Throwable) {
            output.delete()
            throw IOException(
                "MP4 转封装失败：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun selectTracks(extractor: MediaExtractor): List<SourceTrack> {
        var videoTrack: SourceTrack? = null
        var audioTrack: SourceTrack? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mimeType = format.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mimeType.startsWith("video/") && videoTrack == null -> {
                    videoTrack = SourceTrack(index, mimeType, format)
                }

                mimeType.startsWith("audio/") && audioTrack == null -> {
                    audioTrack = SourceTrack(index, mimeType, format)
                }
            }
        }
        return listOfNotNull(videoTrack, audioTrack)
    }

    private fun MediaFormat.maxInputSizeOrNull(): Int? =
        if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).takeIf { it > 0 }
        } else {
            null
        }

    private fun MediaFormat.durationUsOrNull(): Long? =
        if (containsKey(MediaFormat.KEY_DURATION)) {
            getLong(MediaFormat.KEY_DURATION).takeIf { it > 0L }
        } else {
            null
        }

    private data class SourceTrack(
        val sourceIndex: Int,
        val mimeType: String,
        val format: MediaFormat,
    )

    companion object {
        private const val DEFAULT_BUFFER_SIZE_BYTES = 8 * 1024 * 1024
        private const val PROGRESS_REPORT_STEP = 0.005f
    }
}

internal data class RemuxedMediaInfo(
    val videoMimeType: String,
    val audioMimeType: String?,
    val ignoredTrackCount: Int,
    val durationMillis: Long,
)
