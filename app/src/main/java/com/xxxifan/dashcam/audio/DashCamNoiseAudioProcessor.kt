@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.xxxifan.dashcam.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DashCamNoiseAudioProcessor(
    private val analysis: AudioNoiseAnalysis,
) : BaseAudioProcessor() {
    private var filters: List<BiquadFilter> = emptyList()
    private var harshEventSuppressors: List<HarshEventSuppressor> = emptyList()
    private var processedFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        filters = buildList {
            add(
                BiquadFilter(
                    coefficients = BiquadCoefficients.highPass(inputAudioFormat.sampleRate, 75.0),
                    channelCount = inputAudioFormat.channelCount,
                ),
            )
            if (analysis.lowSuppressionDb > 0.1) {
                add(
                    BiquadFilter(
                        coefficients = BiquadCoefficients.lowShelf(
                            sampleRate = inputAudioFormat.sampleRate,
                            frequency = 250.0,
                            gainDb = -analysis.lowSuppressionDb,
                        ),
                        channelCount = inputAudioFormat.channelCount,
                    ),
                )
            }
            if (analysis.midSuppressionDb > 0.1) {
                add(
                    BiquadFilter(
                        coefficients = BiquadCoefficients.peaking(
                            sampleRate = inputAudioFormat.sampleRate,
                            frequency = 550.0,
                            quality = 0.7,
                            gainDb = -analysis.midSuppressionDb,
                        ),
                        channelCount = inputAudioFormat.channelCount,
                    ),
                )
            }
            if (analysis.highSuppressionDb > 0.1) {
                add(
                    BiquadFilter(
                        coefficients = BiquadCoefficients.highShelf(
                            sampleRate = inputAudioFormat.sampleRate,
                            frequency = 3_000.0,
                            gainDb = -analysis.highSuppressionDb,
                        ),
                        channelCount = inputAudioFormat.channelCount,
                    ),
                )
                if (analysis.highSuppressionDb >= 8.0) {
                    add(
                        BiquadFilter(
                            coefficients = BiquadCoefficients.lowPass(
                                sampleRate = inputAudioFormat.sampleRate,
                                frequency = 11_000.0,
                            ),
                            channelCount = inputAudioFormat.channelCount,
                        ),
                    )
                }
            }
        }
        harshEventSuppressors = analysis.harshNoiseEvents.map { event ->
            HarshEventSuppressor(
                event = event,
                sampleRate = inputAudioFormat.sampleRate,
                channelCount = inputAudioFormat.channelCount,
            )
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        var channel = 0
        while (inputBuffer.remaining() >= Short.SIZE_BYTES) {
            var sample = inputBuffer.short / 32768.0
            filters.forEach { filter ->
                sample = filter.process(sample, channel)
            }
            val timeSeconds = processedFrames.toDouble() / inputAudioFormat.sampleRate
            harshEventSuppressors.forEach { suppressor ->
                sample = suppressor.process(sample, channel, timeSeconds)
            }
            outputBuffer.putShort((sample.coerceIn(-1.0, 0.999969) * 32768.0).toInt().toShort())
            channel = (channel + 1) % inputAudioFormat.channelCount
            if (channel == 0) {
                processedFrames += 1
            }
        }
        outputBuffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        filters.forEach(BiquadFilter::reset)
        harshEventSuppressors.forEach(HarshEventSuppressor::reset)
        processedFrames = streamMetadata.positionOffsetUs * inputAudioFormat.sampleRate / 1_000_000L
    }

    override fun onReset() {
        filters = emptyList()
        harshEventSuppressors = emptyList()
        processedFrames = 0L
    }
}

private class HarshEventSuppressor(
    private val event: HarshNoiseEvent,
    sampleRate: Int,
    channelCount: Int,
) {
    private val filters = buildList {
        val low = event.lowFrequencyHz.coerceAtLeast(2_000.0)
        val high = event.highFrequencyHz.coerceAtMost(sampleRate * 0.45)
        val logLow = ln(low)
        val logHigh = ln(high.coerceAtLeast(low + 100.0))
        val bandWidth = (high - low).coerceAtLeast(300.0) / FILTER_COUNT
        repeat(FILTER_COUNT) { index ->
            val center = exp(logLow + (index + 0.5) / FILTER_COUNT * (logHigh - logLow))
            add(
                BiquadFilter(
                    coefficients = BiquadCoefficients.peaking(
                        sampleRate = sampleRate,
                        frequency = center,
                        quality = (center / bandWidth * 0.75).coerceIn(0.8, 4.0),
                        gainDb = -event.attenuationDb * 0.75,
                    ),
                    channelCount = channelCount,
                ),
            )
        }
    }

    fun process(input: Double, channel: Int, timeSeconds: Double): Double {
        val mix = envelope(timeSeconds)
        if (mix <= 0.0) {
            return input
        }
        var filtered = input
        filters.forEach { filter -> filtered = filter.process(filtered, channel) }
        return input + (filtered - input) * mix
    }

    fun reset() {
        filters.forEach(BiquadFilter::reset)
    }

    private fun envelope(timeSeconds: Double): Double = when {
        timeSeconds < event.startSeconds || timeSeconds > event.endSeconds -> 0.0
        timeSeconds < event.startSeconds + ATTACK_SECONDS ->
            (timeSeconds - event.startSeconds) / ATTACK_SECONDS
        timeSeconds > event.endSeconds - RELEASE_SECONDS ->
            (event.endSeconds - timeSeconds) / RELEASE_SECONDS
        else -> 1.0
    }.coerceIn(0.0, 1.0)

    private companion object {
        const val FILTER_COUNT = 3
        const val ATTACK_SECONDS = 0.5
        const val RELEASE_SECONDS = 1.0
    }
}

private class BiquadFilter(
    private val coefficients: BiquadCoefficients,
    channelCount: Int,
) {
    private val state1 = DoubleArray(channelCount)
    private val state2 = DoubleArray(channelCount)

    fun process(input: Double, channel: Int): Double {
        val output = coefficients.b0 * input + state1[channel]
        state1[channel] = coefficients.b1 * input - coefficients.a1 * output + state2[channel]
        state2[channel] = coefficients.b2 * input - coefficients.a2 * output
        return output
    }

    fun reset() {
        state1.fill(0.0)
        state2.fill(0.0)
    }
}

private data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    companion object {
        fun highPass(sampleRate: Int, frequency: Double): BiquadCoefficients {
            val values = common(sampleRate, frequency)
            return normalize(
                b0 = (1.0 + values.cosine) / 2.0,
                b1 = -(1.0 + values.cosine),
                b2 = (1.0 + values.cosine) / 2.0,
                a0 = 1.0 + values.alpha,
                a1 = -2.0 * values.cosine,
                a2 = 1.0 - values.alpha,
            )
        }

        fun lowPass(sampleRate: Int, frequency: Double): BiquadCoefficients {
            val values = common(sampleRate, frequency)
            return normalize(
                b0 = (1.0 - values.cosine) / 2.0,
                b1 = 1.0 - values.cosine,
                b2 = (1.0 - values.cosine) / 2.0,
                a0 = 1.0 + values.alpha,
                a1 = -2.0 * values.cosine,
                a2 = 1.0 - values.alpha,
            )
        }

        fun peaking(
            sampleRate: Int,
            frequency: Double,
            quality: Double,
            gainDb: Double,
        ): BiquadCoefficients {
            val omega = 2.0 * PI * frequency / sampleRate
            val alpha = sin(omega) / (2.0 * quality)
            val amplitude = 10.0.pow(gainDb / 40.0)
            return normalize(
                b0 = 1.0 + alpha * amplitude,
                b1 = -2.0 * cos(omega),
                b2 = 1.0 - alpha * amplitude,
                a0 = 1.0 + alpha / amplitude,
                a1 = -2.0 * cos(omega),
                a2 = 1.0 - alpha / amplitude,
            )
        }

        fun lowShelf(
            sampleRate: Int,
            frequency: Double,
            gainDb: Double,
        ): BiquadCoefficients {
            val values = shelfCommon(sampleRate, frequency, gainDb)
            return normalize(
                b0 = values.amplitude * (
                    (values.amplitude + 1.0) -
                        (values.amplitude - 1.0) * values.cosine +
                        values.beta
                    ),
                b1 = 2.0 * values.amplitude * (
                    (values.amplitude - 1.0) -
                        (values.amplitude + 1.0) * values.cosine
                    ),
                b2 = values.amplitude * (
                    (values.amplitude + 1.0) -
                        (values.amplitude - 1.0) * values.cosine -
                        values.beta
                    ),
                a0 = (values.amplitude + 1.0) +
                    (values.amplitude - 1.0) * values.cosine + values.beta,
                a1 = -2.0 * (
                    (values.amplitude - 1.0) +
                        (values.amplitude + 1.0) * values.cosine
                    ),
                a2 = (values.amplitude + 1.0) +
                    (values.amplitude - 1.0) * values.cosine - values.beta,
            )
        }

        fun highShelf(
            sampleRate: Int,
            frequency: Double,
            gainDb: Double,
        ): BiquadCoefficients {
            val values = shelfCommon(sampleRate, frequency, gainDb)
            return normalize(
                b0 = values.amplitude * (
                    (values.amplitude + 1.0) +
                        (values.amplitude - 1.0) * values.cosine +
                        values.beta
                    ),
                b1 = -2.0 * values.amplitude * (
                    (values.amplitude - 1.0) +
                        (values.amplitude + 1.0) * values.cosine
                    ),
                b2 = values.amplitude * (
                    (values.amplitude + 1.0) +
                        (values.amplitude - 1.0) * values.cosine -
                        values.beta
                    ),
                a0 = (values.amplitude + 1.0) -
                    (values.amplitude - 1.0) * values.cosine + values.beta,
                a1 = 2.0 * (
                    (values.amplitude - 1.0) -
                        (values.amplitude + 1.0) * values.cosine
                    ),
                a2 = (values.amplitude + 1.0) -
                    (values.amplitude - 1.0) * values.cosine - values.beta,
            )
        }

        private fun common(sampleRate: Int, frequency: Double): CommonValues {
            val omega = 2.0 * PI * frequency.coerceAtMost(sampleRate * 0.49) / sampleRate
            return CommonValues(
                cosine = cos(omega),
                alpha = sin(omega) / (2.0 * 0.70710678118),
            )
        }

        private fun shelfCommon(sampleRate: Int, frequency: Double, gainDb: Double): ShelfValues {
            val omega = 2.0 * PI * frequency.coerceAtMost(sampleRate * 0.49) / sampleRate
            val amplitude = 10.0.pow(gainDb / 40.0)
            val alpha = sin(omega) / (2.0 * 0.70710678118)
            return ShelfValues(
                amplitude = amplitude,
                cosine = cos(omega),
                beta = 2.0 * sqrt(amplitude) * alpha,
            )
        }

        private fun normalize(
            b0: Double,
            b1: Double,
            b2: Double,
            a0: Double,
            a1: Double,
            a2: Double,
        ): BiquadCoefficients = BiquadCoefficients(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0,
        )

        private data class CommonValues(
            val cosine: Double,
            val alpha: Double,
        )

        private data class ShelfValues(
            val amplitude: Double,
            val cosine: Double,
            val beta: Double,
        )
    }
}
