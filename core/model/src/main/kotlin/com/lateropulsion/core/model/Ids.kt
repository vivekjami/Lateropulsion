package com.lateropulsion.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable @JvmInline public value class PatientId(public val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline public value class ClinicianId(public val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline public value class BaselineId(public val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline public value class AssessmentId(public val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline public value class SessionId(public val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline public value class BlockResultId(public val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline public value class EventId(public val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline public value class AssetId(public val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline public value class AuditId(public val value: String) { init { require(value.isNotBlank()) } }

public object Ids {
    public fun uuid(): String = UUID.randomUUID().toString()
    public fun patient(): PatientId = PatientId(uuid())
    public fun clinician(): ClinicianId = ClinicianId(uuid())
    public fun baseline(): BaselineId = BaselineId(uuid())
    public fun assessment(): AssessmentId = AssessmentId(uuid())
    public fun session(): SessionId = SessionId(uuid())
    public fun blockResult(): BlockResultId = BlockResultId(uuid())
    public fun event(): EventId = EventId(uuid())
    public fun asset(): AssetId = AssetId(uuid())
    public fun audit(): AuditId = AuditId(uuid())
}
