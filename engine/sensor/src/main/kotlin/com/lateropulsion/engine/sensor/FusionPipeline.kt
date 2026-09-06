package com.lateropulsion.engine.sensor

import com.lateropulsion.core.common.Angles
import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.MetricsConfig
import com.lateropulsion.core.model.MutablePose
import com.lateropulsion.core.model.SafetyConfig
import com.lateropulsion.core.model.ValidityFlags
import com.lateropulsion.core.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pure, allocation-free pipeline from raw IMU events to a [MutablePose]. Gyro events drive the update;
 * the latest accelerometer sample corrects it. Platform code (Android or a simulator) feeds it.
 *
 * Thread confinement: all methods are called from the fusion thread only; the output is handed to other
 * threads via [PoseTripleBuffer].
 */
public class FusionPipeline(
    profile: DeviceProfile,
    private val safety: SafetyConfig = SafetyConfig(),
    metrics: MetricsConfig = MetricsConfig(),
    alpha: Double = 0.02,
) {
    public val filter: ComplementaryFilter = ComplementaryFilter(alpha)
    public val estimator: RollEstimator = RollEstimator(profile.rollSign, profile.thetaMountDeg, profile.scaleError, metrics.pitchGuardGz)
    public val bias: GyroBiasEstimator = GyroBiasEstimator()
    public val drift: DriftMonitor = DriftMonitor()
    public val mountShift: MountShiftDetector = MountShiftDetector(safety.mountShiftStepDeg, safety.mountShiftWindowMs * 1_000_000L)
    public val disagreement: FusionDisagreementMonitor = FusionDisagreementMonitor(safety.fusionDisagreementDeg)

    public var thetaRefDeg: Double = 0.0
    public var bandDeg: Double = metrics.bandPrimaryDeg
    public var calibrating: Boolean = false
    public var paused: Boolean = false
    /** Sticky until the therapist re-seats the headset and re-checks the midline. */
    public var mountShifted: Boolean = false
        private set
    public var trackingLost: Boolean = false
        private set
    public var biasVec: Vec3? = null
        private set

    private var lastGyroNs = -1L
    private var lastAccelNs = -1L
    private var ax = 0.0; private var ay = 0.0; private var az = 0.0
    private var haveAccel = false
    /** Gyro-only orientation (never corrected by the accelerometer) for drift and jolt detection. */
    private val gyroOnlyQ = DoubleArray(4).also { QuatMath.identity(it) }
    private var gyroOnlyInit = false
    private val gq = DoubleArray(3)
    private val tmpA = DoubleArray(4)
    private val tmpB = DoubleArray(4)
    private val g = DoubleArray(3)
    private var gyroCount = 0L
    private var rateWindowStartNs = -1L
    private var rateCount = 0
    public var imuRateHz: Double = 0.0
        private set
    public var disagreementEvents: Int = 0
        private set
    public var pitchOutOfRangeCount: Long = 0
        private set
    public var vendorSamples: Long = 0
        private set
    /** True while the device is not moving: |ω| small and |a| within 10 % of 1 g. Monitors only trust these samples. */
    public var still: Boolean = false
        private set

    private val pose = MutablePose()

    public fun setProfile(profile: DeviceProfile) {
        estimator.rollSign = profile.rollSign
        estimator.thetaMountDeg = profile.thetaMountDeg
        estimator.scaleError = profile.scaleError
    }

    public fun clearMountShift() { mountShifted = false }

    public fun onAccel(tNs: Long, x: Double, y: Double, z: Double) {
        ax = x; ay = y; az = z; haveAccel = true; lastAccelNs = tNs
        if (!filter.initialized) filter.initializeFromAccel(x, y, z)
    }

    /** Returns true when a new pose is available in [current]. */
    public fun onGyro(tNs: Long, wxRaw: Double, wyRaw: Double, wzRaw: Double): Boolean {
        if (!haveAccel) return false
        if (lastGyroNs < 0) { lastGyroNs = tNs; rateWindowStartNs = tNs; return false }
        var dt = (tNs - lastGyroNs) / 1e9
        val gapMs = dt * 1000.0
        if (gapMs > safety.imuDropoutMs) trackingLost = true
        else if (trackingLost && gapMs <= safety.imuDropoutMs) trackingLost = false
        lastGyroNs = tNs
        if (dt <= 0 || dt > 0.5) dt = 0.005
        gyroCount++
        rateCount++
        if (tNs - rateWindowStartNs >= 1_000_000_000L) {
            imuRateHz = rateCount * 1e9 / (tNs - rateWindowStartNs)
            rateCount = 0; rateWindowStartNs = tNs
        }

        bias.feed(wxRaw, wyRaw, wzRaw, ax, ay, az, dt)
        if (biasVec == null) bias.result?.let { biasVec = it }
        val b = biasVec
        val wx = if (b != null) wxRaw - b.x else wxRaw
        val wy = if (b != null) wyRaw - b.y else wyRaw
        val wz = if (b != null) wzRaw - b.z else wzRaw

        filter.integrateGyro(wx, wy, wz, dt)
        filter.correctWithAccel(ax, ay, az)
        filter.gravity(g)

        // Gyro-only roll in 3D: the same roll extraction applied to an orientation integrated from the gyro alone,
        // so pitch/yaw hand motion does not masquerade as roll disagreement.
        if (!gyroOnlyInit) { gyroOnlyQ[0] = filter.q[0]; gyroOnlyQ[1] = filter.q[1]; gyroOnlyQ[2] = filter.q[2]; gyroOnlyQ[3] = filter.q[3]; gyroOnlyInit = true }
        else QuatMath.integrateBodyRate(gyroOnlyQ, wx, wy, wz, dt, tmpA, tmpB)
        QuatMath.rotateInverse(gyroOnlyQ, 0.0, 1.0, 0.0, gq)
        val s = if (estimator.rollSign == 0) 1 else estimator.rollSign
        val gyroRollDeg = Angles.wrapDeg(s * Angles.radToDeg(atan2(gq[0], gq[1])) / estimator.scaleError + estimator.thetaMountDeg)
        val gravityRollDeg = Angles.wrapDeg(s * Angles.radToDeg(atan2(ax, ay)) / estimator.scaleError + estimator.thetaMountDeg)
        val an = sqrt(ax * ax + ay * ay + az * az)
        val accelSteady = abs(an / ComplementaryFilter.STANDARD_GRAVITY - 1.0) < 0.10
        val nowStill = accelSteady && abs(wx) + abs(wy) + abs(wz) < 0.15
        if (nowStill && !still) {
            // Motion just ended: gyro-only integration through the movement is not a drift measurement.
            // Re-align the gyro-only orientation to the fused one and start a fresh drift window.
            gyroOnlyQ[0] = filter.q[0]; gyroOnlyQ[1] = filter.q[1]; gyroOnlyQ[2] = filter.q[2]; gyroOnlyQ[3] = filter.q[3]
            drift.reset()
        }
        still = nowStill
        // Drift: slope of (gyro-only roll − gravity roll) over a continuous still period (jig hold, headset at rest).
        if (still) drift.feed(tNs / 1e9, gyroRollDeg, gravityRollDeg)
        // Jolt / possible headset shift: a gravity-roll step the gyro does not explain, judged only on steady samples.
        if (!calibrating && accelSteady && mountShift.feed(tNs, gravityRollDeg, gyroRollDeg)) mountShifted = true

        val ok = estimator.estimate(g[0], g[1], g[2], thetaRefDeg)
        if (!ok) pitchOutOfRangeCount++

        pose.tNanos = tNs
        pose.qw = filter.q[0]; pose.qx = filter.q[1]; pose.qy = filter.q[2]; pose.qz = filter.q[3]
        pose.thetaHeadDeg = estimator.thetaHeadDeg
        pose.thetaDeg = estimator.thetaDeg
        pose.gx = g[0]; pose.gy = g[1]; pose.gz = g[2]
        pose.wx = wx; pose.wy = wy; pose.wz = wz
        var flags = ValidityFlags.VALID
        if (!ok) flags = flags or ValidityFlags.PITCH_OUT_OF_RANGE
        if (trackingLost) flags = flags or ValidityFlags.TRACKING_LOST
        if (mountShifted) flags = flags or ValidityFlags.MOUNT_SHIFT
        if (calibrating) flags = flags or ValidityFlags.CALIBRATING
        if (paused) flags = flags or ValidityFlags.PAUSED
        if (abs(estimator.thetaDeg) <= bandDeg) flags = flags or ValidityFlags.IN_BAND
        if (disagreement.disagreeing) flags = flags or ValidityFlags.FUSION_DISAGREEMENT
        pose.flags = flags
        return true
    }

    /** Vendor fusion cross-check. `vendorRollDeg` must already be in the θ_head convention. */
    public fun onVendorRoll(tNs: Long, vendorRollDeg: Double) {
        vendorSamples++
        if (disagreement.feed(tNs, estimator.thetaHeadDeg, vendorRollDeg)) disagreementEvents++
    }

    public val current: MutablePose get() = pose

    public fun copyCurrent(into: MutablePose) { into.copyFrom(pose) }

    public fun resetForSession() {
        bias.reset(); biasVec = null; drift.reset(); mountShift.reset(); disagreement.reset()
        mountShifted = false; trackingLost = false; gyroOnlyInit = false; disagreementEvents = 0; pitchOutOfRangeCount = 0
    }
}
