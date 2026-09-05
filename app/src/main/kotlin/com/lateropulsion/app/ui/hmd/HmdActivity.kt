package com.lateropulsion.app.ui.hmd

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
import com.lateropulsion.engine.render.StereoRenderer
import com.lateropulsion.engine.render.ThermalMonitor
import com.lateropulsion.engine.vision.CameraSource
import com.lateropulsion.engine.vision.CameraState
import com.lateropulsion.feature.protocol.AbortSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The headset view: full-screen landscape stereo passthrough on a SurfaceView, rendered by
 * [RenderThread] from the shared pose provider and render state (ARCHITECTURE §5, §7).
 *
 * Abort controls while the phone is in the headset: any touch, any volume key, the Bluetooth clicker's
 * ENTER/DPAD_CENTER, or Back — all reach [AbortController] first and the protocol engine second (REQ-SAF-004).
 * VOLUME_UP alone marks a checkpoint (therapist mark); everything else aborts.
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
            override fun handleOnBackPressed() { doAbort("back"); finish() }
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
    }

    private fun startRendering(holder: SurfaceHolder) {
        if (renderThread != null) return
        val poses = runtime.poseProvider ?: run { LpLog.e(TAG, "no pose provider; open a session first"); finish(); return }
        val dev = runtime.device.value ?: return
        val hs = runtime.headset.value ?: return
        val renderer = StereoRenderer(hs, dev, runtime.appConfig.visual)
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
                    is CameraState.Streaming -> { LpLog.i(TAG, "streaming", "fps" to s.fps.upper, "w" to s.size.width); Handler(ct.looper).postDelayed({ cam.setExposureLock(true) }, 2500) }
                    else -> Unit
                }
            }
        }
        Handler(ct.looper).post { cam.open(texture, targetFps = 60) }
    }

    override fun onPerfDegraded(motionToPhotonMs: Double) { controller.onPerfDegraded(motionToPhotonMs) }
    override fun onCameraStall() { controller.onCameraStall(true) }
    override fun onCameraRecovered() { controller.onCameraStall(false) }
    override fun onFrame(telemetry: RenderTelemetry) { mirror?.update(telemetry) }

    // ---- abort controls ----
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) { doAbort("touch") }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { controller.mark(); true }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> { doAbort("clicker"); true }
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
        if (controller.state.value.phase is com.lateropulsion.feature.protocol.SessionState.BlockRunning) { abort.abort("HMD_BACKGROUNDED"); controller.pause() }
    }

    override fun onDestroy() {
        stopRendering()
        thermal?.stop()
        audio?.stop()
        mirror?.dismiss()
        super.onDestroy()
    }

    private companion object { const val TAG = "Hmd" }
}
