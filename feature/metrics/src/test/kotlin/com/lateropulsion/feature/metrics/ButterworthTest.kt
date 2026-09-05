package com.lateropulsion.feature.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ButterworthTest {
    private fun gainDb(sections: List<Biquad>, fHz: Double, fs: Double): Double {
        val w = 2 * PI * fHz / fs
        var re = 1.0; var im = 0.0
        for (s in sections) {
            val zr = cos(-w); val zi = sin(-w)
            val z2r = cos(-2 * w); val z2i = sin(-2 * w)
            val nr = s.b0 + s.b1 * zr + s.b2 * z2r; val ni = s.b1 * zi + s.b2 * z2i
            val dr = 1 + s.a1 * zr + s.a2 * z2r; val di = s.a1 * zi + s.a2 * z2i
            val den = dr * dr + di * di
            val hr = (nr * dr + ni * di) / den; val hi = (ni * dr - nr * di) / den
            val r = re * hr - im * hi; val i = re * hi + im * hr
            re = r; im = i
        }
        return 20 * log10(sqrt(re * re + im * im))
    }

    @Test
    fun `REQ-MET-010 fourth order design is minus 3 dB at cutoff with unity DC gain`() {
        val s = Butterworth.lowpass(4, 5.0, 100.0)
        assertEquals(2, s.size)
        assertEquals(-3.0103, gainDb(s, 5.0, 100.0), 1e-3)
        assertEquals(0.0, gainDb(s, 0.001, 100.0), 1e-6)
        assertTrue(gainDb(s, 10.0, 100.0) < -20.0) // 4th order: -24 dB/octave
        assertTrue(gainDb(s, 20.0, 100.0) < -44.0)
    }

    @Test
    fun `second order live filter is minus 3 dB at cutoff and has bounded group delay`() {
        val s = Butterworth.lowpass(2, 5.0, 100.0)
        assertEquals(-3.0103, gainDb(s, 5.0, 100.0), 1e-3)
        val gd = Butterworth.dcGroupDelayS(s, 100.0)
        assertTrue(gd > 0.03 && gd < 0.06, "group delay $gd s should be ~45 ms for a 5 Hz 2nd-order Butterworth")
    }

    @Test
    fun `REQ-MET-011 zero-phase filter introduces no delay on a symmetric pulse`() {
        val n = 401
        val x = DoubleArray(n) { i -> val d = (i - 200) / 20.0; kotlin.math.exp(-d * d) * 10 }
        val y = ZeroPhaseFilter.filtfilt(Butterworth.lowpass(4, 5.0, 100.0), x)
        var peak = 0; for (i in y.indices) if (y[i] > y[peak]) peak = i
        assertEquals(200, peak)
        for (k in 1..150) assertEquals(y[200 - k], y[200 + k], 1e-9) // symmetric output
    }

    @Test
    fun `causal filter is primed at steady state so a constant passes without a start-up swing`() {
        val f = CausalFilter.live(100.0)
        for (i in 0 until 50) assertEquals(12.0, f.step(12.0), 1e-9)
    }

    @Test
    fun `short signals are filtered without padding`() {
        val y = ZeroPhaseFilter.filtfilt(Butterworth.lowpass(4, 5.0, 50.0), DoubleArray(10) { 3.0 })
        assertEquals(10, y.size)
        assertTrue(y.all { abs(it) <= 3.0 + 1e-9 })
    }
}
