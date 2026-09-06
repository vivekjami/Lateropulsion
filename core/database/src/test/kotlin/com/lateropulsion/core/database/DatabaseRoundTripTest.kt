package com.lateropulsion.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateropulsion.core.common.EngineVersions
import com.lateropulsion.core.common.ManualClock
import com.lateropulsion.core.database.repo.AssessmentRepositoryImpl
import com.lateropulsion.core.database.repo.AuditRepositoryImpl
import com.lateropulsion.core.database.repo.Auditor
import com.lateropulsion.core.database.repo.BaselineRepositoryImpl
import com.lateropulsion.core.database.repo.ClinicianRepositoryImpl
import com.lateropulsion.core.database.repo.CurrentClinician
import com.lateropulsion.core.database.repo.PatientRepositoryImpl
import com.lateropulsion.core.database.repo.SessionRepositoryImpl
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.AuditAction
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BaselineMeasurement
import com.lateropulsion.core.model.BlockEndReason
import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.Checkpoint
import com.lateropulsion.core.model.Clinician
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.ClinicianRole
import com.lateropulsion.core.model.CompletedSession
import com.lateropulsion.core.model.CorrectionAbility
import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.DeviationMetrics
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.Episode
import com.lateropulsion.core.model.EpisodeStats
import com.lateropulsion.core.model.ExerciseType
import com.lateropulsion.core.model.FallRisk
import com.lateropulsion.core.model.FilterParams
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.LesionSide
import com.lateropulsion.core.model.MidlineAwareness
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientIdentity
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionEvent
import com.lateropulsion.core.model.SessionEventType
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.Severity
import com.lateropulsion.core.model.Sex
import com.lateropulsion.core.model.Side
import com.lateropulsion.core.model.SittingBalance
import com.lateropulsion.core.model.StandingBalance
import com.lateropulsion.core.model.TimeseriesFile
import com.lateropulsion.core.model.VisualMode
import com.lateropulsion.core.model.WalkingAbility
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DatabaseRoundTripTest {
    private lateinit var db: LpDatabase
    private val clock = ManualClock(utcMillis = 1_756_000_000_000L)
    private val me = ClinicianId("clin-1")
    private val who = CurrentClinician { me }
    private lateinit var auditor: Auditor
    private val deleted = mutableListOf<String>()

    @Before
    fun setUp() {
        db = LpDatabase.inMemory(ApplicationProvider.getApplicationContext())
        auditor = Auditor(db, clock, who)
    }

    @After
    fun tearDown() { db.close() }

    private fun patient(displayId: String) = Patient(
        id = Ids.patient(), displayId = displayId, age = 58, sex = Sex.FEMALE, diagnosis = "Right MCA infarct", lesionSide = LesionSide.RIGHT,
        affectedSide = Side.LEFT, lateropulsionDirection = Side.LEFT, onsetDate = LocalDate.of(2026, 8, 1), consentMedia = false, consentResearch = true,
        consentRecordedAt = clock.nowUtcMillis(), createdAt = clock.nowUtcMillis(), updatedAt = clock.nowUtcMillis(),
    )

    private fun baseline(p: Patient, measured: Boolean = true) = Baseline(
        id = Ids.baseline(), patientId = p.id, severity = Severity.MODERATE, headDeviationDeg = 12.0, trunkDeviationDeg = 15.0,
        sittingBalance = SittingBalance.SUPPORTED_ONLY, standingBalance = StandingBalance.UNABLE, walkingAbility = WalkingAbility.NON_AMBULANT,
        assistanceLevel = AssistanceLevel.ONE_PERSON, midlineAwareness = MidlineAwareness.PARTIAL, correctionAbility = CorrectionAbility.TOLERATES_PASSIVE,
        fallRisk = FallRisk.HIGH,
        measured = if (measured) BaselineMeasurement(10.0, 10.0, 10.5, 2.0, 15.0, 5.0, 40.0, 0.1, 98.0, 60.0, listOf(1, 2, 3), listOf(-5.0, 0.0, 5.0, 10.0), FilterParams.summary(50.0), 3000) else null,
        thetaRefDeg = 1.5, thetaRefSetBy = me, thetaRefSetAt = clock.nowUtcMillis(), recordedAt = clock.nowUtcMillis(), recordedBy = me,
    )

    private val metrics = DeviationMetrics(4.0, 5.0, 6.0, 12.0, 2.0, 55.0, 90.0, 20.0, 300.0, 5.0, 0.3, 4500, 4600, 90.0)

    @Test
    fun `REQ-PAT-001 register assess retrieve round-trips and is audited`() = runTest {
        val clinicians = ClinicianRepositoryImpl(db, clock, auditor)
        clinicians.create(Clinician(me, "A. Therapist", ClinicianRole.PHYSIOTHERAPIST, clock.nowUtcMillis()), "hash", "salt").getOrThrow()
        val patients = PatientRepositoryImpl(db, clock, auditor) { deleted += it }
        assertEquals("LP-2026-0001", patients.allocateDisplayId(2026))
        assertEquals("LP-2026-0002", patients.allocateDisplayId(2026))
        val p = patient("LP-2026-0002")
        patients.create(p, PatientIdentity(p.id, "Asha Rao", "MRN-77", null)).getOrThrow()
        // duplicate display id refused
        val other = patient("LP-2026-0002").copy(id = Ids.patient())
        val dup = patients.create(other, PatientIdentity(other.id, "Other Person", null, null))
        assertTrue(dup.isFailure)

        assertEquals(p, patients.findById(p.id))
        assertEquals("Asha Rao", patients.identity(p.id)!!.name)
        assertEquals(1, patients.search("asha").size)
        assertEquals(1, patients.search("lp-2026-0002").size)
        assertEquals(1, patients.findPossibleDuplicates(PatientIdentity(Ids.patient(), "ASHA  rao", null, null), 59).size)

        val baselines = BaselineRepositoryImpl(db, auditor)
        val b = baseline(p)
        baselines.save(b).getOrThrow()
        assertEquals(b, baselines.current(p.id))

        val assessments = AssessmentRepositoryImpl(db, auditor)
        assessments.save(com.lateropulsion.core.model.Assessment(Ids.assessment(), p.id, "SCP", "1996.1", "{}", 3.5, null, clock.nowUtcMillis(), me)).getOrThrow()
        assertEquals(3.5, assessments.latest(p.id, "SCP")!!.totalScore, 0.0)

        val audit = AuditRepositoryImpl(db).recent()
        val actions = audit.map { it.action }
        assertTrue(actions.contains(AuditAction.CREATE))
        assertTrue(actions.contains(AuditAction.VIEW))
        assertTrue(audit.all { it.clinicianId == me })
        // no PHI in audit details
        assertTrue(audit.none { it.detail.contains("Asha") || it.detail.contains("MRN-77") })
    }

    @Test
    fun `REQ-SES-030 session lifecycle persists blocks summary events and timeseries atomically`() = runTest {
        val patients = PatientRepositoryImpl(db, clock, auditor) { deleted += it }
        val p = patient("LP-2026-0001"); patients.create(p, PatientIdentity(p.id, "Test Person", null, null)).getOrThrow()
        val baselines = BaselineRepositoryImpl(db, auditor)
        val b = baseline(p); baselines.save(b).getOrThrow()
        val sessions = SessionRepositoryImpl(db, auditor)
        assertEquals(1, sessions.nextSessionNumber(p.id))
        val s = Session(
            Ids.session(), p.id, me, "std-sitting-v3", 3, 1, VisualMode.VERTICAL_REFERENCE, 0.0, 1.5, b.id, "redmi-note-10s", "generic-open-bottom-v1",
            BodyPosition.SITTING_UNSUPPORTED, clock.nowUtcMillis(), 123L, "Asia/Kolkata", appVersion = "0.1.0",
        )
        sessions.start(s).getOrThrow()
        assertTrue(baselines.findById(b.id)!!.locked) // ADR-004: first session locks the baseline
        assertTrue(baselines.save(b.copy(notes = "edit")).isFailure)
        assertEquals(1, sessions.unfinished().size)

        val block = BlockResult(
            Ids.blockResult(), s.id, "hold-1", 0, ExerciseType.SITTING_HOLD, BodyPosition.SITTING_UNSUPPORTED, 123L, 180.0, 0.0, 5.0, 0.0,
            listOf(CueType.PLUMB_LINE, CueType.HAPTIC), metrics, EpisodeStats(1, 0, 2.0, 2.0, 3.0, 3.0), listOf(Episode(10.0, 12.0, 14.0, false, 3.0, Side.LEFT)),
            listOf(Checkpoint(60.0, 3.0, 4.5, true)), BlockEndReason.COMPLETED, FilterParams.summary(50.0),
        )
        val summary = SessionSummary(s.id, b.id, 10.0, metrics, block.episodes, -5.0, 50.0, false, 2.0, false, null, 0, AssistanceLevel.SUPERVISION, 0.0,
            VisualMode.VERTICAL_REFERENCE, EndReason.COMPLETED, EngineVersions.METRICS_ENGINE, clock.nowUtcMillis())
        val events = listOf(SessionEvent(Ids.event(), s.id, 0L, SessionEventType.SESSION_START), SessionEvent(Ids.event(), s.id, 5_000_000_000L, SessionEventType.CHECKPOINT, """{"at_s":60}"""))
        val ts = TimeseriesFile(s.id, "/data/sessions/${s.id.value}.lpx", 50, 9000, "abc", FilterParams.summary(50.0), false)
        val done = s.copy(endedAtUtc = clock.nowUtcMillis() + 600_000, endReason = EndReason.COMPLETED, notes = "went well")
        sessions.complete(CompletedSession(done, listOf(block), summary, events, ts)).getOrThrow()

        assertEquals(done, sessions.findById(s.id))
        assertEquals(listOf(block), sessions.blocks(s.id))
        assertEquals(summary, sessions.summary(s.id))
        assertEquals(events, sessions.events(s.id))
        assertEquals(ts, sessions.timeseries(s.id))
        assertEquals(1, sessions.summariesForPatient(p.id, "std-sitting-v3").size)
        assertEquals(0, sessions.summariesForPatient(p.id, "other").size)
        assertTrue(sessions.unfinished().isEmpty())
        assertEquals(2, sessions.nextSessionNumber(p.id))
        assertEquals(1 to 1, sessions.countToday(clock.nowUtcMillis() - 1000))
        // a completed session without an end reason is refused
        assertTrue(sessions.complete(CompletedSession(s, emptyList(), summary, emptyList(), null)).isFailure)
    }

    /** A calm session has no episodes, so its means are NaN; it must still save and read back (found on hardware). */
    @Test
    fun `REQ-DAT-004 a session with no episodes and undefined metrics saves and reads back`() = runTest {
        val patients = PatientRepositoryImpl(db, clock, auditor) { deleted += it }
        val p = patient("LP-2026-0002"); patients.create(p, PatientIdentity(p.id, "Calm Person", null, null)).getOrThrow()
        val sessions = SessionRepositoryImpl(db, auditor)
        val s = Session(
            Ids.session(), p.id, me, "std-sitting-v3", 3, 1, VisualMode.VERTICAL_REFERENCE, 0.0, 0.0, null, "redmi-note-10s", "phone-visor-mono-v1",
            BodyPosition.SITTING_UNSUPPORTED, clock.nowUtcMillis(), 123L, "Asia/Kolkata", appVersion = "0.1.0",
        )
        sessions.start(s).getOrThrow()
        val calm = DeviationMetrics.EMPTY.copy(totalSamples = 40)
        val block = BlockResult(
            Ids.blockResult(), s.id, "warmup", 0, ExerciseType.MIDLINE_TRAINING, BodyPosition.SITTING_UNSUPPORTED, 123L, 8.0, 0.0, 10.0, 0.0,
            listOf(CueType.PLUMB_LINE), calm, EpisodeStats.NONE, emptyList(), emptyList(), BlockEndReason.ABORTED, FilterParams.summary(50.0),
        )
        val summary = SessionSummary(s.id, null, null, calm, EpisodeStats.NONE, null, null, null, 2.0, true, null, 0, null, 0.0,
            VisualMode.VERTICAL_REFERENCE, EndReason.ABORTED, EngineVersions.METRICS_ENGINE, clock.nowUtcMillis())
        val done = s.copy(endedAtUtc = clock.nowUtcMillis() + 60_000, endReason = EndReason.ABORTED, abortReason = "THERAPIST_CONTROL back")
        sessions.complete(CompletedSession(done, listOf(block), summary, emptyList(), null)).getOrThrow()
        val back = sessions.summary(s.id)!!
        assertTrue(back.episodes.meanDurationS.isNaN())
        assertTrue(back.metrics.madDeg.isNaN())
        assertEquals(0, back.episodes.count)
        assertEquals(summary, back)
        assertEquals(listOf(block), sessions.blocks(s.id))
        assertTrue(sessions.unfinished().isEmpty())
    }

    @Test
    fun `REQ-SEC-010 erasure removes identifiers and media, optionally keeping de-identified data`() = runTest {
        val patients = PatientRepositoryImpl(db, clock, auditor) { deleted += it }
        val p = patient("LP-2026-0001"); patients.create(p, PatientIdentity(p.id, "Erase Me", "MRN-1", "+91")).getOrThrow()
        val sessions = SessionRepositoryImpl(db, auditor)
        val s = Session(Ids.session(), p.id, me, "std-sitting-v3", 3, 1, VisualMode.VERTICAL_REFERENCE, 0.0, 1.5, null, "d", "h",
            BodyPosition.SITTING_UNSUPPORTED, clock.nowUtcMillis(), 1L, "UTC", appVersion = "0.1.0")
        sessions.start(s).getOrThrow()
        db.sessions().insertTimeseries(TimeseriesFile(s.id, "/x/ts.lpx", 50, 1, "h", FilterParams.summary(50.0), false).let {
            com.lateropulsion.core.database.entity.TimeseriesFileEntity(it.sessionId.value, it.path, 50, 1, "h", "{}", false)
        })

        patients.erase(p.id, retainDeidentified = true).getOrThrow()
        assertNull(patients.identity(p.id))
        assertTrue(patients.findById(p.id)!!.isErased)
        assertEquals(1, sessions.listForPatient(p.id).size) // de-identified series retained
        assertTrue(patients.search("erase").isEmpty())
        assertTrue(deleted.isEmpty())

        val p2 = patient("LP-2026-0002"); patients.create(p2, PatientIdentity(p2.id, "Gone Fully", null, null)).getOrThrow()
        val s2 = s.copy(id = Ids.session(), patientId = p2.id)
        sessions.start(s2).getOrThrow()
        db.sessions().insertTimeseries(com.lateropulsion.core.database.entity.TimeseriesFileEntity(s2.id.value, "/x/ts2.lpx", 50, 1, "h", "{}", false))
        patients.erase(p2.id, retainDeidentified = false).getOrThrow()
        assertNull(patients.findById(p2.id))
        assertTrue(sessions.listForPatient(p2.id).isEmpty())
        assertEquals(listOf("/x/ts2.lpx"), deleted)
        assertTrue(AuditRepositoryImpl(db).recent().any { it.action == AuditAction.ERASE })
    }

    @Test
    fun `REQ-SEC-005 audit table has no update or delete path`() {
        val methods = com.lateropulsion.core.database.dao.AuditDao::class.java.methods.map { it.name }
        assertFalse(methods.any { it.startsWith("update") || it.startsWith("delete") })
        assertNotNull(methods.firstOrNull { it == "insert" })
    }
}
