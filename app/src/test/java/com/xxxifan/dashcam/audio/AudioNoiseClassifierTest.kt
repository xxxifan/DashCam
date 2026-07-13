package com.xxxifan.dashcam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNoiseClassifierTest {
    @Test
    fun quietVoiceAndSceneSamples_areKeptClean() {
        listOf(
            Sample(-40.9, 25.4, -3.2, -3.7, 0.4411),
            Sample(-39.0, 15.1, 1.0, -18.1, 0.0180),
            Sample(-38.3, 13.6, -1.0, -18.9, 0.0119),
        ).forEach { sample ->
            assertEquals(AudioNoiseClassification.Clean, sample.classify().classification)
        }
    }

    @Test
    fun persistentWindAndBroadbandNoise_isProcessed() {
        val result = Sample(-28.8, 13.6, -3.4, -7.6, 0.1735).classify()

        assertEquals(AudioNoiseClassification.Noise, result.classification)
        assertTrue(result.highSuppressionDb >= 8.0)
        assertTrue(result.midSuppressionDb > 0.0)
    }

    @Test
    fun grayZone_isKeptUnchanged() {
        val result = Sample(-33.5, 12.0, -5.0, -14.0, 0.04).classify()

        assertEquals(AudioNoiseClassification.Uncertain, result.classification)
    }

    private data class Sample(
        val noiseFloor: Double,
        val dynamicRange: Double,
        val lowVsVoice: Double,
        val highVsVoice: Double,
        val flatness: Double,
    ) {
        fun classify(): AudioNoiseAnalysis = AudioNoiseClassifier.classify(
            noiseFloorDbfs = noiseFloor,
            dynamicRangeDb = dynamicRange,
            lowVsVoiceDb = lowVsVoice,
            highVsVoiceDb = highVsVoice,
            spectralFlatness = flatness,
        )
    }
}
