package com.lateropulsion.app.session

import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.feature.protocol.SessionHistoryEntry

object HistoryMapper {
    fun entries(sessions: List<Session>, summaries: List<SessionSummary>): List<SessionHistoryEntry> {
        val byId = summaries.associateBy { it.sessionId }
        return sessions.filter { it.isFinished }.sortedBy { it.sessionNumber }.mapNotNull { s ->
            val m = byId[s.id] ?: return@mapNotNull null
            SessionHistoryEntry(s.protocolId, s.visualMode, s.position, s.gainUsed, m, s.ssqPost?.flagged == true, s.appliedTiltDeg)
        }
    }
}
