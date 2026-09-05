package com.lateropulsion.engine.render

import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.VisualMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class RenderMathTest {
    @Test
    fun `REQ-VIS-001 correction is slew limited and prediction is clamped`() {
        val c = CorrectionTransform(slewLimitDegPerS = 30.0, predictionClampS = 0.05)
        val a1 = c.update(gain = 1.0, thetaPredDeg = 20.0, dtS = 1.0 / 60)
        assertEquals(-0.5, a1, 1e-9) // 30°/s × 16.7 ms
        var a = a1
        repeat(200) { a = c.update(1.0, 20.0, 1.0 / 60) }
        assertEquals(-20.0, a, 1e-9)
        assertEquals(10.0 + 100.0 * 0.05, c.predictTheta(10.0, 100.0, 0.5), 1e-9) // clamped to 50 ms
        assertEquals(10.5, c.predictTheta(10.0, 50.0, 0.01), 1e-9)
        c.reset()
        assertEquals(0.0, c.appliedDeg)
    }

    @Test
    fun `REQ-SAF-032 watchdog trips after three consecutive slow frames only`() {
        val w = RenderWatchdog(60.0, 3)
        assertFalse(w.onFrame(70.0)); assertFalse(w.onFrame(70.0)); assertFalse(w.onFrame(40.0))
        assertFalse(w.onFrame(70.0)); assertFalse(w.onFrame(70.0)); assertTrue(w.onFrame(70.0))
        assertEquals(1, w.degradedEvents)
        assertEquals(70.0, w.worstMs)
        assertEquals(100.0 * 5 / 6, w.slowPct, 1e-9)
        assertEquals(45.0 + 6.0 + 2 * 16.667, LatencyEstimate.motionToPhotonMs(45.0, 6.0, 16.667), 1e-9)
    }

    @Test
    fun `REQ-VIS-010 distortion mesh keeps the centre fixed and grows radially`() {
        val m = DistortionMesh.forEye(true, 16, 0.22, 0.24, 0.996, 1.004, aspect = 1f)
        assertEquals(17 * 17, m.vertexCount)
        assertEquals(16 * 16 * 6, m.indices.size)
        val cIdx = 8 * 17 + 8
        assertEquals(0f, m.positions[cIdx * 2], 1e-6f); assertEquals(0f, m.positions[cIdx * 2 + 1], 1e-6f)
        assertEquals(0.25f, m.texG[cIdx * 2], 1e-6f); assertEquals(0.5f, m.texG[cIdx * 2 + 1], 1e-6f) // left eye centre → u = 0.25
        assertTrue(m.distort(0.0) == 1.0)
        assertTrue(m.distort(0.5) < m.distort(1.0))
        // red samples closer to the centre than green than blue (chromatic aberration)
        val edge = 8 * 17 + 16
        val gx = m.texG[edge * 2] - 0.25f; val rx = m.texR[edge * 2] - 0.25f; val bx = m.texB[edge * 2] - 0.25f
        assertTrue(abs(rx) < abs(gx) && abs(gx) < abs(bx))
        // symmetric about the centre
        val left = 8 * 17 + 0
        assertEquals(m.texG[edge * 2] - 0.25f, -(m.texG[left * 2] - 0.25f), 1e-6f)
    }

    private fun state(cues: Set<CueType>) = RenderState(mode = VisualMode.VERTICAL_REFERENCE, cues = cues, toleranceDeg = 10.0, idle = false)

    @Test
    fun `REQ-VIS-020 plumb line is gravity locked - a right roll draws it leaning left`() {
        val plumb0 = OverlayGeometry.build(OverlayInput(0.0, 0.0, state(setOf(CueType.PLUMB_LINE)), true)).single() as Primitive.Line
        assertEquals(0f, plumb0.x2, 1e-6f)
        val plumb30 = OverlayGeometry.build(OverlayInput(30.0, 30.0, state(setOf(CueType.PLUMB_LINE)), true)).single() as Primitive.Line
        val top = if (plumb30.y2 > plumb30.y1) plumb30.x2 to plumb30.y2 else plumb30.x1 to plumb30.y1
        assertTrue(top.first < 0f, "top of the plumb line should lean left for a right roll, x=${top.first}")
        val angle = Math.toDegrees(Math.atan2(-top.first.toDouble(), top.second.toDouble()))
        assertEquals(30.0, angle, 1e-3)
        // length preserved
        val len = sqrt(((plumb30.x2 - plumb30.x1) * (plumb30.x2 - plumb30.x1) + (plumb30.y2 - plumb30.y1) * (plumb30.y2 - plumb30.y1)).toDouble())
        assertEquals(1.9, len, 1e-4)
    }

    @Test
    fun `REQ-VIS-021 tolerance band colour follows deviation and validity, idle draws nothing`() {
        fun color(theta: Double, valid: Boolean = true) = (OverlayGeometry.build(OverlayInput(theta, theta, state(setOf(CueType.TOLERANCE_BAND)), valid)).first() as Primitive.Wedge).color
        assertEquals(Rgba.GREEN, color(2.0))
        assertEquals(Rgba.AMBER, color(8.0))
        assertEquals(Rgba.RED, color(15.0))
        assertEquals(Rgba.GREY, color(2.0, valid = false))
        assertTrue(OverlayGeometry.build(OverlayInput(5.0, 5.0, RenderState.NEUTRAL, true)).isEmpty())
    }

    @Test
    fun `tessellator produces triangles without allocation beyond its buffer`() {
        val t = OverlayTessellator(capacityVertices = 5000)
        t.add(OverlayGeometry.build(OverlayInput(12.0, 12.0, state(CueType.entries.toSet()), true)))
        t.number(-12.0, 0f, 0f, 0.1f, Rgba.WHITE)
        assertTrue(t.vertexCount > 100 && t.vertexCount % 3 == 0)
        assertFalse(t.overflowed)
        val tiny = OverlayTessellator(capacityVertices = 30)
        tiny.add(Primitive.Wedge(0f, 0f, 1f, 0f, 3f, Rgba.RED))
        assertTrue(tiny.overflowed)
    }

    @Test
    fun `band edge detector debounces`() {
        val d = BandEdgeDetector(300_000_000L)
        assertEquals(BandEdgeDetector.Edge.NONE, d.feed(0L, true))
        assertEquals(BandEdgeDetector.Edge.EXIT, d.feed(1_000_000_000L, false))
        assertEquals(BandEdgeDetector.Edge.NONE, d.feed(1_100_000_000L, true)) // too soon
        assertEquals(BandEdgeDetector.Edge.RETURN, d.feed(1_400_000_000L, true))
        val (pan, loud) = OverlayGeometry.audioPan(-15.0, 5.0)
        assertEquals(-1f, pan); assertEquals(1f, loud)
    }
}
