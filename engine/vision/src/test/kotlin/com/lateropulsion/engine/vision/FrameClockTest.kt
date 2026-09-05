package com.lateropulsion.engine.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameClockTest {
    @Test
    fun `fps jitter and drop estimate`() {
        val c = FrameClock(30.0)
        var t = 0L
        repeat(60) { c.onFrame(t); t += 33_333_333L }
        assertEquals(30.0, c.fps, 0.01)
        assertTrue(c.jitterMs < 0.01)
        assertEquals(0L, c.droppedEstimate)
        t += 100_000_000L // a 133 ms gap ≈ 3 missed frames
        c.onFrame(t)
        assertEquals(3L, c.droppedEstimate)
        assertTrue(c.droppedPct > 4.0 && c.droppedPct < 5.0)
    }

    @Test
    fun `REQ-SAF-031 watchdog reports a stall after 200 ms without frames`() {
        val c = FrameClock(30.0)
        val w = CameraWatchdog(200.0)
        assertFalse(w.isStalled(c, 0L)) // never streamed: not a stall
        c.onFrame(1_000_000_000L)
        assertFalse(w.isStalled(c, 1_150_000_000L))
        assertTrue(w.isStalled(c, 1_250_000_000L))
    }
}
