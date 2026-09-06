package com.lateropulsion.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lateropulsion.app.R
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.AngleDial
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.InfoCard
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.hmd.HmdActivity
import com.lateropulsion.core.common.Angles
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.PoseSample
import com.lateropulsion.core.model.ValidityFlags
import com.lateropulsion.engine.render.RenderState
import com.lateropulsion.engine.render.RenderStateHolder
import com.lateropulsion.engine.sensor.FieldCalibration
import com.lateropulsion.engine.sensor.SensorDiagnostics
import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.VisualMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.atan2

/** Shared live-sensor ViewModel for the calibration, debug and lens screens. */
@HiltViewModel
class SensorViewModel @Inject constructor(private val runtime: SessionRuntime, private val settings: SettingsStore, private val clock: Clock, private val renderStates: RenderStateHolder) : ViewModel() {
    val sample = MutableStateFlow<PoseSample?>(null)
    val diag = MutableStateFlow(SensorDiagnostics())
    val neutralRaw = MutableStateFlow<Double?>(null)
    val tiltRaw = MutableStateFlow<Double?>(null)
    val result = MutableStateFlow<FieldCalibration.Result?>(null)
    val message = MutableStateFlow<String?>(null)
    val headset = runtime.headset
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val p = runtime.poseProvider(simulatedPatient = false)
            p.start()
            job = viewModelScope.launch { var last = 0L; p.samples.collect { if (it.tNanos - last > 100_000_000L) { last = it.tNanos; sample.value = it; diag.value = p.diagnostics.value } } }
        }
    }

    /** θ_raw = atan2(g_x, g_y) straight from the fused gravity: independent of the current profile sign/mount. */
    fun thetaRaw(): Double? = sample.value?.let { Angles.radToDeg(atan2(it.gx, it.gy)) }
    fun captureNeutral() { neutralRaw.value = thetaRaw(); result.value = null }
    fun captureTilt() { tiltRaw.value = thetaRaw(); val n = neutralRaw.value; val t = tiltRaw.value; if (n != null && t != null) result.value = FieldCalibration.resolve(n, t) }
    fun apply() = viewModelScope.launch {
        val r = result.value ?: return@launch
        if (!r.resolved) return@launch
        settings.update { it.copy(fieldCalibratedSign = r.sign, fieldCalibratedMountDeg = r.thetaMountDeg, fieldCalibratedAt = clock.nowUtcMillis()) }
        runtime.resolveProfiles()
        message.value = "Applied. Restart the sensor screen to see θ_head with the new profile."
    }

    fun setRotationSign(sign: Int) = viewModelScope.launch { settings.update { it.copy(renderRotationSign = sign) }; runtime.resolveProfiles() }
    fun setCameraTurns(turns: Int) = viewModelScope.launch { settings.update { it.copy(cameraQuarterTurnsOverride = turns) }; runtime.resolveProfiles() }
    fun setMirror(on: Boolean) = viewModelScope.launch { settings.update { it.copy(cameraMirror = on) }; runtime.resolveProfiles() }
    fun setIpd(mm: Double) = viewModelScope.launch { settings.update { it.copy(ipdMm = mm) }; runtime.resolveProfiles() }

    /**
     * Lens check: Mode B at k = 1 with the plumb line; the line must sit on a real vertical edge as the head rolls.
     * The current head roll is taken as the zero so the preview is usable before the mount offset is calibrated.
     */
    fun startLensPreview() {
        sample.value?.let { runtime.poseProvider?.setThetaRef(it.thetaHeadDeg) }
        renderStates.set(RenderState(mode = VisualMode.COMPENSATED_VIEW, gain = 1.0, cues = setOf(CueType.PLUMB_LINE, CueType.HORIZON), idle = false, showReadout = true))
    }
    fun stopLensPreview() { renderStates.set(RenderState.NEUTRAL) }

    override fun onCleared() { job?.cancel(); runtime.releasePoseProvider(); renderStates.set(RenderState.NEUTRAL) }
}

@Composable
fun CalibrationScreen(nav: NavHostController, vm: SensorViewModel = hiltViewModel()) {
    val s by vm.sample.collectAsState()
    val neutral by vm.neutralRaw.collectAsState()
    val tilt by vm.tiltRaw.collectAsState()
    val r by vm.result.collectAsState()
    val msg by vm.message.collectAsState()
    LpScreen(stringResource(R.string.calibration), onBack = { nav.popBackStack() }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.theta_raw, vm.thetaRaw() ?: 0.0), style = MaterialTheme.typography.headlineMedium)
            s?.let { Text(stringResource(R.string.theta_head, it.thetaHeadDeg)) }
            Text(stringResource(R.string.calib_step1), style = MaterialTheme.typography.bodyLarge)
            BigButton(stringResource(R.string.capture_neutral) + (neutral?.let { " (%.1f°)".format(it) } ?: ""), { vm.captureNeutral() }, Modifier.fillMaxWidth(), enabled = s != null)
            Text(stringResource(R.string.calib_step2), style = MaterialTheme.typography.bodyLarge)
            BigButton(stringResource(R.string.capture_tilt) + (tilt?.let { " (%.1f°)".format(it) } ?: ""), { vm.captureTilt() }, Modifier.fillMaxWidth(), enabled = neutral != null)
            val d by vm.diag.collectAsState()
            r?.let { res ->
                if (res.resolved) Text(stringResource(R.string.calib_result, res.sign, res.thetaMountDeg, res.message), style = MaterialTheme.typography.titleMedium) else WarningText(res.message)
                val vendorOk = !d.hasVendorFusion || d.disagreementDeg <= 2.0
                Text("Cross-check vs phone's own sensor fusion: ${"%.1f".format(d.disagreementDeg)}° (${d.vendorSamples} samples)" + if (vendorOk) " — agrees" else " — DISAGREES, do not apply")
                BigButton(stringResource(R.string.calib_apply), { vm.apply() }, Modifier.fillMaxWidth(), enabled = res.resolved && vendorOk)
            }
            msg?.let { Text(it) }
            Text("A jig-derived profile (tools/jig/analyse.py) replaces this field calibration and is required for a qualified device (REQ-SEN-040).", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SensorDebugScreen(nav: NavHostController, vm: SensorViewModel = hiltViewModel()) {
    val s by vm.sample.collectAsState()
    val d by vm.diag.collectAsState()
    LpScreen(stringResource(R.string.sensor_debug), onBack = { nav.popBackStack() }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val p = s
            AngleDial(p?.thetaHeadDeg ?: 0.0, 5.0, true, p?.let { ValidityFlags.isValidForMetrics(it.flags) } ?: false, Modifier.size(240.dp))
            InfoCard("Live") {
                Text(stringResource(R.string.theta_raw, vm.thetaRaw() ?: 0.0)); Text(stringResource(R.string.theta_head, p?.thetaHeadDeg ?: 0.0))
                Text("g = (${"%.3f".format(p?.gx ?: 0.0)}, ${"%.3f".format(p?.gy ?: 0.0)}, ${"%.3f".format(p?.gz ?: 0.0)})  ω = (${"%.3f".format(p?.wx ?: 0.0)}, ${"%.3f".format(p?.wy ?: 0.0)}, ${"%.3f".format(p?.wz ?: 0.0)}) rad/s")
                Text("flags: ${p?.let { ValidityFlags.describe(it.flags).joinToString() } ?: "—"}")
            }
            InfoCard("Diagnostics") {
                Text(stringResource(R.string.imu_rate, d.imuRateHz, d.poseRateHz))
                Text(stringResource(R.string.drift, d.driftDegPerMin))
                Text(stringResource(R.string.bias, d.gyroBias?.let { "(%.4f, %.4f, %.4f) rad/s".format(it.x, it.y, it.z) } ?: "estimating ${(d.biasProgress * 100).toInt()} %"))
                Text("vendor disagreement ${"%.1f".format(d.disagreementDeg)}° (${d.disagreementEvents} events, ${d.vendorSamples} samples) · jolts ${d.mountShiftCount} · still ${d.still}")
                Text("tracking lost ${d.trackingLost} · gyro ${d.hasGyro} · vendor fusion ${d.hasVendorFusion}")
            }
        }
    }
}

@Composable
fun LensCalibrationScreen(nav: NavHostController, vm: SensorViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val hs by vm.headset.collectAsState()
    val mono = hs?.isMono != false
    LpScreen(stringResource(if (mono) R.string.display_check else R.string.lens_calibration), onBack = { vm.stopLensPreview(); nav.popBackStack() }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (mono) R.string.visor_check_text else R.string.lens_check_text), style = MaterialTheme.typography.bodyLarge)
            hs?.let { Text("Profile: ${it.name}", style = MaterialTheme.typography.bodyMedium) }
            val settings by vm.let { it.diag }.collectAsState() // keeps the sensor alive while on this screen
            @Suppress("UNUSED_VARIABLE") val unused = settings
            if (!mono) {
                var ipd = 63.0
                Text(stringResource(R.string.ipd))
                Slider(value = ipd.toFloat(), onValueChange = { ipd = it.toDouble(); vm.setIpd(Math.round(it).toDouble()) }, valueRange = 56f..72f, steps = 15)
            }
            Text("Mode B counter-rotation direction (the picture must rotate against your head roll):")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Rotation +1", { vm.setRotationSign(1) }, Modifier.weight(1f), secondary = true)
                BigButton("Rotation −1", { vm.setRotationSign(-1) }, Modifier.weight(1f), secondary = true)
            }
            // Only half turns: a landscape-locked view on a phone whose camera buffer is landscape never needs a quarter turn,
            // and a quarter turn would show a portrait strip in the middle of the screen (ADR-017).
            Text(stringResource(R.string.camera_orientation_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(stringResource(R.string.camera_auto), { vm.setCameraTurns(-1) }, Modifier.weight(1f), secondary = true)
                BigButton(stringResource(R.string.flip_180), { vm.setCameraTurns(2) }, Modifier.weight(1f), secondary = true)
            }
            Text(stringResource(R.string.mirror_hint_check))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(stringResource(R.string.mirror_off), { vm.setMirror(false) }, Modifier.weight(1f), secondary = true)
                BigButton(stringResource(R.string.mirror_on), { vm.setMirror(true) }, Modifier.weight(1f), secondary = true)
            }
            BigButton(stringResource(R.string.start_preview), { vm.startLensPreview(); ctx.startActivity(Intent(ctx, HmdActivity::class.java)) }, Modifier.fillMaxWidth())
            if (!mono) {
                Text("Distortion coefficients (k1, k2) come from config/headsets/*.json; tune per headset model and re-run the Phase 3 plumb-line check (overlay vertical within 1° over ±30°).",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
