package com.lateropulsion.app.ui.patient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lateropulsion.app.R
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.session.SessionDraft
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.AngleDial
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.BigNumber
import com.lateropulsion.app.ui.components.Expander
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.StepHeader
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.fold
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineMeasurement
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.ValidityFlags
import com.lateropulsion.engine.sensor.PoseProvider
import com.lateropulsion.feature.assessment.BaselineCaptureAccumulator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CaptureState(
    val patient: Patient? = null, val name: String = "", val baseline: Baseline? = null,
    val thetaHead: Double = 0.0,
    /** Reference the tilt is measured against. 0 = true vertical (gravity), the default (ADR-020). */
    val thetaRef: Double = 0.0, val setBy: String = "",
    val capturing: Boolean = false, val elapsedS: Int = 0, val captureS: Int = 60, val measured: BaselineMeasurement? = null,
    val valid: Boolean = true, val error: String? = null, val saved: Boolean = false, val running: Boolean = false,
    /** The phone's roll has not been calibrated on the visor: the number would be meaningless. Blocks unless research mode or training. */
    val uncalibrated: Boolean = false, val needsCalibration: Boolean = false, val simulated: Boolean = false,
)

/**
 * The baseline is the patient's error: how far from true vertical they hold their head when they believe they are
 * upright, and how steady they are. One tap, [captureS] seconds, saved automatically (ADR-020). The clinical
 * picture (severity, balance, assistance) gets defaults if the operator has not filled it in yet.
 */
@HiltViewModel
class BaselineCaptureViewModel @Inject constructor(
    handle: SavedStateHandle, private val patients: PatientRepository, private val baselines: BaselineRepository,
    private val runtime: SessionRuntime, private val draft: SessionDraft, private val appConfig: AppConfig, private val clock: Clock, private val auth: AuthManager,
    private val settings: SettingsStore,
) : ViewModel() {
    private val patientId = PatientId(handle.get<String>("patientId")!!)
    val state = MutableStateFlow(CaptureState(captureS = appConfig.session.baselineCaptureS))
    private var poses: PoseProvider? = null
    private var poseJob: Job? = null
    private var acc: BaselineCaptureAccumulator? = null
    private var captureStartNs = 0L

    init {
        viewModelScope.launch {
            val p = patients.findById(patientId); val name = patients.identity(patientId)?.name ?: ""
            val b = baselines.current(patientId)
            val (dev, _) = runtime.resolveProfiles()
            val research = settings.current().researchMode
            state.value = state.value.copy(patient = p, name = name, baseline = b, thetaRef = b?.thetaRefDeg ?: 0.0, simulated = draft.simulated,
                uncalibrated = !dev.allowsClinicalSession && !draft.simulated, needsCalibration = !dev.allowsClinicalSession && !research && !draft.simulated)
            startPoses()
        }
    }

    private suspend fun startPoses() {
        val pp = runtime.poseProvider(simulatedPatient = draft.simulated)
        poses = pp
        pp.setThetaRef(state.value.thetaRef)
        pp.start()
        state.value = state.value.copy(running = true)
        poseJob = viewModelScope.launch {
            var lastUi = 0L
            pp.samples.collect { s ->
                val a = acc
                if (a != null) {
                    a.add((s.tNanos - captureStartNs) / 1e9, s.thetaDeg, s.flags)
                    val el = ((s.tNanos - captureStartNs) / 1e9).toInt()
                    if (el >= state.value.captureS) finishCapture()
                    else if (s.tNanos - lastUi > 200_000_000L) { lastUi = s.tNanos; state.value = state.value.copy(elapsedS = el, thetaHead = s.thetaHeadDeg, valid = ValidityFlags.isValidForMetrics(s.flags)) }
                } else if (s.tNanos - lastUi > 100_000_000L) {
                    lastUi = s.tNanos
                    state.value = state.value.copy(thetaHead = s.thetaHeadDeg, valid = ValidityFlags.isValidForMetrics(s.flags))
                }
            }
        }
    }

    /** Default: measure against true vertical. */
    fun useTrueVertical() { poses?.setThetaRef(0.0); poses?.clearMountShift(); state.value = state.value.copy(thetaRef = 0.0, setBy = "") }

    /** Optional clinical judgement: take the patient's current posture as their midline (ARCH §6.4), recorded with who set it. */
    fun useCurrentPostureAsMidline() {
        val ref = state.value.thetaHead
        poses?.setThetaRef(ref); poses?.clearMountShift()
        state.value = state.value.copy(thetaRef = ref, setBy = auth.current?.displayName ?: "")
    }

    fun startCapture() {
        if (state.value.needsCalibration) return
        poses?.clearMountShift()
        acc = BaselineCaptureAccumulator(appConfig.metrics.sampleRateHz.toDouble(), appConfig.metrics)
        captureStartNs = clock.monotonicNanos()
        state.value = state.value.copy(capturing = true, elapsedS = 0, measured = null, error = null)
    }

    private fun finishCapture() {
        val a = acc ?: return
        acc = null
        a.result(minValidSeconds = minOf(30.0, state.value.captureS / 2.0)).fold(
            onSuccess = { m -> state.value = state.value.copy(capturing = false, measured = m); save(m) },
            onFailure = { state.value = state.value.copy(capturing = false, error = it.message) },
        )
    }

    /** Saves straight away: a measurement the operator has to remember to save is a measurement that gets lost. */
    private fun save(m: BaselineMeasurement) = viewModelScope.launch {
        val s = state.value
        val me = auth.current?.id ?: return@launch
        val patient = s.patient ?: return@launch
        val now = clock.nowUtcMillis()
        val base = s.baseline ?: Baseline.defaultFor(patient, me, now)
        val updated = if (base.locked) {
            base.copy(id = com.lateropulsion.core.model.Ids.baseline(), supersedes = base.id, locked = false, measured = m, thetaRefDeg = s.thetaRef, thetaRefSetBy = me,
                thetaRefSetAt = now, recordedAt = now, recordedBy = me)
        } else {
            base.copy(measured = m, thetaRefDeg = s.thetaRef, thetaRefSetBy = me, thetaRefSetAt = now)
        }
        baselines.save(updated).fold({ state.value = state.value.copy(saved = true, baseline = updated) }, { e -> state.value = state.value.copy(error = e.message) })
    }

    override fun onCleared() { poseJob?.cancel(); runtime.releasePoseProvider() }
}

@Composable
fun BaselineCaptureScreen(nav: NavHostController, patientId: String, vm: BaselineCaptureViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    LpScreen(stringResource(R.string.baseline_measurement), onBack = { nav.popBackStack() }, banner = { st.patient?.let { PatientBanner(it.displayId, st.name) } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StepHeader(2, 3, stringResource(R.string.step_baseline), stringResource(R.string.step_baseline_hint))
            if (st.uncalibrated) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WarningText(stringResource(if (st.needsCalibration) R.string.visor_not_calibrated else R.string.visor_not_calibrated_research))
                        BigButton(stringResource(R.string.calibrate_visor), { nav.navigate(Routes.CALIBRATION) }, Modifier.fillMaxWidth())
                    }
                }
            }
            val m = st.measured
            if (m != null && st.saved) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.baseline_result_title), style = MaterialTheme.typography.titleMedium)
                        BigNumber(Baseline.describeTilt(m.meanDeg), stringResource(R.string.baseline_result_mean))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BigNumber("±${"%.1f".format(m.madDeg)}°", stringResource(R.string.baseline_result_steadiness), emphasis = false)
                            BigNumber("${"%.0f".format(m.tib5Pct)} %", stringResource(R.string.baseline_result_tib5), emphasis = false)
                        }
                        Text(stringResource(R.string.baseline_saved_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BigButton(stringResource(R.string.continue_to_session), { nav.navigate(Routes.setup(patientId)) }, Modifier.fillMaxWidth())
                BigButton(stringResource(R.string.repeat_measurement), { vm.startCapture() }, Modifier.fillMaxWidth(), secondary = true, enabled = st.running && !st.needsCalibration)
            } else {
                Text(stringResource(R.string.baseline_instruction), style = MaterialTheme.typography.bodyLarge)
                AngleDial(st.thetaHead - st.thetaRef, 5.0, kotlin.math.abs(st.thetaHead - st.thetaRef) <= 5.0, st.valid, Modifier.size(240.dp))
                Text(Baseline.describeTilt(st.thetaHead - st.thetaRef), style = MaterialTheme.typography.titleMedium)
                if (!st.valid && st.running) WarningText(stringResource(R.string.visor_not_level))
                if (st.capturing) {
                    LinearProgressIndicator(progress = { st.elapsedS / st.captureS.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.capture_progress_of, st.elapsedS, st.captureS), style = MaterialTheme.typography.titleMedium)
                } else {
                    BigButton(stringResource(R.string.start_capture_s, st.captureS), { vm.startCapture() }, Modifier.fillMaxWidth(), enabled = st.running && !st.needsCalibration)
                }
                Expander(stringResource(R.string.reference_title),
                    if (st.thetaRef == 0.0) stringResource(R.string.reference_true_vertical) else stringResource(R.string.midline_set, st.thetaRef, st.setBy.ifBlank { "—" })) {
                    Text(stringResource(R.string.reference_explain), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton(stringResource(R.string.use_true_vertical), { vm.useTrueVertical() }, Modifier.weight(1f), secondary = true, enabled = !st.capturing)
                        BigButton(stringResource(R.string.set_midline), { vm.useCurrentPostureAsMidline() }, Modifier.weight(1f), secondary = true, enabled = st.running && !st.capturing)
                    }
                }
            }
            st.error?.let { WarningText(it) }
            if (!st.running) WarningText(stringResource(R.string.sensor_unavailable))
            if (st.simulated) Text(stringResource(R.string.simulated_note), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
