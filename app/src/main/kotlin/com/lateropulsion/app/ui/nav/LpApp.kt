package com.lateropulsion.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.auth.AuthState
import com.lateropulsion.app.ui.auth.AuthScreen
import com.lateropulsion.app.ui.dashboard.DashboardScreen
import com.lateropulsion.app.ui.patient.BaselineCaptureScreen
import com.lateropulsion.app.ui.patient.BaselineFormScreen
import com.lateropulsion.app.ui.patient.PatientLookupScreen
import com.lateropulsion.app.ui.patient.PatientProfileScreen
import com.lateropulsion.app.ui.patient.RegisterPatientScreen
import com.lateropulsion.app.ui.patient.ScaleEntryScreen
import com.lateropulsion.app.ui.reports.ProgressScreen
import com.lateropulsion.app.ui.reports.SessionDetailScreen
import com.lateropulsion.app.ui.session.LiveSessionScreen
import com.lateropulsion.app.ui.session.PreCheckScreen
import com.lateropulsion.app.ui.session.ProtocolSetupScreen
import com.lateropulsion.app.ui.session.SummaryScreen
import com.lateropulsion.app.ui.settings.CalibrationScreen
import com.lateropulsion.app.ui.settings.LensCalibrationScreen
import com.lateropulsion.app.ui.settings.SensorDebugScreen
import com.lateropulsion.app.ui.settings.SettingsScreen
import com.lateropulsion.app.ui.training.TrainingScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

object Routes {
    const val DASHBOARD = "dashboard"
    const val PATIENT_NEW = "patient/new"
    const val PATIENT_LOOKUP = "patient/lookup"
    const val PATIENT_PROFILE = "patient/{patientId}"
    const val BASELINE_FORM = "patient/{patientId}/baseline"
    const val BASELINE_CAPTURE = "patient/{patientId}/baseline/capture"
    const val SCALE_ENTRY = "patient/{patientId}/scale/{code}"
    const val SESSION_SETUP = "session/setup/{patientId}"
    const val SESSION_PRECHECK = "session/precheck"
    const val SESSION_LIVE = "session/live"
    const val SESSION_SUMMARY = "session/summary"
    const val REPORTS_SEARCH = "reports/search"
    const val PROGRESS = "reports/{patientId}/progress"
    const val SESSION_DETAIL = "reports/session/{sessionId}"
    const val SETTINGS = "settings"
    const val CALIBRATION = "settings/calibration"
    const val SENSOR_DEBUG = "settings/sensor"
    const val LENS = "settings/lens"
    const val TRAINING = "training"

    fun profile(id: String) = "patient/$id"
    fun baselineForm(id: String) = "patient/$id/baseline"
    fun baselineCapture(id: String) = "patient/$id/baseline/capture"
    fun scale(id: String, code: String) = "patient/$id/scale/$code"
    fun setup(id: String) = "session/setup/$id"
    fun progress(id: String) = "reports/$id/progress"
    fun sessionDetail(id: String) = "reports/session/$id"
}

@HiltViewModel
class AuthGateViewModel @Inject constructor(val auth: AuthManager) : ViewModel()

@Composable
fun LpApp(onUserInteraction: () -> Unit) {
    val vm: AuthGateViewModel = hiltViewModel()
    val state by vm.auth.state.collectAsState()
    when (state) {
        AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        AuthState.NoAccount, is AuthState.Locked -> AuthScreen()
        is AuthState.Unlocked -> MainNav(rememberNavController(), onUserInteraction)
    }
}

@Composable
private fun MainNav(nav: NavHostController, onUserInteraction: () -> Unit) {
    NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { DashboardScreen(nav) }
        composable(Routes.PATIENT_NEW) { RegisterPatientScreen(nav) }
        composable(Routes.PATIENT_LOOKUP) { PatientLookupScreen(nav, forReports = false) }
        composable(Routes.REPORTS_SEARCH) { PatientLookupScreen(nav, forReports = true) }
        composable(Routes.PATIENT_PROFILE, arguments = listOf(navArgument("patientId") { type = NavType.StringType })) { PatientProfileScreen(nav, it.arguments!!.getString("patientId")!!) }
        composable(Routes.BASELINE_FORM, arguments = listOf(navArgument("patientId") { type = NavType.StringType })) { BaselineFormScreen(nav, it.arguments!!.getString("patientId")!!) }
        composable(Routes.BASELINE_CAPTURE, arguments = listOf(navArgument("patientId") { type = NavType.StringType })) { BaselineCaptureScreen(nav, it.arguments!!.getString("patientId")!!) }
        composable(Routes.SCALE_ENTRY, arguments = listOf(navArgument("patientId") { type = NavType.StringType }, navArgument("code") { type = NavType.StringType })) {
            ScaleEntryScreen(nav, it.arguments!!.getString("patientId")!!, it.arguments!!.getString("code")!!)
        }
        composable(Routes.SESSION_SETUP, arguments = listOf(navArgument("patientId") { type = NavType.StringType })) { ProtocolSetupScreen(nav, it.arguments!!.getString("patientId")!!) }
        composable(Routes.SESSION_PRECHECK) { PreCheckScreen(nav) }
        composable(Routes.SESSION_LIVE) { LiveSessionScreen(nav) }
        composable(Routes.SESSION_SUMMARY) { SummaryScreen(nav) }
        composable(Routes.PROGRESS, arguments = listOf(navArgument("patientId") { type = NavType.StringType })) { ProgressScreen(nav, it.arguments!!.getString("patientId")!!) }
        composable(Routes.SESSION_DETAIL, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { SessionDetailScreen(nav, it.arguments!!.getString("sessionId")!!) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.CALIBRATION) { CalibrationScreen(nav) }
        composable(Routes.SENSOR_DEBUG) { SensorDebugScreen(nav) }
        composable(Routes.LENS) { LensCalibrationScreen(nav) }
        composable(Routes.TRAINING) { TrainingScreen(nav) }
    }
}
