package com.lateropulsion.core.model

import com.lateropulsion.core.common.EngineVersions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GainScheduleTest {
    private fun summary(tib5: Double, lowConfidence: Boolean = false) = SessionSummary(
        sessionId = SessionId("s"), baselineId = null, baselineMadDeg = null,
        metrics = DeviationMetrics.EMPTY.copy(tib5Pct = tib5, validSamples = 100, totalSamples = 100),
        episodes = EpisodeStats.NONE, deltaDeg = null, improvementPct = null, withinMdc = null, mdcDeg = null,
        lowConfidence = lowConfidence, comparisonRefusedReason = null, balanceLossEvents = 0, assistanceLevel = null,
        gainUsed = 0.6, visualMode = VisualMode.COMPENSATED_VIEW, endReason = EndReason.COMPLETED,
        generatedByVersion = EngineVersions.METRICS_ENGINE, generatedAt = 0L,
    )

    @Test
    fun `REQ-SES-011 linear schedule steps down to floor`() {
        val s = GainSchedule.Linear(step = 0.25, floor = 0.1)
        assertEquals(0.35, s.nextGain(emptyList(), 0.6), 1e-12)
        assertEquals(0.1, s.nextGain(emptyList(), 0.2), 1e-12)
        assertTrue(s.isFading)
    }

    @Test
    fun `REQ-SES-012 performance-driven only fades after consecutive gated sessions`() {
        val s = GainSchedule.PerformanceDriven(step = 0.1, gateTib5 = 60.0, consecutiveSessions = 2)
        assertEquals(0.6, s.nextGain(listOf(summary(70.0)), 0.6), 1e-12)
        assertEquals(0.6, s.nextGain(listOf(summary(70.0), summary(50.0)), 0.6), 1e-12)
        assertEquals(0.5, s.nextGain(listOf(summary(50.0), summary(70.0), summary(65.0)), 0.6), 1e-12)
    }

    @Test
    fun `REQ-SES-012 low-confidence sessions never earn a fade`() {
        val s = GainSchedule.PerformanceDriven(step = 0.1, gateTib5 = 60.0, consecutiveSessions = 2)
        assertEquals(0.6, s.nextGain(listOf(summary(90.0), summary(90.0, lowConfidence = true)), 0.6), 1e-12)
    }

    @Test
    fun `manual schedule is not fading and keeps current`() {
        assertFalse(GainSchedule.Manual.isFading)
        assertEquals(0.3, GainSchedule.Manual.nextGain(emptyList(), 0.3))
    }

    @Test
    fun `schedules round-trip through JSON with a type discriminator`() {
        val json = LpJson.strict.encodeToString(GainSchedule.serializer(), GainSchedule.PerformanceDriven(gateTib5 = 55.0))
        assertTrue(json.contains("\"type\": \"PERFORMANCE_DRIVEN\""), json)
        assertTrue(json.contains("\"gate_tib5\": 55.0"), json)
        val back = LpJson.strict.decodeFromString(GainSchedule.serializer(), json)
        assertEquals(GainSchedule.PerformanceDriven(gateTib5 = 55.0), back)
        assertEquals(GainSchedule.Manual, LpJson.strict.decodeFromString(GainSchedule.serializer(), """{"type":"MANUAL"}"""))
    }
}
