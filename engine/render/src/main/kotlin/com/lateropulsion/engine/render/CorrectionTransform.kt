package com.lateropulsion.engine.render

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Mode B counter-rotation with pose prediction and slew limiting (ARCHITECTURE §5, §7.2).
 * `applied = -k·θ_pred`, rate-limited so a head jerk can never produce a violent whole-field rotation.
 * Pure Kotlin; the renderer feeds it once per frame.
 */
public class CorrectionTransform(
    public var slewLimitDegPerS: Double = 30.0,
    public var predictionClampS: Double = 0.05,
) {
    public var appliedDeg: Double = 0.0
        private set

    /** Extrapolates θ to photon time using the roll rate, clamped to [predictionClampS]. */
    public fun predictTheta(thetaDeg: Double, omegaDegPerS: Double, latencyS: Double): Double =
        thetaDeg + omegaDegPerS * min(latencyS.coerceAtLeast(0.0), predictionClampS)

    /** Advances toward the target rotation for this frame; returns the rotation to apply (degrees). */
    public fun update(gain: Double, thetaPredDeg: Double, dtS: Double): Double {
        val target = -gain * thetaPredDeg
        val maxStep = slewLimitDegPerS * dtS.coerceIn(0.0, 0.1)
        val delta = target - appliedDeg
        appliedDeg = if (abs(delta) <= maxStep) target else appliedDeg + sign(delta) * maxStep
        return appliedDeg
    }

    /** Instant neutral: used by the abort path. */
    public fun reset() { appliedDeg = 0.0 }
}

/**
 * Frame-time watchdog (ARCHITECTURE §5). Three consecutive frames above the abort threshold → degraded.
 */
public class RenderWatchdog(private val abortMs: Double = 60.0, private val consecutiveFrames: Int = 3) {
    private var over = 0
    public var degradedEvents: Int = 0
        private set
    public var worstMs: Double = 0.0
        private set
    public var frames: Long = 0
        private set
    public var slowFrames: Long = 0
        private set

    /** @param motionToPhotonMs estimated latency for this frame. Returns true when the threshold has been breached. */
    public fun onFrame(motionToPhotonMs: Double): Boolean {
        frames++
        if (motionToPhotonMs > worstMs) worstMs = motionToPhotonMs
        if (motionToPhotonMs > abortMs) {
            slowFrames++
            over++
            if (over >= consecutiveFrames) { over = 0; degradedEvents++; return true }
        } else {
            over = 0
        }
        return false
    }

    public val slowPct: Double get() = if (frames == 0L) 0.0 else 100.0 * slowFrames / frames
    public fun reset() { over = 0; worstMs = 0.0; frames = 0; slowFrames = 0 }
}

/**
 * Motion-to-photon estimate = age of the pose at draw time + render time + display pipeline latency
 * (vsync period × the compositor's pipeline depth). An estimate for the watchdog; the bench rig in
 * docs/verification/latency-procedure.md measures the real number.
 */
public object LatencyEstimate {
    public fun motionToPhotonMs(poseAgeMs: Double, renderMs: Double, vsyncPeriodMs: Double, pipelineDepth: Int = 2): Double =
        poseAgeMs + renderMs + vsyncPeriodMs * pipelineDepth
}

/** Camera orientation bookkeeping for a landscape-locked HMD activity (works for any sensor orientation / display rotation). */
public object CameraOrientation {
    /**
     * @param sensorOrientationDeg CameraCharacteristics.SENSOR_ORIENTATION (0/90/180/270)
     * @param displayRotationDeg the activity's display rotation in degrees (Surface.ROTATION_x × 90)
     * @return quarter turns to apply to the camera image so it appears upright on the display
     */
    public fun quarterTurns(sensorOrientationDeg: Int, displayRotationDeg: Int): Int =
        (((sensorOrientationDeg - displayRotationDeg) % 360 + 360) % 360) / 90
}
