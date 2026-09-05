package com.lateropulsion.engine.sensor

import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.MutablePose
import com.lateropulsion.core.model.ValidityFlags
import com.lateropulsion.core.model.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class FusionTest {
    private val g = ComplementaryFilter.STANDARD_GRAVITY

    /** Gravity-up in the device frame for a device rolled by [rollDeg] about its +Z axis, upright portrait. */
    private fun gravityFor(rollDeg: Double): Vec3 {
        val q = Quaternion.fromAxisAngle(Vec3(0.0, 0.0, 1.0), rollDeg * PI / 180)
        return q.inverseRotate(Vec3(0.0, 1.0, 0.0))
    }

    @Test
    fun `REQ-SEN-001 filter converges from a wrong initial orientation to the accelerometer within 3 s`() {
        val f = ComplementaryFilter(alpha = 0.02)
        f.initializeFromAccel(0.0, g, 0.0) // upright
        val target = gravityFor(25.0)
        val out = DoubleArray(3)
        var t = 0.0
        while (t < 3.0) { f.integrateGyro(0.0, 0.0, 0.0, 0.005); f.correctWithAccel(target.x * g, target.y * g, target.z * g); t += 0.005 }
        f.gravity(out)
        val err = Math.toDegrees(kotlin.math.acos((out[0] * target.x + out[1] * target.y + out[2] * target.z).coerceIn(-1.0, 1.0)))
        assertTrue(err < 0.5, "residual error $err°")
    }

    @Test
    fun `REQ-SEN-002 gyro path and accel path agree on the direction of roll`() {
        // Simulate a true roll trajectory; gyro sees +wz, accel sees the rotated gravity.
        val f = ComplementaryFilter(alpha = 0.02)
        val est = RollEstimator(rollSign = 1, thetaMountDeg = 0.0)
        f.initializeFromAccel(0.0, g, 0.0)
        val dt = 0.005
        var maxErr = 0.0
        var t = 0.0
        var prev = 0.0
        val out = DoubleArray(3)
        while (t < 20.0) {
            val roll = 15.0 * sin(2 * PI * 0.5 * t)
            val wz = (roll - prev) * PI / 180 / dt
            prev = roll
            f.integrateGyro(0.0, 0.0, wz, dt)
            val gv = gravityFor(roll)
            f.correctWithAccel(gv.x * g, gv.y * g, gv.z * g)
            f.gravity(out)
            est.estimate(out[0], out[1], out[2], 0.0)
            if (t > 2.0) maxErr = maxOf(maxErr, abs(est.thetaHeadDeg - (-roll)).coerceAtMost(abs(est.thetaHeadDeg - roll)))
            t += dt
        }
        // If gyro and accel disagreed on sign the estimate would oscillate wildly; require tight tracking.
        assertTrue(maxErr < 1.0, "max tracking error $maxErr°")
    }

    @Test
    fun `REQ-SEN-003 roll extraction with a landscape mount and sign`() {
        // Landscape: gravity-up along -X when the head is upright; s = -1, θ_mount = -90 make θ_head = 0.
        val est = RollEstimator(rollSign = -1, thetaMountDeg = -90.0)
        est.estimate(-1.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, est.thetaHeadDeg, 1e-9)
        // s = -1 flips the sense: a raw angle of -100° (10° beyond the mount offset) is a +10° head roll
        val a = -100.0 * PI / 180
        est.estimate(sin(a), cos(a), 0.0, 2.0)
        assertEquals(10.0, est.thetaHeadDeg, 1e-9)
        assertEquals(8.0, est.thetaDeg, 1e-9)
        assertTrue(est.estimate(0.1, 0.2, 0.9, 0.0).not())
        assertTrue(est.pitchOutOfRange)
    }

    @Test
    fun `REQ-SEN-004 scale error is corrected in roll extraction`() {
        val est = RollEstimator(rollSign = 1, thetaMountDeg = 0.0, scaleError = 1.05)
        val raw = 21.0 * PI / 180
        est.estimate(sin(raw), cos(raw), 0.0, 0.0)
        assertEquals(20.0, est.thetaHeadDeg, 1e-9)
    }

    @Test
    fun `REQ-SEN-010 gyro bias estimator needs three still seconds and rejects motion`() {
        val b = GyroBiasEstimator()
        val rnd = Random(1)
        var t = 0.0
        while (t < 2.0) { b.feed(0.004 + rnd.nextDouble(-0.005, 0.005), -0.002, 0.001, 0.0, g, 0.0, 0.005); t += 0.005 }
        assertFalse(b.complete)
        b.feed(0.5, 0.0, 0.0, 0.0, g, 0.0, 0.005) // a jerk restarts the window
        assertEquals(0.0, b.progress, 0.01)
        t = 0.0
        while (t < 3.1) { b.feed(0.004 + rnd.nextDouble(-0.005, 0.005), -0.002, 0.001, 0.0, g, 0.0, 0.005); t += 0.005 }
        assertTrue(b.complete)
        assertEquals(0.004, b.result!!.x, 0.001)
        assertEquals(-0.002, b.result!!.y, 1e-9)
    }

    @Test
    fun `REQ-SAF-030 mount shift detected when gravity roll steps without gyro`() {
        val d = MountShiftDetector(stepDeg = 8.0, windowNs = 100_000_000L)
        var t = 0L
        var detected = false
        for (i in 0 until 400) { detected = d.feed(t, 2.0, 2.0) || detected; t += 5_000_000L }
        assertFalse(detected)
        for (i in 0 until 40) { detected = d.feed(t, 14.0, 2.0) || detected; t += 5_000_000L }
        assertTrue(detected)
        assertEquals(1, d.count)
        // a real head roll: gyro explains the change → no shift
        val d2 = MountShiftDetector()
        t = 0L
        var det2 = false
        for (i in 0 until 400) { det2 = d2.feed(t, 2.0, 2.0) || det2; t += 5_000_000L }
        for (i in 0 until 40) { det2 = d2.feed(t, 14.0, 14.0) || det2; t += 5_000_000L }
        assertFalse(det2)
    }

    @Test
    fun `REQ-SEN-011 drift monitor reports deg per minute`() {
        val m = DriftMonitor(windowS = 60.0)
        var t = 0.0
        while (t <= 61.0) { m.feed(t, 0.02 * t, 0.0); t += 0.05 } // 0.02 deg/s = 1.2 deg/min
        assertEquals(1.2, m.driftDegPerMin, 0.01)
    }

    @Test
    fun `REQ-SEN-012 fusion disagreement fires once after persisting`() {
        val m = FusionDisagreementMonitor(3.0, 2_000_000_000L)
        var t = 0L
        var fired = 0
        for (i in 0 until 600) { if (m.feed(t, 10.0, 5.0)) fired++; t += 5_000_000L }
        assertEquals(1, fired)
        assertTrue(m.disagreeing)
        m.feed(t, 10.0, 9.0)
        assertFalse(m.disagreeing)
    }

    @Test
    fun `REQ-SEN-020 jig fit recovers sign scale and mount from a noisy sweep`() {
        val rnd = Random(7)
        val cmd = (-40..40 step 5).map { it.toDouble() }.toDoubleArray()
        val sTrue = -1; val mountTrue = -90.0; val scaleTrue = 1.02
        // θ_raw = scale · s · (θ_cmd − θ_mount)
        val raw = DoubleArray(cmd.size) { scaleTrue * sTrue * (cmd[it] - mountTrue) + rnd.nextDouble(-0.3, 0.3) }
        val r = JigFit.fit(cmd, raw)
        assertEquals(sTrue, r.sign)
        assertEquals(scaleTrue, r.scaleError, 0.01)
        assertEquals(mountTrue, r.thetaMountDeg, 0.5)
        assertTrue(r.residualRmsDeg < 0.4, "rms ${r.residualRmsDeg}")
        assertTrue(r.passes)
    }

    @Test
    fun `REQ-SEN-021 sign calibration refuses ambiguous tilts`() {
        assertFalse(SignCalibration.resolve(-88.0, -86.0).resolved)
        val r = SignCalibration.resolve(-88.0, -70.0)
        assertTrue(r.resolved); assertEquals(1, r.sign)
        val f = FieldCalibration.resolve(-88.0, -100.0)
        assertTrue(f.resolved); assertEquals(-1, f.sign); assertEquals(-88.0, f.thetaMountDeg, 1e-9)
    }

    private fun simDevice() = DeviceProfile("sim", "s", "m", "m", rollSign = 1, thetaMountDeg = 0.0, qualified = true)

    @Test
    fun `REQ-SEN-030 pipeline tracks a simulated roll within 1 degree and flags dropouts`() {
        val p = FusionPipeline(simDevice())
        p.thetaRefDeg = 2.0
        val dt = 0.005
        var tNs = 1_000_000_000L
        var prev = 0.0
        var maxErr = 0.0
        val pose = MutablePose()
        for (i in 0 until 4000) {
            val t = i * dt
            val roll = if (t < 1.0) 0.0 else 12.0 * sin(2 * PI * 0.3 * t)
            val gv = gravityFor(roll)
            p.onAccel(tNs, gv.x * g, gv.y * g, gv.z * g)
            val wz = (roll - prev) * PI / 180 / dt
            prev = roll
            if (p.onGyro(tNs, 0.0, 0.0, wz)) {
                p.copyCurrent(pose)
                if (t > 3.0) {
                    maxErr = maxOf(maxErr, abs(pose.thetaHeadDeg - roll))
                    assertEquals(roll - 2.0, pose.thetaDeg, 1.0)
                    assertTrue(ValidityFlags.isValidForMetrics(pose.flags))
                }
            }
            tNs += 5_000_000L
        }
        assertTrue(maxErr < 1.0, "max err $maxErr")
        assertTrue(p.imuRateHz > 190 && p.imuRateHz < 210, "rate ${p.imuRateHz}")
        // 300 ms dropout → TRACKING_LOST on the next sample, recovering afterwards
        tNs += 300_000_000L
        p.onAccel(tNs, 0.0, g, 0.0)
        p.onGyro(tNs, 0.0, 0.0, 0.0)
        assertTrue(ValidityFlags.has(p.current.flags, ValidityFlags.TRACKING_LOST))
        tNs += 5_000_000L
        p.onGyro(tNs, 0.0, 0.0, 0.0)
        assertFalse(ValidityFlags.has(p.current.flags, ValidityFlags.TRACKING_LOST))
    }

    @Test
    fun `REQ-SEN-031 fusion loop allocates nothing per sample after warm-up`() {
        // java.lang.management is not on the Android unit-test compile classpath; reach it reflectively (HotSpot only).
        val mf = Class.forName("java.lang.management.ManagementFactory")
        val bean = mf.getMethod("getThreadMXBean").invoke(null)
        val m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val tid = Thread.currentThread().id
        val p = FusionPipeline(simDevice())
        var tNs = 1_000_000_000L
        val gv = gravityFor(5.0)
        fun step() { p.onAccel(tNs, gv.x * g, gv.y * g, gv.z * g); p.onGyro(tNs, 0.001, 0.002, 0.003); tNs += 5_000_000L }
        for (i in 0 until 20_000) step() // warm-up incl. bias window completion
        val before = m.invoke(bean, tid) as Long
        for (i in 0 until 20_000) step()
        val allocated = (m.invoke(bean, tid) as Long) - before
        assertTrue(allocated < 16 * 1024, "fusion loop allocated $allocated bytes over 20k samples")
    }

    @Test
    fun `triple buffer never exposes a torn pose across threads`() {
        val buf = PoseTripleBuffer()
        val writer = Thread {
            var i = 0L
            while (i < 200_000) {
                val b = buf.backSlot()
                b.tNanos = i; b.thetaDeg = i.toDouble(); b.thetaHeadDeg = i.toDouble()
                buf.publish()
                i++
            }
        }
        val torn = java.util.concurrent.atomic.AtomicInteger()
        val reader = Thread {
            val p = MutablePose()
            var last = -1L
            val end = System.nanoTime() + 300_000_000L
            while (System.nanoTime() < end) {
                buf.read(p)
                if (p.tNanos.toDouble() != p.thetaDeg || p.thetaDeg != p.thetaHeadDeg) torn.incrementAndGet()
                if (p.tNanos < last) torn.incrementAndGet()
                last = p.tNanos
            }
        }
        writer.start(); reader.start(); writer.join(); reader.join()
        assertEquals(0, torn.get())
        val p = MutablePose(); buf.read(p)
        assertEquals(199_999L, p.tNanos)
    }

    @Test
    fun `simulated provider drives the real pipeline`() = kotlinx.coroutines.runBlocking {
        val sim = SimulatedPoseProvider(realTime = false, rateHz = 100)
        val samples = mutableListOf<com.lateropulsion.core.model.PoseSample>()
        val job = launch { sim.samples.collect { samples += it } }
        kotlinx.coroutines.yield()
        sim.run(durationS = 10.0)
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertTrue(samples.size > 900, "got ${samples.size}")
        val late = samples.filter { it.tNanos > 6_000_000_000L }
        val mean = late.map { it.thetaHeadDeg }.average()
        assertTrue(mean > 4.0 && mean < 20.0, "mean lean $mean should reflect the 8° pusher lean")
        assertTrue(sqrt(late.map { (it.thetaHeadDeg - mean) * (it.thetaHeadDeg - mean) }.average()) > 1.0)
    }
}
