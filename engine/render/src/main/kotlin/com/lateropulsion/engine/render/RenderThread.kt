package com.lateropulsion.engine.render

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import com.lateropulsion.core.common.LpLog
import com.lateropulsion.core.model.MutablePose
import com.lateropulsion.engine.sensor.PoseProvider
import com.lateropulsion.engine.vision.CameraWatchdog
import com.lateropulsion.engine.vision.FrameClock

public interface RenderListener {
    /** GL is ready; the camera may now be opened on [cameraTexture]. */
    public fun onReady(cameraTexture: SurfaceTexture)
    public fun onPerfDegraded(motionToPhotonMs: Double)
    public fun onCameraStall()
    public fun onCameraRecovered()
    public fun onFrame(telemetry: RenderTelemetry)
}

/**
 * Dedicated render thread driven by Choreographer at URGENT_DISPLAY priority (ARCHITECTURE §5).
 * Frame order: abort flag → latest pose (lock-free) → draw → swap → watchdog. The abort flag is read
 * before anything else so neutral passthrough lands on the very next frame (REQ-SAF-004).
 */
public class RenderThread(
    private val surface: Surface,
    public val renderer: PassthroughRenderer,
    private val poses: PoseProvider,
    private val states: RenderStateHolder,
    private val abort: AbortController,
    private val listener: RenderListener,
    private val vsyncHz: Double = 60.0,
    private val abortMs: Double = 60.0,
    private val watchdogFrames: Int = 3,
    private val cameraStallMs: Double = 200.0,
    cameraFrameClock: FrameClock? = null,
) : HandlerThread("lp-render", Process.THREAD_PRIORITY_URGENT_DISPLAY), Choreographer.FrameCallback {
    private val egl = EglCore()
    private val pose = MutablePose()
    private val watchdog = RenderWatchdog(abortMs, watchdogFrames)
    private val cameraWatchdog = CameraWatchdog(cameraStallMs)
    private var cameraClock: FrameClock? = cameraFrameClock
    private var cameraStalled = false
    private lateinit var choreographer: Choreographer
    @Volatile private var running = false
    private var frameCounter = 0L

    public fun attachCameraClock(clock: FrameClock) { cameraClock = clock }

    override fun onLooperPrepared() {
        try {
            egl.init()
            egl.createWindowSurface(surface)
            egl.makeCurrent()
            val (w, h) = egl.surfaceSize()
            renderer.init(w, h)
            renderer.cameraTexture.setOnFrameAvailableListener({ renderer.onCameraFrameAvailable() }, Handler(looper))
            running = true
            listener.onReady(renderer.cameraTexture)
            choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback(this)
            LpLog.i(TAG, "render thread ready", "w" to w, "h" to h)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            LpLog.e(TAG, "render init failed", e)
            abort.abort("RENDER_INIT_FAILED")
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val neutral = abort.isNeutral
        val state = states.get()
        poses.latest(pose)
        val now = SystemClock.elapsedRealtimeNanos()
        val poseAge = (now - pose.tNanos).coerceIn(0L, 200_000_000L)

        val clock = cameraClock
        val cameraOk = clock == null || !cameraWatchdog.isStalled(clock, now)
        if (!cameraOk && !cameraStalled) { cameraStalled = true; listener.onCameraStall() }
        if (cameraOk && cameraStalled) { cameraStalled = false; listener.onCameraRecovered() }

        renderer.drawFrame(pose, state, neutral, frameTimeNanos, poseAge, cameraOk)
        egl.swapBuffers()

        val t = renderer.telemetry
        t.motionToPhotonEstMs = LatencyEstimate.motionToPhotonMs(t.poseAgeMs, t.frameTimeMs, 1000.0 / vsyncHz)
        // The watchdog guards a laggy *correction*. While the view is idle (ready, countdown, rest) nothing is being
        // corrected, so slow frames are logged but must not abort the session (seen on hardware during the countdown).
        if (watchdog.onFrame(t.motionToPhotonEstMs) && !neutral && !state.idle) {
            abort.abort("PERF_DEGRADED")
            listener.onPerfDegraded(t.motionToPhotonEstMs)
        }
        if (++frameCounter % 6 == 0L) listener.onFrame(t)
        choreographer.postFrameCallback(this)
    }

    public val stats: RenderWatchdog get() = watchdog

    public fun shutdown() {
        running = false
        Handler(looper).post {
            if (::choreographer.isInitialized) choreographer.removeFrameCallback(this)
            renderer.release()
            egl.release()
            quitSafely()
        }
    }

    private companion object { const val TAG = "Render" }
}
