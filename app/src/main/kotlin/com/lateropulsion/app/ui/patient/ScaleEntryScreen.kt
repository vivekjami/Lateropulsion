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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lateropulsion.app.R
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.Selector
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.model.Assessment
import com.lateropulsion.core.model.AssessmentRepository
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.ScaleDefinition
import com.lateropulsion.core.model.ScaleResult
import com.lateropulsion.feature.assessment.ScaleScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScaleEntryState(val def: ScaleDefinition? = null, val answers: Map<String, Int> = emptyMap(), val result: ScaleResult? = null, val saved: Boolean = false, val error: String? = null)

@HiltViewModel
class ScaleEntryViewModel @Inject constructor(
    handle: SavedStateHandle, private val config: ConfigRepository, private val assessments: AssessmentRepository, private val clock: Clock, private val auth: AuthManager,
) : ViewModel() {
    private val patientId = PatientId(handle.get<String>("patientId")!!)
    private val code: String = handle.get<String>("code")!!
    val state = MutableStateFlow(ScaleEntryState())

    init { viewModelScope.launch { state.value = state.value.copy(def = config.scale(code)) } }

    fun answer(itemId: String, optionIndex: Int) {
        val s = state.value
        val answers = s.answers + (itemId to optionIndex)
        state.value = s.copy(answers = answers, result = s.def?.let { ScaleScorer.score(it, answers) })
    }

    fun save() = viewModelScope.launch {
        val s = state.value; val def = s.def ?: return@launch; val r = s.result ?: return@launch
        val me = auth.current?.id ?: return@launch
        val a = Assessment(Ids.assessment(), patientId, def.code, def.version, ScaleScorer.itemsToJson(r), r.totalScore,
            if (r.subscores.isEmpty()) null else r.subscores.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }, clock.nowUtcMillis(), me)
        assessments.save(a).let { if (it.isSuccess) state.value = s.copy(saved = true) else state.value = s.copy(error = it.errorOrNull()?.message) }
    }
}

@Composable
fun ScaleEntryScreen(nav: NavHostController, patientId: String, code: String, vm: ScaleEntryViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    if (st.saved) { nav.popBackStack(); return }
    val def = st.def
    LpScreen(def?.name ?: code, onBack = { nav.popBackStack() }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (def == null) { Text("Scale not found: $code"); return@Column }
            Text(def.description, style = MaterialTheme.typography.bodyMedium)
            Text("Version ${def.version} · ${def.licenseNote}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            def.sections.forEach { sec ->
                Text(sec.title, style = MaterialTheme.typography.titleMedium)
                sec.items.forEach { item ->
                    val idx = st.answers[item.id]
                    Selector(item.label, item.options.indices.toList(), idx, { item.options[it].label }, { vm.answer(item.id, it) })
                }
            }
            st.result?.let { r ->
                Text(stringResource(R.string.scale_total, "%.2f".format(r.totalScore) + " / ${def.rangeMax}"), style = MaterialTheme.typography.titleLarge)
                if (!r.complete) WarningText(stringResource(R.string.scale_incomplete, r.missingItems.size))
            }
            st.error?.let { WarningText(it) }
            BigButton(stringResource(R.string.save), { vm.save() }, Modifier.fillMaxWidth(), enabled = st.result?.complete == true)
        }
    }
}
