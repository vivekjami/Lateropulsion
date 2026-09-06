package com.lateropulsion.engine.sensor

import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.lateropulsion.core.common.Angles
import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.MutablePose
import com.lateropulsion.core.model.PoseSample
import com.lateropulsion.core.model.SafetyConfig
import com.lateropulsion.core.model.MetricsConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2

/**
 * Real-device pose provider: a dedicated fusion thread at URGENT_AUDIO priority receives IMU callbacks,
 * runs the [FusionPipeline], and publishes through a lock-free triple buffer (render thread) and a
 * dropping SharedFlow (metrics/logging). Nothing here touches the main thread (ARCHITECTURE §5).
 */
public class AndroidPoseProvider(
    sensorManager: SensorManager,
    profile: DeviceProfile,
    safety: SafetyConfig = SafetyConfig(),
    metrics: MetricsConfig = MetricsConfig(),
    private val requestedRateHz: Int = 200,
) : PoseProvider, ImuListener {
    private val imu = ImuSource(sensorManager)
    public val pipeline: FusionPipeline = FusionPipeline(profile, safety, metrics)
    private val buffer = PoseTripleBuffer()
    private val _samples = MutableSharedFlow<PoseSample>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _diag = MutableStateFlow(SensorDiagnostics(hasGyro = imu.capabilities.hasGyro, hasVendorFusion = imu.capabilities.hasGameRotationVector))
    private var thread: HandlerThread? = null
    private var lastDiagNs = 0L
    private var poseCount = 0
    private var poseWindowStartNs = 0L
    private val vendorG = DoubleArray(3)
    private val vendorQ = DoubleArray(4)

    public val capabilities: ImuCapabilities get() = imu.capabilities

    override val samples: SharedFlow<PoseSample> get() = _samples
    override val diagnostics: StateFlow<SensorDiagnostics> get() = _diag

    override fun latest(into: MutablePose): Boolean = buffer.read(into)

    override fun start() {
        if (thread != null) return
        val t = HandlerThread("lp-fusion", Process.THREAD_PRIORITY_URGENT_AUDIO)
        t.start()
        thread = t
        val ok = imu.start(Handler(t.looper), this, requestedRateHz)
        _diag.value = _diag.value.copy(running = ok, hasGyro = imu.capabilities.hasGyro)
    }

    override fun stop() {
        imu.stop()
        thread?.quitSafely()
        thread = null
        _diag.value = _diag.value.copy(running = false)
    }

    override fun setThetaRef(deg: Double) { pipeline.thetaRefDeg = deg }
    override fun setCalibrating(on: Boolean) { pipeline.calibrating = on }
    override fun setPaused(on: Boolean) { pipeline.paused = on }
    override fun setBand(deg: Double) { pipeline.bandDeg = deg }
    override fun clearMountShift() { pipeline.clearMountShift() }
    override fun resetForSession() { pipeline.resetForSession() }
    public fun setProfile(profile: DeviceProfile) { pipeline.setProfile(profile) }

    override fun onAccel(tNs: Long, ax: Double, ay: Double, az: Double) { pipeline.onAccel(tNs, ax, ay, az) }

    override fun onGyro(tNs: Long, wx: Double, wy: Double, wz: Double) {
        if (!pipeline.onGyro(tNs, wx, wy, wz)) return
        val back = buffer.backSlot()
        pipeline.copyCurrent(back)
        buffer.publish()
        _samples.tryEmit(back.toSample())
        poseCount++
        if (poseWindowStartNs == 0L) poseWindowStartNs = tNs
        if (tNs - lastDiagNs >= 200_000_000L) {
            lastDiagNs = tNs
            val poseRate = if (tNs > poseWindowStartNs) poseCount * 1e9 / (tNs - poseWindowStartNs) else 0.0
            if (tNs - poseWindowStartNs > 2_000_000_000L) { poseCount = 0; poseWindowStartNs = tNs }
            _diag.value = _diag.value.copy(
                running = true, imuRateHz = pipeline.imuRateHz, poseRateHz = poseRate,
                driftDegPerMin = if (pipeline.drift.driftDegPerMin.isNaN()) pipeline.drift.provisionalDegPerMin else pipeline.drift.driftDegPerMin,
                gyroBias = pipeline.biasVec, biasProgress = pipeline.bias.progress,
                disagreementDeg = pipeline.disagreement.currentDeg, disagreementEvents = pipeline.disagreementEvents,
                mountShiftCount = pipeline.mountShift.count, mountShifted = pipeline.mountShifted, trackingLost = pipeline.trackingLost,
                pitchOutOfRange = pipeline.estimator.pitchOutOfRange, lastFlags = back.flags,
                thetaHeadDeg = back.thetaHeadDeg, thetaDeg = back.thetaDeg, vendorSamples = pipeline.vendorSamples, still = pipeline.still,
            )
        }
    }

    override fun onGameRotation(tNs: Long, x: Double, y: Double, z: Double, w: Double) {
        // Vendor quaternion maps device → world (ENU: Z up). Derive gravity-up in the device frame and apply
        // the same roll convention as the in-app estimator, so the two are directly comparable.
        vendorQ[0] = w; vendorQ[1] = x; vendorQ[2] = y; vendorQ[3] = z
        QuatMath.rotateInverse(vendorQ, 0.0, 0.0, 1.0, vendorG)
        val s = if (pipeline.estimator.rollSign == 0) 1 else pipeline.estimator.rollSign
        val roll = Angles.wrapDeg(s * Angles.radToDeg(atan2(vendorG[0], vendorG[1])) / pipeline.estimator.scaleError + pipeline.estimator.thetaMountDeg)
        pipeline.onVendorRoll(tNs, roll)
    }
}
