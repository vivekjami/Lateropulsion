package com.lateropulsion.feature.metrics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan

/**
 * Butterworth low-pass design as cascaded second-order sections (SOS) via the bilinear transform
 * with frequency pre-warping, so the -3 dB point lands exactly on [cutoffHz].
 *
 * The exact same construction is implemented in tools/analysis/reference_metrics.py; the golden
 * tests hold the two to 1e-9 (REQ-MET-030).
 */
public class Biquad(public val b0: Double, public val b1: Double, public val b2: Double, public val a1: Double, public val a2: Double) {
    /** Direct-form II transposed, one sample; state in [z]. */
    public fun step(x: Double, z: DoubleArray): Double {
        val y = b0 * x + z[0]
        z[0] = b1 * x - a1 * y + z[1]
        z[1] = b2 * x - a2 * y
        return y
    }
}

public object Butterworth {
    /** Even-order low-pass only; order 2 and 4 are what the product uses. */
    public fun lowpass(order: Int, cutoffHz: Double, sampleRateHz: Double): List<Biquad> {
        require(order > 0 && order % 2 == 0) { "order must be a positive even number" }
        require(cutoffHz > 0 && cutoffHz < sampleRateHz / 2) { "cutoff must be below Nyquist" }
        val w0 = tan(PI * cutoffHz / sampleRateHz) // pre-warped, normalised to K = 1
        val sections = ArrayList<Biquad>(order / 2)
        for (k in 0 until order / 2) {
            // Pole angle for the k-th conjugate pair of an order-N Butterworth prototype.
            val angle = PI * (2.0 * k + order + 1.0) / (2.0 * order)
            val invQ = -2.0 * cos(angle)
            val a0 = 1.0 + invQ * w0 + w0 * w0
            val b0 = w0 * w0 / a0
            sections += Biquad(
                b0 = b0,
                b1 = 2.0 * b0,
                b2 = b0,
                a1 = (2.0 * w0 * w0 - 2.0) / a0,
                a2 = (1.0 - invQ * w0 + w0 * w0) / a0,
            )
        }
        return sections
    }

    /** Group delay in seconds at (near) DC, by finite-difference of the cascade phase response. */
    public fun dcGroupDelayS(sections: List<Biquad>, sampleRateHz: Double): Double {
        val w1 = 1e-4
        val w2 = 2e-4
        val p1 = phase(sections, w1)
        val p2 = phase(sections, w2)
        return -(p2 - p1) / (w2 - w1) / sampleRateHz
    }

    private fun phase(sections: List<Biquad>, w: Double): Double {
        var re = 1.0
        var im = 0.0
        for (s in sections) {
            // H(e^jw) = (b0 + b1 e^-jw + b2 e^-2jw) / (1 + a1 e^-jw + a2 e^-2jw)
            val c1 = cos(w); val s1 = -kotlin.math.sin(w)
            val c2 = cos(2 * w); val s2 = -kotlin.math.sin(2 * w)
            val nr = s.b0 + s.b1 * c1 + s.b2 * c2
            val ni = s.b1 * s1 + s.b2 * s2
            val dr = 1.0 + s.a1 * c1 + s.a2 * c2
            val di = s.a1 * s1 + s.a2 * s2
            // (nr + j ni) / (dr + j di)
            val den = dr * dr + di * di
            val hr = (nr * dr + ni * di) / den
            val hi = (ni * dr - nr * di) / den
            val r2 = re * hr - im * hi
            val i2 = re * hi + im * hr
            re = r2; im = i2
        }
        return kotlin.math.atan2(im, re)
    }
}

/** Causal filter for the live display. Group delay is documented, not hidden (ARCHITECTURE §8.1). */
public class CausalFilter(private val sections: List<Biquad>) {
    private val state = Array(sections.size) { DoubleArray(2) }
    private var primed = false

    public fun step(x: Double): Double {
        if (!primed) prime(x)
        var v = x
        for (i in sections.indices) v = sections[i].step(v, state[i])
        return v
    }

    /** Start from steady state at the first sample instead of from zero, avoiding a start-up swing. */
    private fun prime(x0: Double) {
        primed = true
        var v = x0
        for (i in sections.indices) {
            val s = sections[i]
            // Steady-state DF2T state for constant input v with DC gain g = (b0+b1+b2)/(1+a1+a2).
            val g = (s.b0 + s.b1 + s.b2) / (1.0 + s.a1 + s.a2)
            val y = v * g
            state[i][1] = s.b2 * v - s.a2 * y
            state[i][0] = s.b1 * v - s.a1 * y + state[i][1]
            v = y
        }
    }

    public fun reset() {
        for (z in state) { z[0] = 0.0; z[1] = 0.0 }
        primed = false
    }

    public companion object {
        public fun live(sampleRateHz: Double, cutoffHz: Double = 5.0): CausalFilter =
            CausalFilter(Butterworth.lowpass(2, cutoffHz, sampleRateHz))
    }
}

/**
 * Zero-phase (forward–backward) filtering for summary metrics.
 * Algorithm, fixed for reproducibility:
 *  1. odd-reflection padding of `padLen = 3 * (2 * nSections + 1)` samples at both ends (skipped if the
 *     signal is not longer than padLen);
 *  2. all sections forward, zero initial state;
 *  3. reverse, all sections forward again, reverse;
 *  4. strip padding.
 */
public object ZeroPhaseFilter {
    public fun padLen(sections: List<Biquad>): Int = 3 * (2 * sections.size + 1)

    public fun filtfilt(sections: List<Biquad>, x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return DoubleArray(0)
        val pad = if (x.size > padLen(sections)) padLen(sections) else 0
        val n = x.size
        val ext = DoubleArray(n + 2 * pad)
        for (i in 0 until pad) ext[i] = 2.0 * x[0] - x[pad - i]
        System.arraycopy(x, 0, ext, pad, n)
        for (i in 0 until pad) ext[pad + n + i] = 2.0 * x[n - 1] - x[n - 2 - i]

        forward(sections, ext)
        ext.reverse()
        forward(sections, ext)
        ext.reverse()
        return ext.copyOfRange(pad, pad + n)
    }

    private fun forward(sections: List<Biquad>, v: DoubleArray) {
        for (s in sections) {
            val z = DoubleArray(2)
            for (i in v.indices) v[i] = s.step(v[i], z)
        }
    }
}
