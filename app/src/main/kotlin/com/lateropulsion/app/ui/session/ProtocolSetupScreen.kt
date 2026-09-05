package com.lateropulsion.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.lateropulsion.app.session.HistoryMapper
import com.lateropulsion.app.session.SessionDraft
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.CheckRow
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.LpTextField
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.Selector
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.PreSessionChecklist
import com.lateropulsion.core.model.ProtocolSpec
import com.lateropulsion.core.model.SessionRepository
import com.lateropulsion.core.model.SessionSpec
import com.lateropulsion.core.model.VisualMode
import com.lateropulsion.feature.protocol.GainScheduler
import com.lateropulsion.feature.protocol.PreconditionContext
import com.lateropulsion.feature.protocol.PreconditionResult
import com.lateropulsion.feature.protocol.SessionHistoryEntry
import com.lateropulsion.feature.protocol.SessionPreconditions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupState(
    val patient: Patient? = null, val name: String = "", val baseline: Baseline? = null, val protocols: List<ProtocolSpec> = emptyList(),
    val protocol: ProtocolSpec? = null, val mode: VisualMode = VisualMode.VERTICAL_REFERENCE, val gain: Double = 0.0, val proposedGain: Double = 0.0,
    val override: String = "", val advanced: Boolean = false, val history: List<SessionHistoryEntry> = emptyList(),
    val preview: PreconditionResult? = null, val ready: Boolean = false, val researchMode: Boolean = false,
)

@HiltViewModel
class ProtocolSetupViewModel @Inject constructor(
    handle: SavedStateHandle, private val patients: PatientRepository, private val baselines: BaselineRepository, private val sessions: SessionRepository,
    private val config: ConfigRepository, private val runtime: SessionRuntime, private val draft: SessionDraft, private val settings: SettingsStore,
    private val appConfig: AppConfig, private val auth: AuthManager,
) : ViewModel() {
    private val patientId = PatientId(handle.get<String>("patientId")!!)
    val state = MutableStateFlow(SetupState())

    init {
        viewModelScope.launch {
            val p = patients.findById(patientId); val name = patients.identity(patientId)?.name ?: ""
            val b = baselines.current(patientId)
            val all = config.protocols().filter { "assessment" !in it.tags }
            val hist = HistoryMapper.entries(sessions.listForPatient(patientId), sessions.summariesForPatient(patientId))
            val last = hist.lastOrNull()
            val proto = all.firstOrNull { it.protocolId == last?.protocolId } ?: all.firstOrNull { it.protocolId == "std-sitting-v3" } ?: all.firstOrNull()
            state.value = SetupState(p, name, b, all, proto, proto?.visualMode ?: VisualMode.VERTICAL_REFERENCE, history = hist, researchMode = settings.current().researchMode)
            proto?.let { selectProtocol(it) }
        }
    }

    fun selectProtocol(p: ProtocolSpec) {
        val s = state.value
        val proposed = GainScheduler.proposeNext(p, s.history.filter { it.protocolId == p.protocolId }, p.gainInitial ?: appConfig.visual.gainInitial)
        state.value = s.copy(protocol = p, mode = p.visualMode, proposedGain = proposed, gain = proposed)
        preview()
    }

    fun setGain(g: Double) { state.value = state.value.copy(gain = (Math.round(g * 20) / 20.0)); preview() }
    fun setOverride(t: String) { state.value = state.value.copy(override = t); preview() }
    fun setAdvanced(a: Boolean) { state.value = state.value.copy(advanced = a); preview() }

    private fun preview() = viewModelScope.launch {
        val s = state.value; val p = s.protocol ?: return@launch; val patient = s.patient ?: return@launch
        val (dev, hs) = runtime.resolveProfiles()
        val spec = buildSpec(s, p, dev, hs) ?: return@launch
        val full = PreSessionChecklist(true, true, true, true, true, true, true, true, dev.qualified || s.researchMode)
        val result = SessionPreconditions.check(PreconditionContext(spec, patient, full, s.history, s.protocols.associateBy { it.protocolId }, s.researchMode))
        state.value = state.value.copy(preview = result)
    }

    private suspend fun buildSpec(s: SetupState, p: ProtocolSpec, dev: com.lateropulsion.core.model.DeviceProfile, hs: com.lateropulsion.core.model.HeadsetProfile): SessionSpec? {
        val me = auth.current?.id ?: return null
        val patient = s.patient ?: return null
        val b = s.baseline
        return SessionSpec(
            sessionId = Ids.session(), patientId = patient.id, patientDisplayId = patient.displayId, clinicianId = me, protocol = p, visualMode = s.mode,
            gain = if (s.mode == VisualMode.VERTICAL_REFERENCE) 0.0 else s.gain, thetaRefDeg = b?.thetaRefDeg ?: 0.0, thetaRefSetBy = b?.thetaRefSetBy ?: me,
            baselineId = b?.id, deviceProfile = dev, headsetProfile = hs, sessionNumber = sessions.nextSessionNumber(patient.id), advancedProtocol = s.advanced,
            config = appConfig, overrideReason = s.override.ifBlank { null },
        )
    }

    fun proceed() = viewModelScope.launch {
        val s = state.value; val p = s.protocol ?: return@launch
        val (dev, hs) = runtime.resolveProfiles()
        val spec = buildSpec(s, p, dev, hs) ?: return@launch
        draft.spec.value = spec; draft.patient = s.patient; draft.patientName = s.name; draft.baseline = s.baseline; draft.history = s.history
        state.value = s.copy(ready = true)
    }
}

@Composable
fun ProtocolSetupScreen(nav: NavHostController, patientId: String, vm: ProtocolSetupViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    if (st.ready) { nav.navigate(Routes.SESSION_PRECHECK); return }
    LpScreen(stringResource(R.string.protocol_setup), onBack = { nav.popBackStack() }, banner = { st.patient?.let { PatientBanner(it.displayId, st.name) } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Selector(stringResource(R.string.protocol), st.protocols, st.protocol, { "${it.name} (${it.positionRequired.name.lowercase().replace('_', ' ')})" }, { vm.selectProtocol(it) })
            st.protocol?.let { p ->
                Text(p.description, style = MaterialTheme.typography.bodyMedium)
                Text("Blocks: " + p.blocks.joinToString(" → ") { "${it.blockId} ${it.durationS}s" } + " · total ${p.totalPlannedS / 60} min", style = MaterialTheme.typography.bodyMedium)
                Text(if (p.visualMode == VisualMode.VERTICAL_REFERENCE) stringResource(R.string.mode_a) else stringResource(R.string.mode_b), style = MaterialTheme.typography.titleMedium)
                if (p.visualMode == VisualMode.COMPENSATED_VIEW) {
                    Text(stringResource(R.string.gain_proposed, st.proposedGain))
                    Text("${stringResource(R.string.gain)}: ${"%.2f".format(st.gain)}")
                    Slider(value = st.gain.toFloat(), onValueChange = { vm.setGain(it.toDouble()) }, valueRange = 0f..1f, steps = 19)
                    CheckRow(st.advanced, { vm.setAdvanced(it) }, stringResource(R.string.advanced_protocol))
                }
            }
            LpTextField(st.override, { vm.setOverride(it) }, stringResource(R.string.override_reason), singleLine = false)
            st.preview?.let { r ->
                if (r.blocking.isNotEmpty()) { WarningText(stringResource(R.string.preconditions_blocking)); r.blocking.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) } }
                if (r.overridden.isNotEmpty()) { Text(stringResource(R.string.preconditions_overridden), style = MaterialTheme.typography.titleMedium); r.overridden.forEach { Text("• $it") } }
                if (r.warnings.isNotEmpty()) { Text(stringResource(R.string.preconditions_warnings), style = MaterialTheme.typography.titleMedium); r.warnings.forEach { Text("• $it") } }
            }
            BigButton(stringResource(R.string.continue_label), { vm.proceed() }, Modifier.fillMaxWidth(), enabled = st.protocol != null && st.preview?.blocking?.all { it.startsWith("Pre-session checklist") } ?: false)
        }
    }
}
