package com.lateropulsion.feature.protocol

import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.MetricNames
import com.lateropulsion.core.model.ProgressionGate
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.VisualMode

/** One past session as the gate and gain logic need to see it (chronological lists, oldest first). */
public data class SessionHistoryEntry(
    val protocolId: String,
    val visualMode: VisualMode,
    val position: BodyPosition,
    val gainUsed: Double,
    val summary: SessionSummary,
    val ssqPostFlagged: Boolean,
    /** Picture tilt applied in that session (ADR-022). */
    val appliedTiltDeg: Double = 0.0,
)

public data class GateResult(val passed: Boolean, val failures: List<String>) {
    public companion object {
        public val PASSED: GateResult = GateResult(true, emptyList())
    }
}

/** Sitting → standing → walking enforcement (ARCHITECTURE §9.3, REQ-SAF-003). */
public object ProgressionGateEvaluator {
    /**
     * @param history sessions run with the protocol that owns [gate], oldest first.
     */
    public fun evaluate(gate: ProgressionGate, history: List<SessionHistoryEntry>): GateResult {
        val failures = ArrayList<String>()
        for (req in gate.requires) {
            val needed = req.consecutiveSessions
            if (history.size < needed) {
                failures += "${req.metric}: need $needed completed session(s), have ${history.size}"
                continue
            }
            val recent = history.takeLast(needed)
            for ((i, entry) in recent.withIndex()) {
                val s = entry.summary
                if (s.lowConfidence) { failures += "${req.metric}: session ${history.size - needed + i + 1} is low confidence"; continue }
                val v = MetricNames.of(s, req.metric)
                if (v == null || v.isNaN()) { failures += "${req.metric}: not available in session ${history.size - needed + i + 1}"; continue }
                if (!req.holds(v)) failures += "${req.metric}=${"%.1f".format(v)} fails ${describe(req)} in session ${history.size - needed + i + 1}"
            }
        }
        return if (failures.isEmpty()) GateResult.PASSED else GateResult(false, failures)
    }

    private fun describe(req: com.lateropulsion.core.model.GateRequirement): String = when {
        req.gte != null -> ">= ${req.gte}"
        req.lte != null -> "<= ${req.lte}"
        req.eq != null -> "== ${req.eq}"
        else -> "?"
    }
}
