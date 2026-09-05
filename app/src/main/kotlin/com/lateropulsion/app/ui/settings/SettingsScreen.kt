package com.lateropulsion.app.ui.settings

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
import com.lateropulsion.app.BuildConfig
import com.lateropulsion.app.R
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.CheckRow
import com.lateropulsion.app.ui.components.InfoCard
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.LpTextField
import com.lateropulsion.app.ui.components.Selector
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.app.work.RetentionCheck
import com.lateropulsion.core.common.EngineVersions
import com.lateropulsion.core.datastore.AssetConfigRepository
import com.lateropulsion.core.datastore.Settings
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.AuditEvent
import com.lateropulsion.core.model.AuditRepository
import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.HeadsetProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class SettingsUi(val devices: List<DeviceProfile> = emptyList(), val headsets: List<HeadsetProfile> = emptyList(), val audit: List<AuditEvent> = emptyList(), val message: String? = null, val retentionPending: Int = 0)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val store: SettingsStore, private val config: AssetConfigRepository, private val runtime: SessionRuntime, private val audit: AuditRepository,
    private val retention: RetentionCheck, val auth: AuthManager,
) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val ui = MutableStateFlow(SettingsUi())
    init { viewModelScope.launch { ui.value = SettingsUi(config.deviceProfiles(), config.headsetProfiles(), audit.recent(50), retentionPending = retention.pending()) } }
    fun update(f: (Settings) -> Settings) = viewModelScope.launch { store.update(f); runtime.resolveProfiles() }
    fun runRetention() = viewModelScope.launch { ui.value = ui.value.copy(retentionPending = retention.scan()) }
    fun confirmDeletion() = viewModelScope.launch { val n = retention.deleteExpired(auth.current?.id); ui.value = ui.value.copy(retentionPending = 0, message = "Deleted $n expired media file(s)") }
}

@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val ui by vm.ui.collectAsState()
    LpScreen(stringResource(R.string.settings), onBack = { nav.popBackStack() }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LpTextField(s.siteName, { v -> vm.update { it.copy(siteName = v) } }, stringResource(R.string.site_name))
            Selector(stringResource(R.string.device_profile), ui.devices,
                ui.devices.firstOrNull { it.id == s.deviceProfileId } ?: ui.devices.firstOrNull { it.id == AssetConfigRepository.GENERIC_ID },
                { "${it.model} ${if (it.qualified) "✓" else "(unqualified)"}" }, { v -> vm.update { it.copy(deviceProfileId = v.id) } })
            Selector(stringResource(R.string.headset_profile), ui.headsets, ui.headsets.firstOrNull { it.id == s.headsetProfileId } ?: ui.headsets.firstOrNull(), { it.name }, { v -> vm.update { it.copy(headsetProfileId = v.id) } })
            LpTextField(s.ipdMm?.toString() ?: "", { v -> vm.update { it.copy(ipdMm = v.toDoubleOrNull()) } }, stringResource(R.string.ipd), number = true)
            CheckRow(s.researchMode, { v -> vm.update { it.copy(researchMode = v) } }, stringResource(R.string.research_mode))
            LpTextField(s.autoLockSeconds.toString(), { v -> v.toIntOrNull()?.let { n -> vm.update { it.copy(autoLockSeconds = n.coerceIn(30, 900)) } } }, stringResource(R.string.auto_lock), number = true)
            if (s.fieldCalibratedSign != 0) {
                val at = s.fieldCalibratedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: ""
                Text("Field calibration: sign ${s.fieldCalibratedSign}, mount ${"%.1f".format(s.fieldCalibratedMountDeg ?: 0.0)}° ($at)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(stringResource(R.string.calibration), { nav.navigate(Routes.CALIBRATION) }, Modifier.weight(1f), secondary = true)
                BigButton(stringResource(R.string.sensor_debug), { nav.navigate(Routes.SENSOR_DEBUG) }, Modifier.weight(1f), secondary = true)
            }
            BigButton(stringResource(R.string.lens_calibration), { nav.navigate(Routes.LENS) }, Modifier.fillMaxWidth(), secondary = true)
            InfoCard("Retention") {
                if (ui.retentionPending > 0) { WarningText(stringResource(R.string.retention_pending, ui.retentionPending)); BigButton("Confirm deletion", { vm.confirmDeletion() }, Modifier.fillMaxWidth(), danger = true) }
                BigButton(stringResource(R.string.retention_check), { vm.runRetention() }, Modifier.fillMaxWidth(), secondary = true)
                ui.message?.let { Text(it) }
            }
            InfoCard(stringResource(R.string.about)) {
                Text(stringResource(R.string.about_versions, BuildConfig.VERSION_NAME, EngineVersions.METRICS_ENGINE, EngineVersions.PROTOCOL_ENGINE, EngineVersions.FUSION))
                Text("Flavour ${BuildConfig.FLAVOR} · SOUP list: docs/traceability/soup.md", style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.not_certified), style = MaterialTheme.typography.bodyMedium)
            }
            InfoCard(stringResource(R.string.audit_log)) {
                ui.audit.take(30).forEach { e -> Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.at))} · ${e.action} ${e.targetType} ${e.targetId.take(8)} ${e.detail}",
                    style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
