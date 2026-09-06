package com.lateropulsion.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.lateropulsion.app.session.CrashRecovery
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.datastore.AssetConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardState(val recovered: Int = 0, val configErrors: List<String> = emptyList(), val clinicianName: String = "")

@HiltViewModel
class DashboardViewModel @Inject constructor(private val recovery: CrashRecovery, private val config: AssetConfigRepository, val auth: AuthManager) : ViewModel() {
    val state = MutableStateFlow(DashboardState())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val recovered = runCatching { recovery.recoverAll().size }.getOrDefault(0)
        config.protocols(); config.scales()
        state.value = DashboardState(recovered, config.loadErrors, auth.current?.displayName ?: "")
    }

    fun lock() = viewModelScope.launch { auth.lock() }
}

/**
 * Two things and nothing else: test the patient (new or existing) or look at reports and records.
 * Device checks, calibration and training live in Settings.
 */
@Composable
fun DashboardScreen(nav: NavHostController, vm: DashboardViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    LpScreen(stringResource(R.string.app_name), actions = {
        TextButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text(stringResource(R.string.settings)) }
        TextButton(onClick = { vm.lock() }) { Text(stringResource(R.string.sign_out)) }
    }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(st.clinicianName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (st.recovered > 0) WarningText(stringResource(R.string.crash_recovered_sessions, st.recovered))
            if (st.configErrors.isNotEmpty()) WarningText(stringResource(R.string.config_errors, st.configErrors.joinToString("; ")))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.treatment), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.treatment_sub), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BigButton(stringResource(R.string.new_patient), { nav.navigate(Routes.PATIENT_NEW) }, Modifier.fillMaxWidth())
                    BigButton(stringResource(R.string.existing_patient), { nav.navigate(Routes.PATIENT_LOOKUP) }, Modifier.fillMaxWidth(), secondary = true)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.reports), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.reports_sub), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BigButton(stringResource(R.string.patient_search), { nav.navigate(Routes.REPORTS_SEARCH) }, Modifier.fillMaxWidth(), secondary = true)
                }
            }
        }
    }
}
