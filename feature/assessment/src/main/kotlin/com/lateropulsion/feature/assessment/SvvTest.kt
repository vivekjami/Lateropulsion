package com.lateropulsion.feature.assessment

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Subjective visual vertical, method of adjustment (README §6 item 8). The patient rotates a line
 * from a random start until it looks vertical; the signed error is the SVV. Assessment only.
 */
public data class SvvTrial(val startDeg: Double, val setDeg: Double) {
    /** Positive = perceived vertical tilted toward the patient's right. */
    val errorDeg: Double get() = setDeg
}

public data class SvvResult(
    val trials: Int,
    val meanErrorDeg: Double,
    val meanAbsErrorDeg: Double,
    val sdDeg: Double,
    /** Sign of the mean error: +1 right, -1 left, 0 within noise. */
    val direction: Int,
)

public object SvvTest {
    public const val DEFAULT_TRIALS: Int = 6
    public const val DEFAULT_AMPLITUDE_DEG: Double = 20.0

    /** Alternating-sign start angles with jitter so the patient cannot count clicks. */
    public fun startAngles(n: Int = DEFAULT_TRIALS, amplitudeDeg: Double = DEFAULT_AMPLITUDE_DEG, random: Random = Random.Default): List<Double> =
        List(n) { i -> val sign = if (i % 2 == 0) 1.0 else -1.0; sign * (amplitudeDeg * (0.7 + 0.3 * random.nextDouble())) }

    public fun analyse(trials: List<SvvTrial>, noiseDeg: Double = 1.0): SvvResult {
        require(trials.isNotEmpty())
        val errs = trials.map { it.errorDeg }
        val mean = errs.average()
        val sd = if (errs.size < 2) Double.NaN else sqrt(errs.sumOf { (it - mean) * (it - mean) } / (errs.size - 1))
        return SvvResult(
            trials = trials.size,
            meanErrorDeg = mean,
            meanAbsErrorDeg = errs.map { abs(it) }.average(),
            sdDeg = sd,
            direction = when { mean > noiseDeg -> 1; mean < -noiseDeg -> -1; else -> 0 },
        )
    }
}
