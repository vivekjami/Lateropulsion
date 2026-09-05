package com.lateropulsion.engine.sensor

import com.lateropulsion.core.common.Angles
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Roll about the line of sight from the fused gravity direction (ARCHITECTURE §6.2):
 * ```
 * θ_raw  = atan2(g_x, g_y)
 * θ_head = s · θ_raw / scale + θ_mount
 * θ      = θ_head − θ_ref
 * ```
 * Positive θ = tilt toward the patient's right, by calibration of `s`, never by assumption.
 */
public class RollEstimator(
    public var rollSign: Int,
    public var thetaMountDeg: Double,
    public var scaleError: Double = 1.0,
    public var pitchGuardGz: Double = 0.85,
) {
    public var thetaRawDeg: Double = 0.0
        private set
    public var thetaHeadDeg: Double = 0.0
        private set
    public var thetaDeg: Double = 0.0
        private set
    public var pitchOutOfRange: Boolean = false
        private set

    /** Returns false when the sample is ill-conditioned (looking far up or down). */
    public fun estimate(gx: Double, gy: Double, gz: Double, thetaRefDeg: Double): Boolean {
        thetaRawDeg = Angles.radToDeg(atan2(gx, gy))
        val s = if (rollSign == 0) 1 else rollSign
        thetaHeadDeg = Angles.wrapDeg(s * thetaRawDeg / scaleError + thetaMountDeg)
        thetaDeg = Angles.wrapDeg(thetaHeadDeg - thetaRefDeg)
        pitchOutOfRange = abs(gz) > pitchGuardGz
        return !pitchOutOfRange
    }
}
