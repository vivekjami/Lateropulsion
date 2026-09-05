package com.lateropulsion.core.model

import kotlinx.serialization.Serializable

/** Protocol as data (ADR-007). See config/protocols/ JSON files and ARCHITECTURE §9.1. */
@Serializable
public data class ProtocolSpec(
    val protocolId: String,
    val version: Int,
    val name: String,
    val description: String = "",
    val positionRequired: BodyPosition,
    val visualMode: VisualMode,
    val gainSchedule: GainSchedule,
    val gainInitial: Double? = null,
    val blocks: List<BlockSpec>,
    val progressionGate: ProgressionGate? = null,
    /** Protocol whose gate must have passed before this one may run (sitting → standing → walking). */
    val requiresGateFrom: String? = null,
    val maxSessionS: Int? = null,
    val tags: List<String> = emptyList(),
) {
    public val totalPlannedS: Int get() = blocks.sumOf { it.durationS + it.restAfterS }
    public val exerciseS: Int get() = blocks.sumOf { it.durationS }
}

@Serializable
public data class BlockSpec(
    val blockId: String,
    val exercise: ExerciseType,
    val durationS: Int,
    val targetDeg: Double = 0.0,
    val toleranceDeg: Double,
    val cues: List<CueType>,
    val checkpointsS: List<Int> = emptyList(),
    val restAfterS: Int = 0,
    val abortIf: MetricCondition? = null,
    val instructionKey: String? = null,
    /** Overrides the protocol position for mixed protocols (e.g. sit → stand). */
    val position: BodyPosition? = null,
    /** Per-block gain override; null = session gain. */
    val gain: Double? = null,
    /** Excursion amplitude for weight-shift; reach distance cue for reach blocks. */
    val amplitudeDeg: Double? = null,
)

/** `{ "metric": "episodes", "gt": 12 }` — exactly one comparator must be set. */
@Serializable
public data class MetricCondition(
    val metric: String,
    val gt: Double? = null,
    val gte: Double? = null,
    val lt: Double? = null,
    val lte: Double? = null,
    val eq: Double? = null,
) {
    public val comparatorCount: Int get() = listOfNotNull(gt, gte, lt, lte, eq).size

    public fun holds(value: Double): Boolean = when {
        gt != null -> value > gt
        gte != null -> value >= gte
        lt != null -> value < lt
        lte != null -> value <= lte
        eq != null -> value == eq
        else -> false
    }
}

@Serializable
public data class ProgressionGate(
    val unlocks: String,
    val requires: List<GateRequirement>,
)

@Serializable
public data class GateRequirement(
    val metric: String,
    val gte: Double? = null,
    val lte: Double? = null,
    val eq: Double? = null,
    val consecutiveSessions: Int = 1,
) {
    public fun holds(value: Double): Boolean = when {
        gte != null -> value >= gte
        lte != null -> value <= lte
        eq != null -> value == eq
        else -> false
    }
}

/** Names accepted in [MetricCondition.metric] and [GateRequirement.metric]. */
public object MetricNames {
    public const val TIB_5: String = "TIB_5"
    public const val TIB_10: String = "TIB_10"
    public const val MAD: String = "MAD"
    public const val RMS: String = "RMS"
    public const val MAX: String = "MAX"
    public const val EPISODES: String = "episodes"
    public const val RECOVERY_MEAN_S: String = "recovery_mean_s"
    public const val BALANCE_LOSS_EVENTS: String = "balance_loss_events"
    public const val PEAK_DEG: String = "peak_deg"
    public const val VALID_SAMPLE_PCT: String = "valid_sample_pct"

    public val all: Set<String> = setOf(TIB_5, TIB_10, MAD, RMS, MAX, EPISODES, RECOVERY_MEAN_S, BALANCE_LOSS_EVENTS, PEAK_DEG, VALID_SAMPLE_PCT)

    public fun of(summary: SessionSummary, name: String): Double? = when (name) {
        TIB_5 -> summary.metrics.tib5Pct
        TIB_10 -> summary.metrics.tib10Pct
        MAD -> summary.metrics.madDeg
        RMS -> summary.metrics.rmsDeg
        MAX, PEAK_DEG -> summary.metrics.maxDeg
        EPISODES -> summary.episodes.count.toDouble()
        RECOVERY_MEAN_S -> summary.episodes.recoveryMeanS
        BALANCE_LOSS_EVENTS -> summary.balanceLossEvents.toDouble()
        VALID_SAMPLE_PCT -> summary.metrics.validSamplePct
        else -> null
    }
}
