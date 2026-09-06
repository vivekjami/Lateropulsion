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

    /**
     * Advances toward the target rotation for this frame; returns the rotation to apply (degrees).
     * @param staticDeg constant rotation added to the target (the entered baseline error, countered; ADR-021).
     */
    public fun update(gain: Double, thetaPredDeg: Double, dtS: Double, staticDeg: Double = 0.0): Double {
        val target = -gain * thetaPredDeg + staticDeg
        val maxStep = slewLimitDegPerS * dtS.coerceIn(0.0, 0.1)
        val delta = target - appliedDeg
        appliedDeg = if (abs(delta) <= maxStep) target else appliedDeg + sign(delta) * maxStep
        return appliedDeg
    }

    /** Instant neutral: used by the abort path. */
    public fun reset() { appliedDeg = 0.0 }

    public companion object {
        /**
         * What the correction should chase this frame. A pose that is invalid for metrics (pitch guard, tracking lost,
         * mount shift, calibrating, paused) is noise or stale, so the picture relaxes to truthful passthrough at the
         * slew limit instead of following it (ARCHITECTURE §15, REQ-VIS-002).
         */
        public fun target(predictedThetaDeg: Double, poseFlags: Int): Double =
            if (com.lateropulsion.core.model.ValidityFlags.isValidForMetrics(poseFlags)) predictedThetaDeg else 0.0
    }
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

/**
 * Camera orientation for a landscape-locked HMD activity, on any phone (ADR-023).
 *
 * Camera2 asks the compositor to rotate preview buffers so they come out upright in the phone's natural (portrait)
 * orientation. A SurfaceTexture consumer receives that rotation inside `getTransformMatrix`, not in the pixels, so
 * the shader has to undo only the display rotation. Whether the rotation is present is read off the matrix
 * ([frameworkTurns]) rather than assumed, so the same code is right on a phone whose camera service leaves the
 * buffer in sensor orientation. Turn units everywhere: quarter turns in the sense of the shader's `quarterTurn`.
 */
public object CameraOrientation {
    /**
     * Quarter turns the camera service already applied, decoded from the SurfaceTexture transform matrix
     * (column-major 4×4). The consumer matrix is `flipV ∘ rot(k)` (GLConsumer), so the direction the logical s axis
     * maps to, with the flip removed, tells k. Returns -1 while the matrix is not yet valid (all zeros before the
     * first frame) or is not a right-angle transform.
     */
    public fun frameworkTurns(m: FloatArray): Int {
        if (m.size < 16) return -1
        val sx = m[0]
        val sy = -m[1] // the vertical flip at the end of the consumer matrix negates y
        return when {
            sx > EPS && kotlin.math.abs(sy) <= EPS -> 0
            sy > EPS && kotlin.math.abs(sx) <= EPS -> 1
            sx < -EPS && kotlin.math.abs(sy) <= EPS -> 2
            sy < -EPS && kotlin.math.abs(sx) <= EPS -> 3
            else -> -1
        }
    }

    /** True when the transform matrix also mirrors the picture (the camera service does this for front cameras). */
    public fun frameworkMirrored(m: FloatArray): Boolean {
        if (m.size < 16) return false
        val det = m[0] * (-m[5]) - (-m[1]) * m[4]
        return det < -EPS
    }

    /**
     * @param sensorOrientationDeg CameraCharacteristics.SENSOR_ORIENTATION (0/90/180/270)
     * @param displayRotationDeg the activity's display rotation in degrees (Surface.ROTATION_x × 90)
     * @param appliedTurns quarter turns already inside the transform matrix ([frameworkTurns]); pass the sensor
     *   orientation's own quarter turns while the matrix is unknown, the Camera2 default
     * @return quarter turns for the shader's `quarterTurn` so the world is upright and left stays left
     */
    public fun quarterTurns(sensorOrientationDeg: Int, displayRotationDeg: Int, appliedTurns: Int): Int {
        val sensor = wrap(sensorOrientationDeg / 90)
        val display = wrap(displayRotationDeg / 90)
        return wrap(display + wrap(appliedTurns) - sensor)
    }

    /** Aspect (w/h) of the camera picture as drawn: the buffer turned by the framework's and the shader's turns together. */
    public fun drawnAspect(bufferAspect: Double, appliedTurns: Int, quarterTurns: Int): Double =
        ViewMapping.turnedAspect(bufferAspect, appliedTurns + quarterTurns)

    /** Kotlin twin of the shader's `quarterTurn` (Shaders.PASSTHROUGH_FS); the unit test pins the two together. */
    public fun quarterTurn(x: Double, y: Double, q: Int): Pair<Double, Double> = when (wrap(q)) {
        1 -> y to 1.0 - x
        2 -> 1.0 - x to 1.0 - y
        3 -> 1.0 - y to x
        else -> x to y
    }

    /** Applies a column-major 4×4 texture matrix to (x, y, 0, 1). */
    public fun apply(m: FloatArray, x: Double, y: Double): Pair<Double, Double> =
        (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])

    public fun wrap(turns: Int): Int = ((turns % 4) + 4) % 4

    private const val EPS = 1e-3f
}
