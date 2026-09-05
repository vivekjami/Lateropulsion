package com.lateropulsion.feature.protocol

import com.lateropulsion.core.model.GainLimits
import com.lateropulsion.core.model.GainSchedule
import com.lateropulsion.core.model.ProtocolSpec
import com.lateropulsion.core.model.VisualMode

/** Gain invariants live here, not in the UI (ARCHITECTURE §7.3). */
public object GainScheduler {
    /** Suggested gain for the next session of [protocol], from the schedule and this protocol's history. */
    public fun proposeNext(protocol: ProtocolSpec, history: List<SessionHistoryEntry>, initial: Double): Double {
        if (protocol.visualMode == VisualMode.VERTICAL_REFERENCE) return 0.0
        val last = history.lastOrNull() ?: return initial
        return protocol.gainSchedule.nextGain(history.map { it.summary }, last.gainUsed)
    }

    public sealed interface Verdict {
        public data object Ok : Verdict
        public data class NeedsNote(val why: String) : Verdict
        public data class Blocked(val why: String) : Verdict
    }

    /**
     * Checks a therapist-chosen gain against the previous session's gain and the schedule.
     * - k outside limits → blocked
     * - negative k without error-augmentation permission → blocked
     * - increase of more than one step (or any increase under Manual) → needs a written note
     */
    public fun verify(
        proposed: Double,
        previous: Double?,
        schedule: GainSchedule,
        allowErrorAugmentation: Boolean,
        advancedProtocol: Boolean,
        note: String?,
    ): Verdict {
        val min = if (allowErrorAugmentation && advancedProtocol) GainLimits.MIN_ERROR_AUGMENTATION else GainLimits.MIN_STANDARD
        if (proposed < min || proposed > GainLimits.MAX) return Verdict.Blocked("gain $proposed outside [$min, ${GainLimits.MAX}]")
        if (previous == null) return Verdict.Ok
        val step = when (schedule) {
            is GainSchedule.Linear -> schedule.step
            is GainSchedule.PerformanceDriven -> schedule.step
            GainSchedule.Manual -> 0.0
        }
        val increase = proposed - previous
        if (increase > step + EPS) {
            return if (note.isNullOrBlank()) Verdict.NeedsNote("gain increased from $previous to $proposed (more than one step)") else Verdict.Ok
        }
        return Verdict.Ok
    }

    private const val EPS = 1e-9
}
