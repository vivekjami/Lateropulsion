package com.lateropulsion.core.database.repo

import com.lateropulsion.core.database.entity.AssessmentEntity
import com.lateropulsion.core.database.entity.AuditEventEntity
import com.lateropulsion.core.database.entity.BaselineEntity
import com.lateropulsion.core.database.entity.BlockResultEntity
import com.lateropulsion.core.database.entity.ClinicianEntity
import com.lateropulsion.core.database.entity.MediaAssetEntity
import com.lateropulsion.core.database.entity.PatientEntity
import com.lateropulsion.core.database.entity.PatientIdentityEntity
import com.lateropulsion.core.database.entity.SessionEntity
import com.lateropulsion.core.database.entity.SessionEventEntity
import com.lateropulsion.core.database.entity.SessionSummaryEntity
import com.lateropulsion.core.database.entity.TimeseriesFileEntity
import com.lateropulsion.core.database.dao.PatientRow
import com.lateropulsion.core.model.Assessment
import com.lateropulsion.core.model.AssessmentId
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.AuditAction
import com.lateropulsion.core.model.AuditEvent
import com.lateropulsion.core.model.AuditId
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineId
import com.lateropulsion.core.model.BaselineMeasurement
import com.lateropulsion.core.model.BlockEndReason
import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.BlockResultId
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.Checkpoint
import com.lateropulsion.core.model.Clinician
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.ClinicianRole
import com.lateropulsion.core.model.CorrectionAbility
import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.DeviationMetrics
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.Episode
import com.lateropulsion.core.model.EpisodeStats
import com.lateropulsion.core.model.EventId
import com.lateropulsion.core.model.ExerciseType
import com.lateropulsion.core.model.FallRisk
import com.lateropulsion.core.model.FilterParams
import com.lateropulsion.core.model.LesionSide
import com.lateropulsion.core.model.LpJson
import com.lateropulsion.core.model.MediaAsset
import com.lateropulsion.core.model.AssetId
import com.lateropulsion.core.model.MediaType
import com.lateropulsion.core.model.MidlineAwareness
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.PatientIdentity
import com.lateropulsion.core.model.PatientListItem
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionEvent
import com.lateropulsion.core.model.SessionEventType
import com.lateropulsion.core.model.SessionId
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.Severity
import com.lateropulsion.core.model.Sex
import com.lateropulsion.core.model.Side
import com.lateropulsion.core.model.SittingBalance
import com.lateropulsion.core.model.SsqScore
import com.lateropulsion.core.model.StandingBalance
import com.lateropulsion.core.model.TimeseriesFile
import com.lateropulsion.core.model.Vec3
import com.lateropulsion.core.model.VisualMode
import com.lateropulsion.core.model.WalkingAbility
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate

private val json = LpJson.lenient

/** SQLite has no NaN: binding one stores NULL, which a NOT NULL column rejects. The listing columns are nullable for that reason (DB v4). */
private fun Double.orNullIfUndefined(): Double? = takeUnless { it.isNaN() || it.isInfinite() }

internal fun Patient.toEntity() = PatientEntity(
    id.value, displayId, age, sex.name, diagnosis, lesionSide.name, affectedSide.name, lateropulsionDirection.name,
    onsetDate?.toEpochDay(), consentMedia, consentResearch, consentRecordedAt, advancedProtocolAllowed, notes, createdAt, updatedAt, erasedAt,
)

internal fun PatientEntity.toDomain() = Patient(
    PatientId(id), displayId, age, Sex.valueOf(sex), diagnosis, LesionSide.valueOf(lesionSide), Side.valueOf(affectedSide),
    Side.valueOf(lateropulsionDirection), onsetEpochDay?.let { LocalDate.ofEpochDay(it) }, consentMedia, consentResearch, consentRecordedAt,
    advancedProtocolAllowed, notes, createdAt, updatedAt, erasedAt,
)

internal fun PatientIdentity.toEntity() = PatientIdentityEntity(patientId.value, name, nameKey, mrn, contact)
internal fun PatientIdentityEntity.toDomain() = PatientIdentity(PatientId(patientId), name, mrn, contact)

internal fun PatientRow.toDomain() = PatientListItem(
    PatientId(id), displayId, name ?: "(erased)", age, Sex.valueOf(sex), Side.valueOf(lateropulsionDirection), sessionCount, lastSessionAt,
)

internal fun Clinician.toEntity(pinHash: String, salt: String) = ClinicianEntity(id.value, displayName, role.name, createdAt, active, pinHash, salt)
internal fun ClinicianEntity.toDomain() = Clinician(ClinicianId(id), displayName, ClinicianRole.valueOf(role), createdAt, active)

internal fun Baseline.toEntity(current: Boolean) = BaselineEntity(
    id.value, patientId.value, severity.name, headDeviationDeg, trunkDeviationDeg, sittingBalance.name, standingBalance.name, walkingAbility.name,
    assistanceLevel.level, midlineAwareness.name, correctionAbility.name, fallRisk.name,
    measured?.let { json.encodeToString(BaselineMeasurement.serializer(), it) }, thetaRefDeg, thetaRefSetBy?.value, thetaRefSetAt,
    position.name, exercise.name, recordedAt, recordedBy.value, locked, supersedes?.value, notes, current,
)

internal fun BaselineEntity.toDomain() = Baseline(
    BaselineId(id), PatientId(patientId), Severity.valueOf(severity), headDeviationDeg, trunkDeviationDeg, SittingBalance.valueOf(sittingBalance),
    StandingBalance.valueOf(standingBalance), WalkingAbility.valueOf(walkingAbility), AssistanceLevel.fromLevel(assistanceLevel),
    MidlineAwareness.valueOf(midlineAwareness), CorrectionAbility.valueOf(correctionAbility), FallRisk.valueOf(fallRisk),
    measuredJson?.let { json.decodeFromString(BaselineMeasurement.serializer(), it) }, thetaRefDeg, thetaRefSetBy?.let(::ClinicianId), thetaRefSetAt,
    BodyPosition.valueOf(position), ExerciseType.valueOf(exercise), recordedAt, ClinicianId(recordedBy), locked, supersedes?.let(::BaselineId), notes,
)

internal fun Assessment.toEntity() = AssessmentEntity(id.value, patientId.value, scaleCode, scaleVersion, itemsJson, totalScore, subscoresJson, recordedAt, recordedBy.value, sessionId?.value, notes)
internal fun AssessmentEntity.toDomain() = Assessment(AssessmentId(id), PatientId(patientId), scaleCode, scaleVersion, itemsJson, totalScore, subscoresJson, recordedAt, ClinicianId(recordedBy), sessionId?.let(::SessionId), notes)

internal fun Session.toEntity() = SessionEntity(
    id.value, patientId.value, clinicianId.value, protocolId, protocolVersion, sessionNumber, visualMode.name, gainUsed, thetaRefDeg, baselineId?.value,
    deviceProfileId, headsetProfileId, position.name, startedAtUtc, startedMonoNs, deviceTimezone, endedAtUtc, endReason?.name, abortReason,
    ssqPre?.let { json.encodeToString(SsqScore.serializer(), it) }, ssqPost?.let { json.encodeToString(SsqScore.serializer(), it) }, notes,
    assistanceLevelBefore?.level, assistanceLevelAfter?.level, gyroBias?.let { json.encodeToString(Vec3.serializer(), it) }, driftDegPerMin, appVersion,
    crashRecovered, overrideReason, appliedTiltDeg,
)

internal fun SessionEntity.toDomain() = Session(
    SessionId(id), PatientId(patientId), ClinicianId(clinicianId), protocolId, protocolVersion, sessionNumber, VisualMode.valueOf(visualMode), gainUsed,
    thetaRefDeg, baselineId?.let(::BaselineId), deviceProfileId, headsetProfileId, BodyPosition.valueOf(position), startedAtUtc, startedMonoNs, deviceTimezone,
    endedAtUtc, endReason?.let { EndReason.valueOf(it) }, abortReason, ssqPreJson?.let { json.decodeFromString(SsqScore.serializer(), it) },
    ssqPostJson?.let { json.decodeFromString(SsqScore.serializer(), it) }, notes, assistanceBefore?.let { AssistanceLevel.fromLevel(it) },
    assistanceAfter?.let { AssistanceLevel.fromLevel(it) }, gyroBiasJson?.let { json.decodeFromString(Vec3.serializer(), it) }, driftDegPerMin, appVersion,
    crashRecovered, overrideReason, appliedTiltDeg,
)

private val cueListSer = ListSerializer(CueType.serializer())
private val episodeListSer = ListSerializer(Episode.serializer())
private val checkpointListSer = ListSerializer(Checkpoint.serializer())

internal fun BlockResult.toEntity() = BlockResultEntity(
    id.value, sessionId.value, blockId, orderIndex, exercise.name, position.name, startedMonoNs, durationS, targetDeg, toleranceDeg, gain,
    json.encodeToString(cueListSer, cues), json.encodeToString(DeviationMetrics.serializer(), metrics), json.encodeToString(EpisodeStats.serializer(), episodes),
    json.encodeToString(episodeListSer, episodeList), json.encodeToString(checkpointListSer, checkpoints), endReason.name,
    json.encodeToString(FilterParams.serializer(), filterParams), metrics.madDeg.orNullIfUndefined(), metrics.tib5Pct.orNullIfUndefined(), metrics.validSamplePct.orNullIfUndefined(),
)


internal fun BlockResultEntity.toDomain() = BlockResult(
    BlockResultId(id), SessionId(sessionId), blockId, orderIndex, ExerciseType.valueOf(exercise), BodyPosition.valueOf(position), startedMonoNs, durationS,
    targetDeg, toleranceDeg, gain, json.decodeFromString(cueListSer, cuesJson), json.decodeFromString(DeviationMetrics.serializer(), metricsJson),
    json.decodeFromString(EpisodeStats.serializer(), episodeStatsJson), json.decodeFromString(episodeListSer, episodesJson),
    json.decodeFromString(checkpointListSer, checkpointsJson), BlockEndReason.valueOf(endReason), json.decodeFromString(FilterParams.serializer(), filterParamsJson),
)

internal fun SessionSummary.toEntity() = SessionSummaryEntity(
    sessionId.value, baselineId?.value, baselineMadDeg, json.encodeToString(DeviationMetrics.serializer(), metrics), json.encodeToString(EpisodeStats.serializer(), episodes),
    deltaDeg, improvementPct, withinMdc, mdcDeg, lowConfidence, comparisonRefusedReason, balanceLossEvents, assistanceLevel?.level, gainUsed, visualMode.name,
    endReason.name, generatedByVersion, generatedAt, metrics.madDeg.orNullIfUndefined(), metrics.tib5Pct.orNullIfUndefined(),
)

internal fun SessionSummaryEntity.toDomain() = SessionSummary(
    SessionId(sessionId), baselineId?.let(::BaselineId), baselineMadDeg, json.decodeFromString(DeviationMetrics.serializer(), metricsJson),
    json.decodeFromString(EpisodeStats.serializer(), episodesJson), deltaDeg, improvementPct, withinMdc, mdcDeg, lowConfidence, comparisonRefusedReason,
    balanceLossEvents, assistanceLevel?.let { AssistanceLevel.fromLevel(it) }, gainUsed, VisualMode.valueOf(visualMode), EndReason.valueOf(endReason),
    generatedByVersion, generatedAt,
)

internal fun SessionEvent.toEntity() = SessionEventEntity(id.value, sessionId.value, tNanos, type.name, payloadJson)
internal fun SessionEventEntity.toDomain() = SessionEvent(EventId(id), SessionId(sessionId), tNanos, SessionEventType.valueOf(type), payloadJson)

internal fun TimeseriesFile.toEntity() = TimeseriesFileEntity(sessionId.value, path, sampleRateHz, sampleCount, sha256, json.encodeToString(FilterParams.serializer(), filterParams), crashRecovered)
internal fun TimeseriesFileEntity.toDomain() = TimeseriesFile(SessionId(sessionId), path, sampleRateHz, sampleCount, sha256, json.decodeFromString(FilterParams.serializer(), filterParamsJson), crashRecovered)

internal fun MediaAsset.toEntity(patientId: PatientId) = MediaAssetEntity(id.value, sessionId.value, patientId.value, type.name, pathEncrypted, capturedAt, retentionUntil)
internal fun MediaAssetEntity.toDomain() = MediaAsset(AssetId(id), SessionId(sessionId), MediaType.valueOf(type), pathEncrypted, capturedAt, retentionUntil)

internal fun AuditEvent.toEntity() = AuditEventEntity(id.value, clinicianId?.value, at, action.name, targetType, targetId, detail)
internal fun AuditEventEntity.toDomain() = AuditEvent(AuditId(id), clinicianId?.let(::ClinicianId), at, AuditAction.valueOf(action), targetType, targetId, detail)
