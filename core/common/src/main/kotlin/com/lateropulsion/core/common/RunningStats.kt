package com.lateropulsion.core.common

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Welford's online mean/variance with running min/max/abs sums.
 * O(1) memory regardless of session length (ARCHITECTURE §8.5).
 */
public class RunningStats {
    public var count: Long = 0
        private set
    public var mean: Double = 0.0
        private set
    private var m2: Double = 0.0
    public var min: Double = Double.POSITIVE_INFINITY
        private set
    public var max: Double = Double.NEGATIVE_INFINITY
        private set
    public var sumAbs: Double = 0.0
        private set
    public var sumSquares: Double = 0.0
        private set
    public var maxAbs: Double = 0.0
        private set

    public fun add(x: Double) {
        count++
        val delta = x - mean
        mean += delta / count
        m2 += delta * (x - mean)
        if (x < min) min = x
        if (x > max) max = x
        val ax = abs(x)
        sumAbs += ax
        sumSquares += x * x
        maxAbs = max(maxAbs, ax)
    }

    /** Sample variance (n-1). NaN when fewer than two samples. */
    public val variance: Double get() = if (count < 2) Double.NaN else m2 / (count - 1)
    public val populationVariance: Double get() = if (count < 1) Double.NaN else m2 / count
    public val sd: Double get() = sqrt(variance)
    public val meanAbs: Double get() = if (count == 0L) Double.NaN else sumAbs / count
    public val rms: Double get() = if (count == 0L) Double.NaN else sqrt(sumSquares / count)

    public fun reset() {
        count = 0; mean = 0.0; m2 = 0.0
        min = Double.POSITIVE_INFINITY; max = Double.NEGATIVE_INFINITY
        sumAbs = 0.0; sumSquares = 0.0; maxAbs = 0.0
    }
}

/** Ordinary least squares y = a + b x, online. Used for drift slope estimation (deg/min). */
public class LinearRegression {
    private var n = 0L
    private var sx = 0.0
    private var sy = 0.0
    private var sxx = 0.0
    private var sxy = 0.0

    public fun add(x: Double, y: Double) {
        n++; sx += x; sy += y; sxx += x * x; sxy += x * y
    }

    public val count: Long get() = n

    /** Slope b; NaN if fewer than two distinct x. */
    public val slope: Double
        get() {
            if (n < 2) return Double.NaN
            val denom = n * sxx - sx * sx
            return if (abs(denom) < EPS) Double.NaN else (n * sxy - sx * sy) / denom
        }

    public val intercept: Double
        get() {
            val b = slope
            return if (b.isNaN()) Double.NaN else (sy - b * sx) / n
        }

    public fun reset() { n = 0; sx = 0.0; sy = 0.0; sxx = 0.0; sxy = 0.0 }

    private companion object { const val EPS = 1e-12 }
}
