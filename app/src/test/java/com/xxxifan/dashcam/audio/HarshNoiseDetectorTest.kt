package com.xxxifan.dashcam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarshNoiseDetectorTest {
    @Test
    fun sustainedSharpMechanicalBand_isDetectedAndBounded() {
        val frames = List(60) { index ->
            val eventActive = index in 7..34
            HarshAnalysisFrame(
                startSeconds = index * 2.0,
                levelDbfs = if (eventActive) -25.0 else -40.0,
                bandLevelsDb = DoubleArray(8) { band ->
                    if (eventActive && band in 1..5) -42.0 else -60.0
                },
            )
        }

        val events = HarshNoiseDetector.detect(frames, frameDurationSeconds = 2.0)

        assertEquals(1, events.size)
        assertTrue(events.single().startSeconds in 13.0..14.0)
        assertTrue(events.single().endSeconds in 70.0..72.0)
        assertTrue(events.single().lowFrequencyHz >= 2_000.0)
        assertTrue(events.single().highFrequencyHz <= 7_500.0)
        assertTrue(events.single().attenuationDb >= 9.0)
    }

    @Test
    fun loudLowFrequencySafetySound_isNotClassifiedAsHarsh() {
        val frames = List(20) { index ->
            val loud = index in 5..10
            HarshAnalysisFrame(
                startSeconds = index * 2.0,
                levelDbfs = if (loud) -15.0 else -40.0,
                bandLevelsDb = DoubleArray(8) { band ->
                    if (loud && band == 0) -30.0 else -60.0
                },
            )
        }

        assertTrue(HarshNoiseDetector.detect(frames, frameDurationSeconds = 2.0).isEmpty())
    }

    @Test
    fun loudSceneSoundWithChangingSpectrum_isProtected() {
        val frames = List(30) { index ->
            val loud = index in 5..22
            HarshAnalysisFrame(
                startSeconds = index * 2.0,
                levelDbfs = if (loud) -18.0 + index % 4 * 2.0 else -42.0,
                bandLevelsDb = DoubleArray(8) { band ->
                    when {
                        !loud -> -64.0
                        band == 1 + index % 6 -> -34.0
                        band in 1..6 -> -48.0 + (index + band) % 5 * 3.0
                        else -> -60.0
                    }
                },
            )
        }

        assertTrue(HarshNoiseDetector.detect(frames, frameDurationSeconds = 2.0).isEmpty())
    }

    @Test
    fun stableButStronglySlopedSceneSpectrum_isProtected() {
        val frames = List(30) { index ->
            val loud = index in 4..20
            HarshAnalysisFrame(
                startSeconds = index * 2.0,
                levelDbfs = if (loud) -20.0 else -45.0,
                bandLevelsDb = DoubleArray(8) { band ->
                    if (loud && band in 1..6) -50.0 + band * 4.0 else -65.0
                },
            )
        }

        assertTrue(HarshNoiseDetector.detect(frames, frameDurationSeconds = 2.0).isEmpty())
    }
}
