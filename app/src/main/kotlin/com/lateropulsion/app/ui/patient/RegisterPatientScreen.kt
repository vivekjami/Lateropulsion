package com.lateropulsion.app.ui.patient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.CheckRow
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.LpTextField
import com.lateropulsion.app.ui.components.Selector
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.fold
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.LesionSide
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientIdentity
import com.lateropulsion.core.model.PatientListItem
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.Sex
import com.lateropulsion.core.model.Side
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class RegisterForm(
    val name: String = "", val age: String = "", val sex: Sex = Sex.UNSPECIFIED, val mrn: String = "", val contact: String = "",
    val diagnosis: String = "", val lesionSide: LesionSide = LesionSide.RIGHT, val affectedSide: Side = Side.LEFT, val direction: Side = Side.LEFT,
    val onset: String = "", val consentMedia: Boolean = false, val consentResearch: Boolean = false, val consentVersion: String = "v1", val notes: String = "",
    val duplicates: List<PatientListItem> = emptyList(), val error: String? = null, val created: String? = null,
)

@HiltViewModel
class RegisterPatientViewModel @Inject constructor(private val patients: PatientRepository, private val clock: Clock, private val auth: AuthManager) : ViewModel() {
    val form = MutableStateFlow(RegisterForm())

    fun update(f: (RegisterForm) -> RegisterForm) { form.value = f(form.value) }

    fun checkDuplicates() = viewModelScope.launch {
        val f = form.value
        val age = f.age.toIntOrNull() ?: return@launch
        if (f.name.trim().length < 2) return@launch
        update { it.copy(duplicates = patients.findPossibleDuplicates(PatientIdentity(Ids.patient(), f.name, f.mrn.ifBlank { null }, null), age)) }
    }

    fun create() = viewModelScope.launch {
        val f = form.value
        val age = f.age.toIntOrNull() ?: run { update { it.copy(error = "Age required") }; return@launch }
        val onset = f.onset.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: run { update { it.copy(error = "Onset date must be YYYY-MM-DD") }; return@launch } }
        val now = clock.nowUtcMillis()
        val year = LocalDate.now(ZoneId.systemDefault()).year
        val id = Ids.patient()
        val patient = Patient(
            id = id, displayId = patients.allocateDisplayId(year), age = age, sex = f.sex, diagnosis = f.diagnosis.trim(), lesionSide = f.lesionSide,
            affectedSide = f.affectedSide, lateropulsionDirection = f.direction, onsetDate = onset, consentMedia = f.consentMedia, consentResearch = f.consentResearch,
            consentRecordedAt = now, notes = f.notes, createdAt = now, updatedAt = now,
        )
        patients.create(patient, PatientIdentity(id, f.name.trim(), f.mrn.ifBlank { null }, f.contact.ifBlank { null })).fold(
            onSuccess = { update { it.copy(error = null, created = id.value) } },
            onFailure = { e -> update { it.copy(error = e.message) } },
        )
    }
}

@Composable
fun RegisterPatientScreen(nav: NavHostController, vm: RegisterPatientViewModel = hiltViewModel()) {
    val f by vm.form.collectAsState()
    f.created?.let { id -> nav.navigate(Routes.baselineForm(id)) { popUpTo(Routes.DASHBOARD) }; return }
    LpScreen(stringResource(R.string.registration), onBack = { nav.popBackStack() }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Patient ID is assigned automatically (LP-YYYY-NNNN).", style = MaterialTheme.typography.bodyMedium)
            LpTextField(f.name, { v -> vm.update { it.copy(name = v) }; vm.checkDuplicates() }, stringResource(R.string.name))
            LpTextField(f.age, { v -> vm.update { it.copy(age = v.filter(Char::isDigit)) }; vm.checkDuplicates() }, stringResource(R.string.age), number = true)
            Selector(stringResource(R.string.sex), Sex.entries, f.sex, { it.name.lowercase() }, { v -> vm.update { it.copy(sex = v) } })
            LpTextField(f.mrn, { v -> vm.update { it.copy(mrn = v) }; vm.checkDuplicates() }, stringResource(R.string.mrn))
            LpTextField(f.contact, { v -> vm.update { it.copy(contact = v) } }, stringResource(R.string.contact))
            if (f.duplicates.isNotEmpty()) {
                WarningText(stringResource(R.string.possible_duplicates))
                f.duplicates.forEach { d -> Text("• ${d.displayId} — ${d.name}, ${d.age} y, ${d.sessionCount} sessions") }
            }
            LpTextField(f.diagnosis, { v -> vm.update { it.copy(diagnosis = v) } }, stringResource(R.string.diagnosis))
            Selector(stringResource(R.string.lesion_side), LesionSide.entries, f.lesionSide, { it.name.lowercase() }, { v -> vm.update { it.copy(lesionSide = v) } })
            Selector(stringResource(R.string.affected_side), Side.entries, f.affectedSide, { it.name.lowercase() }, { v -> vm.update { it.copy(affectedSide = v) } })
            Selector(stringResource(R.string.lateropulsion_direction), Side.entries, f.direction, { it.name.lowercase() }, { v -> vm.update { it.copy(direction = v) } })
            LpTextField(f.onset, { v -> vm.update { it.copy(onset = v) } }, stringResource(R.string.onset_date))
            CheckRow(f.consentMedia, { v -> vm.update { it.copy(consentMedia = v) } }, stringResource(R.string.consent_media))
            CheckRow(f.consentResearch, { v -> vm.update { it.copy(consentResearch = v) } }, stringResource(R.string.consent_research))
            LpTextField(f.consentVersion, { v -> vm.update { it.copy(consentVersion = v) } }, stringResource(R.string.consent_version))
            LpTextField(f.notes, { v -> vm.update { it.copy(notes = v) } }, stringResource(R.string.notes), singleLine = false)
            f.error?.let { WarningText(it) }
            BigButton(stringResource(R.string.create_patient), { vm.create() }, Modifier.fillMaxWidth(), enabled = f.name.trim().length >= 2 && f.age.isNotBlank() && f.diagnosis.isNotBlank())
        }
    }
}
