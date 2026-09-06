package com.lateropulsion.app.ui.settings

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.InfoCard
import com.lateropulsion.app.ui.components.StatusChip
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.DeviceQualification
import com.lateropulsion.core.model.DeviceSelfCheck
import com.lateropulsion.engine.vision.CameraCapabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceCheckViewModel @Inject constructor(@ApplicationContext private val ctx: Context, private val runtime: SessionRuntime, val appConfig: AppConfig) : ViewModel() {
    val check = MutableStateFlow<DeviceSelfCheck?>(null)

    init {
        viewModelScope.launch {
            val (dev, _) = runtime.resolveProfiles()
            val cam = runCatching { CameraCapabilities.probe(ctx) }.getOrNull()
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val stat = StatFs(Environment.getDataDirectory().path)
            check.value = DeviceSelfCheck(
                cameraOk = cam?.usable == true, imuOk = runtime.imuCapabilities.measurementCapable, imuRateHz = runtime.imuCapabilities.gyroMaxRateHz,
                batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), thermalStatus = pm.currentThermalStatus,
                freeStorageMb = stat.availableBytes / (1024 * 1024), deviceProfileId = dev.id, deviceQualification = dev.qualification,
            )
        }
    }
}

/** Hardware and calibration status of this phone, shown in Settings (moved off the dashboard to keep it to two choices). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceCheckCard(nav: NavHostController, vm: DeviceCheckViewModel = hiltViewModel()) {
    val c by vm.check.collectAsState()
    InfoCard(stringResource(R.string.device_check)) {
        val check = c ?: return@InfoCard
        val safety = vm.appConfig.safety
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(stringResource(R.string.check_camera), check.cameraOk)
            StatusChip("${stringResource(R.string.check_imu)} ${"%.0f".format(check.imuRateHz)} Hz", check.imuOk && check.imuRateHz >= 100)
            StatusChip("${stringResource(R.string.check_battery)} ${check.batteryPct} %", check.batteryPct >= safety.minBatteryPct)
            StatusChip(stringResource(R.string.check_thermal), check.thermalStatus <= DeviceSelfCheck.THERMAL_MODERATE)
            StatusChip("${stringResource(R.string.check_storage)} ${check.freeStorageMb / 1024} GB", check.freeStorageMb >= safety.minFreeStorageMb)
            StatusChip(
                "${stringResource(R.string.check_device_profile)} ${check.deviceProfileId} · " + when (check.deviceQualification) {
                    DeviceQualification.JIG -> stringResource(R.string.qual_jig)
                    DeviceQualification.FIELD -> stringResource(R.string.qual_field)
                    DeviceQualification.NONE -> stringResource(R.string.qual_none)
                },
                check.deviceQualified,
            )
        }
        if (check.deviceQualification == DeviceQualification.NONE) {
            Text(stringResource(R.string.unqualified_device))
            BigButton(stringResource(R.string.calibrate_visor), { nav.navigate(Routes.CALIBRATION) }, Modifier.fillMaxWidth())
        }
    }
}
