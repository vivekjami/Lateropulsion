package com.lateropulsion.core.model

/** Append-only. Every read/write/export/delete of patient data produces one of these (REQ-SEC-005). */
public data class AuditEvent(
    val id: AuditId,
    val clinicianId: ClinicianId?,
    val at: Long,
    val action: AuditAction,
    val targetType: String,
    val targetId: String,
    val detail: String = "",
)
