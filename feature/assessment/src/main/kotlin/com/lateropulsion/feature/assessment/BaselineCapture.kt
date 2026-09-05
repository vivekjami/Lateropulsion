package com.lateropulsion.feature.assessment

import com.lateropulsion.core.common.LinearRegression
import com.lateropulsion.core.common.LpError
import com.lateropulsion.core.common.Outcome
import com.lateropulsion.core.model.BaselineMeasurement
import com.lateropulsion.core.model.MetricsConfig
import com.lateropulsion.core.model.ValidityFlags
import com.lateropulsion.feature.metrics.RawTrace
import com.lateropulsion.feature.metrics.TraceProcessor

/**
 * 60 s baseline capture with correction off (README §4.2 step 3). Collects head-roll samples relative
 * to the therapist-set midline and produces the immutable [BaselineMeasurement].
 */
public class BaselineCaptureAccumulator(
    private val sampleRateHz: Double,
    private val config: MetricsConfig = MetricsConfig(),
    private val histogramEdgesDeg: List<Double> = DEFAULT_EDGES,
) {
    private val t = ArrayList<Double>()
    private val theta = ArrayList<Double>()
    private val flags = ArrayList<Int>()

    public fun add(tS: Double, thetaDeg: Double, flags: Int) {
        t += tS; theta += thetaDeg; this.flags += flags
    }

    public val sampleCount: Int get() = t.size
    public val durationS: Double get() = if (t.isEmpty()) 0.0 else t.last() - t.first()

    public fun result(minValidSeconds: Double = 30.0): Outcome<BaselineMeasurement> {
        if (t.isEmpty()) return Outcome.failure(LpError.Precondition("No samples captured"))
        val trace = RawTrace(t.toDoubleArray(), theta.toDoubleArray(), flags.toIntArray())
        val p = TraceProcessor.process(trace, sampleRateHz, targetDeg = 0.0, toleranceDeg = config.bandPrimaryDeg, config = config)
        if (p.metrics.validDurationS < minValidSeconds) {
            return Outcome.failure(LpError.Precondition("Only ${"%.0f".format(p.metrics.validDurationS)} s of valid data; need $minValidSeconds s. Re-instruct the patient and repeat."))
        }
        val reg = LinearRegression()
        for (i in t.indices) if (p.valid[i]) reg.add(t[i] / 60.0, p.filteredDeg[i])
        val hist = IntArray(histogramEdgesDeg.size - 1)
        for (i in t.indices) if (p.valid[i]) {
            val v = p.filteredDeg[i]
            val bin = histogramEdgesDeg.indexOfLast { v >= it }.coerceIn(0, hist.size - 1)
            if (v <= histogramEdgesDeg.last()) hist[bin]++
        }
        return Outcome.success(
            BaselineMeasurement(
                meanDeg = p.metrics.meanDeg,
                madDeg = p.metrics.madDeg,
                rmsDeg = p.metrics.rmsDeg,
                sdDeg = p.metrics.sdDeg,
                maxDeg = p.metrics.maxDeg,
                tib5Pct = p.metrics.tib5Pct,
                tib10Pct = p.metrics.tib10Pct,
                driftDegPerMin = reg.slope.takeIf { !it.isNaN() } ?: 0.0,
                validSamplePct = p.metrics.validSamplePct,
                durationS = p.metrics.validDurationS,
                histogram = hist.toList(),
                binEdgesDeg = histogramEdgesDeg,
                filterParams = p.filterParams,
                sampleCount = t.size.toLong(),
            ),
        )
    }

    public fun reset() { t.clear(); theta.clear(); flags.clear() }

    public companion object {
        /** 5° bins from -40 to +40. */
        public val DEFAULT_EDGES: List<Double> = (-8..8).map { it * 5.0 }
        public fun validFlags(): Int = ValidityFlags.VALID
    }
}
