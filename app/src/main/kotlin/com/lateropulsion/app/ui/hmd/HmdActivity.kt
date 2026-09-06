package com.lateropulsion.app.ui.hmd

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.lateropulsion.app.session.SessionController
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.core.common.LpLog
import com.lateropulsion.core.model.CueType
import com.lateropulsion.engine.render.AbortController
import com.lateropulsion.engine.render.AudioPanCue
import com.lateropulsion.engine.render.OverlayGeometry
import com.lateropulsion.engine.render.RenderListener
import com.lateropulsion.engine.render.RenderStateHolder
import com.lateropulsion.engine.render.RenderTelemetry
import com.lateropulsion.engine.render.RenderThread
import com.lateropulsion.engine.render.PassthroughRenderer
import com.lateropulsion.engine.render.ThermalMonitor
import com.lateropulsion.engine.vision.CameraCapabilities
import com.lateropulsion.engine.vision.CameraSource
import com.lateropulsion.engine.vision.CameraState
import com.lateropulsion.feature.protocol.AbortSource
import com.lateropulsion.feature.protocol.SessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The patient view: full-screen landscape camera passthrough on a SurfaceView, rendered by [RenderThread]
 * from the shared pose provider and render state (ARCHITECTURE §5, §7). The headset profile decides whether
 * this is one visor image or two lens viewports (ADR-019); everything else here is identical.
 *
 * Single-phone operation (ADR-020): while the session is Ready, or resting with the rest complete, a countdown runs
 * and the next block starts by itself so the operator can mount the visor and step back; a touch during the countdown
 * restarts it instead of aborting; VOLUME_UP starts at once. The view closes itself when the session is over.
 *
 * Stop controls once a block is running (ADR-024): a held press on the screen, Back pressed twice, VOLUME_DOWN, or the
 * Bluetooth clicker's ENTER/DPAD_CENTER — all reach [AbortController] first and the protocol engine second (REQ-SAF-004).
 * A brief touch or a single edge swipe is ignored, because a phone on a visor gets touched. VOLUME_UP marks a checkpoint.
 */
@AndroidEntryPoint
class HmdActivity : ComponentActivity(), RenderListener {
    @Inject lateinit var runtime: SessionRuntime
    @Inject lateinit var controller: SessionController
    @Inject lateinit var renderStates: RenderStateHolder
    @Inject lateinit var abort: AbortController

    private lateinit var surfaceView: SurfaceView
    private var renderThread: RenderThread? = null
    private var camera: CameraSource? = null
    private var cameraThread: HandlerThread? = null
    private var thermal: ThermalMonitor? = null
    private var audio: AudioPanCue? = null
    private var mirror: TherapistMirrorPresentation? = null
    private var pendingTexture: SurfaceTexture? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingTexture?.let { openCamera(it) } else { LpLog.w(TAG, "camera permission denied"); abort.abort("NO_CAMERA_PERMISSION") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        hideSystemBars()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // One edge swipe while a block runs is ignored; a second within BACK_TWICE_MS stops the session (ADR-024).
                val now = SystemClock.uptimeMillis()
                if (controller.state.value.phase is SessionState.BlockRunning && now - lastBackAt > BACK_TWICE_MS) { lastBackAt = now; return }
                doAbort("back"); finish()
            }
        })
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { startRendering(holder) }
            override fun surfaceDestroyed(holder: SurfaceHolder) { stopRendering() }
        })
        thermal = ThermalMonitor(this).also { it.start() }
        lifecycleScope.launch {
            thermal!!.status.collectLatest { s ->
                if (ThermalMonitor.shouldAbort(s)) { abort.abort("THERMAL"); controller.abort(AbortSource.THERMAL, "status $s") }
            }
        }
        // Audio pan cue follows the live deviation when the block asks for it.
        audio = AudioPanCue()
        lifecycleScope.launch {
            controller.state.collectLatest { st ->
                val rs = renderStates.get()
                if (!rs.idle && CueType.AUDIO_PAN in rs.cues && !abort.isNeutral) {
                    audio?.start()
                    val (pan, loud) = OverlayGeometry.audioPan(st.thetaDeg - rs.targetDeg, rs.toleranceDeg)
                    audio?.update(pan, loud)
                } else audio?.update(0f, 0f)
            }
        }
        showMirrorIfSecondDisplay()
        lifecycleScope.launch {
            controller.state.map { st -> autoAdvanceKey(st) }.distinctUntilChanged().combine(countdownRestart) { key, nonce -> key to nonce }
                .collectLatest { (key, _) -> autoAdvance(key) }
        }
    }

    /** What the auto-advance logic cares about; θ updates and block timers must not restart the countdown. */
    private fun autoAdvanceKey(st: com.lateropulsion.app.session.LiveSessionState): String = when (val p = st.phase) {
        SessionState.Ready -> "ready"
        is SessionState.Resting -> if (st.restComplete) "rest-complete" else "resting"
        SessionState.Summarizing, SessionState.Saved, SessionState.Cancelled, is SessionState.Failed, is SessionState.Aborted -> "done"
        else -> p::class.simpleName ?: "other"
    }

    private suspend fun autoAdvance(key: String) {
        val delayS = runtime.appConfig.session.autoStartDelayS
        when (key) {
            "ready" -> if (delayS > 0) { countdown(delayS); startFromHmd() }
            "rest-complete" -> if (delayS > 0) { countdown(delayS); controller.nextBlock() }
            "done" -> { renderStates.update { it.copy(countdownS = 0) }; finish() }
            else -> renderStates.update { it.copy(countdownS = 0) }
        }
    }

    private suspend fun countdown(seconds: Int) {
        try {
            for (i in seconds downTo 1) { renderStates.update { it.copy(countdownS = i) }; delay(1000) }
        } finally {
            renderStates.update { it.copy(countdownS = 0) }
        }
    }

    /** Confirms the session reference (baseline midline or true vertical) if the operator has not, then starts. */
    private fun startFromHmd() {
        val st = controller.state.value
        if (st.phase != SessionState.Ready) return
        if (!st.midlineConfirmed) controller.confirmMidline(st.spec?.thetaRefDeg ?: 0.0)
        controller.start()
    }

    private val countdownRestart = MutableStateFlow(0)
    private val inCountdownPhase: Boolean get() = controller.state.value.let { it.phase == SessionState.Ready || (it.phase is SessionState.Resting && it.restComplete) }

    private fun startRendering(holder: SurfaceHolder) {
        if (renderThread != null) return
        val poses = runtime.poseProvider ?: run { LpLog.e(TAG, "no pose provider; open a session first"); finish(); return }
        val dev = runtime.device.value ?: return
        val hs = runtime.headset.value ?: return
        val renderer = PassthroughRenderer(hs, dev, runtime.appConfig.visual)
        // Landscape-locked activity: the renderer turns the camera image by whole quarter turns from the sensor orientation,
        // the display rotation and what the camera service already rotated (ADR-023), plus the operator's Flip 180°.
        val sensorOrientation = runCatching { CameraCapabilities.probe(this).sensorOrientation }.getOrDefault(90)
        val displayDeg = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation)?.times(90) ?: 90
        renderer.sensorOrientationDeg = sensorOrientation
        renderer.displayRotationDeg = displayDeg
        renderer.extraQuarterTurns = runtime.cameraExtraQuarterTurns
        renderer.cameraMirror = runtime.cameraMirror
        LpLog.i(TAG, "camera orientation", "sensor_deg" to sensorOrientation, "display_deg" to displayDeg, "extra_turns" to renderer.extraQuarterTurns, "mirror" to renderer.cameraMirror, "display_mode" to hs.displayMode)
        val refresh = currentDisplayRefreshRate()
        val t = RenderThread(holder.surface, renderer, poses, renderStates, abort, this, vsyncHz = refresh, abortMs = runtime.appConfig.safety.motionToPhotonAbortMs.toDouble(),
            watchdogFrames = runtime.appConfig.safety.watchdogFrames, cameraStallMs = runtime.appConfig.safety.cameraStallMs.toDouble())
        renderThread = t
        t.start()
    }

    private fun stopRendering() {
        camera?.close(); camera = null
        cameraThread?.quitSafely(); cameraThread = null
        renderThread?.shutdown(); renderThread = null
    }

    override fun onReady(cameraTexture: SurfaceTexture) {
        pendingTexture = cameraTexture
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(cameraTexture)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun openCamera(texture: SurfaceTexture) {
        if (runtime.simulated) return // training mode: no camera needed; the grey field is drawn
        val ct = HandlerThread("lp-camera").also { it.start() }
        cameraThread = ct
        val cam = CameraSource(this, Handler(ct.looper))
        camera = cam
        renderThread?.attachCameraClock(cam.frameClock)
        lifecycleScope.launch {
            cam.state.collectLatest { s ->
                when (s) {
                    is CameraState.Error -> { LpLog.e(TAG, "camera error", null, "code" to s.code, "message" to s.message); controller.onCameraStall(true) }
                    is CameraState.Streaming -> {
                        renderThread?.renderer?.cameraBufferAspect = s.size.width.toDouble() / s.size.height
                        LpLog.i(TAG, "streaming", "fps" to s.fps.upper, "w" to s.size.width, "h" to s.size.height)
                        // Auto exposure stays on for a walking patient; a locked exposure turns doorways black (ADR-022).
                        if (runtime.appConfig.visual.lockExposure) Handler(ct.looper).postDelayed({ cam.setExposureLock(true) }, 2500)
                    }
                    else -> Unit
                }
            }
        }
        val dm = resources.displayMetrics
        val (dw, dh) = maxOf(dm.widthPixels, dm.heightPixels) to minOf(dm.widthPixels, dm.heightPixels)
        Handler(ct.looper).post { cam.open(texture, targetFps = 60, displayW = dw, displayH = dh) }
    }

    override fun onPerfDegraded(motionToPhotonMs: Double) { controller.onPerfDegraded(motionToPhotonMs) }
    override fun onCameraStall() { controller.onCameraStall(true) }
    override fun onCameraRecovered() { controller.onCameraStall(false) }
    private var lastTelemetryLogNs = 0L
    override fun onFrame(telemetry: RenderTelemetry) {
        mirror?.update(telemetry)
        val now = System.nanoTime()
        if (now - lastTelemetryLogNs > 5_000_000_000L) {
            lastTelemetryLogNs = now
            LpLog.d(TAG, "frame", "frame_ms" to "%.1f".format(telemetry.frameTimeMs), "pose_age_ms" to "%.1f".format(telemetry.poseAgeMs),
                "m2p_est_ms" to "%.0f".format(telemetry.motionToPhotonEstMs), "camera_frames" to telemetry.cameraFrames, "rot_deg" to "%.1f".format(telemetry.appliedRotationDeg),
                "neutral" to telemetry.neutral, "slow_pct" to "%.1f".format(renderThread?.stats?.slowPct ?: 0.0))
        }
    }

    // ---- controls ----
    private val longPress = Handler(Looper.getMainLooper())
    private val longPressAbort = Runnable { doAbort("long-press") }
    private var lastBackAt = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // Mounting the visor means touching the screen: before a block runs that only restarts the countdown; while a
            // block runs a brief touch does nothing and only a press held for LONG_PRESS_MS stops the session (ADR-024).
            MotionEvent.ACTION_DOWN -> if (inCountdownPhase) countdownRestart.value++ else longPress.postDelayed(longPressAbort, LONG_PRESS_MS)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> longPress.removeCallbacks(longPressAbort)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val st = controller.state.value
                when {
                    st.phase == SessionState.Ready -> startFromHmd()
                    st.phase is SessionState.Resting && st.restComplete -> controller.nextBlock()
                    else -> controller.mark()
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> {
                if (inCountdownPhase) countdownRestart.value++ else doAbort("clicker")
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun doAbort(source: String) {
        abort.abort(source) // neutral passthrough on the next frame, before anything else
        controller.abort(if (source == "clicker") AbortSource.CLICKER else AbortSource.THERAPIST_CONTROL, source)
    }

    private fun showMirrorIfSecondDisplay() {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val ext = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull() ?: return
        mirror = TherapistMirrorPresentation(this, ext, controller, renderStates).also { runCatching { it.show() } }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun currentDisplayRefreshRate(): Double {
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        return d?.refreshRate?.toDouble()?.takeIf { it > 10 } ?: 60.0
    }

    override fun onPause() {
        super.onPause()
        // Leaving the HMD view with a block running is unsafe: neutral + pause.
        if (controller.state.value.phase is SessionState.BlockRunning) { abort.abort("HMD_BACKGROUNDED"); controller.pause() }
    }

    override fun onDestroy() {
        longPress.removeCallbacks(longPressAbort)
        stopRendering()
        thermal?.stop()
        audio?.stop()
        mirror?.dismiss()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Hmd"
        /** A press held this long on the patient view stops the session; anything shorter is an incidental touch. */
        const val LONG_PRESS_MS = 1500L
        /** Back twice within this window stops the session while a block runs. */
        const val BACK_TWICE_MS = 2000L
    }
}
