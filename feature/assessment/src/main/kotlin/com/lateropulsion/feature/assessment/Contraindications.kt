package com.lateropulsion.feature.assessment

/** Relative contraindications screened before every session (README §15). Any positive answer blocks the session. */
public enum class Contraindication(public val labelKey: String) {
    SEIZURE_DISORDER("ci_seizure"),
    SEVERE_VISUAL_IMPAIRMENT("ci_visual"),
    VESTIBULAR_CRISIS("ci_vestibular"),
    UNSTABLE_CARDIOVASCULAR("ci_cardiovascular"),
    FACIAL_WOUND_OR_INFECTION("ci_facial"),
    SEVERE_AGITATION("ci_agitation"),
    REDUCED_CONSCIOUSNESS("ci_consciousness"),
    RECENT_OCULAR_SURGERY("ci_ocular_surgery"),
}

public data class ContraindicationScreen(val positives: List<Contraindication>, val answered: Int) {
    public val complete: Boolean get() = answered == Contraindication.entries.size
    public val blocked: Boolean get() = positives.isNotEmpty()
    public val passes: Boolean get() = complete && !blocked
}

public object ContraindicationChecklist {
    /** @param answers true = present. Every item must be answered. */
    public fun screen(answers: Map<Contraindication, Boolean>): ContraindicationScreen =
        ContraindicationScreen(answers.filterValues { it }.keys.sortedBy { it.ordinal }, answers.size)
}

/** Stop rules the therapist is prompted with during a session (README §15). Any one → stop the block. */
public enum class StopRule(public val labelKey: String) {
    NAUSEA("sr_nausea"), DIZZINESS("sr_dizziness"), PALLOR_SWEATING("sr_pallor"), SSQ_ABOVE_THRESHOLD("sr_ssq"),
    HEADACHE("sr_headache"), BALANCE_LOSS("sr_balance"), PATIENT_REQUEST("sr_patient"), THERAPIST_JUDGEMENT("sr_therapist"),
    THERMAL_WARNING("sr_thermal"), FRAME_TIMING("sr_frame_timing"),
}
