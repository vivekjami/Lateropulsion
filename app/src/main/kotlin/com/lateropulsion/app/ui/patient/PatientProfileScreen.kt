package com.lateropulsion.app.ui.patient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.InfoCard
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.StatusChip
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.model.Assessment
import com.lateropulsion.core.model.AssessmentRepository
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionRepository
import com.lateropulsion.core.model.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class ProfileState(val patient: Patient? = null, val name: String = "", val baseline: Baseline? = null, val sessions: List<Session> = emptyList(), val summaries: Map<String, SessionSummary> = emptyMap(), val assessments: List<Assessment> = emptyList())

@HiltViewModel
class PatientProfileViewModel @Inject constructor(
    handle: SavedStateHandle, private val patients: PatientRepository, private val baselines: BaselineRepository,
    private val sessions: SessionRepository, private val assessments: AssessmentRepository,
) : ViewModel() {
    val patientId = PatientId(handle.get<String>("patientId")!!)
    val state = MutableStateFlow(ProfileState())
    init { refresh() }
    fun refresh() = viewModelScope.launch {
        val s = sessions.listForPatient(patientId)
        state.value = ProfileState(patients.findById(patientId), patients.identity(patientId)?.name ?: "", baselines.current(patientId), s,
            sessions.summariesForPatient(patientId).associateBy { it.sessionId.value }, assessments.listForPatient(patientId))
    }
}

@Composable
fun PatientProfileScreen(nav: NavHostController, patientId: String, vm: PatientProfileViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val p = st.patient
    LpScreen(stringResource(R.string.profile), onBack = { nav.popBackStack() }, banner = { p?.let { PatientBanner(it.displayId, st.name, "${it.age} y · ${it.sex.name.lowercase()} · lesion ${it.lesionSide.name.lowercase()} · pushes ${it.lateropulsionDirection.name.lowercase()}") } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (p == null) return@Column
            val b = st.baseline
            InfoCard(stringResource(R.string.clinical_baseline)) {
                if (b == null) { WarningText("No baseline yet."); BigButton(stringResource(R.string.clinical_baseline), { nav.navigate(Routes.baselineForm(patientId)) }, Modifier.fillMaxWidth()) }
                else {
                    Text("Severity ${b.severity.name.lowercase().replace('_', ' ')} · head ${b.headDeviationDeg}° · trunk ${b.trunkDeviationDeg}° · assistance ${b.assistanceLevel.level} · fall risk ${b.fallRisk.name.lowercase()}")
                    b.measured?.let { m -> Text("Measured: MAD ${"%.1f".format(m.madDeg)}° · RMS ${"%.1f".format(m.rmsDeg)}° · TIB5 ${"%.0f".format(m.tib5Pct)} % · θ_ref ${"%.1f".format(b.thetaRefDeg ?: 0.0)}°") }
                        ?: run { WarningText("Headset baseline measurement missing."); BigButton(stringResource(R.string.baseline_measurement), { nav.navigate(Routes.baselineCapture(patientId)) }, Modifier.fillMaxWidth(), secondary = true) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(if (b.locked) "locked" else "editable", null)
                        BigButton("Edit / supersede", { nav.navigate(Routes.baselineForm(patientId)) }, Modifier.weight(1f), secondary = true)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(stringResource(R.string.start_new_session), { nav.navigate(Routes.setup(patientId)) }, Modifier.weight(1f), enabled = b?.isComplete == true)
                BigButton(stringResource(R.string.progress), { nav.navigate(Routes.progress(patientId)) }, Modifier.weight(1f), secondary = true)
            }
            if (b?.isComplete != true) Text("Complete the baseline (form + 60 s measurement) before the first session.", style = MaterialTheme.typography.bodyMedium)
            InfoCard(stringResource(R.string.session_history)) {
                if (st.sessions.isEmpty()) Text("No sessions yet.")
                st.sessions.sortedByDescending { it.sessionNumber }.forEach { s ->
                    val m = st.summaries[s.id.value]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("#${s.sessionNumber} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.startedAtUtc))}", style = MaterialTheme.typography.bodyLarge)
                            Text("${s.protocolId} · ${if (s.visualMode.name.startsWith("VERT")) "Mode A" else "Mode B k=${s.gainUsed}"}" + (m?.let { " · MAD ${"%.1f".format(it.madDeg)}° · TIB5 ${"%.0f".format(it.tib5Pct)} %" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            StatusChip(when (s.endReason) { EndReason.COMPLETED -> stringResource(R.string.status_completed); EndReason.ABORTED -> stringResource(R.string.status_aborted); EndReason.CRASH_RECOVERED -> stringResource(R.string.status_crash_recovered); EndReason.ERROR -> "error"; null -> "in progress" }, s.endReason == EndReason.COMPLETED)
                            if (m?.lowConfidence == true) StatusChip(stringResource(R.string.status_low_confidence), false)
                        }
                    }
                    BigButton("Open report", { nav.navigate(Routes.sessionDetail(s.id.value)) }, Modifier.fillMaxWidth(), secondary = true)
                }
            }
            InfoCard(stringResource(R.string.scales_optional)) {
                st.assessments.take(10).forEach { a -> Text("${a.scaleCode} ${"%.1f".format(a.totalScore)} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(a.recordedAt))} (v${a.scaleVersion})") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SCP", "BLS", "FAC").forEach { code -> BigButton(code, { nav.navigate(Routes.scale(patientId, code)) }, Modifier.weight(1f), secondary = true) }
                }
            }
        }
    }
}
