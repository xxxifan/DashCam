package com.xxxifan.dashcam.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioNoiseAnalyzer {
    suspend fun analyze(file: File): AudioNoiseAnalysis = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("视频没有可分析的音轨")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("音轨缺少 MIME 类型")
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val accumulator = NoiseFeatureAccumulator(
                sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
            decoder = MediaCodec.createDecoderByType(mimeType).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            decode(extractor, decoder, accumulator)
            accumulator.finish()
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private suspend fun decode(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        accumulator: NoiseFeatureAccumulator,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        while (!outputEnded) {
            coroutineContext.ensureActive()
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                        ?: error("无法取得音频解码输入缓冲区")
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime.coerceAtLeast(0L),
                            extractor.sampleFlags,
                        )
                        extractor.advance()
                    }
                }
            }
            when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    accumulator.updateFormat(
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    )
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            ?: error("无法取得音频解码输出缓冲区")
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        accumulator.add(outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer())
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
    }
}

private class NoiseFeatureAccumulator(
    sampleRate: Int,
    channelCount: Int,
) {
    private var sampleRate = sampleRate
    private var channelCount = channelCount.coerceAtLeast(1)
    private var block = FloatArray(blockSize())
    private var blockPosition = 0
    private var channelPosition = 0
    private var frameSum = 0f
    private val features = mutableListOf<BlockFeatures>()

    fun updateFormat(sampleRate: Int, channelCount: Int) {
        check(blockPosition == 0) { "音频解码过程中格式发生变化" }
        this.sampleRate = sampleRate
        this.channelCount = channelCount.coerceAtLeast(1)
        block = FloatArray(blockSize())
        channelPosition = 0
        frameSum = 0f
    }

    fun add(buffer: java.nio.ShortBuffer) {
        while (buffer.hasRemaining()) {
            frameSum += buffer.get() / 32768f
            channelPosition += 1
            if (channelPosition == channelCount) {
                block[blockPosition++] = frameSum / channelCount
                channelPosition = 0
                frameSum = 0f
                if (blockPosition == block.size) {
                    features += analyzeBlock(block)
                    blockPosition = 0
                }
            }
        }
    }

    fun finish(): AudioNoiseAnalysis {
        if (blockPosition >= sampleRate) {
            features += analyzeBlock(block.copyOf(blockPosition))
        }
        check(features.isNotEmpty()) { "音轨没有足够的有效采样" }
        val levels = features.map { it.levelDbfs }
        val noiseFloor = levels.percentile(0.10)
        val dynamicRange = levels.percentile(0.90) - noiseFloor
        val baseAnalysis = AudioNoiseClassifier.classify(
            noiseFloorDbfs = noiseFloor,
            dynamicRangeDb = dynamicRange,
            lowVsVoiceDb = features.map { it.lowVsVoiceDb }.median(),
            highVsVoiceDb = features.map { it.highVsVoiceDb }.median(),
            spectralFlatness = features.map { it.spectralFlatness }.median(),
        )
        val harshEvents = HarshNoiseDetector.detect(
            frames = features.mapIndexed { index, feature ->
                HarshAnalysisFrame(
                    startSeconds = index * BLOCK_DURATION_SECONDS,
                    levelDbfs = feature.levelDbfs,
                    bandLevelsDb = feature.harshBandLevelsDb,
                )
            },
            frameDurationSeconds = BLOCK_DURATION_SECONDS,
        )
        val strongMechanicalEvent = harshEvents.any { event ->
            event.confidence >= 0.85 && event.endSeconds - event.startSeconds >= 4.0
        }
        return baseAnalysis.copy(
            classification = if (
                baseAnalysis.classification == AudioNoiseClassification.Clean && strongMechanicalEvent
            ) {
                AudioNoiseClassification.Noise
            } else {
                baseAnalysis.classification
            },
            harshNoiseEvents = harshEvents,
            reason = if (harshEvents.isEmpty()) {
                baseAnalysis.reason
            } else {
                "${baseAnalysis.reason}；检测到 ${harshEvents.size} 段重复刺耳机械噪声"
            },
        )
    }

    private fun blockSize(): Int = sampleRate * 2

    private fun analyzeBlock(samples: FloatArray): BlockFeatures {
        val levelDbfs = 20.0 * log10(sqrt(samples.sumOf { it.toDouble() * it } / samples.size) + EPSILON)
        var lowEnergy = 0.0
        var voiceEnergy = 0.0
        var highEnergy = 0.0
        var flatLogSum = 0.0
        var flatLinearSum = 0.0
        var flatCount = 0
        var windowCount = 0
        val harshBandPowers = Array(HARSH_BAND_EDGES_HZ.lastIndex) { mutableListOf<Double>() }
        var offset = 0
        while (offset + FFT_SIZE <= samples.size) {
            val real = DoubleArray(FFT_SIZE)
            val imaginary = DoubleArray(FFT_SIZE)
            for (index in 0 until FFT_SIZE) {
                val window = 0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))
                real[index] = samples[offset + index] * window
            }
            fft(real, imaginary)
            val windowHarshBandPower = DoubleArray(HARSH_BAND_EDGES_HZ.lastIndex)
            for (index in 1..FFT_SIZE / 2) {
                val frequency = index.toDouble() * sampleRate / FFT_SIZE
                val power = real[index] * real[index] + imaginary[index] * imaginary[index] + EPSILON
                when {
                    frequency < 20.0 -> Unit
                    frequency < 300.0 -> lowEnergy += power
                    frequency < 3_500.0 -> voiceEnergy += power
                    frequency < 15_000.0 -> highEnergy += power
                }
                if (frequency in 150.0..12_000.0) {
                    flatLogSum += ln(power)
                    flatLinearSum += power
                    flatCount += 1
                }
                val harshBand = HARSH_BAND_EDGES_HZ.indexOfLast { edge -> frequency >= edge }
                if (harshBand in windowHarshBandPower.indices && frequency < HARSH_BAND_EDGES_HZ.last()) {
                    windowHarshBandPower[harshBand] += power
                }
            }
            windowHarshBandPower.forEachIndexed { band, power -> harshBandPowers[band] += power }
            windowCount += 1
            offset += FFT_SIZE
        }
        check(windowCount > 0) { "音频窗口过短" }
        val flatness = if (flatCount > 0) {
            kotlin.math.exp(flatLogSum / flatCount) / (flatLinearSum / flatCount + EPSILON)
        } else {
            0.0
        }
        return BlockFeatures(
            levelDbfs = levelDbfs,
            lowVsVoiceDb = 10.0 * log10((lowEnergy + EPSILON) / (voiceEnergy + EPSILON)),
            highVsVoiceDb = 10.0 * log10((highEnergy + EPSILON) / (voiceEnergy + EPSILON)),
            spectralFlatness = flatness,
            harshBandLevelsDb = DoubleArray(harshBandPowers.size) { band ->
                10.0 * log10(harshBandPowers[band].median() + EPSILON)
            },
        )
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var target = 0
        for (index in 1 until real.size) {
            var bit = real.size shr 1
            while (target and bit != 0) {
                target = target xor bit
                bit = bit shr 1
            }
            target = target xor bit
            if (index < target) {
                real[index] = real[target].also { real[target] = real[index] }
                imaginary[index] = imaginary[target].also { imaginary[target] = imaginary[index] }
            }
        }
        var length = 2
        while (length <= real.size) {
            val angle = -2.0 * PI / length
            val wLengthReal = cos(angle)
            val wLengthImaginary = sin(angle)
            for (start in real.indices step length) {
                var wReal = 1.0
                var wImaginary = 0.0
                for (index in 0 until length / 2) {
                    val even = start + index
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImaginary
                    val oddImaginary = real[odd] * wImaginary + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextWReal = wReal * wLengthReal - wImaginary * wLengthImaginary
                    wImaginary = wReal * wLengthImaginary + wImaginary * wLengthReal
                    wReal = nextWReal
                }
            }
            length = length shl 1
        }
    }

    private data class BlockFeatures(
        val levelDbfs: Double,
        val lowVsVoiceDb: Double,
        val highVsVoiceDb: Double,
        val spectralFlatness: Double,
        val harshBandLevelsDb: DoubleArray,
    )

    private companion object {
        const val FFT_SIZE = 4096
        const val EPSILON = 1e-20
        const val BLOCK_DURATION_SECONDS = 2.0
        val HARSH_BAND_EDGES_HZ = doubleArrayOf(
            1_000.0,
            2_000.0,
            3_000.0,
            4_000.0,
            5_000.0,
            6_000.0,
            7_500.0,
            10_000.0,
            15_000.0,
        )
    }
}

private fun List<Double>.median(): Double = percentile(0.5)

private fun List<Double>.percentile(fraction: Double): Double {
    val sorted = sorted()
    if (sorted.size == 1) {
        return sorted.first()
    }
    val position = fraction.coerceIn(0.0, 1.0) * (sorted.lastIndex)
    val lower = position.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    val remainder = position - lower
    return sorted[lower] * (1.0 - remainder) + sorted[upper] * remainder
}
