package com.lateropulsion.feature.metrics

import com.lateropulsion.core.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpisodeDetectorTest {
    private val dt = 0.02

    private fun run(values: List<Pair<Double, Boolean>>, det: EpisodeDetector = EpisodeDetector()) =
        values.forEachIndexed { i, (v, ok) -> det.feed(i * dt, v, ok) }.let { det }

    private fun hold(v: Double, s: Double, valid: Boolean = true) = List((s / dt).toInt()) { v to valid }

    @Test
    fun `REQ-MET-014 chatter between exit and enter thresholds never becomes an episode`() {
        val det = run(hold(8.0, 5.0) + hold(9.9, 5.0) + hold(7.5, 5.0))
        assertEquals(EpisodeDetector.State.IN_BAND, det.state)
        assertTrue(det.finish().isEmpty())
    }

    @Test
    fun `REQ-MET-014 excursion shorter than the minimum duration is discarded`() {
        val det = run(hold(0.0, 2.0) + hold(12.0, 0.8) + hold(0.0, 3.0))
        assertTrue(det.finish().isEmpty())
    }

    @Test
    fun `REQ-MET-014 sustained excursion becomes an episode with recovery time`() {
        val det = run(hold(0.0, 2.0) + hold(15.0, 3.0) + hold(6.0, 0.4) + hold(2.0, 1.0) + hold(0.0, 2.0))
        val eps = det.finish()
        assertEquals(1, eps.size)
        val e = eps[0]
        assertEquals(2.0, e.startS, 1e-9)
        assertEquals(5.0, e.endS!!, 1e-9)
        assertEquals(15.0, e.peakDeg, 1e-9)
        assertEquals(Side.RIGHT, e.direction)
        assertFalse(e.partial)
        // recovery: |d| ≤ 5 begins at 5.4 s; hold 0.5 s → 5.9 s → recovery = 3.9 s
        assertEquals(3.9, e.recoveryS!!, 1e-6)
    }

    @Test
    fun `REQ-MET-015 re-entry during recovery continues the same episode`() {
        val det = run(hold(0.0, 1.0) + hold(-14.0, 2.0) + hold(-6.0, 0.3) + hold(-13.0, 1.0) + hold(-1.0, 1.0) + hold(0.0, 1.0))
        val eps = det.finish()
        assertEquals(1, eps.size)
        assertEquals(Side.LEFT, eps[0].direction)
        assertEquals(1.0, eps[0].startS, 1e-9)
        assertEquals(4.3, eps[0].endS!!, 1e-6)
    }

    @Test
    fun `REQ-MET-016 invalid samples inside an episode mark it partial and it is still counted`() {
        val det = run(hold(0.0, 1.0) + hold(14.0, 1.5) + hold(14.0, 0.5, valid = false) + hold(14.0, 1.0) + hold(0.0, 2.0))
        val eps = det.finish()
        assertEquals(1, eps.size)
        assertTrue(eps[0].partial)
        assertNotNull(eps[0].recoveryS)
        val stats = EpisodeDetector.stats(eps)
        assertEquals(1, stats.count)
        assertEquals(1, stats.partialCount)
        assertTrue(stats.meanDurationS.isNaN()) // partial episodes are excluded from means
    }

    @Test
    fun `REQ-MET-016 block ending mid-episode finalises a partial episode without recovery`() {
        val det = run(hold(0.0, 1.0) + hold(14.0, 2.0))
        val eps = det.finish()
        assertEquals(1, eps.size)
        assertTrue(eps[0].partial)
        assertNull(eps[0].recoveryS)
        assertEquals(EpisodeDetector.State.IN_BAND, det.state)
    }

    @Test
    fun `live count includes the episode in progress`() {
        val det = run(hold(0.0, 1.0) + hold(14.0, 2.0))
        assertEquals(1, det.liveCount)
    }

    @Test
    fun `stats over several episodes`() {
        val det = run(
            hold(0.0, 1.0) + hold(14.0, 2.0) + hold(0.0, 2.0) +
                hold(-12.0, 4.0) + hold(0.0, 2.0),
        )
        val stats = EpisodeDetector.stats(det.finish())
        assertEquals(2, stats.count)
        assertEquals(0, stats.partialCount)
        assertEquals(3.0, stats.meanDurationS, 1e-6)
        assertEquals(4.0, stats.maxDurationS, 1e-6)
    }
}
