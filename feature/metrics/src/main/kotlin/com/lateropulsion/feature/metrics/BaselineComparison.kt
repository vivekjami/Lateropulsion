package com.lateropulsion.feature.metrics

import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.DeviationMetrics
import com.lateropulsion.core.model.ExerciseType
import kotlin.math.abs
import kotlin.math.sqrt

/** Result of comparing a session against the immutable baseline (ARCHITECTURE §8.4). */
public sealed interface Comparison {
    public data class Refused(val reason: String) : Comparison
    public data class Result(
        val baselineMadDeg: Double,
        val sessionMadDeg: Double,
        val deltaDeg: Double,
        val improvementPct: Double,
        val lowConfidence: Boolean,
        val mdcDeg: Double,
        val withinMdc: Boolean,
    ) : Comparison
}

public object BaselineComparison {
    /**
     * @param sessionPosition body position of the session; must match the baseline's.
     * @param sessionExercise exercise family of the session; a baseline captured with [ExerciseType.BASELINE_CAPTURE]
     *   is comparable with static hold exercises only.
     */
    public fun compare(
        baseline: Baseline,
        session: DeviationMetrics,
        sessionPosition: BodyPosition,
        sessionExercise: ExerciseType,
        mdcDeg: Double,
        minValidSeconds: Double = 60.0,
    ): Comparison {
        val measured = baseline.measured ?: return Comparison.Refused("Baseline has no headset measurement")
        if (!positionsComparable(baseline.position, sessionPosition)) {
            return Comparison.Refused("Body position differs: baseline ${baseline.position}, session $sessionPosition")
        }
        if (!exercisesComparable(baseline.exercise, sessionExercise)) {
            return Comparison.Refused("Exercise type not comparable with baseline: $sessionExercise")
        }
        if (measured.madDeg.isNaN() || measured.madDeg <= 0.0) return Comparison.Refused("Baseline MAD is not positive")
        if (session.madDeg.isNaN() || session.validSamples == 0L) return Comparison.Refused("Session has no valid samples")
        val delta = session.madDeg - measured.madDeg
        return Comparison.Result(
            baselineMadDeg = measured.madDeg,
            sessionMadDeg = session.madDeg,
            deltaDeg = delta,
            improvementPct = 100.0 * (measured.madDeg - session.madDeg) / measured.madDeg,
            lowConfidence = session.validDurationS < minValidSeconds,
            mdcDeg = mdcDeg,
            withinMdc = abs(delta) < mdcDeg,
        )
    }

    public fun positionsComparable(a: BodyPosition, b: BodyPosition): Boolean = a == b

    /** The baseline is a static seated hold; only static hold exercises are compared against it. */
    public fun exercisesComparable(baselineExercise: ExerciseType, sessionExercise: ExerciseType): Boolean {
        val staticHolds = setOf(ExerciseType.BASELINE_CAPTURE, ExerciseType.MIDLINE_TRAINING, ExerciseType.SITTING_HOLD, ExerciseType.STANDING_HOLD)
        return if (baselineExercise == ExerciseType.BASELINE_CAPTURE) sessionExercise in staticHolds else baselineExercise == sessionExercise
    }
}

/**
 * Minimal detectable change from test–retest pairs (ARCHITECTURE §8.4, Phase 8a).
 * SEM = SD(differences) / sqrt(2); MDC95 = 1.96 * sqrt(2) * SEM = 1.96 * SD(differences).
 */
public object MinimalDetectableChange {
    public fun fromTestRetest(first: DoubleArray, second: DoubleArray): Double {
        require(first.size == second.size && first.size >= 2) { "need paired measurements" }
        val diffs = DoubleArray(first.size) { first[it] - second[it] }
        val mean = diffs.average()
        val variance = diffs.sumOf { (it - mean) * (it - mean) } / (diffs.size - 1)
        return 1.96 * sqrt(variance)
    }

    /** Combine instrument accuracy (jig RMS) with biological test–retest in quadrature. */
    public fun combined(jigRmsDeg: Double, testRetestMdcDeg: Double): Double = sqrt(jigRmsDeg * jigRmsDeg + testRetestMdcDeg * testRetestMdcDeg)
}
