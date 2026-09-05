package com.lateropulsion.core.model

import com.lateropulsion.core.common.DisplayIdFormat
import com.lateropulsion.core.common.ValidationErrors
import java.time.LocalDate

/**
 * De-identified patient record. Identifiers live in [PatientIdentity], a separate table joined only
 * by [id] (REQ-SEC-002). Research exports carry this record and never the identity.
 */
public data class Patient(
    val id: PatientId,
    val displayId: String,
    val age: Int,
    val sex: Sex,
    val diagnosis: String,
    val lesionSide: LesionSide,
    val affectedSide: Side,
    val lateropulsionDirection: Side,
    val onsetDate: LocalDate?,
    val consentMedia: Boolean,
    val consentResearch: Boolean,
    val consentRecordedAt: Long?,
    val advancedProtocolAllowed: Boolean = false,
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val erasedAt: Long? = null,
) {
    public val isErased: Boolean get() = erasedAt != null

    public fun validate(): ValidationErrors = ValidationErrors()
        .require(DisplayIdFormat.isValid(displayId), "displayId", "Display ID must look like LP-YYYY-NNNN")
        .require(age in MIN_AGE..MAX_AGE, "age", "Age must be between $MIN_AGE and $MAX_AGE")
        .require(diagnosis.isNotBlank(), "diagnosis", "Diagnosis is required")
        .require(!(consentMedia && consentRecordedAt == null), "consentRecordedAt", "Media consent must be timestamped")
        .require(onsetDate == null || !onsetDate.isAfter(LocalDate.now().plusDays(1)), "onsetDate", "Onset cannot be in the future")

    public companion object {
        public const val MIN_AGE: Int = 1
        public const val MAX_AGE: Int = 120
    }
}

/** Identifying data. Encrypted at rest and never exported by default. */
public data class PatientIdentity(
    val patientId: PatientId,
    val name: String,
    val mrn: String?,
    val contact: String?,
) {
    public fun validate(): ValidationErrors = ValidationErrors()
        .require(name.trim().length >= 2, "name", "Name is required")
        .require(mrn == null || mrn.isNotBlank(), "mrn", "MRN cannot be blank if given")

    /** Lower-cased, whitespace-collapsed key used only for duplicate detection. */
    public val nameKey: String get() = normalizeName(name)

    public companion object {
        public fun normalizeName(name: String): String = name.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
    }
}

public data class Clinician(
    val id: ClinicianId,
    val displayName: String,
    val role: ClinicianRole,
    val createdAt: Long,
    val active: Boolean = true,
)

/** Row for search lists: everything a therapist needs to pick the right patient, nothing more. */
public data class PatientListItem(
    val id: PatientId,
    val displayId: String,
    val name: String,
    val age: Int,
    val sex: Sex,
    val lateropulsionDirection: Side,
    val sessionCount: Int,
    val lastSessionAt: Long?,
)
