package com.lateropulsion.feature.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TiltSchedulerTest {
    private fun entry(tilt: Double) = Fixtures.entry("std-sitting-v3", 70.0).copy(appliedTiltDeg = tilt)

    @Test
    fun `REQ-VIS-016 the picture tilt starts at the entered error and fades by a fixed fraction per session`() {
        assertEquals(-12.0, TiltScheduler.proposeNext(-12.0, emptyList(), 0.15), 1e-9)
        assertEquals(-12.0 + 1.8, TiltScheduler.proposeNext(-12.0, listOf(entry(-12.0)), 0.15), 1e-9)
        assertEquals(-12.0 + 3.6, TiltScheduler.proposeNext(-12.0, listOf(entry(-12.0), entry(-10.2)), 0.15), 1e-9)
        // an operator override carries forward
        assertEquals(-4.0 + 1.8, TiltScheduler.proposeNext(-12.0, listOf(entry(-4.0)), 0.15), 1e-9)
        // never below zero, never above the (re-entered) error, zero error stays zero
        assertEquals(0.0, TiltScheduler.proposeNext(-12.0, listOf(entry(-1.0)), 0.15), 1e-9)
        assertEquals(6.0, TiltScheduler.proposeNext(6.0, listOf(entry(12.0)), 0.15), 1e-9)
        assertEquals(0.0, TiltScheduler.proposeNext(0.0, listOf(entry(5.0)), 0.15), 1e-9)
        assertEquals(1.0, TiltScheduler.fractionForSession(1, 0.15), 1e-9)
        assertEquals(0.7, TiltScheduler.fractionForSession(3, 0.15), 1e-9)
        assertEquals(0.0, TiltScheduler.fractionForSession(20, 0.15), 1e-9)
    }
}
