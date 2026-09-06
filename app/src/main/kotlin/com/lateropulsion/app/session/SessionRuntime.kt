package com.lateropulsion.app.session

import android.content.Context
import android.os.Build
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.HeadsetProfile
import com.lateropulsion.core.datastore.AssetConfigRepository
import com.lateropulsion.engine.sensor.AndroidPoseProvider
import com.lateropulsion.engine.sensor.ImuCapabilities
import com.lateropulsion.engine.sensor.PoseProvider
import com.lateropulsion.engine.sensor.SimulatedPoseProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the long-lived engine objects the HMD activity and the therapist screens share: the pose
 * provider, the resolved device and headset profiles. Built once per process, re-resolved when the
 * settings change.
 */
@Singleton
class SessionRuntime @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val config: AssetConfigRepository,
    private val settings: SettingsStore,
    val appConfig: AppConfig,
) {
    private val _device = MutableStateFlow<DeviceProfile?>(null)
    private val _headset = MutableStateFlow<HeadsetProfile?>(null)
    val device: StateFlow<DeviceProfile?> get() = _device
    val headset: StateFlow<HeadsetProfile?> get() = _headset

    var poseProvider: PoseProvider? = null
        private set
    var simulated: Boolean = false
        private set
    /** Read at HMD start; -1 = automatic. */
    var cameraQuarterTurnsOverride: Int = -1
        private set
    /** Mode B rotation direction override from the lens check; 0 = use the device profile. */
    var renderRotationSignOverride: Int = 0
        private set

    val imuCapabilities: ImuCapabilities by lazy {
        ImuCapabilities.probe(ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager)
    }

    /** Resolves the device profile: settings override → model pattern → generic; applies field calibration if present. */
    suspend fun resolveProfiles(): Pair<DeviceProfile, HeadsetProfile> {
        val s = settings.current()
        val profiles = config.deviceProfiles()
        var dev = s.deviceProfileId?.let { id -> profiles.firstOrNull { it.id == id } } ?: config.deviceProfileFor(Build.MODEL)
            ?: DeviceProfile(AssetConfigRepository.GENERIC_ID, "any", Build.MODEL, ".*", 0, 0.0)
        val fieldMount = s.fieldCalibratedMountDeg
        if (!dev.qualified && s.fieldCalibratedSign != 0 && fieldMount != null) {
            dev = dev.copy(rollSign = s.fieldCalibratedSign, thetaMountDeg = fieldMount, fieldQualified = true, fieldQualifiedAt = s.fieldCalibratedAt)
        }
        val hs = (s.headsetProfileId?.let { id -> config.headsetProfiles().firstOrNull { it.id == id } } ?: config.headsetProfiles().firstOrNull() ?: HeadsetProfile("default", "Default"))
            .let { h -> s.ipdMm?.let { h.copy(ipdMm = it) } ?: h }
        cameraQuarterTurnsOverride = s.cameraQuarterTurnsOverride
        renderRotationSignOverride = s.renderRotationSign
        if (renderRotationSignOverride != 0) dev = dev.copy(renderRotationSign = renderRotationSignOverride)
        _device.value = dev; _headset.value = hs
        return dev to hs
    }

    /** Real IMU, or the simulated patient for training mode. Stops any previous provider. */
    suspend fun poseProvider(simulatedPatient: Boolean): PoseProvider {
        poseProvider?.stop()
        val (dev, _) = resolveProfiles()
        simulated = simulatedPatient
        val p: PoseProvider = if (simulatedPatient || !imuCapabilities.measurementCapable) {
            SimulatedPoseProvider(dev.copy(rollSign = 1, thetaMountDeg = 0.0), rateHz = appConfig.metrics.sampleRateHz)
        } else {
            AndroidPoseProvider(ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager, dev, appConfig.safety, appConfig.metrics)
        }
        poseProvider = p
        return p
    }

    fun releasePoseProvider() { poseProvider?.stop(); poseProvider = null }
}
