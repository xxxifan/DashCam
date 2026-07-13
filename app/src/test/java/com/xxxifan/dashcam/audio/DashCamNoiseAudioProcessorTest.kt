package com.xxxifan.dashcam.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashCamNoiseAudioProcessorTest {
    @Test
    fun harshEvent_isAttenuatedOnlyInsideDetectedTimeRange() {
        val sampleRate = 44_100
        val samples = ShortArray(sampleRate * 4) { index ->
            (sin(2.0 * PI * 4_500.0 * index / sampleRate) * 12_000.0).toInt().toShort()
        }
        val filtered = process(
            samples = samples,
            sampleRate = sampleRate,
            analysis = analysis(
                lowSuppressionDb = 0.0,
                midSuppressionDb = 0.0,
                highSuppressionDb = 0.0,
                harshEvents = listOf(
                    HarshNoiseEvent(
                        startSeconds = 1.0,
                        endSeconds = 3.0,
                        lowFrequencyHz = 3_000.0,
                        highFrequencyHz = 7_500.0,
                        attenuationDb = 13.0,
                        confidence = 0.95,
                    ),
                ),
            ),
        )

        val inputBefore = rms(samples, 0, sampleRate / 2)
        val outputBefore = rms(filtered, 0, sampleRate / 2)
        val inputDuring = rms(samples, sampleRate * 3 / 2, sampleRate * 5 / 2)
        val outputDuring = rms(filtered, sampleRate * 3 / 2, sampleRate * 5 / 2)
        val inputAfter = rms(samples, sampleRate * 7 / 2, sampleRate * 4)
        val outputAfter = rms(filtered, sampleRate * 7 / 2, sampleRate * 4)

        assertTrue(outputBefore / inputBefore > 0.9)
        assertTrue(outputDuring / inputDuring < 0.5)
        assertTrue(outputAfter / inputAfter > 0.9)
    }

    @Test
    fun strongHighFrequencyNoise_isAttenuatedWithoutChangingFrameCount() {
        val sampleRate = 44_100
        val samples = ShortArray(sampleRate) { index ->
            (sin(2.0 * PI * 6_000.0 * index / sampleRate) * 16_000.0).toInt().toShort()
        }
        val processor = DashCamNoiseAudioProcessor(analysis())
        processor.configure(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val filtered = ShortArray(output.remaining() / Short.SIZE_BYTES)
        output.asShortBuffer().get(filtered)

        assertEquals(samples.size, filtered.size)
        assertTrue(rms(filtered, 1_024) < rms(samples, 1_024) * 0.35)
    }

    private fun process(
        samples: ShortArray,
        sampleRate: Int,
        analysis: AudioNoiseAnalysis,
    ): ShortArray {
        val processor = DashCamNoiseAudioProcessor(analysis)
        processor.configure(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return ShortArray(output.remaining() / Short.SIZE_BYTES).also { filtered ->
            output.asShortBuffer().get(filtered)
        }
    }

    private fun analysis(
        lowSuppressionDb: Double = 2.5,
        midSuppressionDb: Double = 5.0,
        highSuppressionDb: Double = 12.0,
        harshEvents: List<HarshNoiseEvent> = emptyList(),
    ) = AudioNoiseAnalysis(
        classification = AudioNoiseClassification.Noise,
        noiseFloorDbfs = -20.0,
        dynamicRangeDb = 4.0,
        lowVsVoiceDb = -4.0,
        highVsVoiceDb = -1.0,
        spectralFlatness = 0.1,
        lowSuppressionDb = lowSuppressionDb,
        midSuppressionDb = midSuppressionDb,
        highSuppressionDb = highSuppressionDb,
        harshNoiseEvents = harshEvents,
        reason = "test",
    )

    private fun rms(samples: ShortArray, offset: Int): Double {
        val energy = samples.drop(offset).sumOf { value ->
            val normalized = value / 32768.0
            normalized * normalized
        }
        return sqrt(energy / (samples.size - offset))
    }

    private fun rms(samples: ShortArray, start: Int, end: Int): Double {
        val energy = samples.sliceArray(start until end).sumOf { value ->
            val normalized = value / 32768.0
            normalized * normalized
        }
        return sqrt(energy / (end - start))
    }
}
