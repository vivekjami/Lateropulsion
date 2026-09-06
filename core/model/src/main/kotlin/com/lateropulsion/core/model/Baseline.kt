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

    public companion object {
        public const val MAX_DEVIATION: Double = 90.0
        public const val MAX_THETA_REF: Double = 45.0

        /**
         * Clinical picture pre-filled with "none / no impairment" for every item, so the operator only marks what the
         * patient actually has (ADR-020). Everything is editable on the patient-condition screen; the first session's
         * protocol gating starts from sitting regardless of what is recorded here.
         */
        public fun defaultFor(patient: Patient, recordedBy: ClinicianId, nowUtcMillis: Long): Baseline = Baseline(
            id = Ids.baseline(), patientId = patient.id, severity = Severity.NONE, headDeviationDeg = 0.0, trunkDeviationDeg = 0.0,
            sittingBalance = SittingBalance.UNSUPPORTED_DYNAMIC, standingBalance = StandingBalance.INDEPENDENT, walkingAbility = WalkingAbility.INDEPENDENT,
            assistanceLevel = AssistanceLevel.INDEPENDENT, midlineAwareness = MidlineAwareness.PRESENT, correctionAbility = CorrectionAbility.ACTIVE_INDEPENDENT,
            fallRisk = FallRisk.LOW, measured = null, thetaRefDeg = null, thetaRefSetBy = null, thetaRefSetAt = null,
            recordedAt = nowUtcMillis, recordedBy = recordedBy, notes = "",
        )

        /** Plain-language description of a mean head tilt for operators and patients: θ > 0 is toward the patient's right. */
        public fun describeTilt(meanDeg: Double, thresholdDeg: Double = 1.0): String = when {
            meanDeg > thresholdDeg -> "${"%.1f".format(meanDeg)}° to the RIGHT"
            meanDeg < -thresholdDeg -> "${"%.1f".format(-meanDeg)}° to the LEFT"
            else -> "upright within ${"%.1f".format(thresholdDeg)}°"
        }
    }

    public fun validate(): ValidationErrors = ValidationErrors()
        .require(headDeviationDeg in -MAX_DEVIATION..MAX_DEVIATION, "headDeviationDeg", "Head deviation out of range")
        .require(trunkDeviationDeg in -MAX_DEVIATION..MAX_DEVIATION, "trunkDeviationDeg", "Trunk deviation out of range")
        .require(thetaRefDeg == null || thetaRefDeg in -MAX_THETA_REF..MAX_THETA_REF, "thetaRefDeg", "Midline reference out of range")
        .require(thetaRefDeg == null || thetaRefSetBy != null, "thetaRefSetBy", "Midline must record who set it")

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
