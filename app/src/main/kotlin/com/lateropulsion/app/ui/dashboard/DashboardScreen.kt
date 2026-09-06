package com.lateropulsion.app.ui.dashboard

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
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
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.StatusChip
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.datastore.AssetConfigRepository
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.DeviceSelfCheck
import com.lateropulsion.core.model.PatientListItem
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.SessionRepository
import com.lateropulsion.engine.vision.CameraCapabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DashboardState(
    val sessionsToday: Int = 0,
    val patientsToday: Int = 0,
    val check: DeviceSelfCheck? = null,
    val recovered: Int = 0,
    val configErrors: List<String> = emptyList(),
    val clinicianName: String = "",
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val sessions: SessionRepository,
    private val runtime: SessionRuntime,
    private val recovery: CrashRecovery,
    private val config: AssetConfigRepository,
    private val appConfig: AppConfig,
    val auth: AuthManager,
    patients: PatientRepository,
) : ViewModel() {
    val state = MutableStateFlow(DashboardState())
    val recent = patients.observeRecent(5).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val recovered = runCatching { recovery.recoverAll().size }.getOrDefault(0)
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val (s, p) = sessions.countToday(dayStart)
        val (dev, _) = runtime.resolveProfiles()
        val cam = runCatching { CameraCapabilities.probe(ctx) }.getOrNull()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val stat = StatFs(Environment.getDataDirectory().path)
        val check = DeviceSelfCheck(
            cameraOk = cam?.usable == true, imuOk = runtime.imuCapabilities.measurementCapable, imuRateHz = runtime.imuCapabilities.gyroMaxRateHz,
            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), thermalStatus = pm.currentThermalStatus,
            freeStorageMb = stat.availableBytes / (1024 * 1024), deviceProfileId = dev.id, deviceQualification = dev.qualification,
        )
        config.protocols(); config.scales()
        state.value = DashboardState(s, p, check, recovered, config.loadErrors, auth.current?.displayName ?: "")
    }

    val safety get() = appConfig.safety
    fun lock() = viewModelScope.launch { auth.lock() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(nav: NavHostController, vm: DashboardViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    LpScreen(stringResource(R.string.app_name), actions = {
        TextButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text(stringResource(R.string.settings)) }
        TextButton(onClick = { vm.lock() }) { Text(stringResource(R.string.sign_out)) }
    }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(st.clinicianName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.weight(1f).heightIn(min = 140.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.treatment), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.treatment_sub))
                        BigButton(stringResource(R.string.new_patient), { nav.navigate(Routes.PATIENT_NEW) }, Modifier.fillMaxWidth())
                        BigButton(stringResource(R.string.existing_patient), { nav.navigate(Routes.PATIENT_LOOKUP) }, Modifier.fillMaxWidth(), secondary = true)
                    }
                }
                Card(Modifier.weight(1f).heightIn(min = 140.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.reports), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.reports_sub))
                        BigButton(stringResource(R.string.patient_search), { nav.navigate(Routes.REPORTS_SEARCH) }, Modifier.fillMaxWidth())
                    }
                }
            }
            val recent by vm.recent.collectAsState()
            if (recent.isEmpty()) Text(stringResource(R.string.flow_guide), style = MaterialTheme.typography.bodyLarge)
            else {
                Text(stringResource(R.string.recent_patients), style = MaterialTheme.typography.titleMedium)
                recent.forEach { p -> RecentPatientRow(p) { nav.navigate(Routes.profile(p.id.value)) } }
            }
            st.check?.takeIf { it.deviceQualification == com.lateropulsion.core.model.DeviceQualification.NONE }?.let {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.calibrate_first_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.unqualified_device), style = MaterialTheme.typography.bodyMedium)
                        BigButton(stringResource(R.string.calibrate_visor), { nav.navigate(Routes.CALIBRATION) }, Modifier.fillMaxWidth())
                    }
                }
            }
            Text(stringResource(R.string.today_summary, st.sessionsToday, st.patientsToday), style = MaterialTheme.typography.titleMedium)
            st.check?.let { c ->
                Text(stringResource(R.string.device_check), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(stringResource(R.string.check_camera), c.cameraOk)
                    StatusChip("${stringResource(R.string.check_imu)} ${"%.0f".format(c.imuRateHz)} Hz", c.imuOk && c.imuRateHz >= 100)
                    StatusChip("${stringResource(R.string.check_battery)} ${c.batteryPct} %", c.batteryPct >= vm.safety.minBatteryPct)
                    StatusChip(stringResource(R.string.check_thermal), c.thermalStatus <= DeviceSelfCheck.THERMAL_MODERATE)
                    StatusChip("${stringResource(R.string.check_storage)} ${c.freeStorageMb / 1024} GB", c.freeStorageMb >= vm.safety.minFreeStorageMb)
                    StatusChip(
                        "${stringResource(R.string.check_device_profile)} ${c.deviceProfileId} · " + when (c.deviceQualification) {
                            com.lateropulsion.core.model.DeviceQualification.JIG -> stringResource(R.string.qual_jig)
                            com.lateropulsion.core.model.DeviceQualification.FIELD -> stringResource(R.string.qual_field)
                            com.lateropulsion.core.model.DeviceQualification.NONE -> stringResource(R.string.qual_none)
                        },
                        c.deviceQualified,
                    )
                }
            }
            if (st.recovered > 0) WarningText(stringResource(R.string.crash_recovered_sessions, st.recovered))
            if (st.configErrors.isNotEmpty()) WarningText(stringResource(R.string.config_errors, st.configErrors.joinToString("; ")))
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.training_mode), { nav.navigate(Routes.TRAINING) }, Modifier.fillMaxWidth(), secondary = true)
            Text(stringResource(R.string.not_certified), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentPatientRow(p: PatientListItem, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(p.displayId, style = MaterialTheme.typography.titleMedium)
                Text("${p.name} · ${p.age} y · pushes ${p.lateropulsionDirection.name.lowercase()}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.sessions_count, p.sessionCount), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
