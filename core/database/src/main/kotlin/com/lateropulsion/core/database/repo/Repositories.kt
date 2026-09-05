package com.lateropulsion.core.database.repo

import android.database.sqlite.SQLiteConstraintException
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.DisplayIdFormat
import com.lateropulsion.core.common.LpError
import com.lateropulsion.core.common.Outcome
import com.lateropulsion.core.common.Redaction
import com.lateropulsion.core.database.LpDatabase
import com.lateropulsion.core.model.AssetId
import com.lateropulsion.core.model.Assessment
import com.lateropulsion.core.model.AssessmentRepository
import com.lateropulsion.core.model.AuditAction
import com.lateropulsion.core.model.AuditEvent
import com.lateropulsion.core.model.AuditRepository
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineId
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.Clinician
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.ClinicianRepository
import com.lateropulsion.core.model.CompletedSession
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.MediaAsset
import com.lateropulsion.core.model.MediaRepository
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.PatientIdentity
import com.lateropulsion.core.model.PatientListItem
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionEvent
import com.lateropulsion.core.model.SessionId
import com.lateropulsion.core.model.SessionRepository
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.TimeseriesFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Who is acting, for the audit trail. Null while no clinician is logged in (e.g. crash recovery at start-up). */
public fun interface CurrentClinician {
    public fun id(): ClinicianId?
}

/** Every repository writes through this so no data access escapes the audit log (REQ-SEC-005). */
public class Auditor(private val db: LpDatabase, private val clock: Clock, private val who: CurrentClinician) {
    public suspend fun record(action: AuditAction, targetType: String, targetId: String, detail: String = "") {
        db.audit().insert(AuditEvent(Ids.audit(), who.id(), clock.nowUtcMillis(), action, targetType, targetId, detail).toEntity())
    }
}

public class PatientRepositoryImpl(
    private val db: LpDatabase,
    private val clock: Clock,
    private val auditor: Auditor,
    /** Deletes a file by path; injected so the repository stays free of filesystem policy. */
    private val deleteFile: (String) -> Unit,
) : PatientRepository {
    private val dao get() = db.patients()

    override suspend fun create(patient: Patient, identity: PatientIdentity): Outcome<Patient> {
        patient.validate().toList().firstOrNull()?.let { return Outcome.failure(it) }
        identity.validate().toList().firstOrNull()?.let { return Outcome.failure(it) }
        if (identity.patientId != patient.id) return Outcome.failure(LpError.Validation("identity", "identity does not belong to this patient"))
        return try {
            dao.insert(patient.toEntity())
            dao.insertIdentity(identity.toEntity())
            auditor.record(AuditAction.CREATE, TARGET, patient.id.value, "display_id=${patient.displayId}")
            Outcome.success(patient)
        } catch (e: SQLiteConstraintException) {
            Outcome.failure(LpError.Conflict("Display ID ${patient.displayId} already exists"))
        }
    }

    override suspend fun update(patient: Patient): Outcome<Patient> {
        patient.validate().toList().firstOrNull()?.let { return Outcome.failure(it) }
        val existing = dao.byId(patient.id.value) ?: return Outcome.failure(LpError.NotFound(TARGET, patient.id.value))
        if (existing.erasedAt != null) return Outcome.failure(LpError.Precondition("Patient record is erased"))
        dao.update(patient.copy(updatedAt = clock.nowUtcMillis()).toEntity().copy(consentVersion = existing.consentVersion))
        auditor.record(AuditAction.EDIT, TARGET, patient.id.value)
        return Outcome.success(patient)
    }

    override suspend fun findById(id: PatientId): Patient? = dao.byId(id.value)?.toDomain()?.also { auditor.record(AuditAction.VIEW, TARGET, id.value) }

    override suspend fun findByDisplayId(displayId: String): Patient? =
        dao.byDisplayId(displayId.trim().uppercase())?.toDomain()?.also { auditor.record(AuditAction.VIEW, TARGET, it.id.value) }

    override suspend fun identity(id: PatientId): PatientIdentity? = dao.identity(id.value)?.toDomain()?.also { auditor.record(AuditAction.VIEW, "patient_identity", id.value) }

    override suspend fun search(query: String, limit: Int): List<PatientListItem> {
        val q = "%${query.trim()}%"
        return dao.search(q, "%${PatientIdentity.normalizeName(query)}%", limit).map { it.toDomain() }
    }

    override fun observeRecent(limit: Int): Flow<List<PatientListItem>> = dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun findPossibleDuplicates(identity: PatientIdentity, age: Int): List<PatientListItem> =
        dao.possibleDuplicates(identity.nameKey, age, identity.mrn?.takeIf { it.isNotBlank() }).map { it.toDomain() }

    override suspend fun allocateDisplayId(year: Int): String = DisplayIdFormat.format(year, dao.allocateSequence(year))

    override suspend fun erase(id: PatientId, retainDeidentified: Boolean): Outcome<Unit> {
        val existing = dao.byId(id.value) ?: return Outcome.failure(LpError.NotFound(TARGET, id.value))
        val media = db.media().forPatient(id.value)
        val paths = ArrayList<String>()
        media.forEach { paths += it.pathEncrypted }
        if (!retainDeidentified) paths += db.sessions().timeseriesPathsForPatient(id.value)
        dao.deleteIdentity(id.value)
        db.media().deleteForPatient(id.value)
        if (retainDeidentified) {
            dao.update(existing.copy(erasedAt = clock.nowUtcMillis(), notes = "", updatedAt = clock.nowUtcMillis()))
        } else {
            dao.deletePatient(id.value) // cascades to baselines, assessments, sessions, blocks, summaries, events, timeseries
        }
        paths.forEach { runCatching { deleteFile(it) } }
        auditor.record(AuditAction.ERASE, TARGET, id.value, "retain_deidentified=$retainDeidentified files=${paths.size}")
        return Outcome.success(Unit)
    }

    private companion object { const val TARGET = "patient" }
}

public class BaselineRepositoryImpl(private val db: LpDatabase, private val auditor: Auditor) : BaselineRepository {
    override suspend fun save(baseline: Baseline): Outcome<Baseline> {
        baseline.validate().toList().firstOrNull()?.let { return Outcome.failure(it) }
        val existing = db.baselines().byId(baseline.id.value)
        if (existing != null) {
            if (existing.locked) return Outcome.failure(LpError.Precondition("Baseline is locked; record a new baseline that supersedes it (ADR-004)"))
            db.baselines().update(baseline.toEntity(current = existing.isCurrent))
            auditor.record(AuditAction.EDIT, "baseline", baseline.id.value)
        } else {
            baseline.supersedes?.let { prev ->
                val p = db.baselines().byId(prev.value) ?: return Outcome.failure(LpError.NotFound("baseline", prev.value))
                if (p.patientId != baseline.patientId.value) return Outcome.failure(LpError.Validation("supersedes", "belongs to another patient"))
            }
            db.baselines().insertAsCurrent(baseline.toEntity(current = true))
            auditor.record(AuditAction.CREATE, "baseline", baseline.id.value, baseline.supersedes?.let { "supersedes=${Redaction.shortId(it.value)}" } ?: "")
        }
        return Outcome.success(baseline)
    }

    override suspend fun current(patientId: PatientId): Baseline? = db.baselines().current(patientId.value)?.toDomain()
    override suspend fun findById(id: BaselineId): Baseline? = db.baselines().byId(id.value)?.toDomain()
    override suspend fun history(patientId: PatientId): List<Baseline> = db.baselines().history(patientId.value).map { it.toDomain() }

    override suspend fun lock(id: BaselineId): Outcome<Unit> {
        db.baselines().byId(id.value) ?: return Outcome.failure(LpError.NotFound("baseline", id.value))
        db.baselines().lock(id.value)
        return Outcome.success(Unit)
    }
}

public class AssessmentRepositoryImpl(private val db: LpDatabase, private val auditor: Auditor) : AssessmentRepository {
    override suspend fun save(assessment: Assessment): Outcome<Assessment> {
        db.assessments().insert(assessment.toEntity())
        auditor.record(AuditAction.CREATE, "assessment", assessment.id.value, "scale=${assessment.scaleCode}@${assessment.scaleVersion}")
        return Outcome.success(assessment)
    }
    override suspend fun listForPatient(patientId: PatientId): List<Assessment> = db.assessments().forPatient(patientId.value).map { it.toDomain() }
    override suspend fun latest(patientId: PatientId, scaleCode: String): Assessment? = db.assessments().latest(patientId.value, scaleCode)?.toDomain()
}

public class SessionRepositoryImpl(private val db: LpDatabase, private val auditor: Auditor) : SessionRepository {
    private val dao get() = db.sessions()

    override suspend fun start(session: Session): Outcome<Session> {
        dao.insert(session.toEntity())
        session.baselineId?.let { db.baselines().lock(it.value) } // first session against a baseline freezes it (ADR-004)
        auditor.record(AuditAction.CREATE, "session", session.id.value, "protocol=${session.protocolId} n=${session.sessionNumber}")
        return Outcome.success(session)
    }

    override suspend fun update(session: Session): Outcome<Session> {
        dao.byId(session.id.value) ?: return Outcome.failure(LpError.NotFound("session", session.id.value))
        dao.update(session.toEntity())
        return Outcome.success(session)
    }

    override suspend fun complete(completed: CompletedSession): Outcome<Unit> {
        val s = completed.session
        if (s.endReason == null) return Outcome.failure(LpError.Precondition("completed session must carry an end reason"))
        dao.byId(s.id.value) ?: return Outcome.failure(LpError.NotFound("session", s.id.value))
        dao.complete(s.toEntity(), completed.blocks.map { it.toEntity() }, completed.summary.toEntity(), completed.events.map { it.toEntity() }, completed.timeseries?.toEntity())
        auditor.record(AuditAction.EDIT, "session", s.id.value, "end=${s.endReason} blocks=${completed.blocks.size}")
        return Outcome.success(Unit)
    }

    override suspend fun findById(id: SessionId): Session? = dao.byId(id.value)?.toDomain()?.also { auditor.record(AuditAction.VIEW, "session", id.value) }
    override suspend fun listForPatient(patientId: PatientId): List<Session> = dao.forPatient(patientId.value).map { it.toDomain() }
    override fun observeForPatient(patientId: PatientId): Flow<List<Session>> = dao.observeForPatient(patientId.value).map { l -> l.map { it.toDomain() } }
    override suspend fun summariesForPatient(patientId: PatientId, protocolId: String?): List<SessionSummary> = dao.summariesForPatient(patientId.value, protocolId).map { it.toDomain() }
    override suspend fun summary(id: SessionId): SessionSummary? = dao.summary(id.value)?.toDomain()
    override suspend fun blocks(id: SessionId): List<BlockResult> = dao.blocks(id.value).map { it.toDomain() }
    override suspend fun events(id: SessionId): List<SessionEvent> = dao.events(id.value).map { it.toDomain() }
    override suspend fun appendEvents(events: List<SessionEvent>) { if (events.isNotEmpty()) dao.insertEvents(events.map { it.toEntity() }) }
    override suspend fun timeseries(id: SessionId): TimeseriesFile? = dao.timeseries(id.value)?.toDomain()
    override suspend fun nextSessionNumber(patientId: PatientId): Int = dao.nextSessionNumber(patientId.value)
    override suspend fun unfinished(): List<Session> = dao.unfinished().map { it.toDomain() }
    override suspend fun countToday(dayStartUtc: Long): Pair<Int, Int> = dao.countSince(dayStartUtc) to dao.patientsSince(dayStartUtc)
}

public class AuditRepositoryImpl(private val db: LpDatabase) : AuditRepository {
    override suspend fun record(event: AuditEvent) { db.audit().insert(event.toEntity()) }
    override suspend fun recent(limit: Int): List<AuditEvent> = db.audit().recent(limit).map { it.toDomain() }
    override suspend fun forTarget(targetType: String, targetId: String): List<AuditEvent> = db.audit().forTarget(targetType, targetId).map { it.toDomain() }
}

public class ClinicianRepositoryImpl(private val db: LpDatabase, private val clock: Clock, private val auditor: Auditor) : ClinicianRepository {
    override suspend fun create(clinician: Clinician, pinHash: String, salt: String): Outcome<Clinician> {
        if (clinician.displayName.isBlank()) return Outcome.failure(LpError.Validation("displayName", "required"))
        db.clinicians().insert(clinician.toEntity(pinHash, salt))
        auditor.record(AuditAction.CREATE, "clinician", clinician.id.value)
        return Outcome.success(clinician)
    }
    override suspend fun list(): List<Clinician> = db.clinicians().list().map { it.toDomain() }
    override suspend fun findById(id: ClinicianId): Clinician? = db.clinicians().byId(id.value)?.toDomain()
    override suspend fun credentials(id: ClinicianId): Pair<String, String>? = db.clinicians().byId(id.value)?.let { it.pinHash to it.salt }
    override suspend fun updatePin(id: ClinicianId, pinHash: String, salt: String): Outcome<Unit> {
        val c = db.clinicians().byId(id.value) ?: return Outcome.failure(LpError.NotFound("clinician", id.value))
        db.clinicians().update(c.copy(pinHash = pinHash, salt = salt, failedAttempts = 0, lockedUntil = 0))
        auditor.record(AuditAction.EDIT, "clinician", id.value, "pin changed")
        return Outcome.success(Unit)
    }
    override suspend fun recordFailedAttempt(id: ClinicianId): Int {
        val c = db.clinicians().byId(id.value) ?: return 0
        val n = c.failedAttempts + 1
        db.clinicians().update(c.copy(failedAttempts = n))
        return n
    }
    override suspend fun resetFailedAttempts(id: ClinicianId) {
        db.clinicians().byId(id.value)?.let { db.clinicians().update(it.copy(failedAttempts = 0, lockedUntil = 0)) }
    }
    public suspend fun setLockedUntil(id: ClinicianId, until: Long) {
        db.clinicians().byId(id.value)?.let { db.clinicians().update(it.copy(lockedUntil = until)) }
    }
    public suspend fun lockedUntil(id: ClinicianId): Long = db.clinicians().byId(id.value)?.lockedUntil ?: 0L
    public suspend fun count(): Int = db.clinicians().count()
    @Suppress("unused") private val now get() = clock.nowUtcMillis()
}

public class MediaRepositoryImpl(private val db: LpDatabase, private val auditor: Auditor) : MediaRepository {
    override suspend fun save(asset: MediaAsset): Outcome<MediaAsset> {
        val session = db.sessions().byId(asset.sessionId.value) ?: return Outcome.failure(LpError.NotFound("session", asset.sessionId.value))
        val patient = db.patients().byId(session.patientId) ?: return Outcome.failure(LpError.NotFound("patient", session.patientId))
        if (!patient.consentMedia) return Outcome.failure(LpError.Precondition("Patient has not consented to media capture"))
        db.media().insert(asset.toEntity(PatientId(session.patientId)))
        auditor.record(AuditAction.CREATE, "media", asset.id.value, "type=${asset.type}")
        return Outcome.success(asset)
    }
    override suspend fun listForSession(sessionId: SessionId): List<MediaAsset> = db.media().forSession(sessionId.value).map { it.toDomain() }
    override suspend fun listForPatient(patientId: PatientId): List<MediaAsset> = db.media().forPatient(patientId.value).map { it.toDomain() }
    override suspend fun expired(nowUtc: Long): List<MediaAsset> = db.media().expired(nowUtc).map { it.toDomain() }
    override suspend fun delete(id: AssetId): Outcome<Unit> {
        db.media().delete(id.value)
        auditor.record(AuditAction.DELETE, "media", id.value)
        return Outcome.success(Unit)
    }
}
