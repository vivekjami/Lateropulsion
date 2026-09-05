package com.lateropulsion.feature.protocol

import com.lateropulsion.core.model.BlockEndReason
import com.lateropulsion.core.model.BlockSpec
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.SessionEventType

/** States of ARCHITECTURE §9.2. Unsafe states are unrepresentable: e.g. a block cannot run before calibration. */
public sealed interface SessionState {
    public data object PreCheck : SessionState
    public data object Cancelled : SessionState
    public data object Calibrating : SessionState
    public data class Failed(val reason: String) : SessionState
    public data object Ready : SessionState
    public data class BlockRunning(
        val blockIndex: Int,
        val blockStartNs: Long,
        val pausedNs: Long,
        val emittedCheckpoints: Set<Int>,
    ) : SessionState
    public data class Paused(val block: BlockRunning, val pausedAtNs: Long) : SessionState
    public data class Resting(
        val completedBlockIndex: Int,
        val restStartNs: Long,
        val restDurationNs: Long,
        val mandatory: Boolean,
        val completeAnnounced: Boolean,
    ) : SessionState
    public data class Aborted(val reason: AbortReason) : SessionState
    public data object Summarizing : SessionState
    public data object Saved : SessionState

    public val isTerminal: Boolean get() = this is Saved || this is Failed || this is Cancelled
    public val headsetActive: Boolean get() = this is BlockRunning || this is Paused || this is Resting || this is Ready
}

public enum class AbortSource {
    THERAPIST_CONTROL, CLICKER, LONG_PRESS, STOP_RULE, WATCHDOG, SESSION_CAP, IMU_DROPOUT, CAMERA_STALL, THERMAL, BATTERY, SSQ, PATIENT_REQUEST, ERROR,
}

public data class AbortReason(val source: AbortSource, val detail: String = "") {
    override fun toString(): String = if (detail.isBlank()) source.name else "${source.name}: $detail"
}

public sealed interface SessionInput {
    public data object ChecklistComplete : SessionInput
    public data object Cancel : SessionInput
    public data object CalibrationSucceeded : SessionInput
    public data class CalibrationFailed(val reason: String) : SessionInput
    public data object Start : SessionInput
    public data object Tick : SessionInput
    public data object Pause : SessionInput
    public data object Resume : SessionInput
    /** Therapist stop: in Paused → aborted; in Resting → ends the session early (summarised as completed). */
    public data object Stop : SessionInput
    public data class Abort(val reason: AbortReason) : SessionInput
    public data object NextBlock : SessionInput
    /** Live metric snapshot for the running block, used to evaluate `abort_if` conditions. */
    public data class LiveMetrics(val blockIndex: Int, val values: Map<String, Double>) : SessionInput
    public data object Proceed : SessionInput
    public data object Confirm : SessionInput
    public data object TherapistMark : SessionInput
    public data object BalanceLoss : SessionInput
}

public sealed interface Effect {
    public data class StartBlock(val index: Int, val block: BlockSpec, val gain: Double, val position: BodyPosition) : Effect
    public data class EndBlock(val index: Int, val reason: BlockEndReason, val elapsedS: Double) : Effect
    public data class Checkpoint(val index: Int, val atS: Int) : Effect
    public data class StartRest(val afterBlock: Int, val durationS: Int, val mandatory: Boolean) : Effect
    public data object RestComplete : Effect
    public data class Abort(val reason: AbortReason) : Effect
    public data object RenderNeutral : Effect
    public data class SetGain(val gain: Double) : Effect
    public data class Instruction(val key: String) : Effect
    public data class Alert(val message: String) : Effect
    public data class Event(val type: SessionEventType, val payload: String = "{}") : Effect
    public data object Summarize : Effect
    public data object Save : Effect
    /** Input was not legal in the current state; nothing changed. */
    public data class Rejected(val input: SessionInput, val state: SessionState, val why: String) : Effect
}
