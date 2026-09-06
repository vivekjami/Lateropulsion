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
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.LpTextField
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.Selector
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.fold
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.CorrectionAbility
import com.lateropulsion.core.model.FallRisk
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.MidlineAwareness
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.Severity
import com.lateropulsion.core.model.SittingBalance
import com.lateropulsion.core.model.StandingBalance
import com.lateropulsion.core.model.WalkingAbility
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BaselineForm(
    val patient: Patient? = null, val name: String = "",
    val severity: Severity = Severity.NONE, val head: String = "0", val trunk: String = "0",
    val sitting: SittingBalance = SittingBalance.UNSUPPORTED_DYNAMIC, val standing: StandingBalance = StandingBalance.INDEPENDENT, val walking: WalkingAbility = WalkingAbility.INDEPENDENT,
    val assistance: AssistanceLevel = AssistanceLevel.INDEPENDENT, val midline: MidlineAwareness = MidlineAwareness.PRESENT, val correction: CorrectionAbility = CorrectionAbility.ACTIVE_INDEPENDENT,
    val fall: FallRisk = FallRisk.LOW, val notes: String = "", val existing: Baseline? = null, val scales: List<String> = emptyList(),
    val error: String? = null, val savedId: String? = null,
)

@HiltViewModel
class BaselineFormViewModel @Inject constructor(
    handle: SavedStateHandle, private val patients: PatientRepository, private val baselines: BaselineRepository,
    private val config: ConfigRepository, private val clock: Clock, private val auth: AuthManager,
) : ViewModel() {
    private val patientId = PatientId(handle.get<String>("patientId")!!)
    val form = MutableStateFlow(BaselineForm())

    init {
        viewModelScope.launch {
            val p = patients.findById(patientId)
            val name = patients.identity(patientId)?.name ?: ""
            val existing = baselines.current(patientId)
            form.value = form.value.copy(patient = p, name = name, existing = existing, scales = config.scales().map { it.code }.filter { it != "SSQ" }).let { f ->
                existing?.let { b -> f.copy(severity = b.severity, head = b.headDeviationDeg.toString(), trunk = b.trunkDeviationDeg.toString(), sitting = b.sittingBalance,
                    standing = b.standingBalance, walking = b.walkingAbility, assistance = b.assistanceLevel, midline = b.midlineAwareness, correction = b.correctionAbility, fall = b.fallRisk, notes = b.notes) } ?: f
            }
        }
    }

    fun update(f: (BaselineForm) -> BaselineForm) { form.value = f(form.value) }

    fun save() = viewModelScope.launch {
        val f = form.value
        val me = auth.current?.id ?: return@launch
        val head = f.head.toDoubleOrNull() ?: 0.0
        val trunk = f.trunk.toDoubleOrNull() ?: 0.0
        val prev = f.existing
        // The measurement may already exist (baseline captured first, ADR-020); editing the picture must keep it.
        // A locked baseline cannot be edited: create a superseding one (ADR-004).
        val base = Baseline(
            id = if (prev != null && !prev.locked) prev.id else Ids.baseline(), patientId = patientId, severity = f.severity, headDeviationDeg = head, trunkDeviationDeg = trunk,
            sittingBalance = f.sitting, standingBalance = f.standing, walkingAbility = f.walking, assistanceLevel = f.assistance, midlineAwareness = f.midline,
            correctionAbility = f.correction, fallRisk = f.fall, measured = if (prev != null && !prev.locked) prev.measured else null,
            thetaRefDeg = if (prev != null && !prev.locked) prev.thetaRefDeg else null, thetaRefSetBy = if (prev != null && !prev.locked) prev.thetaRefSetBy else null,
            thetaRefSetAt = if (prev != null && !prev.locked) prev.thetaRefSetAt else null, recordedAt = clock.nowUtcMillis(), recordedBy = me,
            supersedes = if (prev != null && prev.locked) prev.id else null, notes = f.notes,
        )
        baselines.save(base).fold({ update { it.copy(savedId = base.id.value, error = null) } }, { e -> update { it.copy(error = e.message) } })
    }
}

/** Optional clinical picture. Pre-filled with the defaults the baseline capture records, so nothing here gates a session (ADR-020). */
@Composable
fun BaselineFormScreen(nav: NavHostController, patientId: String, vm: BaselineFormViewModel = hiltViewModel()) {
    val f by vm.form.collectAsState()
    f.savedId?.let { nav.popBackStack(); return }
    LpScreen(stringResource(R.string.patient_condition), onBack = { nav.popBackStack() }, banner = { f.patient?.let { PatientBanner(it.displayId, f.name, "${it.age} y · pushes ${it.lateropulsionDirection.name.lowercase()}") } }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.patient_condition_hint), style = MaterialTheme.typography.bodyMedium)
            f.existing?.let { if (it.locked) WarningText(stringResource(R.string.baseline_locked_supersede)) }
            Selector(stringResource(R.string.severity), Severity.entries, f.severity, { it.name.lowercase().replace('_', ' ') }, { v -> vm.update { it.copy(severity = v) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LpTextField(f.head, { v -> vm.update { it.copy(head = v) } }, stringResource(R.string.head_deviation), Modifier.weight(1f), number = true)
                LpTextField(f.trunk, { v -> vm.update { it.copy(trunk = v) } }, stringResource(R.string.trunk_deviation), Modifier.weight(1f), number = true)
            }
            Selector(stringResource(R.string.sitting_balance), SittingBalance.entries, f.sitting, { it.name.lowercase().replace('_', ' ') }, { v -> vm.update { it.copy(sitting = v) } })
            Selector(stringResource(R.string.standing_balance), StandingBalance.entries, f.standing, { it.name.lowercase().replace('_', ' ') }, { v -> vm.update { it.copy(standing = v) } })
            Selector(stringResource(R.string.walking_ability), WalkingAbility.entries, f.walking, { it.name.lowercase().replace('_', ' ') }, { v -> vm.update { it.copy(walking = v) } })
            Selector(stringResource(R.string.assistance_level), AssistanceLevel.entries, f.assistance, { "${it.level} – ${it.name.lowercase().replace('_', ' ')}" }, { v -> vm.update { it.copy(assistance = v) } })
            Selector(stringResource(R.string.midline_awareness), MidlineAwareness.entries, f.midline, { it.name.lowercase() }, { v -> vm.update { it.copy(midline = v) } })
            Selector(stringResource(R.string.correction_ability), CorrectionAbility.entries, f.correction, { it.name.lowercase().replace('_', ' ') }, { v -> vm.update { it.copy(correction = v) } })
            Selector(stringResource(R.string.fall_risk), FallRisk.entries, f.fall, { it.name.lowercase() }, { v -> vm.update { it.copy(fall = v) } })
            LpTextField(f.notes, { v -> vm.update { it.copy(notes = v) } }, stringResource(R.string.notes), singleLine = false)
            Text(stringResource(R.string.scales_optional), style = MaterialTheme.typography.titleMedium)
            f.scales.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { code -> BigButton(stringResource(R.string.enter_scale, code), { nav.navigate(Routes.scale(patientId, code)) }, Modifier.weight(1f), secondary = true) }
                }
            }
            f.error?.let { WarningText(it) }
            Text(stringResource(R.string.baseline_locked_note), style = MaterialTheme.typography.bodyMedium)
            BigButton(stringResource(R.string.save), { vm.save() }, Modifier.fillMaxWidth(), enabled = f.patient != null)
        }
    }
}
