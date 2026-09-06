package com.lateropulsion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema mirroring ARCHITECTURE §10. The whole database file is SQLCipher-encrypted (REQ-SEC-001);
 * identifiers additionally live in their own table so de-identified exports can never join them by accident.
 * Composite values that are never queried (metrics, episodes, SSQ items) are stored as JSON columns.
 */
@Entity(tableName = "clinician")
data class ClinicianEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val active: Boolean,
    @ColumnInfo(name = "pin_hash") val pinHash: String,
    val salt: String,
    @ColumnInfo(name = "failed_attempts") val failedAttempts: Int = 0,
    @ColumnInfo(name = "locked_until") val lockedUntil: Long = 0L,
)

@Entity(tableName = "patient", indices = [Index(value = ["display_id"], unique = true)])
data class PatientEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_id") val displayId: String,
    val age: Int,
    val sex: String,
    val diagnosis: String,
    @ColumnInfo(name = "lesion_side") val lesionSide: String,
    @ColumnInfo(name = "affected_side") val affectedSide: String,
    @ColumnInfo(name = "lateropulsion_direction") val lateropulsionDirection: String,
    @ColumnInfo(name = "onset_epoch_day") val onsetEpochDay: Long?,
    @ColumnInfo(name = "consent_media") val consentMedia: Boolean,
    @ColumnInfo(name = "consent_research") val consentResearch: Boolean,
    @ColumnInfo(name = "consent_recorded_at") val consentRecordedAt: Long?,
    @ColumnInfo(name = "advanced_protocol_allowed") val advancedProtocolAllowed: Boolean,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "erased_at") val erasedAt: Long?,
    /** Added in schema v2: which consent form version the patient signed (DPDP traceability). */
    @ColumnInfo(name = "consent_version", defaultValue = "") val consentVersion: String = "",
)

@Entity(
    tableName = "patient_identity",
    foreignKeys = [ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patient_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("name_key"), Index("mrn")],
)
data class PatientIdentityEntity(
    @PrimaryKey @ColumnInfo(name = "patient_id") val patientId: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    val mrn: String?,
    val contact: String?,
)

@Entity(
    tableName = "baseline",
    foreignKeys = [ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patient_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("patient_id")],
)
data class BaselineEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "patient_id") val patientId: String,
    val severity: String,
    @ColumnInfo(name = "head_deviation_deg") val headDeviationDeg: Double,
    @ColumnInfo(name = "trunk_deviation_deg") val trunkDeviationDeg: Double,
    @ColumnInfo(name = "sitting_balance") val sittingBalance: String,
    @ColumnInfo(name = "standing_balance") val standingBalance: String,
    @ColumnInfo(name = "walking_ability") val walkingAbility: String,
    @ColumnInfo(name = "assistance_level") val assistanceLevel: Int,
    @ColumnInfo(name = "midline_awareness") val midlineAwareness: String,
    @ColumnInfo(name = "correction_ability") val correctionAbility: String,
    @ColumnInfo(name = "fall_risk") val fallRisk: String,
    @ColumnInfo(name = "measured_json") val measuredJson: String?,
    @ColumnInfo(name = "theta_ref_deg") val thetaRefDeg: Double?,
    @ColumnInfo(name = "theta_ref_set_by") val thetaRefSetBy: String?,
    @ColumnInfo(name = "theta_ref_set_at") val thetaRefSetAt: Long?,
    val position: String,
    val exercise: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "recorded_by") val recordedBy: String,
    val locked: Boolean,
    val supersedes: String?,
    val notes: String,
    /** Exactly one current baseline per patient. */
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
)

@Entity(
    tableName = "assessment",
    foreignKeys = [ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patient_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("patient_id"), Index("scale_code")],
)
data class AssessmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "patient_id") val patientId: String,
    @ColumnInfo(name = "scale_code") val scaleCode: String,
    @ColumnInfo(name = "scale_version") val scaleVersion: String,
    @ColumnInfo(name = "items_json") val itemsJson: String,
    @ColumnInfo(name = "total_score") val totalScore: Double,
    @ColumnInfo(name = "subscores_json") val subscoresJson: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "recorded_by") val recordedBy: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    val notes: String,
)

@Entity(
    tableName = "session",
    foreignKeys = [ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patient_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("patient_id"), Index("started_at_utc"), Index("end_reason")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "patient_id") val patientId: String,
    @ColumnInfo(name = "clinician_id") val clinicianId: String,
    @ColumnInfo(name = "protocol_id") val protocolId: String,
    @ColumnInfo(name = "protocol_version") val protocolVersion: Int,
    @ColumnInfo(name = "session_number") val sessionNumber: Int,
    @ColumnInfo(name = "visual_mode") val visualMode: String,
    @ColumnInfo(name = "gain_used") val gainUsed: Double,
    @ColumnInfo(name = "theta_ref_deg") val thetaRefDeg: Double,
    @ColumnInfo(name = "baseline_id") val baselineId: String?,
    @ColumnInfo(name = "device_profile_id") val deviceProfileId: String,
    @ColumnInfo(name = "headset_profile_id") val headsetProfileId: String,
    val position: String,
    @ColumnInfo(name = "started_at_utc") val startedAtUtc: Long,
    @ColumnInfo(name = "started_mono_ns") val startedMonoNs: Long,
    @ColumnInfo(name = "device_timezone") val deviceTimezone: String,
    @ColumnInfo(name = "ended_at_utc") val endedAtUtc: Long?,
    @ColumnInfo(name = "end_reason") val endReason: String?,
    @ColumnInfo(name = "abort_reason") val abortReason: String?,
    @ColumnInfo(name = "ssq_pre_json") val ssqPreJson: String?,
    @ColumnInfo(name = "ssq_post_json") val ssqPostJson: String?,
    val notes: String,
    @ColumnInfo(name = "assistance_before") val assistanceBefore: Int?,
    @ColumnInfo(name = "assistance_after") val assistanceAfter: Int?,
    @ColumnInfo(name = "gyro_bias_json") val gyroBiasJson: String?,
    @ColumnInfo(name = "drift_deg_per_min") val driftDegPerMin: Double?,
    @ColumnInfo(name = "app_version") val appVersion: String,
    @ColumnInfo(name = "crash_recovered") val crashRecovered: Boolean,
    @ColumnInfo(name = "override_reason") val overrideReason: String?,
    @ColumnInfo(name = "applied_tilt_deg", defaultValue = "0") val appliedTiltDeg: Double = 0.0,
)

@Entity(
    tableName = "block_result",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id")],
)
data class BlockResultEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "block_id") val blockId: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    val exercise: String,
    val position: String,
    @ColumnInfo(name = "started_mono_ns") val startedMonoNs: Long,
    @ColumnInfo(name = "duration_s") val durationS: Double,
    @ColumnInfo(name = "target_deg") val targetDeg: Double,
    @ColumnInfo(name = "tolerance_deg") val toleranceDeg: Double,
    val gain: Double,
    @ColumnInfo(name = "cues_json") val cuesJson: String,
    @ColumnInfo(name = "metrics_json") val metricsJson: String,
    @ColumnInfo(name = "episode_stats_json") val episodeStatsJson: String,
    @ColumnInfo(name = "episodes_json") val episodesJson: String,
    @ColumnInfo(name = "checkpoints_json") val checkpointsJson: String,
    @ColumnInfo(name = "end_reason") val endReason: String,
    @ColumnInfo(name = "filter_params_json") val filterParamsJson: String,
    // Denormalised for queries and charts
    @ColumnInfo(name = "mad_deg") val madDeg: Double,
    @ColumnInfo(name = "tib5_pct") val tib5Pct: Double,
    @ColumnInfo(name = "valid_sample_pct") val validSamplePct: Double,
)

@Entity(
    tableName = "session_summary",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)],
)
data class SessionSummaryEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "baseline_id") val baselineId: String?,
    @ColumnInfo(name = "baseline_mad_deg") val baselineMadDeg: Double?,
    @ColumnInfo(name = "metrics_json") val metricsJson: String,
    @ColumnInfo(name = "episodes_json") val episodesJson: String,
    @ColumnInfo(name = "delta_deg") val deltaDeg: Double?,
    @ColumnInfo(name = "improvement_pct") val improvementPct: Double?,
    @ColumnInfo(name = "within_mdc") val withinMdc: Boolean?,
    @ColumnInfo(name = "mdc_deg") val mdcDeg: Double?,
    @ColumnInfo(name = "low_confidence") val lowConfidence: Boolean,
    @ColumnInfo(name = "comparison_refused_reason") val comparisonRefusedReason: String?,
    @ColumnInfo(name = "balance_loss_events") val balanceLossEvents: Int,
    @ColumnInfo(name = "assistance_level") val assistanceLevel: Int?,
    @ColumnInfo(name = "gain_used") val gainUsed: Double,
    @ColumnInfo(name = "visual_mode") val visualMode: String,
    @ColumnInfo(name = "end_reason") val endReason: String,
    @ColumnInfo(name = "generated_by_version") val generatedByVersion: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "mad_deg") val madDeg: Double,
    @ColumnInfo(name = "tib5_pct") val tib5Pct: Double,
)

@Entity(
    tableName = "session_event",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id")],
)
data class SessionEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "t_ns") val tNanos: Long,
    val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
)

@Entity(
    tableName = "timeseries_file",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)],
)
data class TimeseriesFileEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    val path: String,
    @ColumnInfo(name = "sample_rate_hz") val sampleRateHz: Int,
    @ColumnInfo(name = "sample_count") val sampleCount: Long,
    val sha256: String,
    @ColumnInfo(name = "filter_params_json") val filterParamsJson: String,
    @ColumnInfo(name = "crash_recovered") val crashRecovered: Boolean,
)

@Entity(
    tableName = "media_asset",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id"), Index("patient_id"), Index("retention_until")],
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "patient_id") val patientId: String,
    val type: String,
    @ColumnInfo(name = "path_encrypted") val pathEncrypted: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "retention_until") val retentionUntil: Long,
)

/** Append-only; no update or delete DAO methods exist for this table (REQ-SEC-005). */
@Entity(tableName = "audit_event", indices = [Index("at"), Index(value = ["target_type", "target_id"])])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "clinician_id") val clinicianId: String?,
    val at: Long,
    val action: String,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val detail: String,
)

@Entity(tableName = "id_sequence")
data class IdSequenceEntity(
    @PrimaryKey val year: Int,
    @ColumnInfo(name = "next_value") val nextValue: Int,
)
