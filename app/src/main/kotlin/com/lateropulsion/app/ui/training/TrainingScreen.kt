package com.lateropulsion.app.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.session.SessionDraft
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineMeasurement
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.CorrectionAbility
import com.lateropulsion.core.model.FallRisk
import com.lateropulsion.core.model.FilterParams
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.LesionSide
import com.lateropulsion.core.model.MidlineAwareness
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientIdentity
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.Severity
import com.lateropulsion.core.model.Sex
import com.lateropulsion.core.model.Side
import com.lateropulsion.core.model.SittingBalance
import com.lateropulsion.core.model.StandingBalance
import com.lateropulsion.core.model.WalkingAbility
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Training mode (IMPLEMENTATION Phase 7): the full workflow against a simulated pusher patient, using a
 * dedicated training record so nothing mixes with real patients. The camera is not used; the HMD shows
 * the grey field with live cues driven by the simulator.
 */
@HiltViewModel
class TrainingViewModel @Inject constructor(private val patients: PatientRepository, private val baselines: BaselineRepository, private val draft: SessionDraft, private val clock: Clock, private val auth: AuthManager) : ViewModel() {
    val ready = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun start() = viewModelScope.launch {
        val me = auth.current?.id ?: return@launch
        runCatching {
            val existing = patients.findByDisplayId(TRAINING_ID)
            val id = existing?.id ?: run {
                val now = clock.nowUtcMillis()
                val p = Patient(Ids.patient(), TRAINING_ID, 65, Sex.UNSPECIFIED, "TRAINING — simulated patient, not a real person", LesionSide.RIGHT, Side.LEFT, Side.LEFT, null, false,
                    false, null, notes = "training", createdAt = now, updatedAt = now)
                patients.create(p, PatientIdentity(p.id, "Training Patient", null, null)).getOrThrow().id
            }
            if (baselines.current(id) == null) {
                baselines.save(Baseline(Ids.baseline(), id, Severity.MODERATE, 10.0, 12.0, SittingBalance.UNSUPPORTED_STATIC, StandingBalance.ONE_PERSON, WalkingAbility.ASSISTED_ONE, AssistanceLevel.ONE_PERSON,
                    MidlineAwareness.PARTIAL, CorrectionAbility.ACTIVE_WITH_CUE, FallRisk.MODERATE,
                    BaselineMeasurement(8.0, 8.5, 9.2, 3.0, 20.0, 20.0, 60.0, 0.1, 100.0, 60.0, emptyList(), emptyList(), FilterParams.summary(100.0), 6000),
                    0.0, me, clock.nowUtcMillis(), BodyPosition.SUPPORTED_SITTING, recordedAt = clock.nowUtcMillis(), recordedBy = me)).getOrThrow()
            }
            draft.clear(); draft.simulated = true
            ready.value = id.value
        }.onFailure { error.value = it.message }
    }

    companion object { const val TRAINING_ID = "LP-2020-0001" }
}

@Composable
fun TrainingScreen(nav: NavHostController, vm: TrainingViewModel = hiltViewModel()) {
    val ready by vm.ready.collectAsState()
    val error by vm.error.collectAsState()
    ready?.let { nav.navigate(Routes.setup(it)) { popUpTo(Routes.DASHBOARD) }; return }
    LpScreen(stringResource(R.string.training_mode), onBack = { nav.popBackStack() }) { mod ->
        Column(mod.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Runs the whole workflow — protocol setup, checklist, calibration, blocks, summary, report — against a simulated pusher patient (8° lean, sway, episodes). " +
                "Sessions are saved under the training record ${TrainingViewModel.TRAINING_ID}. The camera is not opened; the headset shows the grey field with live cues.", style = MaterialTheme.typography.bodyLarge)
            Text("Use it to rehearse the abort control, checkpoint marks, rest enforcement and the stop rules before touching a patient (IEC 62366-1 training).", style = MaterialTheme.typography.bodyMedium)
            error?.let { WarningText(it) }
            BigButton("Start training session", { vm.start() }, Modifier.fillMaxWidth())
        }
    }
}
