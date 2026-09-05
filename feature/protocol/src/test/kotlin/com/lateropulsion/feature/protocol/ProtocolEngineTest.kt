package com.lateropulsion.feature.protocol

import com.lateropulsion.core.model.BlockEndReason
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.SessionConfig
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.SessionEventType
import com.lateropulsion.core.model.VisualMode
import com.lateropulsion.feature.protocol.Fixtures.S
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolEngineTest {
    private fun started(engine: ProtocolEngine = ProtocolEngine(Fixtures.spec()), t0: Long = 0L): ProtocolEngine {
        engine.handle(SessionInput.ChecklistComplete, t0)
        engine.handle(SessionInput.CalibrationSucceeded, t0)
        engine.handle(SessionInput.Start, t0)
        return engine
    }

    @Test
    fun `REQ-SES-020 happy path runs every state to Saved`() {
        val e = started()
        assertInstanceOf(SessionState.BlockRunning::class.java, e.state)
        var fx = e.handle(SessionInput.Tick, 20 * S)
        assertTrue(fx.any { it is Effect.Checkpoint && it.atS == 20 })
        fx = e.handle(SessionInput.Tick, 60 * S)
        assertTrue(fx.any { it is Effect.EndBlock && it.reason == BlockEndReason.COMPLETED })
        assertTrue(fx.any { it is Effect.StartRest && it.durationS == 30 })
        assertInstanceOf(SessionState.Resting::class.java, e.state)
        // rest not complete yet → refused
        fx = e.handle(SessionInput.NextBlock, 70 * S)
        assertTrue(fx.any { it is Effect.Rejected })
        assertInstanceOf(SessionState.Resting::class.java, e.state)
        fx = e.handle(SessionInput.Tick, 90 * S)
        assertTrue(fx.contains(Effect.RestComplete))
        fx = e.handle(SessionInput.NextBlock, 91 * S)
        assertTrue(fx.any { it is Effect.StartBlock && it.index == 1 })
        fx = e.handle(SessionInput.Tick, 91 * S + 90 * S)
        assertTrue(fx.contains(Effect.Summarize))
        assertEquals(SessionState.Summarizing, e.state)
        assertEquals(EndReason.COMPLETED, e.endReason)
        fx = e.handle(SessionInput.Confirm, 200 * S)
        assertTrue(fx.contains(Effect.Save))
        assertEquals(SessionState.Saved, e.state)
        assertTrue(e.state.isTerminal)
    }

    @Test
    fun `checkpoints are emitted once each and never after the block ends`() {
        val e = started()
        val all = mutableListOf<Effect>()
        for (t in 0..65) all += e.handle(SessionInput.Tick, t * S)
        val cps = all.filterIsInstance<Effect.Checkpoint>().map { it.atS }
        assertEquals(listOf(20, 40, 60), cps)
    }

    @Test
    fun `REQ-SES-022 pause excludes paused time from block elapsed`() {
        val e = started()
        e.handle(SessionInput.Pause, 30 * S)
        assertInstanceOf(SessionState.Paused::class.java, e.state)
        e.handle(SessionInput.Resume, 50 * S) // 20 s paused
        var fx = e.handle(SessionInput.Tick, 60 * S) // only 40 s of exercise elapsed
        assertFalse(fx.any { it is Effect.EndBlock })
        fx = e.handle(SessionInput.Tick, 80 * S)
        assertTrue(fx.any { it is Effect.EndBlock })
    }

    @Test
    fun `REQ-SAF-004 abort from block emits RenderNeutral first and saves an aborted session`() {
        val e = started()
        val fx = e.handle(SessionInput.Abort(AbortReason(AbortSource.CLICKER)), 10 * S)
        assertEquals(Effect.RenderNeutral, fx.first())
        assertTrue(fx.any { it is Effect.EndBlock && it.reason == BlockEndReason.ABORTED })
        assertInstanceOf(SessionState.Aborted::class.java, e.state)
        e.handle(SessionInput.Proceed, 11 * S)
        assertEquals(SessionState.Summarizing, e.state)
        assertEquals(EndReason.ABORTED, e.endReason)
        e.handle(SessionInput.Confirm, 12 * S)
        assertEquals(SessionState.Saved, e.state) // aborted sessions are always saved (ADR-009)
    }

    @Test
    fun `REQ-SAF-004 abort is accepted from every active state`() {
        for (stage in listOf("ready", "block", "paused", "resting")) {
            val e = ProtocolEngine(Fixtures.spec())
            e.handle(SessionInput.ChecklistComplete, 0); e.handle(SessionInput.CalibrationSucceeded, 0)
            if (stage != "ready") e.handle(SessionInput.Start, 0)
            if (stage == "paused") e.handle(SessionInput.Pause, S)
            if (stage == "resting") e.handle(SessionInput.Tick, 60 * S)
            val fx = e.handle(SessionInput.Abort(AbortReason(AbortSource.WATCHDOG, "m2p")), 61 * S)
            assertEquals(Effect.RenderNeutral, fx.first(), stage)
            assertInstanceOf(SessionState.Aborted::class.java, e.state, stage)
        }
    }

    @Test
    fun `REQ-SES-023 stop rule from live metrics aborts the block`() {
        val e = started()
        e.handle(SessionInput.Tick, 60 * S); e.handle(SessionInput.Tick, 90 * S); e.handle(SessionInput.NextBlock, 91 * S)
        var fx = e.handle(SessionInput.LiveMetrics(1, mapOf("episodes" to 3.0)), 100 * S)
        assertFalse(fx.any { it is Effect.Abort })
        fx = e.handle(SessionInput.LiveMetrics(1, mapOf("episodes" to 4.0)), 101 * S)
        assertTrue(fx.any { it is Effect.Abort && it.reason.source == AbortSource.STOP_RULE })
        assertTrue(fx.any { it is Effect.EndBlock && it.reason == BlockEndReason.STOP_RULE })
        assertTrue(fx.any { it is Effect.Event && it.type == SessionEventType.STOP_RULE })
    }

    @Test
    fun `REQ-SAF-005 hard session cap aborts even while resting or paused`() {
        val cfg = AppConfig(session = SessionConfig(maxDurationMin = 2))
        val e = started(ProtocolEngine(Fixtures.spec(config = cfg)))
        e.handle(SessionInput.Tick, 60 * S) // → resting
        val fx = e.handle(SessionInput.Tick, 121 * S)
        assertTrue(fx.any { it is Effect.Abort && it.reason.source == AbortSource.SESSION_CAP })

        val e2 = started(ProtocolEngine(Fixtures.spec(config = cfg)))
        e2.handle(SessionInput.Pause, 10 * S)
        val fx2 = e2.handle(SessionInput.Tick, 121 * S)
        assertTrue(fx2.any { it is Effect.Abort && it.reason.source == AbortSource.SESSION_CAP })
    }

    @Test
    fun `REQ-SAF-006 mandatory rest is enforced after five minutes of exercise`() {
        val p = Fixtures.sittingProtocol.copy(
            blocks = listOf(
                Fixtures.sittingProtocol.blocks[0].copy(durationS = 300, restAfterS = 10, checkpointsS = emptyList()),
                Fixtures.sittingProtocol.blocks[1].copy(durationS = 60, checkpointsS = emptyList()),
            ),
        )
        val e = started(ProtocolEngine(Fixtures.spec(protocol = p)))
        val fx = e.handle(SessionInput.Tick, 300 * S)
        val rest = fx.filterIsInstance<Effect.StartRest>().single()
        assertTrue(rest.mandatory)
        assertEquals(60, rest.durationS) // max(10, mandatory 60)
    }

    @Test
    fun `therapist stop during rest ends early and is summarised as completed`() {
        val e = started()
        e.handle(SessionInput.Tick, 60 * S)
        val fx = e.handle(SessionInput.Stop, 70 * S)
        assertTrue(fx.contains(Effect.Summarize))
        assertTrue(e.endedEarly)
        assertEquals(EndReason.COMPLETED, e.endReason)
    }

    @Test
    fun `calibration failure and cancellation are terminal`() {
        val e = ProtocolEngine(Fixtures.spec())
        e.handle(SessionInput.ChecklistComplete, 0)
        e.handle(SessionInput.CalibrationFailed("drift"), 0)
        assertInstanceOf(SessionState.Failed::class.java, e.state)
        assertTrue(e.handle(SessionInput.Start, 0).any { it is Effect.Rejected })
        val c = ProtocolEngine(Fixtures.spec())
        c.handle(SessionInput.Cancel, 0)
        assertEquals(SessionState.Cancelled, c.state)
    }

    @Test
    fun `illegal inputs are rejected without changing state`() {
        val e = ProtocolEngine(Fixtures.spec())
        val fx = e.handle(SessionInput.Start, 0)
        assertTrue(fx.single() is Effect.Rejected)
        assertEquals(SessionState.PreCheck, e.state)
        assertTrue(e.history.isEmpty())
    }

    @Test
    fun `REQ-SES-013 Mode A forces gain zero even if the protocol carries gains`() {
        val p = Fixtures.modeBProtocol.copy(visualMode = VisualMode.VERTICAL_REFERENCE, blocks = listOf(Fixtures.modeBProtocol.blocks[0].copy(gain = 0.5)))
        val e = started(ProtocolEngine(Fixtures.spec(protocol = p, mode = VisualMode.VERTICAL_REFERENCE)))
        assertEquals(0.0, e.gainForBlock(0))
        val eB = started(ProtocolEngine(Fixtures.spec(protocol = Fixtures.modeBProtocol.copy(blocks = listOf(Fixtures.modeBProtocol.blocks[0].copy(gain = 0.3))), mode = VisualMode.COMPENSATED_VIEW, gain = 0.4)))
        assertEquals(0.3, eB.gainForBlock(0))
    }

    @Test
    fun `therapist marks and balance losses are counted in any state`() {
        val e = started()
        e.handle(SessionInput.TherapistMark, S)
        e.handle(SessionInput.BalanceLoss, 2 * S)
        e.handle(SessionInput.BalanceLoss, 3 * S)
        assertEquals(1, e.therapistMarks)
        assertEquals(2, e.balanceLossEvents)
    }

    @Test
    fun `state transition history covers every state in the diagram`() {
        val e = started()
        e.handle(SessionInput.Pause, S); e.handle(SessionInput.Resume, 2 * S)
        e.handle(SessionInput.Tick, 62 * S); e.handle(SessionInput.Tick, 100 * S); e.handle(SessionInput.NextBlock, 100 * S)
        e.handle(SessionInput.Abort(AbortReason(AbortSource.THERMAL)), 110 * S)
        e.handle(SessionInput.Proceed, 111 * S); e.handle(SessionInput.Confirm, 112 * S)
        val visited = e.history.map { it.second::class.simpleName }.toSet() + "PreCheck"
        assertTrue(visited.containsAll(listOf("PreCheck", "Calibrating", "Ready", "BlockRunning", "Paused", "Resting", "Aborted", "Summarizing", "Saved")), visited.toString())
    }
}
