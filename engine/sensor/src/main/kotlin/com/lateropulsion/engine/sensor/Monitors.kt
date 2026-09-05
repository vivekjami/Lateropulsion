package com.lateropulsion.engine.sensor

import com.lateropulsion.core.common.LinearRegression
import com.lateropulsion.core.common.RunningStats
import com.lateropulsion.core.model.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Gyro bias from a stillness window (ARCHITECTURE §6.3). Stillness = low gyro variance and steady
 * 1 g accelerometer magnitude over the whole window; any motion restarts the window.
 */
public class GyroBiasEstimator(
    private val windowS: Double = 3.0,
    private val gyroSdMax: Double = 0.02,
    private val accelDevMax: Double = 0.15,
) {
    private val sx = RunningStats(); private val sy = RunningStats(); private val sz = RunningStats()
    private var elapsed = 0.0
    public var result: Vec3? = null
        private set
    public val progress: Double get() = (elapsed / windowS).coerceIn(0.0, 1.0)
    public val complete: Boolean get() = result != null

    public fun feed(wx: Double, wy: Double, wz: Double, ax: Double, ay: Double, az: Double, dt: Double) {
        if (complete) return
        val an = sqrt(ax * ax + ay * ay + az * az)
        if (abs(an / ComplementaryFilter.STANDARD_GRAVITY - 1.0) > accelDevMax) { restart(); return }
        sx.add(wx); sy.add(wy); sz.add(wz)
        elapsed += dt
        if (sx.count >= 2 && (sdOrZero(sx) > gyroSdMax || sdOrZero(sy) > gyroSdMax || sdOrZero(sz) > gyroSdMax)) { restart(); return }
        if (elapsed >= windowS) result = Vec3(sx.mean, sy.mean, sz.mean)
    }

    private fun sdOrZero(s: RunningStats): Double = if (s.count < 2) 0.0 else s.sd

    public fun restart() { sx.reset(); sy.reset(); sz.reset(); elapsed = 0.0 }
    public fun reset() { restart(); result = null }
}

/**
 * Residual drift: slope of (gyro-only roll − gravity roll) over a window, in deg/min.
 * Beyond `driftRecalibrateDegPerMin` the app prompts re-calibration (ARCHITECTURE §15).
 */
public class DriftMonitor(private val windowS: Double = 60.0) {
    private val reg = LinearRegression()
    private var windowStart = Double.NaN
    public var driftDegPerMin: Double = Double.NaN
        private set
    /** Provisional slope of the window in progress once it holds 10 s of data. */
    public var provisionalDegPerMin: Double = Double.NaN
        private set

    public fun feed(tS: Double, gyroRollDeg: Double, gravityRollDeg: Double) {
        if (windowStart.isNaN()) windowStart = tS
        reg.add((tS - windowStart) / 60.0, gyroRollDeg - gravityRollDeg)
        if (tS - windowStart >= 10.0) provisionalDegPerMin = reg.slope
        if (tS - windowStart >= windowS) {
            driftDegPerMin = reg.slope
            reg.reset()
            windowStart = tS
        }
    }

    public fun reset() { reg.reset(); windowStart = Double.NaN; driftDegPerMin = Double.NaN; provisionalDegPerMin = Double.NaN }
}

/**
 * Headset slip heuristic (ARCHITECTURE §15): a step in gravity roll of more than [stepDeg] within
 * [windowNs] that the integrated gyro does not explain. Edge-triggered; [count] is cumulative.
 */
public class MountShiftDetector(
    private val stepDeg: Double = 8.0,
    private val windowNs: Long = 100_000_000L,
    capacity: Int = 128,
) {
    private val t = LongArray(capacity)
    private val acc = DoubleArray(capacity)
    private val gyr = DoubleArray(capacity)
    private var head = 0
    private var size = 0
    private val cap = capacity
    public var count: Int = 0
        private set
    public var lastShiftNs: Long = -1L
        private set

    /** Returns true on the sample where a shift is detected. */
    public fun feed(tNanos: Long, gravityRollDeg: Double, gyroRollDeg: Double): Boolean {
        val idx = (head + size) % cap
        if (size == cap) { head = (head + 1) % cap } else size++
        val w = (head + size - 1) % cap
        t[w] = tNanos; acc[w] = gravityRollDeg; gyr[w] = gyroRollDeg
        // find the newest sample at least windowNs old
        var i = size - 2
        var ref = -1
        while (i >= 0) {
            val k = (head + i) % cap
            if (tNanos - t[k] >= windowNs) { ref = k; break }
            i--
        }
        if (ref < 0) return false
        if (lastShiftNs >= 0 && tNanos - lastShiftNs < 2 * windowNs) return false // debounce
        val dAcc = abs(acc[w] - acc[ref])
        val dGyr = abs(gyr[w] - gyr[ref])
        val detected = dAcc > stepDeg && dGyr < stepDeg / 2
        if (detected) { count++; lastShiftNs = tNanos }
        @Suppress("UNUSED_VARIABLE") val unused = idx
        return detected
    }

    public fun reset() { head = 0; size = 0; count = 0; lastShiftNs = -1 }
}

/** Persistent disagreement between the in-app filter and the vendor GAME_ROTATION_VECTOR (ARCHITECTURE §6.3). */
public class FusionDisagreementMonitor(private val thresholdDeg: Double = 3.0, private val persistNs: Long = 2_000_000_000L) {
    private var sinceNs = -1L
    public var currentDeg: Double = 0.0
        private set
    public var events: Int = 0
        private set
    public val disagreeing: Boolean get() = sinceNs >= 0

    /** Returns true once per disagreement episode when it has persisted long enough. */
    public fun feed(tNanos: Long, oursDeg: Double, vendorDeg: Double): Boolean {
        currentDeg = abs(com.lateropulsion.core.common.Angles.diffDeg(oursDeg, vendorDeg))
        if (currentDeg <= thresholdDeg) { sinceNs = -1; fired = false; return false }
        if (sinceNs < 0) sinceNs = tNanos
        if (!fired && tNanos - sinceNs >= persistNs) { fired = true; events++; return true }
        return false
    }
    private var fired = false
    public fun reset() { sinceNs = -1; fired = false; events = 0; currentDeg = 0.0 }
}
