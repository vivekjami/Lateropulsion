package com.lateropulsion.core.common

import kotlin.math.PI

/** Angle helpers. All clinical angles are stored in degrees; all maths runs in radians. */
public object Angles {
    public const val DEG_PER_RAD: Double = 180.0 / PI
    public const val RAD_PER_DEG: Double = PI / 180.0

    public fun degToRad(deg: Double): Double = deg * RAD_PER_DEG
    public fun radToDeg(rad: Double): Double = rad * DEG_PER_RAD

    /** Wraps into (-180, 180]. */
    public fun wrapDeg(deg: Double): Double {
        var d = deg % FULL_TURN_DEG
        if (d <= -HALF_TURN_DEG) d += FULL_TURN_DEG
        if (d > HALF_TURN_DEG) d -= FULL_TURN_DEG
        return d
    }

    /** Wraps into (-pi, pi]. */
    public fun wrapRad(rad: Double): Double {
        var r = rad % (2 * PI)
        if (r <= -PI) r += 2 * PI
        if (r > PI) r -= 2 * PI
        return r
    }

    /** Signed shortest difference a - b in degrees, in (-180, 180]. */
    public fun diffDeg(a: Double, b: Double): Double = wrapDeg(a - b)

    private const val FULL_TURN_DEG = 360.0
    private const val HALF_TURN_DEG = 180.0
}
