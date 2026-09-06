package com.lateropulsion.feature.protocol

import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PreSessionChecklist
import com.lateropulsion.core.model.ProtocolSpec
import com.lateropulsion.core.model.SessionSpec
import com.lateropulsion.core.model.VisualMode

/** Everything that must be true before Start. Blocking items stop the session; overrides are audited. */
public data class PreconditionResult(
    val blocking: List<String>,
    val overridden: List<String>,
    val warnings: List<String>,
) {
    public val canStart: Boolean get() = blocking.isEmpty()
}

public data class PreconditionContext(
    val spec: SessionSpec,
    val patient: Patient,
    val checklist: PreSessionChecklist,
    /** All of this patient's completed sessions, oldest first. */
    val history: List<SessionHistoryEntry>,
    /** Protocol library, needed to find the gate a protocol depends on. */
    val protocols: Map<String, ProtocolSpec>,
    val researchMode: Boolean = false,
)

public object SessionPreconditions {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    public fun check(ctx: PreconditionContext): PreconditionResult {
        val blocking = ArrayList<String>()
        val overridden = ArrayList<String>()
        val warnings = ArrayList<String>()
        val spec = ctx.spec
        val cfg = spec.config
        val protocol = spec.protocol
        val hasOverride = !spec.overrideReason.isNullOrBlank()

        // REQ-SAF-001/002: checklist incl. harness for standing/walking.
        val missing = ctx.checklist.missing(spec.position, cfg.safety.requireHarnessForStanding)
        if (missing.isNotEmpty()) blocking += "Pre-session checklist incomplete: ${missing.joinToString()}"

        // REQ-SAF-020: device qualification.
        if (!spec.deviceProfile.signResolved) blocking += "Roll sign not resolved for device profile ${spec.deviceProfile.id}; run calibration"
        when (spec.deviceProfile.qualification) {
            com.lateropulsion.core.model.DeviceQualification.JIG -> Unit
            com.lateropulsion.core.model.DeviceQualification.FIELD ->
                warnings += "Device ${spec.deviceProfile.id} is field-calibrated (no jig report); reports will say so"
            com.lateropulsion.core.model.DeviceQualification.NONE ->
                if (ctx.researchMode) warnings += "Device ${spec.deviceProfile.id} is not calibrated; session will be flagged UNQUALIFIED DEVICE"
                else blocking += "Device ${spec.deviceProfile.id} is not calibrated: run Roll calibration in Settings (or enable research mode to proceed with flagged data)"
        }

        // REQ-SAF-003: progression gate.
        protocol.requiresGateFrom?.let { fromId ->
            val gate = ctx.protocols[fromId]?.progressionGate
            if (gate == null) {
                blocking += "Protocol requires gate from '$fromId' which is unknown or has no gate"
            } else {
                val result = ProgressionGateEvaluator.evaluate(gate, ctx.history.filter { it.protocolId == fromId })
                if (!result.passed) {
                    val walkingRule = spec.position == BodyPosition.WALKING && cfg.safety.walkingUnlockRequiresStandingPass
                    if (hasOverride && !walkingRule) overridden += "Progression gate from '$fromId' overridden: ${result.failures.joinToString("; ")}"
                    else if (hasOverride && walkingRule) blocking += "Walking cannot be unlocked by override while walking_unlock_requires_standing_pass is set"
                    else blocking += "Progression gate not passed: ${result.failures.joinToString("; ")}"
                }
            }
        }

        // Mode B rules (README §2, §15; ARCHITECTURE §7.3).
        if (spec.visualMode == VisualMode.COMPENSATED_VIEW) {
            if (protocol.visualMode != VisualMode.COMPENSATED_VIEW) blocking += "Protocol ${protocol.protocolId} is not a compensated-view protocol"
            val advanced = spec.advancedProtocol && ctx.patient.advancedProtocolAllowed
            if (!protocol.gainSchedule.isFading && !advanced) blocking += "Mode B requires a fading gain schedule (REQ-SES-010)"
            if (spec.gain < 0.0 && !(cfg.visual.allowErrorAugmentation && advanced)) blocking += "Negative gain requires error augmentation enabled and the advanced protocol flag"
            if (spec.gain > 1.0 || spec.gain < -0.5) blocking += "Gain ${spec.gain} outside permitted range"
            if (spec.position.stage > BodyPosition.SITTING_UNSUPPORTED.stage) blocking += "Mode B is seated only"

            val modeB = ctx.history.filter { it.visualMode == VisualMode.COMPENSATED_VIEW }
            if (modeB.size < FIRST_EXPOSURES) {
                if (spec.gain > cfg.safety.modeBFirstExposureMaxGain) blocking += "First Mode B exposures are limited to k <= ${cfg.safety.modeBFirstExposureMaxGain}"
                val longBlock = protocol.blocks.filter { (it.gain ?: spec.gain) > 0.0 }.any { it.durationS > cfg.safety.modeBFirstExposureMaxBlockS }
                if (longBlock) blocking += "First Mode B exposures are limited to ${cfg.safety.modeBFirstExposureMaxBlockS} s compensated blocks"
            }
            if (ssqLocked(ctx.history, cfg.session.ssqFlagsToLockModeA)) blocking += "Patient locked to Mode A after ${cfg.session.ssqFlagsToLockModeA} consecutive flagged SSQ scores"

            val previous = modeB.lastOrNull()?.gainUsed
            when (val v = GainScheduler.verify(spec.gain, previous, protocol.gainSchedule, cfg.visual.allowErrorAugmentation, advanced, spec.overrideReason)) {
                is GainScheduler.Verdict.Blocked -> blocking += v.why
                is GainScheduler.Verdict.NeedsNote -> blocking += "${v.why}; a therapist note is required"
                GainScheduler.Verdict.Ok -> Unit
            }
        }

        if (protocol.totalPlannedS > cfg.session.maxDurationMin * 60) blocking += "Protocol exceeds the ${cfg.session.maxDurationMin} min session cap"
        if (ctx.patient.isErased) blocking += "Patient record has been erased"

        return PreconditionResult(blocking, overridden, warnings)
    }

    /** Two consecutive flagged SSQ scores anywhere in history lock the patient to Mode A (REQ-SAF-010). */
    public fun ssqLocked(history: List<SessionHistoryEntry>, consecutive: Int): Boolean {
        var run = 0
        for (h in history) {
            run = if (h.ssqPostFlagged) run + 1 else 0
            if (run >= consecutive) return true
        }
        return false
    }

    private const val FIRST_EXPOSURES = 2
}
