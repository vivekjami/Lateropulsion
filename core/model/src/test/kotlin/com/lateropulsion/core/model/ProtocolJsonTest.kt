package com.lateropulsion.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolJsonTest {
    private val sample = """
        {
          "protocol_id": "std-sitting-v3",
          "version": 3,
          "name": "Standard sitting progression",
          "position_required": "SITTING_UNSUPPORTED",
          "visual_mode": "VERTICAL_REFERENCE",
          "gain_schedule": { "type": "PERFORMANCE_DRIVEN", "step": 0.1, "gate_tib5": 60.0 },
          "blocks": [
            {
              "block_id": "warmup",
              "exercise": "MIDLINE_TRAINING",
              "duration_s": 120,
              "target_deg": 0.0,
              "tolerance_deg": 10.0,
              "cues": ["PLUMB_LINE", "TOLERANCE_BAND", "AUDIO_PAN"],
              "checkpoints_s": [30, 60, 90, 120],
              "rest_after_s": 60
            },
            {
              "block_id": "hold-1",
              "exercise": "SITTING_HOLD",
              "duration_s": 180,
              "target_deg": 0.0,
              "tolerance_deg": 5.0,
              "cues": ["PLUMB_LINE", "HORIZON", "HAPTIC"],
              "checkpoints_s": [60, 120, 180],
              "abort_if": { "metric": "episodes", "gt": 12 }
            }
          ],
          "progression_gate": {
            "unlocks": "std-standing-v3",
            "requires": [
              { "metric": "TIB_5", "gte": 60.0, "consecutive_sessions": 2 },
              { "metric": "balance_loss_events", "eq": 0 }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `REQ-SES-001 parses the architecture example protocol`() {
        val p = LpJson.strict.decodeFromString(ProtocolSpec.serializer(), sample)
        assertEquals("std-sitting-v3", p.protocolId)
        assertEquals(2, p.blocks.size)
        assertEquals(GainSchedule.PerformanceDriven(step = 0.1, gateTib5 = 60.0), p.gainSchedule)
        assertEquals(listOf(CueType.PLUMB_LINE, CueType.TOLERANCE_BAND, CueType.AUDIO_PAN), p.blocks[0].cues)
        assertTrue(p.blocks[1].abortIf!!.holds(13.0))
        assertEquals(360, p.totalPlannedS)
        assertEquals("std-standing-v3", p.progressionGate!!.unlocks)
    }

    @Test
    fun `unknown keys are rejected in strict mode`() {
        val broken = sample.replace("\"tolerance_deg\": 10.0", "\"tolerance_deg\": 10.0, \"tolarance\": 3")
        assertThrows(Exception::class.java) { LpJson.strict.decodeFromString(ProtocolSpec.serializer(), broken) }
    }

    @Test
    fun `round trip preserves the spec`() {
        val p = LpJson.strict.decodeFromString(ProtocolSpec.serializer(), sample)
        val again = LpJson.strict.decodeFromString(ProtocolSpec.serializer(), LpJson.strict.encodeToString(ProtocolSpec.serializer(), p))
        assertEquals(p, again)
    }
}
