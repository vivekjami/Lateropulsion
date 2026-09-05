package com.lateropulsion.core.model

/** A completed clinical scale (SCP, BLS, FAC…). Items are stored as JSON so scale versions can evolve. */
public data class Assessment(
    val id: AssessmentId,
    val patientId: PatientId,
    val scaleCode: String,
    val scaleVersion: String,
    val itemsJson: String,
    val totalScore: Double,
    val subscoresJson: String? = null,
    val recordedAt: Long,
    val recordedBy: ClinicianId,
    val sessionId: SessionId? = null,
    val notes: String = "",
)

/** Kennedy et al. SSQ result. [flagged] is decided against the site threshold, not hard-coded here. */
@kotlinx.serialization.Serializable
public data class SsqScore(
    val nausea: Double,
    val oculomotor: Double,
    val disorientation: Double,
    val total: Double,
    val flagged: Boolean,
    val itemsJson: String? = null,
)
