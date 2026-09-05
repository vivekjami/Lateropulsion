package com.lateropulsion.engine.sensor

import com.lateropulsion.core.model.Vec3
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.PI

class QuaternionTest {
    private fun assertVec(e: Vec3, a: Vec3, tol: Double = 1e-9) {
        assertEquals(e.x, a.x, tol); assertEquals(e.y, a.y, tol); assertEquals(e.z, a.z, tol)
    }

    @Test
    fun `right-handed rotation of x about z by 90 degrees gives y`() {
        val q = Quaternion.fromAxisAngle(Vec3(0.0, 0.0, 1.0), PI / 2)
        assertVec(Vec3(0.0, 1.0, 0.0), q.rotate(Vec3(1.0, 0.0, 0.0)))
        assertVec(Vec3(1.0, 0.0, 0.0), q.inverseRotate(Vec3(0.0, 1.0, 0.0)))
    }

    @Test
    fun `composition order matches rotate then rotate`() = runBlocking {
        checkAll(200, Arb.double(-PI, PI), Arb.double(-PI, PI), Arb.double(-1.0, 1.0)) { a, b, vx ->
            val q1 = Quaternion.fromAxisAngle(Vec3(1.0, 0.3, -0.2), a)
            val q2 = Quaternion.fromAxisAngle(Vec3(-0.4, 1.0, 0.5), b)
            val v = Vec3(vx, 0.5, -0.25)
            assertVec(q1.rotate(q2.rotate(v)), (q1 * q2).rotate(v), 1e-9)
        }
    }

    @Test
    fun `fromTwoVectors maps a onto b including the antiparallel case`() {
        val a = Vec3(0.0, 0.0, 1.0); val b = Vec3(1.0, 0.0, 0.0)
        assertVec(b, Quaternion.fromTwoVectors(a, b).rotate(a))
        assertVec(Vec3(0.0, -1.0, 0.0), Quaternion.fromTwoVectors(Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0)).rotate(Vec3(0.0, 1.0, 0.0)), 1e-9)
    }

    @Test
    fun `allocation-free body-rate integration equals axis-angle`() {
        val q = DoubleArray(4).also { QuatMath.identity(it) }
        val t1 = DoubleArray(4); val t2 = DoubleArray(4)
        val steps = 1000
        repeat(steps) { QuatMath.integrateBodyRate(q, 0.0, 0.0, 0.7, 1.0 / steps, t1, t2) }
        val expected = Quaternion.fromAxisAngle(Vec3(0.0, 0.0, 1.0), 0.7)
        assertEquals(0.0, Quaternion(q[0], q[1], q[2], q[3]).angleTo(expected), 1e-9)
    }

    @Test
    fun `device-frame correction rotates the predicted gravity onto the measured one`() {
        val q = DoubleArray(4); QuatMath.set(q, Quaternion.fromAxisAngle(Vec3(0.2, 1.0, 0.1), 0.9).let { it.w }, 0.0, 0.0, 0.0)
        val qq = Quaternion.fromAxisAngle(Vec3(0.2, 1.0, 0.1), 0.9)
        QuatMath.set(q, qq.w, qq.x, qq.y, qq.z)
        val gp = DoubleArray(3); QuatMath.rotateInverse(q, 0.0, 1.0, 0.0, gp)
        // measured gravity: predicted rotated by 10° about device x
        val d = Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), 0.1745)
        val gm = d.rotate(Vec3(gp[0], gp[1], gp[2]))
        val t1 = DoubleArray(4); val t2 = DoubleArray(4)
        QuatMath.applyDeviceFrameCorrection(q, 1.0, 0.0, 0.0, 0.1745, t1, t2)
        val after = DoubleArray(3); QuatMath.rotateInverse(q, 0.0, 1.0, 0.0, after)
        assertVec(gm, Vec3(after[0], after[1], after[2]), 1e-6)
    }
}
