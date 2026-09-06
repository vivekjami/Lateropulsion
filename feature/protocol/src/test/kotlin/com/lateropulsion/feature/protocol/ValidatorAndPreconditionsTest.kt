package com.lateropulsion.feature.protocol

import com.lateropulsion.core.model.BlockSpec
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.ExerciseType
import com.lateropulsion.core.model.GainSchedule
import com.lateropulsion.core.model.VisualMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidatorAndPreconditionsTest {
    @Test
    fun `REQ-SES-002 shipped protocol library validates against the shipped app config`() {
        val protocols = Fixtures.loadProtocols()
        assertTrue(protocols.size >= 6)
        val cfg = Fixtures.loadAppConfig()
        assertTrue(cfg.validate().isEmpty(), cfg.validate().toString())
        val issues = ProtocolValidator.validateLibrary(protocols, cfg).filter { it.blocking }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
        assertTrue(protocols.any { it.protocolId == "std-sitting-v3" })
        assertTrue(protocols.first { it.protocolId == "std-standing-v3" }.requiresGateFrom == "std-sitting-v3")
        assertTrue(protocols.first { it.protocolId == "walking-to-target-v1" }.requiresGateFrom == "std-standing-v3")
    }

    @Test
    fun `REQ-SAF-003 standing protocol without a gate source is rejected`() {
        val bad = Fixtures.standingProtocol.copy(requiresGateFrom = null)
        assertTrue(ProtocolValidator.validate(bad).any { it.path == "requires_gate_from" })
    }

    @Test
    fun `REQ-SES-010 Mode B protocol with a manual schedule is rejected`() {
        val bad = Fixtures.modeBProtocol.copy(gainSchedule = GainSchedule.Manual)
        assertTrue(ProtocolValidator.validate(bad).any { it.path == "gain_schedule" && it.blocking })
    }

    @Test
    fun `block-level rules`() {
        val p = Fixtures.sittingProtocol.copy(
            blocks = listOf(
                BlockSpec("dup", ExerciseType.MIDLINE_TRAINING, 60, toleranceDeg = 5.0, cues = listOf(CueType.PLUMB_LINE, CueType.PLUMB_LINE), checkpointsS = listOf(70)),
                BlockSpec("dup", ExerciseType.STANDING_HOLD, 400, toleranceDeg = 0.0, cues = emptyList()),
            ),
        )
        val msgs = ProtocolValidator.validate(p).map { it.message }
        assertTrue(msgs.any { it.contains("duplicate block_id") })
        assertTrue(msgs.any { it.contains("duplicate cues") })
        assertTrue(msgs.any { it.contains("checkpoints_s must lie") })
        assertTrue(msgs.any { it.contains("mandatory rest") })
        assertTrue(msgs.any { it.contains("tolerance_deg") })
        assertTrue(msgs.any { it.contains("standing hold requires") })
    }

    @Test
    fun `loader rejects malformed JSON and semantic errors`() {
        assertTrue(ProtocolLoader.parse("{\"protocol_id\": 1}").isFailure)
        val json = """{"protocol_id":"x-y","version":1,"name":"n","position_required":"STANDING","visual_mode":"VERTICAL_REFERENCE",
            "gain_schedule":{"type":"MANUAL"},"blocks":[{"block_id":"a","exercise":"STANDING_HOLD","duration_s":60,"tolerance_deg":5.0,"cues":[]}]}"""
        val r = ProtocolLoader.parse(json)
        assertTrue(r.isFailure)
        assertTrue(r.errorOrNull()!!.message.contains("REQ-SAF-003"))
    }

    @Test
    fun `REQ-SAF-003 gate evaluator requires consecutive confident sessions`() {
        val gate = Fixtures.sittingProtocol.progressionGate!!
        assertFalse(ProgressionGateEvaluator.evaluate(gate, listOf(Fixtures.entry("test-sitting", 70.0))).passed)
        assertFalse(ProgressionGateEvaluator.evaluate(gate, listOf(Fixtures.entry("test-sitting", 70.0), Fixtures.entry("test-sitting", 55.0))).passed)
        assertFalse(ProgressionGateEvaluator.evaluate(gate, listOf(Fixtures.entry("test-sitting", 70.0), Fixtures.entry("test-sitting", 70.0, lowConfidence = true))).passed)
        assertFalse(ProgressionGateEvaluator.evaluate(gate, listOf(Fixtures.entry("test-sitting", 70.0), Fixtures.entry("test-sitting", 70.0, balanceLoss = 1))).passed)
        assertTrue(ProgressionGateEvaluator.evaluate(gate, listOf(Fixtures.entry("test-sitting", 50.0), Fixtures.entry("test-sitting", 70.0), Fixtures.entry("test-sitting", 65.0))).passed)
    }

    private val library = mapOf(Fixtures.sittingProtocol.protocolId to Fixtures.sittingProtocol, Fixtures.standingProtocol.protocolId to Fixtures.standingProtocol, Fixtures.modeBProtocol.protocolId to Fixtures.modeBProtocol)

    private fun ctx(spec: com.lateropulsion.core.model.SessionSpec, history: List<SessionHistoryEntry> = emptyList(), checklist: com.lateropulsion.core.model.PreSessionChecklist = Fixtures.fullChecklist, research: Boolean = false, patient: com.lateropulsion.core.model.Patient = Fixtures.patient) =
        PreconditionContext(spec, patient, checklist, history, library, research)

    @Test
    fun `REQ-SAF-001 incomplete checklist blocks`() {
        val r = SessionPreconditions.check(ctx(Fixtures.spec(), checklist = Fixtures.fullChecklist.copy(supervisionAttested = false)))
        assertFalse(r.canStart)
        assertTrue(r.blocking.single().contains("supervision"))
    }

    @Test
    fun `REQ-SAF-003 standing blocked until the sitting gate passes, override is recorded, walking cannot be overridden`() {
        val standing = Fixtures.spec(protocol = Fixtures.standingProtocol)
        assertFalse(SessionPreconditions.check(ctx(standing)).canStart)
        val passed = listOf(Fixtures.entry("test-sitting", 70.0), Fixtures.entry("test-sitting", 70.0))
        assertTrue(SessionPreconditions.check(ctx(standing, passed)).canStart)
        val overridden = SessionPreconditions.check(ctx(Fixtures.spec(protocol = Fixtures.standingProtocol, override = "clinical judgement: stable in bars")))
        assertTrue(overridden.canStart)
        assertEquals(1, overridden.overridden.size)

        val walking = Fixtures.standingProtocol.copy(protocolId = "test-walking", positionRequired = BodyPosition.WALKING, requiresGateFrom = "test-sitting",
            blocks = listOf(BlockSpec("w", ExerciseType.WALKING_TO_TARGET, 60, toleranceDeg = 10.0, cues = emptyList())))
        val w = SessionPreconditions.check(ctx(Fixtures.spec(protocol = walking, override = "trying anyway")))
        assertFalse(w.canStart)
        assertTrue(w.blocking.any { it.contains("Walking cannot be unlocked by override") })
    }

    @Test
    fun `REQ-SES-010 Mode B needs a fading schedule, first exposures are capped, SSQ locks to Mode A`() {
        val manual = Fixtures.spec(protocol = Fixtures.modeBProtocol.copy(gainSchedule = GainSchedule.Manual), mode = VisualMode.COMPENSATED_VIEW, gain = 0.4)
        assertTrue(SessionPreconditions.check(ctx(manual)).blocking.any { it.contains("fading") })

        val tooHigh = Fixtures.spec(protocol = Fixtures.modeBProtocol, mode = VisualMode.COMPENSATED_VIEW, gain = 0.6)
        assertTrue(SessionPreconditions.check(ctx(tooHigh)).blocking.any { it.contains("First Mode B") })

        val ok = Fixtures.spec(protocol = Fixtures.modeBProtocol, mode = VisualMode.COMPENSATED_VIEW, gain = 0.4)
        assertTrue(SessionPreconditions.check(ctx(ok)).canStart)

        val flagged = listOf(Fixtures.entry("test-mode-b", 50.0, VisualMode.COMPENSATED_VIEW, 0.4, ssq = true), Fixtures.entry("test-mode-b", 50.0, VisualMode.COMPENSATED_VIEW, 0.4, ssq = true))
        assertTrue(SessionPreconditions.check(ctx(ok, flagged)).blocking.any { it.contains("locked to Mode A") })

        val negative = Fixtures.spec(protocol = Fixtures.modeBProtocol, mode = VisualMode.COMPENSATED_VIEW, gain = -0.2)
        assertTrue(SessionPreconditions.check(ctx(negative)).blocking.any { it.contains("Negative gain") })
    }

    @Test
    fun `REQ-SES-012 gain increase of more than one step needs a note`() {
        val history = listOf(Fixtures.entry("test-mode-b", 50.0, VisualMode.COMPENSATED_VIEW, 0.1), Fixtures.entry("test-mode-b", 50.0, VisualMode.COMPENSATED_VIEW, 0.1))
        val jump = Fixtures.spec(protocol = Fixtures.modeBProtocol, mode = VisualMode.COMPENSATED_VIEW, gain = 0.4)
        assertTrue(SessionPreconditions.check(ctx(jump, history)).blocking.any { it.contains("note is required") })
        val withNote = Fixtures.spec(protocol = Fixtures.modeBProtocol, mode = VisualMode.COMPENSATED_VIEW, gain = 0.4, override = "regressed after infection; restarting fade")
        assertTrue(SessionPreconditions.check(ctx(withNote, history)).canStart)
        assertEquals(0.1, GainScheduler.proposeNext(Fixtures.modeBProtocol, history, 0.4), 1e-9)
        val good = history + listOf(Fixtures.entry("test-mode-b", 70.0, VisualMode.COMPENSATED_VIEW, 0.3), Fixtures.entry("test-mode-b", 70.0, VisualMode.COMPENSATED_VIEW, 0.3))
        assertEquals(0.2, GainScheduler.proposeNext(Fixtures.modeBProtocol, good, 0.4), 1e-9)
    }

    @Test
    fun `REQ-SAF-020 unqualified device blocks unless research mode, which warns`() {
        val unq = Fixtures.spec(device = Fixtures.device.copy(qualified = false))
        assertFalse(SessionPreconditions.check(ctx(unq)).canStart)
        val r = SessionPreconditions.check(ctx(unq, research = true))
        assertTrue(r.canStart)
        assertTrue(r.warnings.any { it.contains("UNQUALIFIED") })
        val unresolved = Fixtures.spec(device = Fixtures.device.copy(rollSign = 0))
        assertTrue(SessionPreconditions.check(ctx(unresolved)).blocking.any { it.contains("Roll sign") })
        val field = Fixtures.spec(device = Fixtures.device.copy(qualified = false, fieldQualified = true))
        val fr = SessionPreconditions.check(ctx(field))
        assertTrue(fr.canStart)
        assertTrue(fr.warnings.any { it.contains("field-calibrated") })
    }
}
