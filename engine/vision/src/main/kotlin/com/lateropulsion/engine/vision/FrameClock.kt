package com.lateropulsion.engine.vision

/**
 * Camera frame-timing telemetry (ARCHITECTURE §5). Pure Kotlin so it can be unit tested; the camera
 * callbacks feed it `SENSOR_TIMESTAMP` / `SurfaceTexture.getTimestamp()` values in nanoseconds.
 */
public class FrameClock(private val nominalFps: Double, capacity: Int = 240) {
    private val intervals = DoubleArray(capacity)
    private var head = 0
    private var count = 0
    private var lastNs = -1L
    public var frames: Long = 0
        private set
    public var droppedEstimate: Long = 0
        private set

    public fun onFrame(tNs: Long) {
        frames++
        if (lastNs >= 0) {
            val dt = (tNs - lastNs) / 1e9
            intervals[head] = dt
            head = (head + 1) % intervals.size
            if (count < intervals.size) count++
            val nominal = 1.0 / nominalFps
            if (dt > 1.5 * nominal) droppedEstimate += Math.round(dt / nominal) - 1
        }
        lastNs = tNs
    }

    public val lastFrameNs: Long get() = lastNs

    public fun lastFrameAgeMs(nowNs: Long): Double = if (lastNs < 0) Double.POSITIVE_INFINITY else (nowNs - lastNs) / 1e6

    /** Rolling fps over the retained window. */
    public val fps: Double
        get() {
            if (count == 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) sum += intervals[i]
            return count / sum
        }

    /** Standard deviation of frame intervals in ms; large values mean a stuttering passthrough. */
    public val jitterMs: Double
        get() {
            if (count < 2) return 0.0
            var sum = 0.0
            for (i in 0 until count) sum += intervals[i]
            val mean = sum / count
            var v = 0.0
            for (i in 0 until count) { val d = intervals[i] - mean; v += d * d }
            return Math.sqrt(v / (count - 1)) * 1000.0
        }

    public val droppedPct: Double get() = if (frames == 0L) 0.0 else 100.0 * droppedEstimate / (frames + droppedEstimate)

    public fun reset() { head = 0; count = 0; lastNs = -1; frames = 0; droppedEstimate = 0 }
}

/** No frame in [stallMs] → camera stall (ARCHITECTURE §15). */
public class CameraWatchdog(private val stallMs: Double = 200.0) {
    public fun isStalled(clock: FrameClock, nowNs: Long): Boolean = clock.frames > 0 && clock.lastFrameAgeMs(nowNs) > stallMs
}
