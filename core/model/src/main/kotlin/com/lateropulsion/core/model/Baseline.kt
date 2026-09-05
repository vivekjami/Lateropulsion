package com.lateropulsion.core.model

import com.lateropulsion.core.common.ValidationErrors

/**
 * The immutable reference point for every future session (ADR-004).
 * Once [locked], corrections create a new baseline with [supersedes] pointing at this one.
 */
public data class Baseline(
    val id: BaselineId,
    val patientId: PatientId,
    val severity: Severity,
    val headDeviationDeg: Double,
    val trunkDeviationDeg: Double,
    val sittingBalance: SittingBalance,
    val standingBalance: StandingBalance,
    val walkingAbility: WalkingAbility,
    val assistanceLevel: AssistanceLevel,
    val midlineAwareness: MidlineAwareness,
    val correctionAbility: CorrectionAbility,
    val fallRisk: FallRisk,
    /** Set by the 60 s headset capture; null until it has been performed. */
    val measured: BaselineMeasurement?,
    /** Patient midline; a clinical judgement recorded with who set it (ARCHITECTURE §6.4). */
    val thetaRefDeg: Double?,
    val thetaRefSetBy: ClinicianId?,
    val thetaRefSetAt: Long?,
    val position: BodyPosition = BodyPosition.SUPPORTED_SITTING,
    val exercise: ExerciseType = ExerciseType.BASELINE_CAPTURE,
    val recordedAt: Long,
    val recordedBy: ClinicianId,
    val locked: Boolean = false,
    val supersedes: BaselineId? = null,
    val notes: String = "",
) {
    public val isComplete: Boolean get() = measured != null && thetaRefDeg != null

    public fun validate(): ValidationErrors = ValidationErrors()
        .require(headDeviationDeg in -MAX_DEVIATION..MAX_DEVIATION, "headDeviationDeg", "Head deviation out of range")
        .require(trunkDeviationDeg in -MAX_DEVIATION..MAX_DEVIATION, "trunkDeviationDeg", "Trunk deviation out of range")
        .require(thetaRefDeg == null || thetaRefDeg in -MAX_THETA_REF..MAX_THETA_REF, "thetaRefDeg", "Midline reference out of range")
        .require(thetaRefDeg == null || thetaRefSetBy != null, "thetaRefSetBy", "Midline must record who set it")

    public companion object {
        public const val MAX_DEVIATION: Double = 90.0
        public const val MAX_THETA_REF: Double = 45.0
    }
}

/** Numbers from the 60 s baseline capture with correction off (README §4.2 step 3). */
@kotlinx.serialization.Serializable
public data class BaselineMeasurement(
    val meanDeg: Double,
    val madDeg: Double,
    val rmsDeg: Double,
    val sdDeg: Double,
    val maxDeg: Double,
    val tib5Pct: Double,
    val tib10Pct: Double,
    val driftDegPerMin: Double,
    val validSamplePct: Double,
    val durationS: Double,
    /** Histogram of θ over [binEdgesDeg]; shows directional bias at a glance. */
    val histogram: List<Int>,
    val binEdgesDeg: List<Double>,
    val filterParams: FilterParams,
    val sampleCount: Long,
)
