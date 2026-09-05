package com.lateropulsion.core.model

import kotlinx.serialization.Serializable

/** Per-block or per-session deviation statistics (ARCHITECTURE §8.2). Angles in degrees. */
@Serializable
public data class DeviationMetrics(
    val meanDeg: Double,
    val madDeg: Double,
    val rmsDeg: Double,
    val maxDeg: Double,
    val sdDeg: Double,
    val tib5Pct: Double,
    val tib10Pct: Double,
    val timeOutOfBandS: Double,
    val pathLengthDeg: Double,
    val meanVelocityDegS: Double,
    /** -1 = entirely left of target, +1 = entirely right, 0 = symmetric. */
    val symmetryIndex: Double,
    val validSamples: Long,
    val totalSamples: Long,
    val validDurationS: Double,
) {
    public val validSamplePct: Double get() = if (totalSamples == 0L) 0.0 else 100.0 * validSamples / totalSamples

    public companion object {
        public val EMPTY: DeviationMetrics = DeviationMetrics(
            meanDeg = Double.NaN, madDeg = Double.NaN, rmsDeg = Double.NaN, maxDeg = Double.NaN, sdDeg = Double.NaN,
            tib5Pct = Double.NaN, tib10Pct = Double.NaN, timeOutOfBandS = 0.0, pathLengthDeg = 0.0,
            meanVelocityDegS = Double.NaN, symmetryIndex = Double.NaN, validSamples = 0, totalSamples = 0, validDurationS = 0.0,
        )
    }
}

/** One deviation episode from the hysteresis detector (ARCHITECTURE §8.3). Times in seconds from block start. */
@Serializable
public data class Episode(
    val startS: Double,
    val endS: Double?,
    val peakDeg: Double,
    /** Overlaps an invalid region or the block ended mid-episode: counted and shown, excluded from means. */
    val partial: Boolean,
    /** Onset → sustained return inside the primary band; null if never recovered. */
    val recoveryS: Double?,
    val direction: Side,
) {
    public val durationS: Double? get() = endS?.let { it - startS }
}

@Serializable
public data class EpisodeStats(
    val count: Int,
    val partialCount: Int,
    val meanDurationS: Double,
    val maxDurationS: Double,
    val recoveryMeanS: Double,
    val recoveryMaxS: Double,
) {
    public companion object {
        public val NONE: EpisodeStats = EpisodeStats(0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    }
}

/** Written into every session record so that summary metrics are reproducible offline (ADR-005). */
@Serializable
public data class FilterParams(
    val type: String,
    val order: Int,
    val cutoffHz: Double,
    val sampleRateHz: Double,
    val zeroPhase: Boolean,
    val padding: String,
    val padLen: Int,
) {
    public companion object {
        public fun summary(sampleRateHz: Double, cutoffHz: Double = 5.0): FilterParams =
            FilterParams("butterworth", 4, cutoffHz, sampleRateHz, zeroPhase = true, padding = "odd", padLen = 3 * (2 * 2 + 1))

        public fun live(sampleRateHz: Double, cutoffHz: Double = 5.0): FilterParams =
            FilterParams("butterworth", 2, cutoffHz, sampleRateHz, zeroPhase = false, padding = "none", padLen = 0)
    }
}

/** Minimal detectable change, from the jig study plus within-session test–retest (ARCHITECTURE §8.4). */
@Serializable
public data class MinimalDetectableChange(
    val madDeg: Double,
    val source: String,
)
