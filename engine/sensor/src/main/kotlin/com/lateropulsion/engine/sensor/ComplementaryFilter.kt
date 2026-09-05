package com.lateropulsion.engine.sensor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Gyro-integrating complementary filter with accelerometer gravity correction (ARCHITECTURE §6.3).
 * Magnetometer deliberately excluded (ADR-008). Allocation-free after construction.
 *
 * @param alpha fraction of the gyro/accel disagreement corrected per accelerometer update.
 *   0.02 at 200 Hz ≈ 0.4 s time constant.
 */
public class ComplementaryFilter(public val alpha: Double = 0.02) {
    public val q: DoubleArray = DoubleArray(4).also { QuatMath.identity(it) }
    private val tmp = DoubleArray(4)
    private val tmp2 = DoubleArray(4)
    private val gp = DoubleArray(3)

    public var initialized: Boolean = false
        private set

    /** Accelerometer samples whose magnitude deviates from 1 g by more than this fraction are not trusted. */
    public var accelTrustBand: Double = 0.3

    public fun reset() { QuatMath.identity(q); initialized = false }

    /** Aligns q so that the measured acceleration direction is world-up. */
    public fun initializeFromAccel(ax: Double, ay: Double, az: Double) {
        val n = sqrt(ax * ax + ay * ay + az * az)
        if (n < 1e-6) return
        val ux = ax / n; val uy = ay / n; val uz = az / n
        // q = smallest rotation taking device-up (measured) onto world-up (0,1,0)
        val dot = uy
        if (dot < -1.0 + 1e-9) {
            QuatMath.set(q, 0.0, 0.0, 0.0, 1.0)
        } else {
            // cross(u, up) = (u.y*0 - u.z*1, u.z*0 - u.x*0, u.x*1 - u.y*0) = (-uz, 0, ux)
            QuatMath.set(q, 1.0 + dot, -uz, 0.0, ux)
            QuatMath.normalize(q)
        }
        initialized = true
    }

    /** Integrates body-frame angular rate (rad/s) over dt seconds. */
    public fun integrateGyro(wx: Double, wy: Double, wz: Double, dt: Double) {
        if (!initialized) return
        QuatMath.integrateBodyRate(q, wx, wy, wz, dt, tmp, tmp2)
    }

    /** Nudges q toward the accelerometer's gravity direction. Returns the pre-correction disagreement angle (rad). */
    public fun correctWithAccel(ax: Double, ay: Double, az: Double): Double {
        val n = sqrt(ax * ax + ay * ay + az * az)
        if (n < 1e-6) return 0.0
        if (!initialized) { initializeFromAccel(ax, ay, az); return 0.0 }
        if (abs(n / STANDARD_GRAVITY - 1.0) > accelTrustBand) return Double.NaN // linear acceleration: skip
        val mx = ax / n; val my = ay / n; val mz = az / n
        QuatMath.rotateInverse(q, 0.0, 1.0, 0.0, gp) // predicted gravity-up in device frame
        // rotation taking predicted → measured
        val cx = gp[1] * mz - gp[2] * my
        val cy = gp[2] * mx - gp[0] * mz
        val cz = gp[0] * my - gp[1] * mx
        val sinA = sqrt(cx * cx + cy * cy + cz * cz)
        val cosA = gp[0] * mx + gp[1] * my + gp[2] * mz
        val angle = atan2(sinA, cosA)
        if (sinA < 1e-12) return angle
        QuatMath.applyDeviceFrameCorrection(q, cx, cy, cz, alpha * angle, tmp, tmp2)
        return angle
    }

    /** Fused gravity-up direction in the device frame. */
    public fun gravity(out3: DoubleArray) { QuatMath.rotateInverse(q, 0.0, 1.0, 0.0, out3) }

    public fun snapshot(): Quaternion = Quaternion(q[0], q[1], q[2], q[3])

    public companion object { public const val STANDARD_GRAVITY: Double = 9.80665 }
}
