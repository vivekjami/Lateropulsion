package com.lateropulsion.feature.metrics

import com.lateropulsion.core.common.EngineVersions
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.DeviationMetrics
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.Episode
import com.lateropulsion.core.model.EpisodeStats
import com.lateropulsion.core.model.SessionId
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.VisualMode
import kotlin.math.abs
import kotlin.math.sqrt

/** Combines per-block results into one version-stamped [SessionSummary] (REQ-MET-040). */
public object SessionSummarizer {
    public fun summarize(
        sessionId: SessionId,
        blocks: List<BlockResult>,
        baseline: Baseline?,
        episodes: List<Episode>,
        gainUsed: Double,
        visualMode: VisualMode,
        endReason: EndReason,
        balanceLossEvents: Int,
        assistanceLevel: AssistanceLevel?,
        mdcDeg: Double,
        minValidSeconds: Double,
        nowUtc: Long,
    ): SessionSummary {
        val metrics = merge(blocks.map { it.metrics })
        val comparison = if (baseline != null && blocks.isNotEmpty()) {
            // Compare using the dominant (longest valid) static block's position/exercise.
            val ref = blocks.maxBy { it.metrics.validDurationS }
            BaselineComparison.compare(baseline, metrics, ref.position, ref.exercise, mdcDeg, minValidSeconds)
        } else {
            null
        }
        val result = comparison as? Comparison.Result
        val refused = comparison as? Comparison.Refused
        return SessionSummary(
            sessionId = sessionId,
            baselineId = baseline?.id,
            baselineMadDeg = baseline?.measured?.madDeg,
            metrics = metrics,
            episodes = EpisodeDetector.stats(episodes),
            deltaDeg = result?.deltaDeg,
            improvementPct = result?.improvementPct,
            withinMdc = result?.withinMdc,
            mdcDeg = mdcDeg,
            lowConfidence = metrics.validDurationS < minValidSeconds,
            comparisonRefusedReason = refused?.reason,
            balanceLossEvents = balanceLossEvents,
            assistanceLevel = assistanceLevel,
            gainUsed = gainUsed,
            visualMode = visualMode,
            endReason = endReason,
            generatedByVersion = EngineVersions.METRICS_ENGINE,
            generatedAt = nowUtc,
        )
    }

    /**
     * Exact pooled statistics across blocks: sums are recovered from the per-block means and counts,
     * so the session MAD/RMS equal what a single accumulator over all samples would produce.
     * The pooled SD uses the exact parallel-variance formula.
     */
    public fun merge(parts: List<DeviationMetrics>): DeviationMetrics {
        val valid = parts.filter { it.validSamples > 0 }
        val total = parts.sumOf { it.totalSamples }
        if (valid.isEmpty()) return DeviationMetrics.EMPTY.copy(totalSamples = total)
        val n = valid.sumOf { it.validSamples }
        val sum = valid.sumOf { it.meanDeg * it.validSamples }
        val sumAbs = valid.sumOf { it.madDeg * it.validSamples }
        val sumSq = valid.sumOf { it.rmsDeg * it.rmsDeg * it.validSamples }
        val mean = sum / n
        var m2 = 0.0
        for (p in valid) {
            val k = p.validSamples
            val partM2 = if (k >= 2 && !p.sdDeg.isNaN()) p.sdDeg * p.sdDeg * (k - 1) else 0.0
            val d = p.meanDeg - mean
            m2 += partM2 + d * d * k
        }
        val duration = valid.sumOf { it.validDurationS }
        val pathLength = valid.sumOf { it.pathLengthDeg }
        // Mean velocity: weight each block's velocity by its own pair-time (≈ valid duration).
        val velTime = valid.filter { !it.meanVelocityDegS.isNaN() }.sumOf { it.validDurationS }
        val meanVel = if (velTime == 0.0) Double.NaN else valid.filter { !it.meanVelocityDegS.isNaN() }.sumOf { it.meanVelocityDegS * it.validDurationS } / velTime
        return DeviationMetrics(
            meanDeg = mean,
            madDeg = sumAbs / n,
            rmsDeg = sqrt(sumSq / n),
            maxDeg = valid.maxOf { it.maxDeg },
            sdDeg = if (n < 2) Double.NaN else sqrt(m2 / (n - 1)),
            tib5Pct = valid.sumOf { it.tib5Pct * it.validSamples } / n,
            tib10Pct = valid.sumOf { it.tib10Pct * it.validSamples } / n,
            timeOutOfBandS = valid.sumOf { it.timeOutOfBandS },
            pathLengthDeg = pathLength,
            meanVelocityDegS = meanVel,
            symmetryIndex = if (sumAbs == 0.0) 0.0 else sum / sumAbs,
            validSamples = n,
            totalSamples = total,
            validDurationS = duration,
        )
    }

    public fun episodeStats(episodes: List<Episode>): EpisodeStats = EpisodeDetector.stats(episodes)

    /** Used by reports: is a change large enough to mean anything? */
    public fun isMeaningful(deltaDeg: Double?, mdcDeg: Double): Boolean = deltaDeg != null && abs(deltaDeg) >= mdcDeg
}
