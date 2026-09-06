package com.lateropulsion.app.ui.patient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.BigNumber
import com.lateropulsion.app.ui.components.Expander
import com.lateropulsion.app.ui.components.InfoCard
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.StatusChip
import com.lateropulsion.app.ui.components.StepRow
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

data class ProfileState(val patient: Patient? = null, val name: String = "", val baseline: Baseline? = null, val sessions: List<Session> = emptyList(), val summaries: Map<String,
    SessionSummary> = emptyMap(), val assessments: List<Assessment> = emptyList())

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

/**
 * The patient's home screen reads as the three-step flow (ADR-020): baseline measured? → start a session; the
 * clinical picture and the scales are there to change, never a gate.
 */
@Composable
fun PatientProfileScreen(nav: NavHostController, patientId: String, vm: PatientProfileViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val p = st.patient
    LpScreen(stringResource(R.string.profile), onBack = { nav.popBackStack() }, banner = { p?.let { PatientBanner(it.displayId, st.name,
        "${it.age} y · pushes ${it.lateropulsionDirection.name.lowercase()} · lesion ${it.lesionSide.name.lowercase()}") } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (p == null) return@Column
            val b = st.baseline
            val m = b?.measured
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepRow(true, stringResource(R.string.step_register))
                    StepRow(m != null, stringResource(R.string.step_baseline))
                    StepRow(st.sessions.any { it.endReason == EndReason.COMPLETED }, stringResource(R.string.step_session_n, st.sessions.size + 1))
                }
            }
            if (m == null) {
                Text(stringResource(R.string.baseline_why), style = MaterialTheme.typography.bodyLarge)
                BigButton(stringResource(R.string.measure_baseline), { nav.navigate(Routes.baselineCapture(patientId)) }, Modifier.fillMaxWidth())
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.baseline_result_title), style = MaterialTheme.typography.titleMedium)
                        BigNumber(Baseline.describeTilt(m.meanDeg), stringResource(R.string.baseline_result_mean))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BigNumber("±${"%.1f".format(m.madDeg)}°", stringResource(R.string.baseline_result_steadiness), emphasis = false)
                            BigNumber("${"%.0f".format(m.tib5Pct)} %", stringResource(R.string.baseline_result_tib5), emphasis = false)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(if (b.locked) stringResource(R.string.baseline_locked) else stringResource(R.string.baseline_editable), null)
                            b.thetaRefDeg?.takeIf { it != 0.0 }?.let { StatusChip(stringResource(R.string.midline_chip, it), null) }
                        }
                        BigButton(stringResource(R.string.repeat_measurement), { nav.navigate(Routes.baselineCapture(patientId)) }, Modifier.fillMaxWidth(), secondary = true)
                    }
                }
                BigButton(stringResource(R.string.start_session_n, st.sessions.size + 1), { nav.navigate(Routes.setup(patientId)) }, Modifier.fillMaxWidth())
            }
            Expander(stringResource(R.string.patient_condition), conditionSummary(b)) {
                Text(stringResource(R.string.patient_condition_hint), style = MaterialTheme.typography.bodyMedium)
                BigButton(stringResource(R.string.edit_condition), { nav.navigate(Routes.baselineForm(patientId)) }, Modifier.fillMaxWidth(), secondary = true)
                Text(stringResource(R.string.scales_optional), style = MaterialTheme.typography.titleSmall)
                st.assessments.take(6).forEach { a -> Text("${a.scaleCode} ${"%.1f".format(a.totalScore)} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(a.recordedAt))}") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SCP", "BLS", "FAC").forEach { code -> BigButton(code, { nav.navigate(Routes.scale(patientId, code)) }, Modifier.weight(1f), secondary = true) }
                }
            }
            if (st.sessions.isNotEmpty()) {
                BigButton(stringResource(R.string.progress), { nav.navigate(Routes.progress(patientId)) }, Modifier.fillMaxWidth(), secondary = true)
                InfoCard(stringResource(R.string.session_history)) {
                    st.sessions.sortedByDescending { it.sessionNumber }.forEach { s ->
                        val sm = st.summaries[s.id.value]
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("#${s.sessionNumber} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.startedAtUtc))}", style = MaterialTheme.typography.bodyLarge)
                                Text("${s.protocolId} · ${if (s.visualMode.name.startsWith("VERT")) "Mode A" else "Mode B k=${s.gainUsed}"}" +
                                    (sm?.let { " · ±${"%.1f".format(it.madDeg)}° · in band ${"%.0f".format(it.tib5Pct)} %" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val status = when (s.endReason) {
                                    EndReason.COMPLETED -> stringResource(R.string.status_completed)
                                    EndReason.ABORTED -> stringResource(R.string.status_aborted)
                                    EndReason.CRASH_RECOVERED -> stringResource(R.string.status_crash_recovered)
                                    EndReason.ERROR -> "error"
                                    null -> "in progress"
                                }
                                StatusChip(status, s.endReason == EndReason.COMPLETED)
                                if (sm?.lowConfidence == true) StatusChip(stringResource(R.string.status_low_confidence), false)
                            }
                        }
                        BigButton(stringResource(R.string.open_report), { nav.navigate(Routes.sessionDetail(s.id.value)) }, Modifier.fillMaxWidth(), secondary = true)
                    }
                }
            }
        }
    }
}

private fun conditionSummary(b: Baseline?): String = if (b == null) "defaults will be recorded with the baseline" else
    "${b.severity.name.lowercase().replace('_', ' ')} · ${b.walkingAbility.name.lowercase().replace('_', ' ')} · assistance ${b.assistanceLevel.level} · fall risk ${b.fallRisk.name.lowercase()}"
