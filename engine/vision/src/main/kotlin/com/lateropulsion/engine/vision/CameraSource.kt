package com.lateropulsion.engine.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.util.Range
import android.util.Size
import android.view.Surface
import com.lateropulsion.core.common.LpLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor
import kotlin.math.abs

/** What the phone's camera can do; feeds the device self-check and the device profile notes. */
public data class CameraCapabilities(
    val cameraId: String?,
    val hardwareLevel: Int,
    val maxFps: Int,
    val fixedFpsRanges: List<Int>,
    val sensorOrientation: Int,
    val timestampRealtime: Boolean,
    val previewSizes: List<Size>,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
) {
    public val usable: Boolean get() = cameraId != null && previewSizes.isNotEmpty()
    public val hardwareLevelName: String
        get() = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "EXTERNAL/UNKNOWN"
        }

    public companion object {
        public fun probe(context: Context): CameraCapabilities {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = selectBackCamera(cm) ?: return CameraCapabilities(null, -1, 0, emptyList(), 0, false, emptyList(), false, false)
            val c = cm.getCameraCharacteristics(id)
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
            return CameraCapabilities(
                cameraId = id,
                hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
                maxFps = ranges.maxOfOrNull { it.upper } ?: 0,
                fixedFpsRanges = ranges.filter { it.lower == it.upper }.map { it.upper }.sorted(),
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                timestampRealtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                previewSizes = sizes,
                aeLockAvailable = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false,
                awbLockAvailable = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false,
            )
        }

        public fun selectBackCamera(cm: CameraManager): String? {
            val backs = cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            // Prefer the highest hardware level; ties resolve to the first (usually the main wide camera).
            return backs.maxByOrNull { levelRank(cm.getCameraCharacteristics(it).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1) }
        }

        private fun levelRank(level: Int): Int = when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> 4
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> 3
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> 2
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> 1
            else -> 0
        }

        /** Android's Size is not available in plain JVM tests, so the choice works on this and [choosePreviewSize] wraps it. */
        public data class Dim(val w: Int, val h: Int) { public val area: Long get() = w.toLong() * h }

        /**
         * The picture should fill the landscape screen and be at least as sharp as it (ADR-022): first a size whose
         * aspect matches the display within 3 % and is at least the display's resolution (smallest such), then the
         * largest display-aspect size, then 16:9 at or above 1920×1080, then 16:9 at or above 1280×720, then the
         * largest available. Works on any phone; [displayW] × [displayH] is the landscape screen size.
         */
        public fun choosePreviewDim(sizes: List<Dim>, displayW: Int = 1920, displayH: Int = 1080): Dim? {
            if (sizes.isEmpty()) return null
            val displayAspect = displayW.toDouble() / displayH
            val matching = sizes.filter { abs(it.w.toDouble() / it.h - displayAspect) < 0.03 * displayAspect }
            matching.filter { it.w >= displayW && it.h >= displayH }.minByOrNull { it.area }?.let { return it }
            matching.filter { it.w >= MIN_W && it.h >= MIN_H }.maxByOrNull { it.area }?.let { return it }
            val wide = sizes.filter { abs(it.w.toDouble() / it.h - 16.0 / 9.0) < 0.05 }
            wide.filter { it.w >= FULL_HD_W && it.h >= FULL_HD_H }.minByOrNull { it.area }?.let { return it }
            wide.filter { it.w >= MIN_W && it.h >= MIN_H }.minByOrNull { it.area }?.let { return it }
            return sizes.maxByOrNull { it.area }
        }

        public fun choosePreviewSize(sizes: List<Size>, displayW: Int = 1920, displayH: Int = 1080): Size? =
            choosePreviewDim(sizes.map { Dim(it.width, it.height) }, displayW, displayH)?.let { Size(it.w, it.h) }

        private const val MIN_W = 1280; private const val MIN_H = 720
        private const val FULL_HD_W = 1920; private const val FULL_HD_H = 1080

        /** Highest fixed-rate range (min == max) up to [maxFps]; a fixed rate keeps latency predictable. */
        public fun chooseFpsRange(ranges: List<Range<Int>>, maxFps: Int = 60): Range<Int>? {
            val fixed = ranges.filter { it.lower == it.upper && it.upper <= maxFps }
            return fixed.maxByOrNull { it.upper } ?: ranges.filter { it.upper <= maxFps }.maxByOrNull { it.upper } ?: ranges.maxByOrNull { it.upper }
        }
    }
}

public sealed interface CameraState {
    public data object Closed : CameraState
    public data object Opening : CameraState
    public data class Streaming(val size: Size, val fps: Range<Int>, val sensorOrientation: Int) : CameraState
    public data class Error(val code: Int, val message: String) : CameraState
}

/**
 * Camera2 → SurfaceTexture pipeline (ARCHITECTURE §5, IMPLEMENTATION Phase 3): TEMPLATE_PREVIEW, a fixed
 * target fps range, optional AE/AWB lock, no copies. The caller (render thread) owns the SurfaceTexture.
 * The CAMERA permission must already be granted; the app module handles that.
 */
public class CameraSource(context: Context, private val handler: Handler) {
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    public val state: StateFlow<CameraState> get() = _state
    public val frameClock: FrameClock = FrameClock(30.0)
    private var lockExposure = false

    public val capabilities: CameraCapabilities by lazy { CameraCapabilities.probe(context) }

    @SuppressLint("MissingPermission")
    public fun open(texture: SurfaceTexture, targetFps: Int = 60, displayW: Int = 1920, displayH: Int = 1080) {
        val id = capabilities.cameraId ?: run { _state.value = CameraState.Error(-1, "No back camera"); return }
        _state.value = CameraState.Opening
        val chars = cm.getCameraCharacteristics(id)
        val size = CameraCapabilities.choosePreviewSize(capabilities.previewSizes, displayW, displayH) ?: run { _state.value = CameraState.Error(-2, "No preview sizes"); return }
        val fps = CameraCapabilities.chooseFpsRange(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList(), targetFps)
            ?: Range(30, 30)
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture)
        val executor = Executor { r -> handler.post(r) }
        cm.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
                    if (lockExposure && capabilities.aeLockAvailable) set(CaptureRequest.CONTROL_AE_LOCK, true)
                    if (lockExposure && capabilities.awbLockAvailable) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                request = req
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            frameClock.reset()
                            s.setRepeatingRequest(req.build(), captureCallback, handler)
                            _state.value = CameraState.Streaming(size, fps, capabilities.sensorOrientation)
                            LpLog.i(TAG, "camera streaming", "w" to size.width, "h" to size.height, "fps" to fps.upper)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { _state.value = CameraState.Error(-3, "Capture session failed") }
                    },
                )
                cam.createCaptureSession(config)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); device = null; _state.value = CameraState.Error(-4, "Camera disconnected") }
            override fun onError(cam: CameraDevice, error: Int) { cam.close(); device = null; _state.value = CameraState.Error(error, "Camera error $error") }
        })
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { frameClock.onFrame(it) }
        }
    }

    /** Fix exposure and white balance once the scene is stable; avoids brightness pumping during head motion. */
    public fun setExposureLock(lock: Boolean) {
        lockExposure = lock
        val req = request ?: return
        val s = session ?: return
        if (capabilities.aeLockAvailable) req.set(CaptureRequest.CONTROL_AE_LOCK, lock)
        if (capabilities.awbLockAvailable) req.set(CaptureRequest.CONTROL_AWB_LOCK, lock)
        runCatching { s.setRepeatingRequest(req.build(), captureCallback, handler) }
    }

    public fun close() {
        runCatching { session?.stopRepeating() }
        session?.close(); session = null
        device?.close(); device = null
        _state.value = CameraState.Closed
    }

    private companion object { const val TAG = "Camera" }
}
