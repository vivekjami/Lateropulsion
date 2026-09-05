package com.lateropulsion.engine.sensor

import com.lateropulsion.core.common.Angles
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Resolves the sign constant `s` from two captures: device at neutral, then tilted toward the
 * patient's right by any clear amount (ARCHITECTURE §6.2: `s` is determined empirically, never assumed).
 */
public object SignCalibration {
    public const val MIN_DELTA_DEG: Double = 5.0

    public data class Result(val sign: Int, val deltaDeg: Double, val resolved: Boolean, val message: String)

    public fun resolve(neutralRawDeg: Double, tiltedRightRawDeg: Double): Result {
        val delta = Angles.diffDeg(tiltedRightRawDeg, neutralRawDeg)
        if (abs(delta) < MIN_DELTA_DEG) {
            return Result(0, delta, false, "Tilt of ${"%.1f".format(abs(delta))}° is too small to resolve the sign; tilt at least ${MIN_DELTA_DEG.toInt()}°")
        }
        val s = if (delta > 0) 1 else -1
        return Result(s, delta, true, "Sign resolved: s=$s from a ${"%.1f".format(delta)}° change")
    }
}

/**
 * On-device calibration without a jig: `s` from [SignCalibration] and θ_mount from the neutral capture,
 * trusting the therapist's judgement that the device was upright. Results are flagged FIELD (not jig).
 */
public object FieldCalibration {
    public data class Result(val sign: Int, val thetaMountDeg: Double, val resolved: Boolean, val message: String)

    public fun resolve(neutralRawDeg: Double, tiltedRightRawDeg: Double): Result {
        val s = SignCalibration.resolve(neutralRawDeg, tiltedRightRawDeg)
        if (!s.resolved) return Result(0, 0.0, false, s.message)
        // θ_head(neutral) = 0 = s·θ_raw + θ_mount  →  θ_mount = −s·θ_raw
        val mount = Angles.wrapDeg(-s.sign * neutralRawDeg)
        return Result(s.sign, mount, true, "Field calibration: s=${s.sign}, θ_mount=${"%.1f".format(mount)}° (unqualified until verified on the jig)")
    }
}

/**
 * Least-squares fit of a rotary-jig sweep (ARCHITECTURE §6.4): θ_raw = a·θ_cmd + b with
 * s = sign(a), scale = |a|, θ_mount = −b/a. Residuals are the device's roll error.
 */
public object JigFit {
    public data class Result(
        val sign: Int,
        val scaleError: Double,
        val thetaMountDeg: Double,
        val residualRmsDeg: Double,
        val maxErrorDeg: Double,
        val n: Int,
    ) {
        public val passes: Boolean get() = residualRmsDeg <= 1.0 && maxErrorDeg <= 2.0
    }

    public fun fit(commandedDeg: DoubleArray, measuredRawDeg: DoubleArray): Result {
        require(commandedDeg.size == measuredRawDeg.size && commandedDeg.size >= 3) { "need at least three paired angles" }
        val n = commandedDeg.size
        val mx = commandedDeg.average(); val my = measuredRawDeg.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (commandedDeg[i] - mx) * (measuredRawDeg[i] - my); sxx += (commandedDeg[i] - mx) * (commandedDeg[i] - mx) }
        require(sxx > 0) { "commanded angles must vary" }
        val a = sxy / sxx
        val b = my - a * mx
        val s = if (sign(a) >= 0) 1 else -1
        val scale = abs(a)
        val mount = Angles.wrapDeg(-b / a)
        var ss = 0.0; var maxErr = 0.0
        for (i in 0 until n) {
            val head = Angles.wrapDeg(s * measuredRawDeg[i] / scale + mount)
            val err = Angles.diffDeg(head, commandedDeg[i])
            ss += err * err
            if (abs(err) > maxErr) maxErr = abs(err)
        }
        return Result(s, scale, mount, sqrt(ss / n), maxErr, n)
    }
}
