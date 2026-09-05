package com.lateropulsion.app

import com.lateropulsion.app.session.HistoryMapper
import com.lateropulsion.core.common.EngineVersions
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.DeviationMetrics
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.EpisodeStats
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionId
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.SsqScore
import com.lateropulsion.core.model.VisualMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryMapperTest {
    private fun session(n: Int, finished: Boolean, ssqFlag: Boolean = false) = Session(
        SessionId("s$n"), PatientId("p"), ClinicianId("c"), "std-sitting-v3", 3, n, VisualMode.COMPENSATED_VIEW, 0.4, 0.0, null, "d", "h",
        BodyPosition.SITTING_UNSUPPORTED, n * 1000L, 0L, "UTC", endReason = if (finished) EndReason.COMPLETED else null,
        ssqPost = if (ssqFlag) SsqScore(10.0, 10.0, 10.0, 30.0, true) else null, appVersion = "t",
    )
    private fun summary(n: Int) = SessionSummary(SessionId("s$n"), null, null, DeviationMetrics.EMPTY.copy(tib5Pct = 50.0 + n), EpisodeStats.NONE, null, null, null, 2.0, false, null, 0, AssistanceLevel.SUPERVISION, 0.4, VisualMode.COMPENSATED_VIEW, EndReason.COMPLETED, EngineVersions.METRICS_ENGINE, 0)

    @Test
    fun `only finished sessions with summaries become history, in session order, carrying SSQ flags`() {
        val entries = HistoryMapper.entries(listOf(session(3, true, ssqFlag = true), session(1, true), session(2, false)), listOf(summary(1), summary(3)))
        assertEquals(listOf(1, 3), entries.map { it.summary.metrics.tib5Pct.toInt() - 50 })
        assertTrue(entries[1].ssqPostFlagged)
        assertEquals(0.4, entries[0].gainUsed)
        assertEquals("std-sitting-v3", entries[0].protocolId)
    }
}
