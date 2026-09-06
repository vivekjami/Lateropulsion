package com.lateropulsion.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EntityValidationTest {
    private val patient = Patient(
        id = PatientId("p1"), displayId = "LP-2026-0001", age = 64, sex = Sex.MALE, diagnosis = "Right MCA infarct",
        lesionSide = LesionSide.RIGHT, affectedSide = Side.LEFT, lateropulsionDirection = Side.LEFT,
        onsetDate = LocalDate.of(2026, 8, 20), consentMedia = false, consentResearch = true, consentRecordedAt = 1L,
        createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `REQ-PAT-001 valid patient passes`() {
        assertTrue(patient.validate().isEmpty)
    }

    @Test
    fun `REQ-PAT-001 invalid patient fields are all reported`() {
        val bad = patient.copy(displayId = "nope", age = 0, diagnosis = " ", consentMedia = true, consentRecordedAt = null)
        val fields = bad.validate().toList().map { it.field }
        assertEquals(listOf("displayId", "age", "diagnosis", "consentRecordedAt"), fields)
    }

    @Test
    fun `identity normalises names for duplicate detection only`() {
        val id = PatientIdentity(PatientId("p1"), "  Asha   Rao ", "MRN-1", null)
        assertEquals("asha rao", id.nameKey)
        assertTrue(id.validate().isEmpty)
    }

    @Test
    fun `REQ-SAF-003 body positions order sitting before standing before walking`() {
        assertTrue(BodyPosition.SUPPORTED_SITTING.stage < BodyPosition.SITTING_UNSUPPORTED.stage)
        assertTrue(BodyPosition.SITTING_UNSUPPORTED.stage < BodyPosition.STANDING.stage)
        assertTrue(BodyPosition.STANDING.stage < BodyPosition.WALKING.stage)
        assertTrue(BodyPosition.STANDING.requiresHarness && BodyPosition.WALKING.requiresHarness)
        assertFalse(BodyPosition.SITTING_UNSUPPORTED.requiresHarness)
    }

    @Test
    fun `REQ-MET-020 validity flags exclude invalid samples from metrics`() {
        assertTrue(ValidityFlags.isValidForMetrics(ValidityFlags.VALID or ValidityFlags.IN_BAND))
        assertFalse(ValidityFlags.isValidForMetrics(ValidityFlags.VALID or ValidityFlags.PITCH_OUT_OF_RANGE))
        assertFalse(ValidityFlags.isValidForMetrics(ValidityFlags.VALID or ValidityFlags.TRACKING_LOST))
        assertFalse(ValidityFlags.isValidForMetrics(ValidityFlags.VALID or ValidityFlags.MOUNT_SHIFT))
        assertFalse(ValidityFlags.isValidForMetrics(0))
        assertEquals(listOf("VALID", "IN_BAND"), ValidityFlags.describe(ValidityFlags.VALID or ValidityFlags.IN_BAND))
    }

    @Test
    fun `REQ-SAF-001 checklist enforces harness only for standing and walking`() {
        val allButHarness = PreSessionChecklist(
            supervisionAttested = true, hygieneConfirmed = true, batteryOk = true, thermalOk = true, storageOk = true,
            contraindicationsRechecked = true, identityConfirmed = true, deviceQualified = true,
        )
        assertTrue(allButHarness.isComplete(BodyPosition.SITTING_UNSUPPORTED, requireHarness = true))
        assertEquals(listOf("harness"), allButHarness.missing(BodyPosition.STANDING, requireHarness = true))
    }

    @Test
    fun `device profile qualification thresholds`() {
        val ok = DeviceProfile("d", "X", "M", "M", rollSign = 1, thetaMountDeg = -90.0, residualRmsDeg = 0.8, maxErrorDeg = 1.5, imuRateHz = 200.0)
        assertTrue(ok.meetsAccuracyTargets())
        assertFalse(ok.copy(rollSign = 0).meetsAccuracyTargets())
        assertFalse(ok.copy(residualRmsDeg = 1.2).meetsAccuracyTargets())
        assertFalse(ok.copy(imuRateHz = 90.0).meetsAccuracyTargets())
    }

    @Test
    fun `REQ-PAT-030 baseline defaults are conservative, complete only once measured, and tilt reads in clinical words`() {
        val b = Baseline.defaultFor(patient, ClinicianId("c1"), 1_000L)
        assertEquals(patient.id, b.patientId)
        assertEquals(Severity.MODERATE, b.severity)
        assertEquals(WalkingAbility.NON_AMBULANT, b.walkingAbility)
        assertEquals(AssistanceLevel.ONE_PERSON, b.assistanceLevel)
        assertEquals(FallRisk.HIGH, b.fallRisk)
        assertFalse(b.locked)
        assertFalse(b.isComplete)
        assertTrue(b.validate().toList().isEmpty(), b.validate().toList().toString())
        // measured against true vertical (θ_ref = 0) by default; who set it is still recorded
        val measured = b.copy(thetaRefDeg = 0.0, thetaRefSetBy = ClinicianId("c1"), thetaRefSetAt = 2L, measured = null)
        assertFalse(measured.isComplete)
        assertTrue(measured.validate().toList().isEmpty())
        assertEquals("12.3° to the RIGHT", Baseline.describeTilt(12.34))
        assertEquals("7.0° to the LEFT", Baseline.describeTilt(-7.04))
        assertEquals("upright within 1.0°", Baseline.describeTilt(0.4))
    }

    @Test
    fun `REQ-SES-040 auto-start delay and baseline capture length are range checked`() {
        assertTrue(AppConfig().validate().isEmpty())
        assertEquals(15, AppConfig().session.autoStartDelayS)
        assertEquals(60, AppConfig().session.baselineCaptureS)
        assertTrue(AppConfig(session = SessionConfig(autoStartDelayS = 61)).validate().any { "auto_start_delay_s" in it })
        assertTrue(AppConfig(session = SessionConfig(autoStartDelayS = 0)).validate().isEmpty())
        assertTrue(AppConfig(session = SessionConfig(baselineCaptureS = 10)).validate().any { "baseline_capture_s" in it })
    }
}
