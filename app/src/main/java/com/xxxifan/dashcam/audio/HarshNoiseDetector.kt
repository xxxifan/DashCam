package com.xxxifan.dashcam.audio

import kotlin.math.max
import kotlin.math.sqrt

internal data class HarshAnalysisFrame(
    val startSeconds: Double,
    val levelDbfs: Double,
    val bandLevelsDb: DoubleArray,
)

internal object HarshNoiseDetector {
    private val bandEdgesHz = doubleArrayOf(
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

    fun detect(frames: List<HarshAnalysisFrame>, frameDurationSeconds: Double): List<HarshNoiseEvent> {
        if (frames.size < 3) {
            return emptyList()
        }
        val baselineByBand = DoubleArray(bandEdgesHz.lastIndex) { band ->
            frames.map { it.bandLevelsDb[band] }.percentile(0.20)
        }
        val levelBaseline = frames.map { it.levelDbfs }.percentile(0.20)
        val excessByFrame = frames.map { frame ->
            DoubleArray(baselineByBand.size) { band -> frame.bandLevelsDb[band] - baselineByBand[band] }
        }
        val candidates = BooleanArray(frames.size) { index ->
            val sharpExcess = excessByFrame[index]
                .sliceArray(SHARP_BAND_RANGE)
                .maxOrNull() ?: 0.0
            val levelRise = frames[index].levelDbfs - levelBaseline
            sharpExcess >= MIN_SHARP_EXCESS_DB &&
                (levelRise >= MIN_LEVEL_RISE_DB || sharpExcess >= STRONG_SHARP_EXCESS_DB)
        }
        closeSingleFrameGaps(candidates)
        val segments = buildSegments(
            frames = frames,
            excessByFrame = excessByFrame,
            candidates = candidates,
            frameDurationSeconds = frameDurationSeconds,
        )
        return selectStableEvents(segments, frames, frameDurationSeconds)
    }

    private fun buildSegments(
        frames: List<HarshAnalysisFrame>,
        excessByFrame: List<DoubleArray>,
        candidates: BooleanArray,
        frameDurationSeconds: Double,
    ): List<CandidateSegment> = buildList {
        var start = 0
        while (start < candidates.size) {
            if (!candidates[start]) {
                start += 1
                continue
            }
            var end = start
            while (end + 1 < candidates.size && candidates[end + 1]) {
                end += 1
            }
            val bandExcess = DoubleArray(bandEdgesHz.lastIndex) { band ->
                (start..end).map { excessByFrame[it][band] }.median()
            }
            val dominantBands = (start..end).map { index ->
                SHARP_BAND_RANGE.maxBy { band -> excessByFrame[index][band] }
            }
            val dominantBandStability = dominantBands
                .groupingBy { it }
                .eachCount()
                .maxOf { it.value }
                .toDouble() / dominantBands.size
            val adjacentSimilarities = (start until end).map { index ->
                spectralSimilarity(
                    frames[index].bandLevelsDb,
                    frames[index + 1].bandLevelsDb,
                )
            }
            add(
                CandidateSegment(
                    startFrame = start,
                    endFrame = end,
                    durationSeconds = (end - start + 1) * frameDurationSeconds,
                    bandExcess = bandExcess,
                    spectralSimilarity = adjacentSimilarities.medianOrOne(),
                    dominantBandStability = dominantBandStability,
                ),
            )
            start = end + 1
        }
    }

    private fun selectStableEvents(
        segments: List<CandidateSegment>,
        frames: List<HarshAnalysisFrame>,
        frameDurationSeconds: Double,
    ): List<HarshNoiseEvent> = buildList {
        var index = 0
        while (index < segments.size) {
            val anchor = segments[index]
            if (!anchor.isStableAnchor()) {
                index += 1
                continue
            }
            var combined = anchor
            var nextIndex = index + 1
            while (nextIndex < segments.size) {
                val next = segments[nextIndex]
                val gapSeconds = (
                    frames[next.startFrame].startSeconds -
                        (frames[combined.endFrame].startSeconds + frameDurationSeconds)
                    )
                if (
                    gapSeconds > CONTINUATION_GAP_SECONDS ||
                    !combined.bandsOverlap(next) ||
                    !next.isStableContinuation()
                ) {
                    break
                }
                combined = combined.combine(next)
                nextIndex += 1
            }
            add(combined.toEvent(frames, frameDurationSeconds))
            index = nextIndex
        }
    }

    private fun CandidateSegment.isStableAnchor(): Boolean =
        durationSeconds >= MIN_EVENT_DURATION_SECONDS &&
            spectralSimilarity >= MIN_SPECTRAL_SIMILARITY &&
            dominantBandStability >= MIN_DOMINANT_BAND_STABILITY &&
            activeSharpBands().size >= MIN_ACTIVE_SHARP_BANDS &&
            sharpBandSpreadDb() <= MAX_SHARP_BAND_SPREAD_DB

    private fun CandidateSegment.isStableContinuation(): Boolean {
        val strongestExcess = bandExcess.sliceArray(SHARP_BAND_RANGE).maxOrNull() ?: 0.0
        if (durationSeconds < MIN_CONTINUATION_DURATION_SECONDS) {
            return strongestExcess >= STRONG_SHARP_EXCESS_DB
        }
        return spectralSimilarity >= MIN_SPECTRAL_SIMILARITY &&
            dominantBandStability >= MIN_CONTINUATION_BAND_STABILITY &&
            activeSharpBands().size >= MIN_ACTIVE_SHARP_BANDS &&
            sharpBandSpreadDb() <= MAX_CONTINUATION_BAND_SPREAD_DB
    }

    private fun CandidateSegment.sharpBandSpreadDb(): Double {
        val levels = activeSharpBands().map { bandExcess[it] }
        if (levels.isEmpty()) {
            return Double.POSITIVE_INFINITY
        }
        return (levels.maxOrNull() ?: 0.0) - (levels.minOrNull() ?: 0.0)
    }

    private fun CandidateSegment.activeSharpBands(): List<Int> =
        SHARP_BAND_RANGE.filter { bandExcess[it] >= ACTIVE_BAND_EXCESS_DB }

    private fun CandidateSegment.bandsOverlap(other: CandidateSegment): Boolean {
        val range = activeBandRange()
        val otherRange = other.activeBandRange()
        return range.first <= otherRange.last && otherRange.first <= range.last
    }

    private fun CandidateSegment.activeBandRange(): IntRange {
        val activeBands = activeSharpBands()
        val strongestBand = SHARP_BAND_RANGE.maxBy { bandExcess[it] }
        return (activeBands.firstOrNull() ?: strongestBand)..(activeBands.lastOrNull() ?: strongestBand)
    }

    private fun CandidateSegment.combine(other: CandidateSegment): CandidateSegment {
        val firstFrameCount = endFrame - startFrame + 1
        val secondFrameCount = other.endFrame - other.startFrame + 1
        val totalFrameCount = firstFrameCount + secondFrameCount
        return copy(
            endFrame = other.endFrame,
            durationSeconds = durationSeconds + other.durationSeconds,
            bandExcess = DoubleArray(bandExcess.size) { band ->
                (
                    bandExcess[band] * firstFrameCount +
                        other.bandExcess[band] * secondFrameCount
                    ) / totalFrameCount
            },
            spectralSimilarity = minOf(spectralSimilarity, other.spectralSimilarity),
            dominantBandStability = minOf(dominantBandStability, other.dominantBandStability),
        )
    }

    private fun CandidateSegment.toEvent(
        frames: List<HarshAnalysisFrame>,
        frameDurationSeconds: Double,
    ): HarshNoiseEvent {
        val strongestExcess = bandExcess.sliceArray(SHARP_BAND_RANGE).maxOrNull() ?: 0.0
        val activeRange = activeBandRange()
        val attenuation = (0.7 * (strongestExcess - 4.0)).coerceIn(6.0, 15.0)
        val confidence = (
            0.55 +
                (strongestExcess - MIN_SHARP_EXCESS_DB).coerceIn(0.0, 12.0) / 30.0 +
                (durationSeconds / 20.0).coerceIn(0.0, 0.20) +
                (spectralSimilarity - MIN_SPECTRAL_SIMILARITY).coerceIn(0.0, 0.05)
            ).coerceIn(0.0, 1.0)
        return HarshNoiseEvent(
            startSeconds = max(0.0, frames[startFrame].startSeconds - ATTACK_LOOKBACK_SECONDS),
            endSeconds = frames[endFrame].startSeconds + frameDurationSeconds + RELEASE_SECONDS,
            lowFrequencyHz = bandEdgesHz[activeRange.first].coerceAtLeast(2_000.0),
            highFrequencyHz = bandEdgesHz[activeRange.last + 1],
            attenuationDb = attenuation,
            confidence = confidence,
        )
    }

    private fun closeSingleFrameGaps(candidates: BooleanArray) {
        for (index in 1 until candidates.lastIndex) {
            if (!candidates[index] && candidates[index - 1] && candidates[index + 1]) {
                candidates[index] = true
            }
        }
    }

    private fun spectralSimilarity(first: DoubleArray, second: DoubleArray): Double {
        val firstMean = SHARP_BAND_RANGE.map { first[it] }.average()
        val secondMean = SHARP_BAND_RANGE.map { second[it] }.average()
        var dotProduct = 0.0
        var firstEnergy = 0.0
        var secondEnergy = 0.0
        SHARP_BAND_RANGE.forEach { band ->
            val firstValue = first[band] - firstMean
            val secondValue = second[band] - secondMean
            dotProduct += firstValue * secondValue
            firstEnergy += firstValue * firstValue
            secondEnergy += secondValue * secondValue
        }
        if (firstEnergy < EPSILON || secondEnergy < EPSILON) {
            val meanDifference = SHARP_BAND_RANGE.map { band ->
                kotlin.math.abs(first[band] - second[band])
            }.average()
            return if (meanDifference <= FLAT_PROFILE_TOLERANCE_DB) 1.0 else 0.0
        }
        return dotProduct / sqrt(firstEnergy * secondEnergy)
    }

    private data class CandidateSegment(
        val startFrame: Int,
        val endFrame: Int,
        val durationSeconds: Double,
        val bandExcess: DoubleArray,
        val spectralSimilarity: Double,
        val dominantBandStability: Double,
    )

    private val SHARP_BAND_RANGE = 1..6
    private const val MIN_SHARP_EXCESS_DB = 8.0
    private const val STRONG_SHARP_EXCESS_DB = 12.0
    private const val MIN_LEVEL_RISE_DB = 4.0
    private const val ACTIVE_BAND_EXCESS_DB = 6.0
    private const val MIN_EVENT_DURATION_SECONDS = 6.0
    private const val MIN_CONTINUATION_DURATION_SECONDS = 4.0
    private const val MIN_SPECTRAL_SIMILARITY = 0.94
    private const val MIN_DOMINANT_BAND_STABILITY = 0.60
    private const val MIN_CONTINUATION_BAND_STABILITY = 0.50
    private const val MIN_ACTIVE_SHARP_BANDS = 3
    private const val MAX_SHARP_BAND_SPREAD_DB = 9.0
    private const val MAX_CONTINUATION_BAND_SPREAD_DB = 10.0
    private const val CONTINUATION_GAP_SECONDS = 12.0
    private const val ATTACK_LOOKBACK_SECONDS = 0.5
    private const val RELEASE_SECONDS = 1.0
    private const val FLAT_PROFILE_TOLERANCE_DB = 1.0
    private const val EPSILON = 1e-12
}

private fun List<Double>.median(): Double = percentile(0.50)

private fun List<Double>.medianOrOne(): Double = if (isEmpty()) 1.0 else median()

private fun List<Double>.percentile(fraction: Double): Double {
    val sorted = sorted()
    if (sorted.size == 1) {
        return sorted.first()
    }
    val position = fraction.coerceIn(0.0, 1.0) * sorted.lastIndex
    val lower = position.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    val remainder = position - lower
    return sorted[lower] * (1.0 - remainder) + sorted[upper] * remainder
}
