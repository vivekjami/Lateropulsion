package com.lateropulsion.feature.protocol

import com.lateropulsion.core.common.EngineVersions
import com.lateropulsion.core.common.TimeUnits
import com.lateropulsion.core.model.BlockEndReason
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.SessionEventType
import com.lateropulsion.core.model.SessionSpec
import com.lateropulsion.core.model.VisualMode
import kotlin.math.max

/**
 * Deterministic session state machine (ARCHITECTURE §9.2). No clocks, no threads: every call passes
 * `nowNs`, so the engine is exhaustively unit-testable with a simulated clock (REQ-SES-020).
 *
 * The physical abort path (render → neutral passthrough) bypasses this engine entirely
 * (ARCHITECTURE §1 driver 1); the engine only records aborts and drives the session bookkeeping.
 */
public class ProtocolEngine(public val spec: SessionSpec) {
    public var state: SessionState = SessionState.PreCheck
        private set

    public var sessionStartNs: Long = 0L
        private set
    public var abortReason: AbortReason? = null
        private set
    public var endedEarly: Boolean = false
        private set
    public var balanceLossEvents: Int = 0
        private set
    public var therapistMarks: Int = 0
        private set

    private var exerciseSinceRestNs = 0L
    private var completedBlocks = 0
    public val history: MutableList<Pair<SessionState, SessionState>> = mutableListOf()

    public val version: String get() = EngineVersions.PROTOCOL_ENGINE
    private val protocol get() = spec.protocol
    private val config get() = spec.config

    /** Hard cap on headset time from Start (REQ-SAF-005). */
    public val sessionCapNs: Long
        get() {
            val configured = config.session.maxDurationMin * 60L
            val fromProtocol = protocol.maxSessionS?.toLong() ?: Long.MAX_VALUE
            return TimeUnits.secondsToNanos(minOf(configured, fromProtocol).toDouble())
        }

    public val endReason: EndReason
        get() = if (abortReason != null) EndReason.ABORTED else EndReason.COMPLETED

    public fun handle(input: SessionInput, nowNs: Long): List<Effect> {
        val effects = ArrayList<Effect>(4)
        val before = state
        when (input) {
            SessionInput.TherapistMark -> { therapistMarks++; effects += Effect.Event(SessionEventType.THERAPIST_MARK) }
            SessionInput.BalanceLoss -> { balanceLossEvents++; effects += Effect.Event(SessionEventType.BALANCE_LOSS) }
            else -> dispatch(input, nowNs, effects)
        }
        if (state != before) history += before to state
        return effects
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun dispatch(input: SessionInput, nowNs: Long, out: MutableList<Effect>) {
        val s = state
        when (s) {
            SessionState.PreCheck -> when (input) {
                SessionInput.ChecklistComplete -> { state = SessionState.Calibrating; out += Effect.Event(SessionEventType.CALIBRATION, """{"phase":"start"}""") }
                SessionInput.Cancel -> state = SessionState.Cancelled
                else -> out += reject(input, "not started")
            }
            SessionState.Calibrating -> when (input) {
                SessionInput.CalibrationSucceeded -> { state = SessionState.Ready; out += Effect.Event(SessionEventType.CALIBRATION, """{"phase":"ok"}""") }
                is SessionInput.CalibrationFailed -> { state = SessionState.Failed(input.reason); out += Effect.Alert("Calibration failed: ${input.reason}") }
                SessionInput.Cancel -> state = SessionState.Cancelled
                else -> out += reject(input, "calibrating")
            }
            SessionState.Ready -> when (input) {
                SessionInput.Start -> {
                    sessionStartNs = nowNs
                    out += Effect.Event(SessionEventType.SESSION_START, """{"protocol":"${protocol.protocolId}","version":${protocol.version},"mode":"${spec.visualMode}","gain":${spec.gain}}""")
                    startBlock(0, nowNs, out)
                }
                is SessionInput.Abort -> abort(input.reason, nowNs, out)
                SessionInput.Cancel -> state = SessionState.Cancelled
                else -> out += reject(input, "ready")
            }
            is SessionState.BlockRunning -> when (input) {
                SessionInput.Tick -> tickBlock(s, nowNs, out)
                SessionInput.Pause -> {
                    state = SessionState.Paused(s, nowNs)
                    out += Effect.RenderNeutral
                    out += Effect.Event(SessionEventType.PAUSE)
                }
                is SessionInput.Abort -> abort(input.reason, nowNs, out)
                is SessionInput.LiveMetrics -> checkStopRule(s, input, nowNs, out)
                SessionInput.Stop -> abort(AbortReason(AbortSource.THERAPIST_CONTROL, "stop during block"), nowNs, out)
                else -> out += reject(input, "block running")
            }
            is SessionState.Paused -> when (input) {
                SessionInput.Resume -> {
                    val extra = nowNs - s.pausedAtNs
                    state = s.block.copy(pausedNs = s.block.pausedNs + extra)
                    out += Effect.Event(SessionEventType.RESUME)
                    out += Effect.SetGain(gainForBlock(s.block.blockIndex))
                }
                SessionInput.Stop -> abort(AbortReason(AbortSource.THERAPIST_CONTROL, "stop while paused"), nowNs, out)
                is SessionInput.Abort -> abort(input.reason, nowNs, out)
                SessionInput.Tick -> if (capExceeded(nowNs)) abort(AbortReason(AbortSource.SESSION_CAP), nowNs, out)
                else -> out += reject(input, "paused")
            }
            is SessionState.Resting -> when (input) {
                SessionInput.Tick -> tickRest(s, nowNs, out)
                SessionInput.NextBlock -> {
                    if (nowNs - s.restStartNs < s.restDurationNs) {
                        out += Effect.Alert("Rest interval not complete")
                        out += reject(input, "rest enforced")
                    } else {
                        if (s.mandatory) exerciseSinceRestNs = 0L
                        out += Effect.Event(SessionEventType.REST_END)
                        startBlock(s.completedBlockIndex + 1, nowNs, out)
                    }
                }
                SessionInput.Stop -> {
                    endedEarly = true
                    out += Effect.Event(SessionEventType.SESSION_END, """{"ended_early":true}""")
                    out += Effect.RenderNeutral
                    state = SessionState.Summarizing
                    out += Effect.Summarize
                }
                is SessionInput.Abort -> abort(input.reason, nowNs, out)
                else -> out += reject(input, "resting")
            }
            is SessionState.Aborted -> when (input) {
                SessionInput.Proceed, SessionInput.Tick -> { state = SessionState.Summarizing; out += Effect.Summarize }
                else -> out += reject(input, "aborted")
            }
            SessionState.Summarizing -> when (input) {
                SessionInput.Confirm -> { state = SessionState.Saved; out += Effect.Save }
                else -> out += reject(input, "summarizing")
            }
            SessionState.Saved, is SessionState.Failed, SessionState.Cancelled -> out += reject(input, "terminal")
        }
    }

    private fun startBlock(index: Int, nowNs: Long, out: MutableList<Effect>) {
        val block = protocol.blocks[index]
        state = SessionState.BlockRunning(index, nowNs, 0L, emptySet())
        val gain = gainForBlock(index)
        out += Effect.SetGain(gain)
        out += Effect.StartBlock(index, block, gain, block.position ?: protocol.positionRequired)
        block.instructionKey?.let { out += Effect.Instruction(it) }
        out += Effect.Event(SessionEventType.BLOCK_START, """{"block":"${block.blockId}","index":$index,"gain":$gain}""")
    }

    /** Mode A always renders truthfully: the gain is forced to zero regardless of protocol values. */
    public fun gainForBlock(index: Int): Double {
        if (spec.visualMode == VisualMode.VERTICAL_REFERENCE) return 0.0
        return protocol.blocks[index].gain ?: spec.gain
    }

    private fun tickBlock(s: SessionState.BlockRunning, nowNs: Long, out: MutableList<Effect>) {
        if (capExceeded(nowNs)) { abort(AbortReason(AbortSource.SESSION_CAP), nowNs, out); return }
        val block = protocol.blocks[s.blockIndex]
        val elapsedS = TimeUnits.nanosToSeconds(nowNs - s.blockStartNs - s.pausedNs)
        var emitted = s.emittedCheckpoints
        for (cp in block.checkpointsS) {
            if (cp !in emitted && elapsedS >= cp) {
                emitted = emitted + cp
                out += Effect.Checkpoint(s.blockIndex, cp)
                out += Effect.Event(SessionEventType.CHECKPOINT, """{"block":"${block.blockId}","at_s":$cp}""")
            }
        }
        if (elapsedS >= block.durationS) {
            completeBlock(s.blockIndex, elapsedS, nowNs, out)
        } else if (emitted !== s.emittedCheckpoints) {
            state = s.copy(emittedCheckpoints = emitted)
        }
    }

    private fun completeBlock(index: Int, elapsedS: Double, nowNs: Long, out: MutableList<Effect>) {
        val block = protocol.blocks[index]
        completedBlocks++
        exerciseSinceRestNs += TimeUnits.secondsToNanos(block.durationS.toDouble())
        out += Effect.EndBlock(index, BlockEndReason.COMPLETED, elapsedS)
        out += Effect.Event(SessionEventType.BLOCK_END, """{"block":"${block.blockId}","index":$index,"reason":"COMPLETED"}""")
        out += Effect.RenderNeutral
        if (index == protocol.blocks.lastIndex) {
            out += Effect.Event(SessionEventType.SESSION_END, """{"ended_early":false}""")
            state = SessionState.Summarizing
            out += Effect.Summarize
            return
        }
        val mandatory = exerciseSinceRestNs >= TimeUnits.secondsToNanos(config.session.mandatoryRestEveryS.toDouble())
        val restS = if (mandatory) max(block.restAfterS, config.session.mandatoryRestS) else block.restAfterS
        state = SessionState.Resting(index, nowNs, TimeUnits.secondsToNanos(restS.toDouble()), mandatory, completeAnnounced = restS == 0)
        out += Effect.StartRest(index, restS, mandatory)
        out += Effect.Event(SessionEventType.REST_START, """{"after_block":"${block.blockId}","duration_s":$restS,"mandatory":$mandatory}""")
        if (restS == 0) out += Effect.RestComplete
    }

    private fun tickRest(s: SessionState.Resting, nowNs: Long, out: MutableList<Effect>) {
        if (capExceeded(nowNs)) { abort(AbortReason(AbortSource.SESSION_CAP), nowNs, out); return }
        if (!s.completeAnnounced && nowNs - s.restStartNs >= s.restDurationNs) {
            state = s.copy(completeAnnounced = true)
            out += Effect.RestComplete
        }
    }

    private fun checkStopRule(s: SessionState.BlockRunning, m: SessionInput.LiveMetrics, nowNs: Long, out: MutableList<Effect>) {
        if (m.blockIndex != s.blockIndex) return
        val cond = protocol.blocks[s.blockIndex].abortIf ?: return
        val value = m.values[cond.metric] ?: return
        if (cond.holds(value)) {
            abort(AbortReason(AbortSource.STOP_RULE, "${cond.metric}=$value in block ${protocol.blocks[s.blockIndex].blockId}"), nowNs, out)
        }
    }

    private fun capExceeded(nowNs: Long): Boolean = nowNs - sessionStartNs >= sessionCapNs

    private fun abort(reason: AbortReason, nowNs: Long, out: MutableList<Effect>) {
        // Neutral first, always, before any bookkeeping.
        out += Effect.RenderNeutral
        val running = when (val s = state) {
            is SessionState.BlockRunning -> s
            is SessionState.Paused -> s.block
            else -> null
        }
        if (running != null) {
            val elapsedS = TimeUnits.nanosToSeconds(nowNs - running.blockStartNs - running.pausedNs)
            val blockReason = if (reason.source == AbortSource.STOP_RULE) BlockEndReason.STOP_RULE else BlockEndReason.ABORTED
            out += Effect.EndBlock(running.blockIndex, blockReason, elapsedS)
            out += Effect.Event(SessionEventType.BLOCK_END, """{"index":${running.blockIndex},"reason":"$blockReason"}""")
        }
        abortReason = reason
        state = SessionState.Aborted(reason)
        out += Effect.Abort(reason)
        out += Effect.Alert("Session aborted: $reason")
        val type = if (reason.source == AbortSource.STOP_RULE) SessionEventType.STOP_RULE else SessionEventType.ABORT
        out += Effect.Event(type, """{"source":"${reason.source}","detail":"${reason.detail.replace('"', '\'')}"}""")
    }

    private fun reject(input: SessionInput, why: String): Effect = Effect.Rejected(input, state, why)
}
