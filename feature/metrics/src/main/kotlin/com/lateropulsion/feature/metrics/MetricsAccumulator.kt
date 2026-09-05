package com.lateropulsion.feature.metrics

import com.lateropulsion.core.model.DeviationMetrics
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streaming deviation metrics (ARCHITECTURE §8.2, §8.5). Feed every sample; only valid samples
 * contribute to the statistics, but every sample is counted for `valid_sample_pct`.
 *
 * Inputs are already filtered deviations from the patient midline; [targetDeg] is subtracted here.
 */
public class MetricsAccumulator(
    public val targetDeg: Double,
    public val toleranceDeg: Double,
    private val bandPrimaryDeg: Double = 5.0,
    private val bandSecondaryDeg: Double = 10.0,
    private val sampleIntervalS: Double,
) {
    private var total = 0L
    private var n = 0L
    private var sum = 0.0
    private var sumAbs = 0.0
    private var sumSq = 0.0
    private var mean = 0.0
    private var m2 = 0.0
    private var maxAbs = 0.0
    private var inPrimary = 0L
    private var inSecondary = 0L
    private var outOfTolerance = 0L
    private var pathLength = 0.0
    private var pairs = 0L
    private var lastValid = false
    private var lastD = 0.0

    public fun add(thetaDeg: Double, valid: Boolean) {
        total++
        if (!valid) { lastValid = false; return }
        val d = thetaDeg - targetDeg
        n++
        sum += d
        val ad = abs(d)
        sumAbs += ad
        sumSq += d * d
        val delta = d - mean
        mean += delta / n
        m2 += delta * (d - mean)
        if (ad > maxAbs) maxAbs = ad
        if (ad <= bandPrimaryDeg) inPrimary++
        if (ad <= bandSecondaryDeg) inSecondary++
        if (ad > toleranceDeg) outOfTolerance++
        if (lastValid) { pathLength += abs(d - lastD); pairs++ }
        lastValid = true
        lastD = d
    }

    public val validSamples: Long get() = n
    public val totalSamples: Long get() = total
    public val madSoFar: Double get() = if (n == 0L) Double.NaN else sumAbs / n
    public val lastDeviation: Double get() = lastD

    public fun snapshot(): DeviationMetrics {
        if (n == 0L) return DeviationMetrics.EMPTY.copy(totalSamples = total, validSamples = 0)
        val sd = if (n < 2) Double.NaN else sqrt(m2 / (n - 1))
        val symmetry = if (sumAbs == 0.0) 0.0 else sum / sumAbs
        return DeviationMetrics(
            meanDeg = sum / n,
            madDeg = sumAbs / n,
            rmsDeg = sqrt(sumSq / n),
            maxDeg = maxAbs,
            sdDeg = sd,
            tib5Pct = 100.0 * inPrimary / n,
            tib10Pct = 100.0 * inSecondary / n,
            timeOutOfBandS = outOfTolerance * sampleIntervalS,
            pathLengthDeg = pathLength,
            meanVelocityDegS = if (pairs == 0L) Double.NaN else pathLength / (pairs * sampleIntervalS),
            symmetryIndex = symmetry,
            validSamples = n,
            totalSamples = total,
            validDurationS = n * sampleIntervalS,
        )
    }

    public fun reset() {
        total = 0; n = 0; sum = 0.0; sumAbs = 0.0; sumSq = 0.0; mean = 0.0; m2 = 0.0; maxAbs = 0.0
        inPrimary = 0; inSecondary = 0; outOfTolerance = 0; pathLength = 0.0; pairs = 0; lastValid = false; lastD = 0.0
    }
}
