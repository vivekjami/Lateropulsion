package com.lateropulsion.app.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lateropulsion.app.BuildConfig
import com.lateropulsion.app.R
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.export.ExportManager
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.ChartCanvas
import com.lateropulsion.app.ui.components.CheckRow
import com.lateropulsion.app.ui.components.InfoCard
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.StatusChip
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.AppConfig
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
import com.lateropulsion.feature.report.CsvExporter
import com.lateropulsion.feature.report.ProgressReportBuilder
import com.lateropulsion.feature.report.ProgressReportData
import com.lateropulsion.feature.report.ReportText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class ProgressState(val patient: Patient? = null, val name: String = "", val baseline: Baseline? = null, val rows: List<Pair<Session, SessionSummary>> = emptyList(),
    val assessments: List<Assessment> = emptyList(), val erased: Boolean = false, val message: String? = null)

@HiltViewModel
class ProgressViewModel @Inject constructor(
    handle: SavedStateHandle, private val patients: PatientRepository, private val baselines: BaselineRepository, private val sessions: SessionRepository,
    private val assessments: AssessmentRepository, private val exports: ExportManager, private val auth: AuthManager, private val appConfig: AppConfig,
    private val settings: SettingsStore, private val clock: Clock,
) : ViewModel() {
    val patientId = PatientId(handle.get<String>("patientId")!!)
    val state = MutableStateFlow(ProgressState())
    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val s = sessions.listForPatient(patientId).filter { it.isFinished }
        val m = sessions.summariesForPatient(patientId).associateBy { it.sessionId }
        state.value = ProgressState(patients.findById(patientId), patients.identity(patientId)?.name ?: "", baselines.current(patientId), s.mapNotNull { x -> m[x.id]?.let { x to it } }, assessments.listForPatient(patientId))
    }

    fun trendSpec() = ProgressReportBuilder().trendSpec(reportData())

    private fun reportData(): ProgressReportData {
        val st = state.value
        return ProgressReportData(st.patient!!, st.baseline, st.rows, st.assessments, appConfig.metrics.mdcDeg, BuildConfig.VERSION_NAME, "", clock.nowUtcMillis())
    }

    fun exportCsv(deidentified: Boolean) = viewModelScope.launch {
        val st = state.value; val p = st.patient ?: return@launch
        val code = if (deidentified) p.displayId else "${p.displayId}_${st.name.replace(' ', '_')}"
        exports.share("${p.displayId}_sessions.csv", "text/csv", "sessions-csv", "patient", p.id.value, deidentified, auth.current?.id) { it.writeText(CsvExporter.sessionsCsv(p, st.rows, code)) }
            .onFailure { state.value = state.value.copy(message = "Export failed: ${it.message ?: it::class.simpleName}") }
    }

    fun exportPdf() = viewModelScope.launch {
        val st = state.value; val p = st.patient ?: return@launch
        val data = reportData().copy(siteName = settings.current().siteName)
        exports.share("${p.displayId}_progress.pdf", "application/pdf", "progress-pdf", "patient", p.id.value, true, auth.current?.id) { f -> f.outputStream().use { ProgressReportBuilder().build(data, it) } }
            .onFailure { state.value = state.value.copy(message = "Export failed: ${it.message ?: it::class.simpleName}") }
    }

    fun erase(retain: Boolean) = viewModelScope.launch {
        patients.erase(patientId, retain).let { r -> state.value = state.value.copy(erased = r.isSuccess, message = r.errorOrNull()?.message) }
    }
}

@Composable
fun ProgressScreen(nav: NavHostController, patientId: String, vm: ProgressViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    var showErase by remember { mutableStateOf(false) }
    var identified by remember { mutableStateOf(false) }
    if (st.erased) { nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.DASHBOARD) { inclusive = true } }; return }
    val p = st.patient
    LpScreen(stringResource(R.string.progress), onBack = { nav.popBackStack() }, banner = { p?.let { PatientBanner(it.displayId, st.name, "${it.age} y · pushes ${it.lateropulsionDirection.name.lowercase()}") } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (p == null) return@Column
            if (st.rows.isEmpty()) Text("No completed sessions yet.") else ChartCanvas(vm.trendSpec())
            Text(ReportText.IMPROVEMENT_CAVEAT, style = MaterialTheme.typography.bodyMedium)
            Text(ReportText.PROXY_LIMITATION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoCard(stringResource(R.string.session_history)) {
                st.rows.sortedByDescending { it.first.sessionNumber }.forEach { (s, m) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("#${s.sessionNumber} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(s.startedAtUtc))} · ${s.protocolId}", style = MaterialTheme.typography.bodyLarge)
                            val improvement = m.improvementPct?.let { " · ${"%.0f".format(it)} %${if (m.withinMdc == true) "*" else ""}" } ?: ""
                            Text("MAD ${"%.1f".format(m.madDeg)}° · TIB5 ${"%.0f".format(m.tib5Pct)} % · k ${s.gainUsed} · assist ${m.assistanceLevel?.level ?: "—"}$improvement",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Column {
                            StatusChip(when (s.endReason) { EndReason.COMPLETED -> "completed"; EndReason.ABORTED -> "aborted"; EndReason.CRASH_RECOVERED -> "crash-recovered"; else -> "error" }, s.endReason == EndReason.COMPLETED)
                            if (m.lowConfidence) StatusChip("low confidence", false)
                        }
                    }
                    BigButton("Open session #${s.sessionNumber}", { nav.navigate(Routes.sessionDetail(s.id.value)) }, Modifier.fillMaxWidth(), secondary = true)
                }
            }
            Text("* within MDC (measurement noise, see report)", style = MaterialTheme.typography.bodyMedium)
            InfoCard("Export") {
                CheckRow(!identified, { identified = !it }, stringResource(R.string.export_deidentified))
                if (identified) WarningText(stringResource(R.string.export_identified_warning))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(stringResource(R.string.export_csv), { vm.exportCsv(!identified) }, Modifier.weight(1f), secondary = true)
                    BigButton(stringResource(R.string.progress_report_pdf), { vm.exportPdf() }, Modifier.weight(1f), secondary = true)
                }
            }
            st.message?.let { WarningText(it) }
            BigButton(stringResource(R.string.erase_patient), { showErase = true }, Modifier.fillMaxWidth(), danger = true)
            if (showErase) AlertDialog(
                onDismissRequest = { showErase = false }, title = { Text(stringResource(R.string.erase_patient)) }, text = { Text(stringResource(R.string.erase_warning)) },
                confirmButton = { TextButton(onClick = { showErase = false; vm.erase(retain = p.consentResearch) }) { Text(if (p.consentResearch) stringResource(R.string.erase_keep) else stringResource(R.string.erase_all)) } },
                dismissButton = { TextButton(onClick = { showErase = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

