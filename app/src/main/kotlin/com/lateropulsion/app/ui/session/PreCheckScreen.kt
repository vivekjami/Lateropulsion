package com.lateropulsion.app.ui.session

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lateropulsion.app.R
import com.lateropulsion.app.session.SessionController
import com.lateropulsion.app.session.SessionDraft
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.CheckRow
import com.lateropulsion.app.ui.components.Expander
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.Selector
import com.lateropulsion.app.ui.components.StatusChip
import com.lateropulsion.app.ui.components.StepRow
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.PreSessionChecklist
import com.lateropulsion.core.model.ScaleDefinition
import com.lateropulsion.core.model.SsqScore
import com.lateropulsion.engine.vision.CameraCapabilities
import com.lateropulsion.feature.assessment.Contraindication
import com.lateropulsion.feature.assessment.ContraindicationChecklist
import com.lateropulsion.feature.assessment.SsqScoring
import com.lateropulsion.feature.protocol.PreconditionContext
import com.lateropulsion.feature.protocol.PreconditionResult
import com.lateropulsion.feature.protocol.SessionPreconditions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PreCheckState(
    val checklist: PreSessionChecklist = PreSessionChecklist(),
    /** Every contraindication defaults to absent; the operator marks one present under "Review" (ADR-020). */
    val ci: Map<Contraindication, Boolean> = Contraindication.entries.associateWith { false },
    val ssqDef: ScaleDefinition? = null, val ssqAnswers: Map<String, Int> = emptyMap(), val ssq: SsqScore? = null,
    val assistanceBefore: AssistanceLevel = AssistanceLevel.ONE_PERSON, val result: PreconditionResult? = null,
    val deviceInfo: String = "", val started: Boolean = false, val error: String? = null, val requiresHarness: Boolean = false,
)

/**
 * The safety attestation is one explicit tap ("Confirm all") over a visible list, not a form (ADR-020). Device
 * checks are automatic, the SSQ defaults to "no symptoms", the assistance level comes from the baseline. Anything
 * the operator wants to change is one expander away; anything unsafe still blocks.
 */
@HiltViewModel
class PreCheckViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context, val draft: SessionDraft, private val runtime: SessionRuntime, private val controller: SessionController,
    private val config: ConfigRepository, private val appConfig: AppConfig, private val settings: SettingsStore,
) : ViewModel() {
    val state = MutableStateFlow(PreCheckState())

    init {
        viewModelScope.launch {
            val (dev, _) = runtime.resolveProfiles()
            val cam = runCatching { CameraCapabilities.probe(ctx) }.getOrNull()
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val free = StatFs(Environment.getDataDirectory().path).availableBytes / (1024 * 1024)
            val research = settings.current().researchMode
            val auto = PreSessionChecklist(
                batteryOk = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) >= appConfig.safety.minBatteryPct,
                thermalOk = pm.currentThermalStatus <= com.lateropulsion.core.model.DeviceSelfCheck.THERMAL_MODERATE,
                storageOk = free >= appConfig.safety.minFreeStorageMb,
                deviceQualified = dev.allowsClinicalSession || research || draft.simulated,
            )
            val spec = draft.spec.value
            val ssqDef = config.scale("SSQ")
            // SSQ starts at "none" for every item (ADR-021); the operator changes an item only if the patient reports it.
            val none = ssqDef?.items?.associate { it.id to 0 } ?: emptyMap()
            state.value = state.value.copy(checklist = auto, ssqDef = ssqDef, ssqAnswers = none,
                ssq = ssqDef?.let { SsqScoring.score(it, none, appConfig.session.ssqFlagThresholdTotal) },
                assistanceBefore = draft.baseline?.assistanceLevel ?: AssistanceLevel.ONE_PERSON,
                requiresHarness = spec?.position?.requiresHarness == true,
                deviceInfo = "${dev.id} · camera ${cam?.hardwareLevelName ?: "n/a"} ${cam?.maxFps ?: 0} fps · IMU ${"%.0f".format(runtime.imuCapabilities.gyroMaxRateHz)} Hz${if (draft.simulated) " · SIMULATED PATIENT" else ""}")
            evaluate()
        }
    }

    fun update(f: (PreSessionChecklist) -> PreSessionChecklist) { state.value = state.value.copy(checklist = f(state.value.checklist)); evaluate() }

    /** One explicit attestation covering the listed items; contraindications count as re-checked only when none is marked present. */
    fun confirmAll() = update {
        it.copy(identityConfirmed = true, supervisionAttested = true, hygieneConfirmed = true, harnessConfirmed = true,
            contraindicationsRechecked = ContraindicationChecklist.screen(state.value.ci).passes)
    }

    fun ci(c: Contraindication, present: Boolean) {
        val m = state.value.ci + (c to present)
        state.value = state.value.copy(ci = m, checklist = state.value.checklist.copy(contraindicationsRechecked = ContraindicationChecklist.screen(m).passes && state.value.checklist.contraindicationsRechecked))
        evaluate()
    }

    fun ssq(itemId: String, idx: Int) {
        val s = state.value; val def = s.ssqDef ?: return
        val a = s.ssqAnswers + (itemId to idx)
        state.value = s.copy(ssqAnswers = a, ssq = if (a.size == def.items.size) SsqScoring.score(def, a, appConfig.session.ssqFlagThresholdTotal) else null)
    }

    fun assistance(a: AssistanceLevel) { state.value = state.value.copy(assistanceBefore = a) }

    private fun evaluate() = viewModelScope.launch {
        val spec = draft.spec.value ?: return@launch; val patient = draft.patient ?: return@launch
        val protocols = config.protocols().associateBy { it.protocolId }
        val research = settings.current().researchMode
        state.value = state.value.copy(result = SessionPreconditions.check(PreconditionContext(spec, patient, state.value.checklist, draft.history, protocols, research || draft.simulated)))
    }

    fun begin() = viewModelScope.launch {
        val spec = draft.spec.value ?: return@launch
        val r = state.value.result ?: return@launch
        if (!r.canStart) return@launch
        val s = state.value
        if (s.ssq?.flagged == true && appConfig.session.autoStopOnSsqFlag) { state.value = s.copy(error = "SSQ flagged before the session: do not start today."); return@launch }
        runCatching {
            val poses = runtime.poseProvider(draft.simulated)
            draft.ssqPre = s.ssq; draft.assistanceBefore = s.assistanceBefore; draft.checklist = s.checklist; draft.contraindications = s.ci
            controller.prepare(spec, poses, draft.baseline, s.ssq, s.assistanceBefore)
            controller.checklistComplete()
            state.value = s.copy(started = true)
        }.onFailure { state.value = s.copy(error = it.message) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreCheckScreen(nav: NavHostController, vm: PreCheckViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val spec by vm.draft.spec.collectAsState()
    if (st.started) { nav.navigate(Routes.SESSION_LIVE) { popUpTo(Routes.SESSION_PRECHECK) { inclusive = true } }; return }
    val sp = spec ?: run { nav.popBackStack(); return }
    val c = st.checklist
    val anyCiPresent = st.ci.values.any { it }
    LpScreen(stringResource(R.string.pre_session_checklist), onBack = { nav.popBackStack() }, banner = { PatientBanner(sp.patientDisplayId, vm.draft.patientName,
        "${sp.protocol.name} · ${if (sp.visualMode.name.startsWith("VERT")) "Mode A" else "Mode B k=${sp.gain}"}") }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(stringResource(R.string.check_battery), c.batteryOk); StatusChip(stringResource(R.string.check_thermal), c.thermalOk)
                StatusChip(stringResource(R.string.check_storage), c.storageOk); StatusChip(stringResource(R.string.check_device_profile), c.deviceQualified)
            }
            Text(stringResource(R.string.device_status, st.deviceInfo), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.confirm_title), style = MaterialTheme.typography.titleMedium)
                    StepRow(c.identityConfirmed, stringResource(R.string.chk_identity, sp.patientDisplayId, vm.draft.patientName))
                    StepRow(c.supervisionAttested, stringResource(R.string.chk_supervision))
                    if (st.requiresHarness) StepRow(c.harnessConfirmed, stringResource(R.string.chk_harness))
                    StepRow(c.hygieneConfirmed, stringResource(R.string.chk_hygiene_visor))
                    StepRow(c.contraindicationsRechecked, if (anyCiPresent) stringResource(R.string.chk_ci_present) else stringResource(R.string.chk_ci_none))
                    val allDone = c.identityConfirmed && c.supervisionAttested && c.hygieneConfirmed && c.contraindicationsRechecked && (!st.requiresHarness || c.harnessConfirmed)
                    if (!allDone) BigButton(stringResource(R.string.confirm_all), { vm.confirmAll() }, Modifier.fillMaxWidth(), enabled = !anyCiPresent)
                    if (anyCiPresent) WarningText(stringResource(R.string.ci_blocks))
                }
            }
            Expander(stringResource(R.string.contraindications), if (anyCiPresent) stringResource(R.string.chk_ci_present) else stringResource(R.string.ci_summary_none)) {
                val presentLabel = stringResource(R.string.present); val absentLabel = stringResource(R.string.absent)
                Contraindication.entries.forEach { ci ->
                    val label = when (ci) {
                        Contraindication.SEIZURE_DISORDER -> R.string.ci_seizure; Contraindication.SEVERE_VISUAL_IMPAIRMENT -> R.string.ci_visual
                        Contraindication.VESTIBULAR_CRISIS -> R.string.ci_vestibular; Contraindication.UNSTABLE_CARDIOVASCULAR -> R.string.ci_cardiovascular
                        Contraindication.FACIAL_WOUND_OR_INFECTION -> R.string.ci_facial; Contraindication.SEVERE_AGITATION -> R.string.ci_agitation
                        Contraindication.REDUCED_CONSCIOUSNESS -> R.string.ci_consciousness; Contraindication.RECENT_OCULAR_SURGERY -> R.string.ci_ocular_surgery
                    }
                    Selector(stringResource(label), listOf(false, true), st.ci[ci] ?: false, { if (it) presentLabel else absentLabel }, { vm.ci(ci, it) })
                }
            }
            Expander(stringResource(R.string.ssq_pre), st.ssq?.let { if (it.total == 0.0) stringResource(R.string.ssq_all_none) else stringResource(R.string.ssq_summary, it.total) } ?: "") {
                Text(stringResource(R.string.ssq_item_by_item), style = MaterialTheme.typography.bodyMedium)
                st.ssqDef?.items?.forEach { item -> Selector(item.label, item.options.indices.toList(), st.ssqAnswers[item.id], { item.options[it].label }, { vm.ssq(item.id, it) }) }
                st.ssq?.let { Text(stringResource(R.string.ssq_result, it.total, it.nausea, it.oculomotor, it.disorientation, if (it.flagged) stringResource(R.string.ssq_flagged) else "")) }
            }
            Expander(stringResource(R.string.assistance_level), "${st.assistanceBefore.level} – ${st.assistanceBefore.name.lowercase().replace('_', ' ')}") {
                Selector(stringResource(R.string.assistance_level), AssistanceLevel.entries, st.assistanceBefore, { "${it.level} – ${it.name.lowercase().replace('_', ' ')}" }, { vm.assistance(it) })
            }
            Expander(stringResource(R.string.edit_individually), "") {
                CheckRow(c.identityConfirmed, { v -> vm.update { it.copy(identityConfirmed = v) } }, stringResource(R.string.chk_identity, sp.patientDisplayId, vm.draft.patientName))
                CheckRow(c.supervisionAttested, { v -> vm.update { it.copy(supervisionAttested = v) } }, stringResource(R.string.chk_supervision))
                if (st.requiresHarness) CheckRow(c.harnessConfirmed, { v -> vm.update { it.copy(harnessConfirmed = v) } }, stringResource(R.string.chk_harness))
                CheckRow(c.hygieneConfirmed, { v -> vm.update { it.copy(hygieneConfirmed = v) } }, stringResource(R.string.chk_hygiene_visor))
            }
            st.result?.let { r ->
                if (r.blocking.isNotEmpty()) { WarningText(stringResource(R.string.preconditions_blocking)); r.blocking.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) } }
                r.overridden.forEach { Text("Override: $it") }
                r.warnings.forEach { WarningText(it) }
            }
            st.error?.let { WarningText(it) }
            BigButton(stringResource(R.string.begin_calibration), { vm.begin() }, Modifier.fillMaxWidth(), enabled = st.result?.canStart == true)
        }
    }
}
