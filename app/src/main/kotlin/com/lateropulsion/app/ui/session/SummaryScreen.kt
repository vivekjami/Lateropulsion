package com.lateropulsion.app.ui.session

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lateropulsion.app.R
import com.lateropulsion.app.export.ExportManager
import com.lateropulsion.app.session.SessionController
import com.lateropulsion.app.session.SessionDraft
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.InfoCard
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.LpTextField
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.Selector
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.ScaleDefinition
import com.lateropulsion.core.model.SsqScore
import com.lateropulsion.feature.assessment.SsqScoring
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SummaryUi(val notes: String = "", val assistance: AssistanceLevel? = null, val ssqDef: ScaleDefinition? = null, val ssqAnswers: Map<String, Int> = emptyMap(),
    val ssq: SsqScore? = null, val saving: Boolean = false, val error: String? = null, val savedSessionId: String? = null)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    val controller: SessionController, val draft: SessionDraft, private val runtime: SessionRuntime, private val config: ConfigRepository,
    private val appConfig: AppConfig, val exports: ExportManager,
) : ViewModel() {
    val live = controller.state
    val ui = MutableStateFlow(SummaryUi(assistance = draft.assistanceBefore))
    init { viewModelScope.launch { ui.value = ui.value.copy(ssqDef = config.scale("SSQ")) } }
    fun notes(t: String) { ui.value = ui.value.copy(notes = t) }
    fun assistance(a: AssistanceLevel) { ui.value = ui.value.copy(assistance = a) }
    fun ssq(itemId: String, idx: Int) {
        val s = ui.value; val def = s.ssqDef ?: return
        val a = s.ssqAnswers + (itemId to idx)
        ui.value = s.copy(ssqAnswers = a, ssq = if (a.size == def.items.size) SsqScoring.score(def, a, appConfig.session.ssqFlagThresholdTotal) else null)
    }
    fun save() = viewModelScope.launch {
        val s = ui.value
        ui.value = s.copy(saving = true)
        controller.finalize(s.notes, s.assistance, s.ssq).fold(
            onSuccess = { c -> runtime.releasePoseProvider(); controller.release(); draft.clear(); ui.value = ui.value.copy(saving = false, savedSessionId = c.session.id.value) },
            onFailure = { ui.value = ui.value.copy(saving = false, error = it.message) },
        )
    }
}

@Composable
fun SummaryScreen(nav: NavHostController, vm: SummaryViewModel = hiltViewModel()) {
    val st by vm.live.collectAsState()
    val ui by vm.ui.collectAsState()
    ui.savedSessionId?.let { id -> nav.navigate(Routes.sessionDetail(id)) { popUpTo(Routes.DASHBOARD) }; return }
    val spec = st.spec
    val m = st.summary
    LpScreen(stringResource(R.string.session_summary), banner = { spec?.let { PatientBanner(it.patientDisplayId, vm.draft.patientName, "${it.protocol.name} · session ${it.sessionNumber}") } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            st.abortReason?.let { WarningText(stringResource(R.string.aborted_reason, it)) }
            if (m != null) {
                InfoCard("Metrics") {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column { Text(stringResource(R.string.metric_mad)); Text("${"%.1f".format(m.metrics.madDeg)}°", style = MaterialTheme.typography.headlineMedium) }
                        Column { Text(stringResource(R.string.metric_rms)); Text("${"%.1f".format(m.metrics.rmsDeg)}°", style = MaterialTheme.typography.headlineMedium) }
                        Column { Text(stringResource(R.string.metric_tib5)); Text("${"%.0f".format(m.metrics.tib5Pct)} %", style = MaterialTheme.typography.headlineMedium) }
                        Column { Text(stringResource(R.string.metric_episodes)); Text("${m.episodes.count}", style = MaterialTheme.typography.headlineMedium) }
                    }
                    val recovery = if (m.episodes.recoveryMeanS.isNaN()) "—" else "%.1f s".format(m.episodes.recoveryMeanS)
                    Text(
                        "${stringResource(R.string.metric_tib10)} ${"%.0f".format(m.metrics.tib10Pct)} % · ${stringResource(R.string.metric_recovery)} $recovery · " +
                            "${stringResource(R.string.metric_valid)} ${"%.0f".format(m.metrics.validSamplePct)} %",
                    )
                    when {
                        m.comparisonRefusedReason != null -> WarningText(stringResource(R.string.comparison_refused, m.comparisonRefusedReason!!))
                        m.improvementPct != null -> {
                            Text(stringResource(R.string.improvement, m.improvementPct!!, m.deltaDeg ?: 0.0), style = MaterialTheme.typography.titleMedium)
                            if (m.withinMdc == true) WarningText(stringResource(R.string.within_mdc, m.mdcDeg ?: 0.0))
                        }
                    }
                    if (m.lowConfidence) WarningText(stringResource(R.string.low_confidence))
                    Text(stringResource(R.string.internal_metric_note), style = MaterialTheme.typography.bodyMedium)
                }
                InfoCard("Blocks") { st.blocks.forEach { b -> Text("${b.blockId}: MAD ${"%.1f".format(b.metrics.madDeg)}° · TIB5 ${"%.0f".format(b.metrics.tib5Pct)} % · episodes ${b.episodes.count} · ${b.endReason.name.lowercase()}") } }
            } else Text("Computing summary…")
            LpTextField(ui.notes, { vm.notes(it) }, stringResource(R.string.therapist_notes), singleLine = false)
            Selector(stringResource(R.string.assistance_after), AssistanceLevel.entries, ui.assistance, { "${it.level} – ${it.name.lowercase().replace('_', ' ')}" }, { vm.assistance(it) })
            Text(stringResource(R.string.ssq_post), style = MaterialTheme.typography.titleMedium)
            ui.ssqDef?.items?.forEach { item -> Selector(item.label, item.options.indices.toList(), ui.ssqAnswers[item.id], { item.options[it].label }, { vm.ssq(item.id, it) }) }
            ui.ssq?.let { Text(stringResource(R.string.ssq_result, it.total, it.nausea, it.oculomotor, it.disorientation, if (it.flagged) stringResource(R.string.ssq_flagged) else "")) }
            ui.error?.let { WarningText(it) }
            BigButton(stringResource(R.string.confirm_save), { vm.save() }, Modifier.fillMaxWidth(), enabled = m != null && !ui.saving)
        }
    }
}
