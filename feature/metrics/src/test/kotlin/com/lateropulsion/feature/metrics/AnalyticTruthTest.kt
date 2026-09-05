package com.lateropulsion.feature.metrics

import com.lateropulsion.core.model.ValidityFlags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Metrics against closed-form ground truth, independent of any implementation (REQ-MET-031). */
class AnalyticTruthTest {
    private val fs = 50.0

    private fun trace(dur: Double, f: (Double) -> Double): RawTrace {
        val n = (fs * dur).toInt()
        val t = DoubleArray(n) { it / fs }
        return RawTrace(t, DoubleArray(n) { f(t[it]) }, IntArray(n) { ValidityFlags.VALID })
    }

    @Test
    fun `constant offset gives MAD equal to the offset and no episodes`() {
        val p = TraceProcessor.process(trace(60.0) { 8.0 }, fs, 0.0, 5.0)
        assertEquals(8.0, p.metrics.madDeg, 1e-4)
        assertEquals(8.0, p.metrics.rmsDeg, 1e-4)
        assertEquals(0.0, p.metrics.tib5Pct, 1e-9)
        assertEquals(100.0, p.metrics.tib10Pct, 1e-9)
        assertEquals(1.0, p.metrics.symmetryIndex, 1e-9)
        assertEquals(60.0, p.metrics.timeOutOfBandS, 1e-4)
        assertTrue(p.episodes.isEmpty())
    }

    @Test
    fun `slow sinusoid gives MAD 2A over pi and RMS A over root 2`() {
        val a = 6.0
        val p = TraceProcessor.process(trace(60.0) { a * sin(2 * PI * 0.2 * it) }, fs, 0.0, 5.0)
        assertEquals(2 * a / PI, p.metrics.madDeg, 0.02)
        assertEquals(a / sqrt(2.0), p.metrics.rmsDeg, 0.02)
        assertEquals(a, p.metrics.maxDeg, 0.02)
        assertEquals(0.0, p.metrics.symmetryIndex, 0.01)
    }

    @Test
    fun `slow large sinusoid produces two episodes per cycle with correct alternating direction`() {
        val p = TraceProcessor.process(trace(60.0) { 20.0 * sin(2 * PI * 0.05 * it) }, fs, 0.0, 5.0)
        assertEquals(6, p.episodes.size)
        assertEquals(listOf("RIGHT", "LEFT", "RIGHT", "LEFT", "RIGHT", "LEFT"), p.episodes.map { it.direction.name })
        assertTrue(p.episodes.all { !it.partial && it.recoveryS != null })
    }

    @Test
    fun `invalid samples are excluded and reported`() {
        val n = 3000
        val t = DoubleArray(n) { it / fs }
        val theta = DoubleArray(n) { if (t[it] in 10.0..<20.0) 80.0 else 2.0 }
        val flags = IntArray(n) { if (t[it] in 10.0..<20.0) ValidityFlags.VALID or ValidityFlags.PITCH_OUT_OF_RANGE else ValidityFlags.VALID }
        val p = TraceProcessor.process(RawTrace(t, theta, flags), fs, 0.0, 5.0)
        assertEquals(2500L, p.metrics.validSamples)
        assertEquals(3000L, p.metrics.totalSamples)
        assertEquals(2500.0 / 3000.0 * 100.0, p.metrics.validSamplePct, 1e-9)
        assertEquals(2.0, p.metrics.madDeg, 1e-4) // hold-fill keeps the 80° nonsense out of the filter
        assertTrue(p.episodes.isEmpty())
    }
}
