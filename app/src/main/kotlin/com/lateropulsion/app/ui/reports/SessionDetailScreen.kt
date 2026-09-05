package com.lateropulsion.app.ui.reports

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
import com.lateropulsion.app.BuildConfig
import com.lateropulsion.app.R
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.export.ExportManager
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.ChartCanvas
import com.lateropulsion.app.ui.components.InfoCard
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.SessionId
import com.lateropulsion.core.model.SessionRepository
import com.lateropulsion.core.model.ValidityFlags
import com.lateropulsion.core.timeseries.LpxReader
import com.lateropulsion.feature.report.ChartModel
import com.lateropulsion.feature.report.ChartSpec
import com.lateropulsion.feature.report.CsvExporter
import com.lateropulsion.feature.report.HBand
import com.lateropulsion.feature.report.JsonExporter
import com.lateropulsion.feature.report.Pt
import com.lateropulsion.feature.report.RefLine
import com.lateropulsion.feature.report.ReportText
import com.lateropulsion.feature.report.Series
import com.lateropulsion.feature.report.SessionReportBuilder
import com.lateropulsion.feature.report.SessionReportData
import com.lateropulsion.feature.report.Shade
import com.lateropulsion.feature.report.TracePoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class DetailState(val data: SessionReportData? = null, val error: String? = null)

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    handle: SavedStateHandle, private val sessions: SessionRepository, private val patients: PatientRepository, private val baselines: BaselineRepository,
    private val config: ConfigRepository, private val exports: ExportManager, private val auth: AuthManager, private val settings: SettingsStore, private val clock: Clock,
) : ViewModel() {
    private val sessionId = SessionId(handle.get<String>("sessionId")!!)
    val state = MutableStateFlow(DetailState())

    init {
        viewModelScope.launch {
            runCatching {
                val s = sessions.findById(sessionId) ?: error("Session not found")
                val p = patients.findById(s.patientId) ?: error("Patient not found")
                val summary = sessions.summary(sessionId) ?: error("Summary missing")
                val blocks = sessions.blocks(sessionId)
                val events = sessions.events(sessionId)
                val baseline = s.baselineId?.let { baselines.findById(it) }
                val ts = sessions.timeseries(sessionId)
                val trace = withContext(Dispatchers.IO) {
                    ts?.let { t -> runCatching { LpxReader.read(File(t.path)) }.getOrNull() }?.records?.map { TracePoint(it.tDeltaMs / 1000.0, it.thetaFiltDeg, ValidityFlags.isValidForMetrics(it.flags)) } ?: emptyList()
                }
                val protocolName = config.protocol(s.protocolId)?.name ?: s.protocolId
                val dev = config.deviceProfiles().firstOrNull { it.id == s.deviceProfileId }
                val clinicianName = auth.current?.takeIf { it.id == s.clinicianId }?.displayName ?: "clinician ${s.clinicianId.value.take(8)}"
                SessionReportData(p, clinicianName, s, blocks, summary, baseline, events, ChartModel.downsample(trace.map { Pt(it.tS, it.thetaDeg) }, 3000).let { ds -> ds.map { TracePoint(it.x, it.y, true) } }, protocolName, dev?.qualified == true, BuildConfig.VERSION_NAME, settings.current().siteName, clock.nowUtcMillis())
            }.onSuccess { state.value = DetailState(it) }.onFailure { state.value = DetailState(error = it.message) }
        }
    }

    fun chartSpec(d: SessionReportData): ChartSpec {
        val tol = d.blocks.firstOrNull()?.toleranceDeg ?: 5.0
        val t0 = d.session.startedMonoNs
        return ChartSpec(
            title = "Deviation from midline (filtered)", xLabel = "s", yLabel = "θ (°) + = right",
            series = listOf(Series("θ", d.trace.map { Pt(it.tS, it.thetaDeg) }, 0)),
            shades = d.blocks.flatMap { b -> b.episodeList.map { e -> Shade((b.startedMonoNs - t0) / 1e9 + e.startS, (b.startedMonoNs - t0) / 1e9 + (e.endS ?: e.startS + 1), 3) } },
            bands = listOf(HBand(-tol, tol, 2)), refLines = listOf(RefLine(0.0, "midline", 6, dashed = false)), yMinHint = -30.0, yMaxHint = 30.0,
        )
    }

    fun exportPdf() = viewModelScope.launch {
        val d = state.value.data ?: return@launch
        exports.share("${d.patient.displayId}_session${d.session.sessionNumber}.pdf", "application/pdf", "session-pdf", "session", d.session.id.value, true, auth.current?.id) { f -> f.outputStream().use { SessionReportBuilder().build(d, it) } }
    }
    fun exportJson(deidentified: Boolean) = viewModelScope.launch {
        val d = state.value.data ?: return@launch
        exports.share("${d.patient.displayId}_session${d.session.sessionNumber}.json", "application/json", "session-json", "session", d.session.id.value, deidentified, auth.current?.id) { it.writeText(JsonExporter.sessionBundle(d, deidentified)) }
    }
    fun exportTraceCsv() = viewModelScope.launch {
        val d = state.value.data ?: return@launch
        val ts = sessions.timeseries(d.session.id) ?: return@launch
        exports.share("${d.patient.displayId}_session${d.session.sessionNumber}_trace.csv", "text/csv", "trace-csv", "session", d.session.id.value, true, auth.current?.id) { f -> f.writeText(CsvExporter.traceCsv(LpxReader.read(File(ts.path)))) }
    }
}

@Composable
fun SessionDetailScreen(nav: NavHostController, sessionId: String, vm: SessionDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val d = st.data
    LpScreen("Session report", onBack = { nav.popBackStack() }, banner = { d?.let { PatientBanner(it.patient.displayId, "", "session ${it.session.sessionNumber} · ${it.protocolName}") } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            st.error?.let { WarningText(it); return@Column }
            if (d == null) { Text("Loading…"); return@Column }
            val m = d.summary; val s = d.session
            if (s.endReason?.name == "ABORTED") WarningText("Aborted: ${s.abortReason}. ${ReportText.ABORTED_NOTE}")
            if (s.crashRecovered) WarningText(ReportText.CRASH_RECOVERED_NOTE)
            if (!d.deviceQualified) WarningText(ReportText.UNQUALIFIED_DEVICE)
            Text("${if (s.visualMode.name.startsWith("VERT")) "Mode A" else "Mode B"} · k ${s.gainUsed} · θ_ref ${"%.1f".format(s.thetaRefDeg)}° · ${s.position.name.lowercase().replace('_', ' ')} · valid ${"%.0f".format(m.metrics.validSamplePct)} %")
            if (d.trace.isNotEmpty()) ChartCanvas(vm.chartSpec(d))
            InfoCard("Summary") {
                Text("MAD ${"%.1f".format(m.metrics.madDeg)}° (baseline ${m.baselineMadDeg?.let { "%.1f°".format(it) } ?: "—"}) · RMS ${"%.1f".format(m.metrics.rmsDeg)}° · max ${"%.1f".format(m.metrics.maxDeg)}°")
                Text("TIB5 ${"%.0f".format(m.metrics.tib5Pct)} % · TIB10 ${"%.0f".format(m.metrics.tib10Pct)} % · episodes ${m.episodes.count} (${m.episodes.partialCount} partial) · recovery ${if (m.episodes.recoveryMeanS.isNaN()) "—" else "%.1f s".format(m.episodes.recoveryMeanS)}")
                when {
                    m.comparisonRefusedReason != null -> WarningText(stringResource(R.string.comparison_refused, m.comparisonRefusedReason!!))
                    m.improvementPct != null -> Text(stringResource(R.string.improvement, m.improvementPct!!, m.deltaDeg ?: 0.0) + if (m.withinMdc == true) " — " + stringResource(R.string.within_mdc, m.mdcDeg ?: 0.0) else "", style = MaterialTheme.typography.titleMedium)
                }
                if (m.lowConfidence) WarningText(stringResource(R.string.low_confidence))
                Text(stringResource(R.string.internal_metric_note), style = MaterialTheme.typography.bodyMedium)
                Text("Assistance before ${s.assistanceLevelBefore?.level ?: "—"} → after ${s.assistanceLevelAfter?.level ?: "—"} · balance losses ${m.balanceLossEvents} · SSQ pre ${s.ssqPre?.total?.let { "%.0f".format(it) } ?: "—"} post ${s.ssqPost?.total?.let { "%.0f".format(it) } ?: "—"}")
            }
            InfoCard("Blocks") { d.blocks.forEach { b -> Text("${b.blockId} (${b.exercise.name.lowercase().replace('_', ' ')}, ${"%.0f".format(b.durationS)} s, k ${b.gain}): MAD ${"%.1f".format(b.metrics.madDeg)}° · TIB5 ${"%.0f".format(b.metrics.tib5Pct)} % · episodes ${b.episodes.count} · ${b.endReason.name.lowercase()}") } }
            if (s.notes.isNotBlank()) InfoCard(stringResource(R.string.therapist_notes)) { Text(s.notes) }
            Text(ReportText.PROXY_LIMITATION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(stringResource(R.string.generate_pdf), { vm.exportPdf() }, Modifier.weight(1f))
                BigButton(stringResource(R.string.export_json), { vm.exportJson(true) }, Modifier.weight(1f), secondary = true)
            }
            BigButton("Export raw trace CSV", { vm.exportTraceCsv() }, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}
