package com.lateropulsion.core.model

import com.lateropulsion.core.common.Outcome
import kotlinx.coroutines.flow.Flow

/** Domain repository contracts. Implemented in core:database; faked in tests. */
public interface PatientRepository {
    public suspend fun create(patient: Patient, identity: PatientIdentity): Outcome<Patient>
    public suspend fun update(patient: Patient): Outcome<Patient>
    public suspend fun findById(id: PatientId): Patient?
    public suspend fun findByDisplayId(displayId: String): Patient?
    public suspend fun identity(id: PatientId): PatientIdentity?
    public suspend fun search(query: String, limit: Int = 50): List<PatientListItem>
    public fun observeRecent(limit: Int = 20): Flow<List<PatientListItem>>
    public suspend fun findPossibleDuplicates(identity: PatientIdentity, age: Int): List<PatientListItem>
    public suspend fun allocateDisplayId(year: Int): String
    /** Removes identifiers and media; retains de-identified series only if [retainDeidentified]. */
    public suspend fun erase(id: PatientId, retainDeidentified: Boolean): Outcome<Unit>
}

public interface BaselineRepository {
    public suspend fun save(baseline: Baseline): Outcome<Baseline>
    public suspend fun current(patientId: PatientId): Baseline?
    public suspend fun findById(id: BaselineId): Baseline?
    public suspend fun history(patientId: PatientId): List<Baseline>
    public suspend fun lock(id: BaselineId): Outcome<Unit>
}

public interface AssessmentRepository {
    public suspend fun save(assessment: Assessment): Outcome<Assessment>
    public suspend fun listForPatient(patientId: PatientId): List<Assessment>
    public suspend fun latest(patientId: PatientId, scaleCode: String): Assessment?
}

public interface SessionRepository {
    public suspend fun start(session: Session): Outcome<Session>
    public suspend fun update(session: Session): Outcome<Session>
    public suspend fun complete(completed: CompletedSession): Outcome<Unit>
    public suspend fun findById(id: SessionId): Session?
    public suspend fun listForPatient(patientId: PatientId): List<Session>
    public fun observeForPatient(patientId: PatientId): Flow<List<Session>>
    public suspend fun summariesForPatient(patientId: PatientId, protocolId: String? = null): List<SessionSummary>
    public suspend fun summary(id: SessionId): SessionSummary?
    public suspend fun blocks(id: SessionId): List<BlockResult>
    public suspend fun events(id: SessionId): List<SessionEvent>
    public suspend fun timeseries(id: SessionId): TimeseriesFile?
    public suspend fun nextSessionNumber(patientId: PatientId): Int
    /** Sessions with no end reason: candidates for crash recovery on next launch. */
    public suspend fun unfinished(): List<Session>
    public suspend fun countToday(dayStartUtc: Long): Pair<Int, Int>
}

public interface AuditRepository {
    public suspend fun record(event: AuditEvent)
    public suspend fun recent(limit: Int = 200): List<AuditEvent>
    public suspend fun forTarget(targetType: String, targetId: String): List<AuditEvent>
}

public interface ClinicianRepository {
    public suspend fun create(clinician: Clinician, pinHash: String, salt: String): Outcome<Clinician>
    public suspend fun list(): List<Clinician>
    public suspend fun findById(id: ClinicianId): Clinician?
    public suspend fun credentials(id: ClinicianId): Pair<String, String>?
    public suspend fun updatePin(id: ClinicianId, pinHash: String, salt: String): Outcome<Unit>
    public suspend fun recordFailedAttempt(id: ClinicianId): Int
    public suspend fun resetFailedAttempts(id: ClinicianId)
}

public interface MediaRepository {
    public suspend fun save(asset: MediaAsset): Outcome<MediaAsset>
    public suspend fun listForSession(sessionId: SessionId): List<MediaAsset>
    public suspend fun listForPatient(patientId: PatientId): List<MediaAsset>
    public suspend fun expired(nowUtc: Long): List<MediaAsset>
    public suspend fun delete(id: AssetId): Outcome<Unit>
}

/** Read-only access to the versioned configuration bundled as data (ADR-007). */
public interface ConfigRepository {
    public suspend fun appConfig(): AppConfig
    public suspend fun protocols(): List<ProtocolSpec>
    public suspend fun protocol(id: String): ProtocolSpec?
    public suspend fun scales(): List<ScaleDefinition>
    public suspend fun scale(code: String): ScaleDefinition?
    public suspend fun deviceProfiles(): List<DeviceProfile>
    public suspend fun headsetProfiles(): List<HeadsetProfile>
}
