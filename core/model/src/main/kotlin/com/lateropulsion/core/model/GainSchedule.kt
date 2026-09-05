package com.lateropulsion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * How Mode B gain `k` changes between sessions (ARCHITECTURE §7.3).
 * Invariants are enforced by the protocol engine, not the UI.
 */
@Serializable
public sealed interface GainSchedule {
    /** Next gain given completed session history (most recent last) and the gain used last time. */
    public fun nextGain(history: List<SessionSummary>, current: Double): Double

    /** True when the schedule reduces k over time; Mode B refuses to start without one (REQ-SES-010). */
    public val isFading: Boolean

    /** Fixed decrement each session. Simple, predictable, therapist-friendly. */
    @Serializable
    @SerialName("LINEAR")
    public data class Linear(val step: Double, val floor: Double = 0.0) : GainSchedule {
        init {
            require(step > 0.0) { "step must be positive" }
            require(floor >= 0.0) { "floor must be non-negative" }
        }
        override val isFading: Boolean get() = true
        override fun nextGain(history: List<SessionSummary>, current: Double): Double = max(floor, current - step)
    }

    /** Reduce only when the patient has earned it. Default. */
    @Serializable
    @SerialName("PERFORMANCE_DRIVEN")
    public data class PerformanceDriven(
        val step: Double = 0.10,
        val gateTib5: Double = 60.0,
        val consecutiveSessions: Int = 2,
        val floor: Double = 0.0,
    ) : GainSchedule {
        init {
            require(step > 0.0) { "step must be positive" }
            require(gateTib5 in 0.0..100.0) { "gateTib5 must be a percentage" }
            require(consecutiveSessions >= 1) { "consecutiveSessions must be >= 1" }
        }
        override val isFading: Boolean get() = true
        override fun nextGain(history: List<SessionSummary>, current: Double): Double {
            if (history.size < consecutiveSessions) return current
            val recent = history.takeLast(consecutiveSessions)
            val earned = recent.all { !it.lowConfidence && !it.tib5Pct.isNaN() && it.tib5Pct >= gateTib5 }
            return if (earned) max(floor, current - step) else current
        }
    }

    /** Therapist sets k manually every session; requires a written rationale. */
    @Serializable
    @SerialName("MANUAL")
    public data object Manual : GainSchedule {
        override val isFading: Boolean get() = false
        override fun nextGain(history: List<SessionSummary>, current: Double): Double = current
    }
}

/** Gain bounds. Negative gain (error augmentation) only behind the advanced flag (README §2). */
public object GainLimits {
    public const val MIN_ERROR_AUGMENTATION: Double = -0.5
    public const val MIN_STANDARD: Double = 0.0
    public const val MAX: Double = 1.0
    /** Introduce Mode B seated only at k ≤ 0.4 (README §15). */
    public const val MAX_FIRST_EXPOSURE: Double = 0.4
    public const val MAX_FIRST_EXPOSURE_BLOCK_S: Int = 180
}
