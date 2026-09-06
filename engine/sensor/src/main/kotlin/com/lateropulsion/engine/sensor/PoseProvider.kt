package com.lateropulsion.engine.sensor

import com.lateropulsion.core.model.MutablePose
import com.lateropulsion.core.model.PoseSample
import com.lateropulsion.core.model.Vec3
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Live health of the sensor path, for the debug screen and the pre-session self-check. */
public data class SensorDiagnostics(
    val running: Boolean = false,
    val imuRateHz: Double = 0.0,
    val poseRateHz: Double = 0.0,
    val driftDegPerMin: Double = Double.NaN,
    val gyroBias: Vec3? = null,
    val biasProgress: Double = 0.0,
    val disagreementDeg: Double = 0.0,
    val disagreementEvents: Int = 0,
    val mountShiftCount: Int = 0,
    val mountShifted: Boolean = false,
    val trackingLost: Boolean = false,
    val pitchOutOfRange: Boolean = false,
    val lastFlags: Int = 0,
    val thetaHeadDeg: Double = 0.0,
    val thetaDeg: Double = 0.0,
    val hasGyro: Boolean = true,
    val hasVendorFusion: Boolean = false,
    val vendorSamples: Long = 0,
    val still: Boolean = false,
)

/**
 * Source of head poses. Two implementations: [AndroidPoseProvider] (real IMU) and
 * [SimulatedPoseProvider] (training mode, tests). Consumers on the render thread call [latest];
 * everyone else collects [samples].
 */
public interface PoseProvider {
    public val samples: SharedFlow<PoseSample>
    public val diagnostics: StateFlow<SensorDiagnostics>
    public fun latest(into: MutablePose): Boolean
    public fun start()
    public fun stop()
    public fun setThetaRef(deg: Double)
    public fun setCalibrating(on: Boolean)
    public fun setPaused(on: Boolean)
    public fun setBand(deg: Double)
    public fun clearMountShift()
    public fun resetForSession()
}
