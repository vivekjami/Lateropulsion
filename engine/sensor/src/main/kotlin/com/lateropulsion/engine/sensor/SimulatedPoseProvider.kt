package com.lateropulsion.engine.sensor

import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.MutablePose
import com.lateropulsion.core.model.PoseSample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Simulated pusher patient for the training mode (IMPLEMENTATION Phase 7) and for tests: a slow
 * lean toward the pusher side, low-frequency sway, occasional episodes, measurement noise.
 * Produces synthetic gyro/accel that run through the real [FusionPipeline], so the whole downstream
 * stack is exercised exactly as with a phone.
 */
public class SimulatedPoseProvider(
    profile: DeviceProfile = DeviceProfile("sim", "sim", "sim", "sim", rollSign = 1, thetaMountDeg = 0.0, qualified = true),
    private val rateHz: Int = 100,
    private val model: PatientModel = PatientModel(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val seed: Long = 42L,
    private val realTime: Boolean = true,
) : PoseProvider {
    public data class PatientModel(
        val leanDeg: Double = 8.0,
        val swayAmpDeg: Double = 3.0,
        val swayHz: Double = 0.25,
        val episodeEverySec: Double = 25.0,
        val episodeAmpDeg: Double = 12.0,
        val episodeDurationS: Double = 3.0,
        val noiseDeg: Double = 0.4,
        val gyroNoise: Double = 0.01,
        val gyroBias: Double = 0.004,
    )

    private val pipeline = FusionPipeline(profile)
    private val buffer = PoseTripleBuffer()
    private val _samples = MutableSharedFlow<PoseSample>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _diag = MutableStateFlow(SensorDiagnostics(hasGyro = true, hasVendorFusion = false))
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override val samples: SharedFlow<PoseSample> get() = _samples
    override val diagnostics: StateFlow<SensorDiagnostics> get() = _diag

    override fun latest(into: MutablePose): Boolean = buffer.read(into)

    override fun start() {
        if (job != null) return
        val s = CoroutineScope(SupervisorJob() + dispatcher)
        scope = s
        job = s.launch { run() }
    }

    override fun stop() {
        job?.cancel(); job = null
        scope?.cancel(); scope = null
        _diag.value = _diag.value.copy(running = false)
    }

    override fun setThetaRef(deg: Double) { pipeline.thetaRefDeg = deg }
    override fun setCalibrating(on: Boolean) { pipeline.calibrating = on }
    override fun setPaused(on: Boolean) { pipeline.paused = on }
    override fun setBand(deg: Double) { pipeline.bandDeg = deg }
    override fun clearMountShift() { pipeline.clearMountShift() }
    override fun resetForSession() { pipeline.resetForSession() }

    /** Synthesises a trajectory and feeds it through the pipeline. Public for deterministic tests. */
    public suspend fun run(durationS: Double = Double.POSITIVE_INFINITY) {
        val rnd = Random(seed)
        val dt = 1.0 / rateHz
        var t = 0.0
        var tNs = 1_000_000_000L
        var prevRoll = 0.0
        var count = 0
        var lastDiag = 0.0
        _diag.value = _diag.value.copy(running = true)
        // Let the filter initialise upright for the first 0.5 s (the therapist sets the midline then).
        while (scope?.isActive != false && t < durationS) {
            val settle = t < 0.5
            val rollDeg = if (settle) 0.0 else model.roll(t, rnd)
            val rollRad = rollDeg * PI / 180.0
            // gravity-up in device frame for a roll about device +Z with s = +1: g = (sin θ, cos θ, 0)
            val gx = sin(rollRad) * ComplementaryFilter.STANDARD_GRAVITY
            val gy = cos(rollRad) * ComplementaryFilter.STANDARD_GRAVITY
            val gz = 0.0
            val wz = (rollDeg - prevRoll) * PI / 180.0 / dt
            prevRoll = rollDeg
            pipeline.onAccel(tNs, gx + rnd.nextGaussian(0.05), gy + rnd.nextGaussian(0.05), gz + rnd.nextGaussian(0.05))
            if (pipeline.onGyro(tNs, rnd.nextGaussian(model.gyroNoise) + model.gyroBias, rnd.nextGaussian(model.gyroNoise), wz + rnd.nextGaussian(model.gyroNoise))) {
                val back = buffer.backSlot()
                pipeline.copyCurrent(back)
                buffer.publish()
                _samples.tryEmit(back.toSample())
                count++
            }
            if (t - lastDiag >= 0.2) {
                lastDiag = t
                _diag.value = _diag.value.copy(
                    imuRateHz = rateHz.toDouble(), poseRateHz = rateHz.toDouble(), driftDegPerMin = pipeline.drift.provisionalDegPerMin,
                    gyroBias = pipeline.biasVec, biasProgress = pipeline.bias.progress, mountShiftCount = pipeline.mountShift.count,
                    mountShifted = pipeline.mountShifted, trackingLost = pipeline.trackingLost, lastFlags = pipeline.current.flags,
                    thetaHeadDeg = pipeline.current.thetaHeadDeg, thetaDeg = pipeline.current.thetaDeg, running = true,
                )
            }
            t += dt
            tNs += (dt * 1e9).toLong()
            if (realTime) delay((dt * 1000).toLong().coerceAtLeast(1)) else if (count % 100 == 0) kotlinx.coroutines.yield()
        }
    }

    private fun PatientModel.roll(t: Double, rnd: Random): Double {
        var r = leanDeg + swayAmpDeg * sin(2 * PI * swayHz * t)
        val phase = t % episodeEverySec
        if (phase < episodeDurationS) {
            val e = sin(PI * phase / episodeDurationS)
            r += episodeAmpDeg * e
        }
        return r + rnd.nextGaussian(noiseDeg)
    }

    private fun Random.nextGaussian(sd: Double): Double {
        // Box–Muller
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return sd * kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
    }
}
