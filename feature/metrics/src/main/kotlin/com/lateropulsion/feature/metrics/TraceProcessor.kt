package com.lateropulsion.feature.metrics

import com.lateropulsion.core.model.Checkpoint
import com.lateropulsion.core.model.DeviationMetrics
import com.lateropulsion.core.model.Episode
import com.lateropulsion.core.model.FilterParams
import com.lateropulsion.core.model.MetricsConfig
import com.lateropulsion.core.model.ValidityFlags

/** Raw block trace: what the `.lpx` log holds for one block. */
public class RawTrace(
    public val tS: DoubleArray,
    public val thetaDeg: DoubleArray,
    public val flags: IntArray,
) {
    init {
        require(tS.size == thetaDeg.size && tS.size == flags.size) { "trace arrays must have equal length" }
    }
    public val size: Int get() = tS.size
}

public class ProcessedTrace(
    public val filteredDeg: DoubleArray,
    public val valid: BooleanArray,
    public val metrics: DeviationMetrics,
    public val episodes: List<Episode>,
    public val filterParams: FilterParams,
)

/**
 * The single source of truth for offline/summary metrics: validity mask → hold-fill → zero-phase
 * 4th-order Butterworth → metrics + episodes on valid samples only (ARCHITECTURE §8.1).
 *
 * The same pipeline is implemented independently in tools/analysis/reference_metrics.py and the two
 * are held equal by golden-file tests (REQ-MET-030).
 */
public object TraceProcessor {
    public fun process(
        trace: RawTrace,
        sampleRateHz: Double,
        targetDeg: Double,
        toleranceDeg: Double,
        config: MetricsConfig = MetricsConfig(),
    ): ProcessedTrace {
        val n = trace.size
        val valid = BooleanArray(n) { ValidityFlags.isValidForMetrics(trace.flags[it]) }
        val filled = holdFill(trace.thetaDeg, valid)
        val sections = Butterworth.lowpass(4, config.lowpassCutoffHz, sampleRateHz)
        val filtered = ZeroPhaseFilter.filtfilt(sections, filled)
        val dt = 1.0 / sampleRateHz

        val acc = MetricsAccumulator(targetDeg, toleranceDeg, config.bandPrimaryDeg, config.bandSecondaryDeg, dt)
        val det = EpisodeDetector(
            enterDeg = config.episodeEnterDeg, exitDeg = config.episodeExitDeg, minDurationS = config.episodeMinDurationS,
            inBandDeg = config.bandPrimaryDeg, holdS = config.recoveryHoldS, targetDeg = targetDeg,
        )
        for (i in 0 until n) {
            acc.add(filtered[i], valid[i])
            det.feed(trace.tS[i], filtered[i], valid[i])
        }
        val episodes = det.finish()
        return ProcessedTrace(
            filteredDeg = filtered,
            valid = valid,
            metrics = acc.snapshot(),
            episodes = episodes,
            filterParams = FilterParams.summary(sampleRateHz, config.lowpassCutoffHz).copy(padLen = ZeroPhaseFilter.padLen(sections)),
        )
    }

    /** Checkpoint snapshots at the requested times, computed from the processed trace. */
    public fun checkpoints(trace: RawTrace, processed: ProcessedTrace, atS: List<Int>, targetDeg: Double, toleranceDeg: Double): List<Checkpoint> {
        val out = ArrayList<Checkpoint>(atS.size)
        for (cp in atS) {
            var idx = -1
            for (i in trace.tS.indices) if (trace.tS[i] >= cp) { idx = i; break }
            if (idx < 0) continue
            var sumAbs = 0.0
            var count = 0
            for (i in 0..idx) if (processed.valid[i]) { sumAbs += kotlin.math.abs(processed.filteredDeg[i] - targetDeg); count++ }
            val theta = processed.filteredDeg[idx]
            out += Checkpoint(
                atS = cp.toDouble(),
                thetaDeg = theta,
                madSoFarDeg = if (count == 0) Double.NaN else sumAbs / count,
                inBand = kotlin.math.abs(theta - targetDeg) <= toleranceDeg,
            )
        }
        return out
    }

    /**
     * Replaces invalid samples with the nearest previous valid value (or the first valid value when the
     * gap is at the start) so the filter sees no discontinuities. Invalid samples remain excluded downstream.
     */
    public fun holdFill(x: DoubleArray, valid: BooleanArray): DoubleArray {
        val out = x.copyOf()
        var firstValid = -1
        for (i in x.indices) if (valid[i]) { firstValid = i; break }
        if (firstValid < 0) return DoubleArray(x.size) // no valid data at all → zeros
        for (i in 0 until firstValid) out[i] = x[firstValid]
        var last = x[firstValid]
        for (i in firstValid until x.size) {
            if (valid[i]) last = x[i] else out[i] = last
        }
        return out
    }
}
