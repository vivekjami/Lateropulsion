package com.lateropulsion.app.ui.patient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.fold
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
    val thetaHead: Double = 0.0, val thetaRef: Double? = null, val setBy: String = "",
    val capturing: Boolean = false, val elapsedS: Int = 0, val measured: BaselineMeasurement? = null,
    val valid: Boolean = true, val error: String? = null, val saved: Boolean = false, val running: Boolean = false,
)

@HiltViewModel
class BaselineCaptureViewModel @Inject constructor(
    handle: SavedStateHandle, private val patients: PatientRepository, private val baselines: BaselineRepository,
    private val runtime: SessionRuntime, private val draft: SessionDraft, private val appConfig: AppConfig, private val clock: Clock, private val auth: AuthManager,
) : ViewModel() {
    private val patientId = PatientId(handle.get<String>("patientId")!!)
    val state = MutableStateFlow(CaptureState())
    private var poses: PoseProvider? = null
    private var poseJob: Job? = null
    private var acc: BaselineCaptureAccumulator? = null
    private var captureStartNs = 0L

    init {
        viewModelScope.launch {
            val p = patients.findById(patientId); val name = patients.identity(patientId)?.name ?: ""
            val b = baselines.current(patientId)
            state.value = state.value.copy(patient = p, name = name, baseline = b, thetaRef = b?.thetaRefDeg)
            startPoses()
        }
    }

    private suspend fun startPoses() {
        val pp = runtime.poseProvider(simulatedPatient = draft.simulated)
        poses = pp
        pp.setThetaRef(state.value.thetaRef ?: 0.0)
        pp.start()
        state.value = state.value.copy(running = true)
        poseJob = viewModelScope.launch {
            var lastUi = 0L
            pp.samples.collect { s ->
                val a = acc
                if (a != null) {
                    a.add((s.tNanos - captureStartNs) / 1e9, s.thetaDeg, s.flags)
                    val el = ((s.tNanos - captureStartNs) / 1e9).toInt()
                    if (el >= CAPTURE_S) finishCapture()
                    else if (s.tNanos - lastUi > 200_000_000L) { lastUi = s.tNanos; state.value = state.value.copy(elapsedS = el, thetaHead = s.thetaHeadDeg, valid = ValidityFlags.isValidForMetrics(s.flags)) }
                } else if (s.tNanos - lastUi > 100_000_000L) {
                    lastUi = s.tNanos
                    state.value = state.value.copy(thetaHead = s.thetaHeadDeg, valid = ValidityFlags.isValidForMetrics(s.flags))
                }
            }
        }
    }

    /** θ_ref is the therapist's judgement of the patient's clinical upright, recorded with who set it (ARCH §6.4). */
    fun setMidline() {
        val ref = state.value.thetaHead
        poses?.setThetaRef(ref); poses?.clearMountShift()
        state.value = state.value.copy(thetaRef = ref, setBy = auth.current?.displayName ?: "")
    }

    fun startCapture() {
        if (state.value.thetaRef == null) return
        acc = BaselineCaptureAccumulator(appConfig.metrics.sampleRateHz.toDouble(), appConfig.metrics)
        captureStartNs = clock.monotonicNanos()
        state.value = state.value.copy(capturing = true, elapsedS = 0, measured = null, error = null)
    }

    private fun finishCapture() {
        val a = acc ?: return
        acc = null
        a.result(minValidSeconds = 30.0).fold(
            onSuccess = { state.value = state.value.copy(capturing = false, measured = it) },
            onFailure = { state.value = state.value.copy(capturing = false, error = it.message) },
        )
    }

    fun save() = viewModelScope.launch {
        val s = state.value; val b = s.baseline ?: return@launch; val m = s.measured ?: return@launch; val ref = s.thetaRef ?: return@launch
        val me = auth.current?.id ?: return@launch
        val updated = if (b.locked) b.copy(id = com.lateropulsion.core.model.Ids.baseline(), supersedes = b.id, locked = false, measured = m, thetaRefDeg = ref, thetaRefSetBy = me,
            thetaRefSetAt = clock.nowUtcMillis(), recordedAt = clock.nowUtcMillis(), recordedBy = me)
        else b.copy(measured = m, thetaRefDeg = ref, thetaRefSetBy = me, thetaRefSetAt = clock.nowUtcMillis())
        baselines.save(updated).fold({ state.value = s.copy(saved = true) }, { e -> state.value = s.copy(error = e.message) })
    }

    override fun onCleared() { poseJob?.cancel(); runtime.releasePoseProvider() }

    companion object { const val CAPTURE_S = 60 }
}

@Composable
fun BaselineCaptureScreen(nav: NavHostController, patientId: String, vm: BaselineCaptureViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    if (st.saved) { nav.navigate(Routes.profile(patientId)) { popUpTo(Routes.DASHBOARD) }; return }
    DisposableEffect(Unit) { onDispose { } }
    LpScreen(stringResource(R.string.baseline_measurement), onBack = { nav.popBackStack() }, banner = { st.patient?.let { PatientBanner(it.displayId, st.name) } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.baseline_instruction), style = MaterialTheme.typography.bodyLarge)
            AngleDial(st.thetaHead - (st.thetaRef ?: 0.0), 5.0, kotlin.math.abs(st.thetaHead - (st.thetaRef ?: 0.0)) <= 5.0, st.valid, Modifier.size(240.dp))
            Text(stringResource(R.string.theta_head, st.thetaHead), style = MaterialTheme.typography.titleMedium)
            st.thetaRef?.let { Text(stringResource(R.string.midline_set, it, st.setBy.ifBlank { "—" })) }
            BigButton(stringResource(R.string.set_midline), { vm.setMidline() }, Modifier.fillMaxWidth(), enabled = st.running && !st.capturing, secondary = true)
            if (st.capturing) {
                LinearProgressIndicator(progress = { st.elapsedS / BaselineCaptureViewModel.CAPTURE_S.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.capture_progress, st.elapsedS))
            } else {
                BigButton(stringResource(R.string.start_capture), { vm.startCapture() }, Modifier.fillMaxWidth(), enabled = st.thetaRef != null && st.running)
            }
            st.measured?.let { m ->
                Text(stringResource(R.string.capture_result, m.meanDeg, m.madDeg, m.rmsDeg, m.driftDegPerMin, m.validSamplePct), style = MaterialTheme.typography.bodyLarge)
                BigButton(stringResource(R.string.save_baseline), { vm.save() }, Modifier.fillMaxWidth())
            }
            st.error?.let { WarningText(it) }
            if (!st.running) WarningText("Sensor not available on this device.")
        }
    }
}
