package com.lateropulsion.app.ui.patient

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lateropulsion.app.R
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.LpTextField
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.common.fold
import com.lateropulsion.core.model.PatientListItem
import com.lateropulsion.core.model.PatientRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class PatientLookupViewModel @Inject constructor(private val patients: PatientRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<PatientListItem>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val recent = patients.observeRecent(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun search(q: String) { query.value = q; viewModelScope.launch { results.value = if (q.isBlank()) emptyList() else patients.search(q) } }

    /** Full deletion: identity, baseline, every session, recording and report; audited. There is no undo. */
    fun delete(p: PatientListItem) = viewModelScope.launch {
        patients.erase(p.id, retainDeidentified = false).fold(
            onSuccess = { message.value = null; search(query.value) },
            onFailure = { e -> message.value = e.message },
        )
    }
}

@Composable
fun PatientLookupScreen(nav: NavHostController, forReports: Boolean, vm: PatientLookupViewModel = hiltViewModel()) {
    val q by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val recent by vm.recent.collectAsState()
    val message by vm.message.collectAsState()
    val list = if (q.isBlank()) recent else results
    var pendingDelete by remember { mutableStateOf<PatientListItem?>(null) }
    LpScreen(stringResource(if (forReports) R.string.reports else R.string.existing_patient), onBack = { nav.popBackStack() }) { mod ->
        Column(mod.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LpTextField(q, { vm.search(it) }, stringResource(R.string.search_hint))
            message?.let { WarningText(it) }
            if (list.isEmpty()) Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id.value }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate(if (forReports) Routes.progress(p.id.value) else Routes.profile(p.id.value)) }) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.displayId, style = MaterialTheme.typography.titleLarge)
                                Text("${p.name} · ${p.age} y · pushes ${p.lateropulsionDirection.name.lowercase()}", style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.sessions_count, p.sessionCount) + (p.lastSessionAt?.let { " · " + stringResource(R.string.last_session, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))) } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { pendingDelete = p }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_patient_title, p.displayId)) },
            text = { Text(stringResource(R.string.delete_patient_text, p.name, p.sessionCount)) },
            confirmButton = { TextButton(onClick = { vm.delete(p); pendingDelete = null }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
