package com.lateropulsion.feature.metrics

import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineId
import com.lateropulsion.core.model.BaselineMeasurement
import com.lateropulsion.core.model.BlockEndReason
import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.BlockResultId
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.CorrectionAbility
import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.EpisodeStats
import com.lateropulsion.core.model.ExerciseType
import com.lateropulsion.core.model.FallRisk
import com.lateropulsion.core.model.FilterParams
import com.lateropulsion.core.model.MidlineAwareness
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.SessionId
import com.lateropulsion.core.model.Severity
import com.lateropulsion.core.model.SittingBalance
import com.lateropulsion.core.model.StandingBalance
import com.lateropulsion.core.model.VisualMode
import com.lateropulsion.core.model.WalkingAbility
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AccumulatorAndMergeTest {
    @Test
    fun `REQ-MET-001 accumulator matches brute-force formulas`() = runBlocking {
        checkAll(50, Arb.list(Arb.pair(Arb.double(-40.0, 40.0), Arb.boolean()), 2..400)) { xs ->
            val acc = MetricsAccumulator(targetDeg = 1.0, toleranceDeg = 5.0, sampleIntervalS = 0.02)
            xs.forEach { (v, ok) -> acc.add(v, ok) }
            val d = xs.filter { it.second }.map { it.first - 1.0 }
            val m = acc.snapshot()
            assertEquals(xs.size.toLong(), m.totalSamples)
            assertEquals(d.size.toLong(), m.validSamples)
            if (d.isNotEmpty()) {
                assertEquals(d.map { abs(it) }.average(), m.madDeg, 1e-9)
                assertEquals(sqrt(d.map { it * it }.average()), m.rmsDeg, 1e-9)
                assertEquals(d.maxOf { abs(it) }, m.maxDeg, 1e-12)
                assertEquals(100.0 * d.count { abs(it) <= 5.0 } / d.size, m.tib5Pct, 1e-9)
                assertEquals(100.0 * d.count { abs(it) <= 10.0 } / d.size, m.tib10Pct, 1e-9)
                assertEquals(d.count { abs(it) > 5.0 } * 0.02, m.timeOutOfBandS, 1e-9)
                // path length over consecutive valid pairs only
                var path = 0.0
                for (i in 1 until xs.size) if (xs[i].second && xs[i - 1].second) path += abs(xs[i].first - xs[i - 1].first)
                assertEquals(path, m.pathLengthDeg, 1e-9)
            }
        }
    }

    private fun block(metrics: com.lateropulsion.core.model.DeviationMetrics, position: BodyPosition = BodyPosition.SITTING_UNSUPPORTED) = BlockResult(
        id = BlockResultId("b"), sessionId = SessionId("s"), blockId = "x", orderIndex = 0, exercise = ExerciseType.SITTING_HOLD,
        position = position, startedMonoNs = 0, durationS = 60.0, targetDeg = 0.0, toleranceDeg = 5.0, gain = 0.0,
        cues = listOf(CueType.PLUMB_LINE), metrics = metrics, episodes = EpisodeStats.NONE, episodeList = emptyList(),
        checkpoints = emptyList(), endReason = BlockEndReason.COMPLETED, filterParams = FilterParams.summary(50.0),
    )

    @Test
    fun `REQ-MET-040 merging block metrics equals one accumulator over all samples`() = runBlocking {
        checkAll(30, Arb.list(Arb.list(Arb.pair(Arb.double(-30.0, 30.0), Arb.boolean()), 3..200), 1..5)) { blocks ->
            val whole = MetricsAccumulator(0.0, 5.0, sampleIntervalS = 0.02)
            val parts = blocks.map { b ->
                val acc = MetricsAccumulator(0.0, 5.0, sampleIntervalS = 0.02)
                b.forEach { (v, ok) -> acc.add(v, ok); whole.add(v, ok) }
                // reset pair tracking between blocks like the real runtime does
                acc.snapshot()
            }
            val merged = SessionSummarizer.merge(parts)
            val w = whole.snapshot()
            assertEquals(w.totalSamples, merged.totalSamples)
            assertEquals(w.validSamples, merged.validSamples)
            if (w.validSamples > 0) {
                assertEquals(w.madDeg, merged.madDeg, 1e-9)
                assertEquals(w.rmsDeg, merged.rmsDeg, 1e-9)
                assertEquals(w.meanDeg, merged.meanDeg, 1e-9)
                assertEquals(w.maxDeg, merged.maxDeg, 1e-12)
                assertEquals(w.tib5Pct, merged.tib5Pct, 1e-9)
                assertEquals(w.timeOutOfBandS, merged.timeOutOfBandS, 1e-9)
                if (w.validSamples >= 2 && parts.all { it.validSamples >= 2 || it.validSamples == 0L }) assertEquals(w.sdDeg, merged.sdDeg, 1e-7)
            }
        }
    }

    private val baseline = Baseline(
        id = BaselineId("bl"), patientId = PatientId("p"), severity = Severity.MODERATE, headDeviationDeg = 12.0, trunkDeviationDeg = 15.0,
        sittingBalance = SittingBalance.SUPPORTED_ONLY, standingBalance = StandingBalance.UNABLE, walkingAbility = WalkingAbility.NON_AMBULANT,
        assistanceLevel = AssistanceLevel.ONE_PERSON, midlineAwareness = MidlineAwareness.PARTIAL, correctionAbility = CorrectionAbility.TOLERATES_PASSIVE,
        fallRisk = FallRisk.HIGH,
        measured = BaselineMeasurement(10.0, 10.0, 10.5, 2.0, 15.0, 5.0, 40.0, 0.1, 98.0, 60.0, emptyList(), emptyList(), FilterParams.summary(50.0), 3000),
        thetaRefDeg = 0.0, thetaRefSetBy = ClinicianId("c"), thetaRefSetAt = 1L, position = BodyPosition.SUPPORTED_SITTING,
        recordedAt = 1L, recordedBy = ClinicianId("c"),
    )

    private fun metrics(mad: Double, durationS: Double) = com.lateropulsion.core.model.DeviationMetrics(
        meanDeg = mad, madDeg = mad, rmsDeg = mad, maxDeg = mad, sdDeg = 1.0, tib5Pct = 50.0, tib10Pct = 80.0, timeOutOfBandS = 0.0,
        pathLengthDeg = 10.0, meanVelocityDegS = 1.0, symmetryIndex = 1.0, validSamples = (durationS * 50).toLong(),
        totalSamples = (durationS * 50).toLong(), validDurationS = durationS,
    )

    @Test
    fun `REQ-RPT-003 comparison refuses a different body position`() {
        val c = BaselineComparison.compare(baseline, metrics(6.0, 120.0), BodyPosition.STANDING, ExerciseType.STANDING_HOLD, mdcDeg = 2.0)
        assertTrue(c is Comparison.Refused)
        assertTrue((c as Comparison.Refused).reason.contains("position"))
    }

    @Test
    fun `REQ-RPT-003 comparison refuses a non-static exercise`() {
        val c = BaselineComparison.compare(baseline, metrics(6.0, 120.0), BodyPosition.SUPPORTED_SITTING, ExerciseType.REACH_TO_TARGET, mdcDeg = 2.0)
        assertTrue(c is Comparison.Refused)
    }

    @Test
    fun `REQ-RPT-004 improvement percent, MDC flag and low-confidence rule`() {
        val c = BaselineComparison.compare(baseline, metrics(6.0, 120.0), BodyPosition.SUPPORTED_SITTING, ExerciseType.SITTING_HOLD, mdcDeg = 2.0) as Comparison.Result
        assertEquals(40.0, c.improvementPct, 1e-9)
        assertEquals(-4.0, c.deltaDeg, 1e-9)
        assertFalse(c.withinMdc)
        assertFalse(c.lowConfidence)
        val small = BaselineComparison.compare(baseline, metrics(9.0, 30.0), BodyPosition.SUPPORTED_SITTING, ExerciseType.SITTING_HOLD, mdcDeg = 2.0) as Comparison.Result
        assertTrue(small.withinMdc)
        assertTrue(small.lowConfidence)
    }

    @Test
    fun `summarizer stamps the engine version and carries the refusal reason`() {
        val s = SessionSummarizer.summarize(
            SessionId("s"), listOf(block(metrics(6.0, 120.0), BodyPosition.STANDING)), baseline, emptyList(), 0.0,
            VisualMode.VERTICAL_REFERENCE, EndReason.COMPLETED, 0, AssistanceLevel.SUPERVISION, 2.0, 60.0, 123L,
        )
        assertEquals("metrics-1.0.0", s.generatedByVersion)
        assertTrue(s.comparisonRefusedReason!!.contains("position"))
        assertEquals(null, s.improvementPct)
        assertEquals(6.0, s.madDeg, 1e-9)
    }

    @Test
    fun `MDC from test-retest pairs`() {
        val a = doubleArrayOf(10.0, 12.0, 8.0, 11.0, 9.0)
        val b = doubleArrayOf(10.5, 11.0, 8.5, 12.0, 9.5)
        val mdc = MinimalDetectableChange.fromTestRetest(a, b)
        assertTrue(mdc > 0.5 && mdc < 3.0, "mdc=$mdc")
        assertEquals(sqrt(1.0 + 4.0), MinimalDetectableChange.combined(1.0, 2.0), 1e-12)
    }
}
