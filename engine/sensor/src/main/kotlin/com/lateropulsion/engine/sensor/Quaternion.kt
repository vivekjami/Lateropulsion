package com.lateropulsion.engine.sensor

import com.lateropulsion.core.model.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit quaternion `q = (w, x, y, z)` mapping the device frame to the world frame: `v_w = q ⊗ v_d ⊗ q*`.
 * Hamilton convention. World `+Y` is up (ARCHITECTURE §6.1).
 */
public data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    public operator fun times(o: Quaternion): Quaternion = Quaternion(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    public fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)
    public fun norm(): Double = sqrt(w * w + x * x + y * y + z * z)
    public fun normalized(): Quaternion { val n = norm(); return Quaternion(w / n, x / n, y / n, z / n) }

    /** Device → world. */
    public fun rotate(v: Vec3): Vec3 {
        val tx = 2 * (y * v.z - z * v.y)
        val ty = 2 * (z * v.x - x * v.z)
        val tz = 2 * (x * v.y - y * v.x)
        return Vec3(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    /** World → device. */
    public fun inverseRotate(v: Vec3): Vec3 = conjugate().rotate(v)

    /** Rotation vector (axis * angle) of this quaternion. */
    public fun toRotationVector(): Vec3 {
        val s = sqrt(x * x + y * y + z * z)
        if (s < 1e-12) return Vec3.ZERO
        val angle = 2 * atan2(s, w)
        return Vec3(x / s * angle, y / s * angle, z / s * angle)
    }

    public fun angleTo(o: Quaternion): Double {
        val d = abs(w * o.w + x * o.x + y * o.y + z * o.z).coerceAtMost(1.0)
        return 2 * acos(d)
    }

    public companion object {
        public val IDENTITY: Quaternion = Quaternion(1.0, 0.0, 0.0, 0.0)

        public fun fromAxisAngle(axis: Vec3, angleRad: Double): Quaternion {
            val n = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
            require(n > 0) { "zero axis" }
            val s = sin(angleRad / 2) / n
            return Quaternion(cos(angleRad / 2), axis.x * s, axis.y * s, axis.z * s)
        }

        /** exp(½ r) for a rotation vector r. */
        public fun fromRotationVector(r: Vec3): Quaternion {
            val angle = sqrt(r.x * r.x + r.y * r.y + r.z * r.z)
            if (angle < 1e-9) return Quaternion(1.0, r.x / 2, r.y / 2, r.z / 2).normalized()
            return fromAxisAngle(r, angle)
        }

        /** Smallest rotation taking unit vector [a] onto unit vector [b] (both in the same frame). */
        public fun fromTwoVectors(a: Vec3, b: Vec3): Quaternion {
            val dot = a.x * b.x + a.y * b.y + a.z * b.z
            if (dot < -1.0 + 1e-9) {
                // antiparallel: rotate 180° about any axis orthogonal to a
                val axis = if (abs(a.x) < 0.9) Vec3(0.0, -a.z, a.y) else Vec3(-a.z, 0.0, a.x)
                return fromAxisAngle(axis, Math.PI)
            }
            val cx = a.y * b.z - a.z * b.y
            val cy = a.z * b.x - a.x * b.z
            val cz = a.x * b.y - a.y * b.x
            return Quaternion(1.0 + dot, cx, cy, cz).normalized()
        }
    }
}

/**
 * Allocation-free quaternion maths on `DoubleArray(4) = [w, x, y, z]` for the fusion hot loop
 * (ARCHITECTURE §5: no allocation in the fusion loop).
 */
public object QuatMath {
    public fun identity(q: DoubleArray) { q[0] = 1.0; q[1] = 0.0; q[2] = 0.0; q[3] = 0.0 }

    public fun set(q: DoubleArray, w: Double, x: Double, y: Double, z: Double) { q[0] = w; q[1] = x; q[2] = y; q[3] = z }

    /** out = a ⊗ b. `out` may not alias `a` or `b`. */
    public fun multiply(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
        out[0] = a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3]
        out[1] = a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2]
        out[2] = a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1]
        out[3] = a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
    }

    public fun normalize(q: DoubleArray) {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n > 0) { q[0] /= n; q[1] /= n; q[2] /= n; q[3] /= n }
    }

    /** q ← q ⊗ exp(½ ω dt) for a body-frame angular rate ω (rad/s). `tmp` and `tmp2` are scratch (size 4). */
    public fun integrateBodyRate(q: DoubleArray, wx: Double, wy: Double, wz: Double, dt: Double, tmp: DoubleArray, tmp2: DoubleArray) {
        val ax = wx * dt; val ay = wy * dt; val az = wz * dt
        val angle = sqrt(ax * ax + ay * ay + az * az)
        if (angle < 1e-12) return
        val h = angle / 2
        val s = sin(h) / angle
        tmp[0] = cos(h); tmp[1] = ax * s; tmp[2] = ay * s; tmp[3] = az * s
        multiply(q, tmp, tmp2)
        q[0] = tmp2[0]; q[1] = tmp2[1]; q[2] = tmp2[2]; q[3] = tmp2[3]
        normalize(q)
    }

    /** out3 = R(q)^T v : world → device. */
    public fun rotateInverse(q: DoubleArray, vx: Double, vy: Double, vz: Double, out3: DoubleArray) {
        rotateWith(q[0], -q[1], -q[2], -q[3], vx, vy, vz, out3)
    }

    /** out3 = R(q) v : device → world. */
    public fun rotate(q: DoubleArray, vx: Double, vy: Double, vz: Double, out3: DoubleArray) {
        rotateWith(q[0], q[1], q[2], q[3], vx, vy, vz, out3)
    }

    private fun rotateWith(w: Double, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double, out3: DoubleArray) {
        val tx = 2 * (y * vz - z * vy)
        val ty = 2 * (z * vx - x * vz)
        val tz = 2 * (x * vy - y * vx)
        out3[0] = vx + w * tx + (y * tz - z * ty)
        out3[1] = vy + w * ty + (z * tx - x * tz)
        out3[2] = vz + w * tz + (x * ty - y * tx)
    }

    /**
     * Applies a small correction expressed in the device frame: a rotation about `axis` (device frame, not
     * necessarily unit) by `angle`. With δ = axis-angle, q ← q ⊗ δ* so that R(q_new)^T up = R(δ) R(q)^T up.
     */
    public fun applyDeviceFrameCorrection(q: DoubleArray, ax: Double, ay: Double, az: Double, angle: Double, tmp: DoubleArray, tmp2: DoubleArray) {
        val n = sqrt(ax * ax + ay * ay + az * az)
        if (n < 1e-12 || abs(angle) < 1e-12) return
        val h = angle / 2
        val s = sin(h) / n
        // conj(δ): negate the vector part
        tmp[0] = cos(h); tmp[1] = -ax * s; tmp[2] = -ay * s; tmp[3] = -az * s
        multiply(q, tmp, tmp2)
        q[0] = tmp2[0]; q[1] = tmp2[1]; q[2] = tmp2[2]; q[3] = tmp2[3]
        normalize(q)
    }
}
