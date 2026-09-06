package com.lateropulsion.app.ui.session

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lateropulsion.app.R
import com.lateropulsion.app.session.SessionController
import com.lateropulsion.app.session.SessionDraft
import com.lateropulsion.app.session.SessionRuntime
import com.lateropulsion.app.ui.components.AngleDial
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.Expander
import com.lateropulsion.app.ui.components.LpScreen
import com.lateropulsion.app.ui.components.PatientBanner
import com.lateropulsion.app.ui.components.StatusChip
import com.lateropulsion.app.ui.components.WarningText
import com.lateropulsion.app.ui.hmd.HmdActivity
import com.lateropulsion.app.ui.nav.Routes
import com.lateropulsion.feature.protocol.AbortSource
import com.lateropulsion.feature.protocol.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveSessionViewModel @Inject constructor(val controller: SessionController, val draft: SessionDraft, private val runtime: SessionRuntime) : ViewModel() {
    val state = controller.state
    val headset = runtime.headset
    val autoStartDelayS = runtime.appConfig.session.autoStartDelayS
    init {
        // Calibration completes when the gyro-bias window is full (3 s still) or after a 15 s fallback.
        viewModelScope.launch {
            var waited = 0
            while (controller.state.value.phase == SessionState.Calibrating || controller.state.value.phase == SessionState.PreCheck) {
                delay(250); waited += 250
                val diag = runtime.poseProvider?.diagnostics?.value
                if (controller.state.value.phase == SessionState.Calibrating && (diag?.biasProgress ?: 0.0) >= 1.0 || waited > 15_000) {
                    if (controller.state.value.phase == SessionState.Calibrating) { controller.calibrationFinished(diag?.gyroBias, diag?.driftDegPerMin?.takeIf { !it.isNaN() }); break }
                }
            }
        }
    }
    /** Uses the session's reference (the baseline's clinician midline, or 0 = true vertical), not the head's current position (ADR-020). */
    fun confirmMidline() = controller.confirmMidline(controller.state.value.spec?.thetaRefDeg ?: 0.0)
    fun start() = controller.start(); fun pause() = controller.pause(); fun resume() = controller.resume(); fun stop() = controller.stop()
    fun next() = controller.nextBlock(); fun mark() = controller.mark(); fun balanceLoss() = controller.balanceLoss(); fun proceed() = controller.proceedAfterAbort()
    fun abort() = controller.abort(AbortSource.THERAPIST_CONTROL, "therapist screen")
}

@Composable
fun LiveSessionScreen(nav: NavHostController, vm: LiveSessionViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    val spec = st.spec ?: run { nav.popBackStack(); return }
    LaunchedEffect(st.phase) { if (st.phase == SessionState.Summarizing) nav.navigate(Routes.SESSION_SUMMARY) { popUpTo(Routes.SESSION_LIVE) { inclusive = true } } }
    LpScreen(stringResource(R.string.live_session), banner = { PatientBanner(spec.patientDisplayId, vm.draft.patientName, "${spec.protocol.name} · session ${spec.sessionNumber}") }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Abort is always the first, biggest control (REQ-SAF-004).
            BigButton(stringResource(R.string.abort), { vm.abort() }, Modifier.fillMaxWidth(), danger = true, enabled = st.phase.headsetActive)
            AngleDial(st.thetaDeg, spec.protocol.blocks.getOrNull(st.blockIndex.coerceAtLeast(0))?.toleranceDeg ?: 5.0, st.inBand, !st.trackingLost && !st.pitchOutOfRange, Modifier.size(220.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (st.inBand) stringResource(R.string.in_band) else stringResource(R.string.out_of_band), st.inBand)
                StatusChip(stringResource(R.string.episodes_live, st.episodes), null)
                StatusChip("valid ${"%.0f".format(st.validPct)} %", st.validPct > 80)
            }
            if (st.trackingLost) WarningText(stringResource(R.string.tracking_lost))
            if (st.mountShifted) WarningText(stringResource(R.string.mount_shift))
            if (st.pitchOutOfRange) WarningText(stringResource(R.string.pitch_guard))
            if (st.perfDegraded) WarningText(stringResource(R.string.perf_degraded))
            if (st.cameraStalled) WarningText(stringResource(R.string.camera_stall))
            when (val p = st.phase) {
                SessionState.PreCheck, SessionState.Calibrating -> {
                    Text(stringResource(R.string.calibrating, (st.calibrationProgress * 100).toInt()), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { st.calibrationProgress.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
                SessionState.Ready -> {
                    val hs by vm.headset.collectAsState()
                    Text(stringResource(if (hs?.isMono != false) R.string.mount_phone_visor else R.string.insert_phone), style = MaterialTheme.typography.bodyLarge)
                    if (vm.autoStartDelayS > 0) Text(stringResource(R.string.auto_start_explain, vm.autoStartDelayS), style = MaterialTheme.typography.bodyMedium)
                    BigButton(stringResource(R.string.start_hmd), { ctx.startActivity(Intent(ctx, HmdActivity::class.java)) }, Modifier.fillMaxWidth())
                    Expander(stringResource(R.string.start_from_here), "") {
                        Text(stringResource(R.string.confirm_midline), style = MaterialTheme.typography.bodyMedium)
                        BigButton(stringResource(R.string.confirm_midline_button), { vm.confirmMidline() }, Modifier.fillMaxWidth(), secondary = true)
                        BigButton(stringResource(R.string.start_session), { vm.start() }, Modifier.fillMaxWidth(), enabled = st.midlineConfirmed)
                        Text(stringResource(R.string.mirror_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is SessionState.BlockRunning -> {
                    Text(stringResource(R.string.block_progress, st.blockIndex + 1, st.blockCount, st.blockName, st.blockRemainingS), style = MaterialTheme.typography.titleMedium)
                    st.instructionKey?.let { key -> val id = ctx.resources.getIdentifier(key, "string", ctx.packageName); if (id != 0) Text("“${ctx.getString(id)}”", style = MaterialTheme.typography.bodyLarge) }
                    Text("MAD so far ${"%.1f".format(st.madSoFarDeg)}° · session ${st.sessionElapsedS / 60}:${"%02d".format(st.sessionElapsedS % 60)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton(stringResource(R.string.pause), { vm.pause() }, Modifier.weight(1f), secondary = true)
                        BigButton(stringResource(R.string.mark), { vm.mark() }, Modifier.weight(1f), secondary = true)
                        BigButton(stringResource(R.string.balance_loss), { vm.balanceLoss() }, Modifier.weight(1f), secondary = true)
                    }
                    Text(stringResource(R.string.stop_rules_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.stop_rule_prompt), style = MaterialTheme.typography.bodyMedium)
                }
                is SessionState.Paused -> {
                    Text("Paused · ${st.blockName} · ${st.blockRemainingS} s left", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton(stringResource(R.string.resume), { vm.resume() }, Modifier.weight(1f))
                        BigButton(stringResource(R.string.stop), { vm.stop() }, Modifier.weight(1f), secondary = true)
                    }
                }
                is SessionState.Resting -> {
                    Text(if (st.restComplete) stringResource(R.string.rest_complete) else stringResource(R.string.resting, st.restRemainingS), style = MaterialTheme.typography.titleMedium)
                    if (vm.autoStartDelayS > 0) Text(stringResource(R.string.rest_auto_explain, vm.autoStartDelayS), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton(stringResource(R.string.next_block), { vm.next() }, Modifier.weight(1f), enabled = st.restComplete)
                        BigButton(stringResource(R.string.stop), { vm.stop() }, Modifier.weight(1f), secondary = true)
                    }
                }
                is SessionState.Aborted -> {
                    WarningText(stringResource(R.string.aborted_reason, p.reason.toString()))
                    BigButton(stringResource(R.string.continue_label), { vm.proceed() }, Modifier.fillMaxWidth())
                }
                is SessionState.Failed -> { WarningText(p.reason); BigButton(stringResource(R.string.back), { nav.popBackStack() }, Modifier.fillMaxWidth()) }
                else -> Unit
            }
        }
    }
}
