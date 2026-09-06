package com.lateropulsion.app.session

import android.content.Context
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.LpDispatchers
import com.lateropulsion.core.common.LpLog
import com.lateropulsion.core.common.Redaction
import com.lateropulsion.core.common.TimeUnits
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BlockEndReason
import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.BlockSpec
import com.lateropulsion.core.model.CompletedSession
import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.MetricNames
import com.lateropulsion.core.model.PoseSample
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionEvent
import com.lateropulsion.core.model.SessionEventType
import com.lateropulsion.core.model.SessionRepository
import com.lateropulsion.core.model.SessionSpec
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.SsqScore
import com.lateropulsion.core.model.TimeseriesFile
import com.lateropulsion.core.model.ValidityFlags
import com.lateropulsion.core.model.Vec3
import com.lateropulsion.core.timeseries.CloseReason
import com.lateropulsion.core.timeseries.LpxHeader
import com.lateropulsion.core.timeseries.LpxRecord
import com.lateropulsion.core.timeseries.LpxWriter
import com.lateropulsion.engine.render.AbortController
import com.lateropulsion.engine.render.BandEdgeDetector
import com.lateropulsion.engine.render.HapticCue
import com.lateropulsion.engine.render.RenderState
import com.lateropulsion.engine.render.RenderStateHolder
import com.lateropulsion.engine.sensor.PoseProvider
import com.lateropulsion.feature.metrics.CausalFilter
import com.lateropulsion.feature.metrics.EpisodeDetector
import com.lateropulsion.feature.metrics.MetricsAccumulator
import com.lateropulsion.feature.metrics.RawTrace
import com.lateropulsion.feature.metrics.SessionSummarizer
import com.lateropulsion.feature.metrics.TraceProcessor
import com.lateropulsion.feature.protocol.AbortReason
import com.lateropulsion.feature.protocol.AbortSource
import com.lateropulsion.feature.protocol.Effect
import com.lateropulsion.feature.protocol.ProtocolEngine
import com.lateropulsion.feature.protocol.SessionInput
import com.lateropulsion.feature.protocol.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Therapist-facing snapshot, updated ~5×/s. */
data class LiveSessionState(
    val phase: SessionState = SessionState.PreCheck,
    val spec: SessionSpec? = null,
    val blockIndex: Int = -1,
    val blockCount: Int = 0,
    val blockName: String = "",
    val blockRemainingS: Int = 0,
    val restRemainingS: Int = 0,
    val restComplete: Boolean = false,
    val thetaDeg: Double = 0.0,
    val thetaHeadDeg: Double = 0.0,
    val inBand: Boolean = true,
    val episodes: Int = 0,
    val madSoFarDeg: Double = Double.NaN,
    val trackingLost: Boolean = false,
    val mountShifted: Boolean = false,
    val pitchOutOfRange: Boolean = false,
    val perfDegraded: Boolean = false,
    val cameraStalled: Boolean = false,
    val calibrationProgress: Double = 0.0,
    val midlineConfirmed: Boolean = false,
    val instructionKey: String? = null,
    val abortReason: String? = null,
    val summary: SessionSummary? = null,
    val blocks: List<BlockResult> = emptyList(),
    val saved: Boolean = false,
    val error: String? = null,
    val validPct: Double = 100.0,
    val sessionElapsedS: Int = 0,
)

/**
 * Runs one session end to end (IMPLEMENTATION Phase 4): pose stream → live filter → `.lpx` log →
 * per-block metrics → protocol engine → render state → summary → persistence. The physical abort
 * (render neutral) goes through [AbortController] directly; the engine only records it.
 */
@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val dispatchers: LpDispatchers,
    private val renderStates: RenderStateHolder,
    private val abortController: AbortController,
) {
    private val _state = MutableStateFlow(LiveSessionState())
    val state: StateFlow<LiveSessionState> get() = _state

    private val hotDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "lp-session-hot").apply { priority = Thread.MAX_PRIORITY - 1 } }.asCoroutineDispatcher()
    private var scope: CoroutineScope? = null
    private var poseJob: Job? = null
    private var tickJob: Job? = null

    private var spec: SessionSpec? = null
    private var engine: ProtocolEngine? = null
    private var poses: PoseProvider? = null
    private var session: Session? = null
    private var baseline: Baseline? = null
    private var writer: LpxWriter? = null
    private var lpxPath: String? = null
    private var t0MonoNs = 0L
    private var lastStoredNs = 0L
    private var storeIntervalNs = 20_000_000L
    private var liveFilter: CausalFilter? = null
    private var haptics: HapticCue? = null
    private val bandEdge = BandEdgeDetector()
    private val events = ArrayList<SessionEvent>()
    private val pendingEvents = ArrayList<SessionEvent>()
    private val blockResults = ArrayList<BlockResult>()
    private var current: BlockRecorder? = null
    private var lastLiveMetricsNs = 0L
    private var lastUiNs = 0L
    private var perfDegraded = false
    private var cameraStalled = false
    private var gyroBias: Vec3? = null
    private var drift: Double? = null
    private var ssqPre: SsqScore? = null
    private var assistanceBefore: AssistanceLevel? = null

    val isActive: Boolean get() = engine != null && !(engine!!.state.isTerminal)

    /** Step 1: create the session record and start listening to the pose provider. */
    suspend fun prepare(spec: SessionSpec, poses: PoseProvider, baseline: Baseline?, ssqPre: SsqScore?, assistanceBefore: AssistanceLevel?) {
        release()
        this.spec = spec; this.poses = poses; this.baseline = baseline; this.ssqPre = ssqPre; this.assistanceBefore = assistanceBefore
        val eng = ProtocolEngine(spec)
        engine = eng
        val s = CoroutineScope(SupervisorJob() + dispatchers.default)
        scope = s
        abortController.reset()
        renderStates.set(RenderState.NEUTRAL)
        perfDegraded = false; cameraStalled = false
        events.clear(); pendingEvents.clear(); blockResults.clear(); current = null
        storeIntervalNs = (1e9 / spec.config.metrics.storeRateHz).toLong()
        liveFilter = CausalFilter.live(spec.config.metrics.sampleRateHz.toDouble(), spec.config.metrics.lowpassCutoffHz)
        haptics = HapticCue(ctx)
        bandEdge.reset()

        val now = clock.nowUtcMillis()
        t0MonoNs = clock.monotonicNanos()
        val sess = Session(
            id = spec.sessionId, patientId = spec.patientId, clinicianId = spec.clinicianId, protocolId = spec.protocol.protocolId,
            protocolVersion = spec.protocol.version, sessionNumber = spec.sessionNumber, visualMode = spec.visualMode, gainUsed = spec.gain,
            thetaRefDeg = spec.thetaRefDeg, baselineId = spec.baselineId, deviceProfileId = spec.deviceProfile.id, headsetProfileId = spec.headsetProfile.id,
            position = spec.position, startedAtUtc = now, startedMonoNs = t0MonoNs, deviceTimezone = TimeZone.getDefault().id,
            ssqPre = ssqPre, assistanceLevelBefore = assistanceBefore, appVersion = com.lateropulsion.app.BuildConfig.VERSION_NAME, overrideReason = spec.overrideReason,
            appliedTiltDeg = spec.appliedTiltDeg,
        )
        session = sess
        sessions.start(sess).getOrThrow()

        val dir = File(ctx.filesDir, "sessions").apply { mkdirs() }
        val file = File(dir, "${spec.sessionId.value}.lpx")
        lpxPath = file.absolutePath
        writer = LpxWriter(file, LpxHeader(spec.sessionId.value, spec.config.metrics.storeRateHz, now, t0MonoNs, spec.deviceProfile.id.take(32), 0, spec.thetaRefDeg))

        poses.resetForSession()
        poses.setThetaRef(spec.thetaRefDeg)
        poses.setBand(spec.config.metrics.bandPrimaryDeg)
        poses.setCalibrating(true)
        poses.start()
        poseJob = s.launch(hotDispatcher) { poses.samples.collect { onPose(it) } }
        tickJob = s.launch { while (isActive) { tick(); delay(200) } }
        s.launch(dispatchers.io) { while (isActive) { writer?.flush(); delay(200) } }
        _state.value = LiveSessionState(phase = eng.state, spec = spec, blockCount = spec.protocol.blocks.size)
        abortController.addListener { reason -> s.launch { if (eng.state.headsetActive) handle(SessionInput.Abort(AbortReason(AbortSource.WATCHDOG, reason))) } }
    }

    fun checklistComplete() = input(SessionInput.ChecklistComplete)

    /** Calibration: wait for the gyro-bias window, then the therapist confirms the midline. */
    fun calibrationFinished(bias: Vec3?, driftDegPerMin: Double?) {
        gyroBias = bias; drift = driftDegPerMin
        poses?.setCalibrating(false)
        input(SessionInput.CalibrationSucceeded)
    }
    fun calibrationFailed(reason: String) = input(SessionInput.CalibrationFailed(reason))

    fun confirmMidline(thetaRefDeg: Double) {
        poses?.setThetaRef(thetaRefDeg)
        poses?.clearMountShift()
        spec = spec?.copy(thetaRefDeg = thetaRefDeg)
        session = session?.copy(thetaRefDeg = thetaRefDeg)
        _state.value = _state.value.copy(midlineConfirmed = true)
        record(SessionEventType.CALIBRATION, """{"phase":"midline","theta_ref_deg":$thetaRefDeg}""")
    }

    fun start() = input(SessionInput.Start)
    fun pause() = input(SessionInput.Pause)
    fun resume() = input(SessionInput.Resume)
    fun stop() = input(SessionInput.Stop)
    fun nextBlock() = input(SessionInput.NextBlock)
    fun mark() = input(SessionInput.TherapistMark)
    fun balanceLoss() = input(SessionInput.BalanceLoss)
    fun proceedAfterAbort() = input(SessionInput.Proceed)

    /** The abort path: neutral first, bookkeeping second (REQ-SAF-004). */
    fun abort(source: AbortSource, detail: String = "") {
        abortController.abort("$source $detail".trim())
        renderStates.set(RenderState.NEUTRAL)
        input(SessionInput.Abort(AbortReason(source, detail)))
    }

    fun onPerfDegraded(m2pMs: Double) { perfDegraded = true; record(SessionEventType.PERF_DEGRADED, """{"m2p_ms":${"%.1f".format(m2pMs)}}""") }
    fun onCameraStall(stalled: Boolean) {
        cameraStalled = stalled
        record(SessionEventType.CAMERA_STALL, """{"stalled":$stalled}""")
        if (stalled && engine?.state is SessionState.BlockRunning) input(SessionInput.Pause)
    }

    private fun input(i: SessionInput) { scope?.launch { handle(i) } }

    private suspend fun handle(i: SessionInput) {
        val eng = engine ?: return
        val effects = withContext(hotDispatcher) { eng.handle(i, clock.monotonicNanos()) }
        for (e in effects) apply(e, eng)
        publish()
    }

    private suspend fun tick() { handle(SessionInput.Tick) }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun apply(e: Effect, eng: ProtocolEngine) {
        val sp = spec ?: return
        when (e) {
            is Effect.StartBlock -> {
                val rec = BlockRecorder(e.index, e.block, clock.monotonicNanos(), sp)
                withContext(hotDispatcher) { current = rec }
                renderStates.set(RenderState(
                    mode = sp.visualMode, gain = e.gain, cues = e.block.cues.toSet(), toleranceDeg = e.block.toleranceDeg,
                    bandPrimaryDeg = sp.config.metrics.bandPrimaryDeg, bandSecondaryDeg = sp.config.metrics.bandSecondaryDeg,
                    targetDeg = e.block.targetDeg, progress01 = 0.0, showReadout = CueType.DEVIATION_READOUT in e.block.cues, idle = false,
                    staticOffsetDeg = -sp.appliedTiltDeg,
                ))
                poses?.setBand(e.block.toleranceDeg)
                writer?.sync()
                _state.value = _state.value.copy(instructionKey = e.block.instructionKey)
            }
            is Effect.EndBlock -> {
                val rec = withContext(hotDispatcher) { current.also { current = null } } ?: return
                val result = rec.finish(e.reason, e.elapsedS, sp)
                blockResults += result
                writer?.sync()
                _state.value = _state.value.copy(blocks = blockResults.toList())
            }
            is Effect.Checkpoint -> withContext(hotDispatcher) { current?.checkpoint(e.atS) }
            is Effect.StartRest, Effect.RestComplete, Effect.Save -> Unit
            is Effect.SetGain -> renderStates.update { it.copy(gain = e.gain) }
            is Effect.Instruction -> _state.value = _state.value.copy(instructionKey = e.key)
            is Effect.Alert -> LpLog.w(TAG, "alert", "message" to e.message)
            is Effect.Event -> record(e.type, e.payload)
            Effect.RenderNeutral -> renderStates.set(RenderState.NEUTRAL)
            is Effect.Abort -> { abortController.abort(e.reason.toString()); _state.value = _state.value.copy(abortReason = e.reason.toString()) }
            Effect.Summarize -> summarize(eng)
            is Effect.Rejected -> LpLog.d(TAG, "input rejected", "input" to e.input::class.simpleName, "state" to e.state::class.simpleName, "why" to e.why)
        }
    }

    private fun record(type: SessionEventType, payload: String = "{}") {
        val sid = spec?.sessionId ?: return
        val ev = SessionEvent(Ids.event(), sid, clock.monotonicNanos() - t0MonoNs, type, payload)
        synchronized(events) { events += ev; pendingEvents += ev }
        scope?.launch(dispatchers.io) {
            val batch = synchronized(events) { pendingEvents.toList().also { pendingEvents.clear() } }
            runCatching { sessions.appendEvents(batch) }
        }
    }

    /** Hot path: one call per pose sample on the single session thread. No allocation beyond the log record. */
    private fun onPose(p: PoseSample) {
        val sp = spec ?: return
        val filt = liveFilter?.step(p.thetaDeg) ?: p.thetaDeg
        val eng = engine ?: return
        val running = eng.state is SessionState.BlockRunning
        val flags = if (running) p.flags else p.flags or ValidityFlags.PAUSED
        if (p.tNanos - lastStoredNs >= storeIntervalNs - 500_000L) {
            lastStoredNs = p.tNanos
            writer?.append(LpxRecord(((p.tNanos - t0MonoNs) / 1_000_000L).coerceAtLeast(0), p.thetaDeg, filt, p.gx, p.gy, p.gz, p.wx, p.wy, p.wz, flags and 0xFFFF))
        }
        val rec = current
        if (running && rec != null) {
            rec.add((p.tNanos - rec.startNs) / 1e9, p.thetaDeg, filt, flags)
            val inBand = abs(filt - rec.block.targetDeg) <= rec.block.toleranceDeg
            when (bandEdge.feed(p.tNanos, inBand)) {
                BandEdgeDetector.Edge.EXIT -> if (CueType.HAPTIC in rec.block.cues) haptics?.pulseExit()
                BandEdgeDetector.Edge.RETURN -> if (CueType.HAPTIC in rec.block.cues) haptics?.pulseReturn()
                BandEdgeDetector.Edge.NONE -> Unit
            }
            if (p.tNanos - lastLiveMetricsNs > 1_000_000_000L) {
                lastLiveMetricsNs = p.tNanos
                val values = mapOf(MetricNames.EPISODES to rec.detector.liveCount.toDouble(), MetricNames.MAD to rec.acc.madSoFar, MetricNames.MAX to rec.acc.snapshot().maxDeg)
                scope?.launch { handle(SessionInput.LiveMetrics(rec.index, values)) }
                val progress = ((p.tNanos - rec.startNs) / 1e9 / rec.block.durationS).coerceIn(0.0, 1.0)
                renderStates.update { it.copy(progress01 = progress) }
            }
        }
        if (p.tNanos - lastUiNs > 200_000_000L) {
            lastUiNs = p.tNanos
            val diag = poses?.diagnostics?.value
            _state.value = _state.value.copy(
                thetaDeg = filt, thetaHeadDeg = p.thetaHeadDeg,
                inBand = rec?.let { abs(filt - it.block.targetDeg) <= it.block.toleranceDeg } ?: (abs(filt) <= sp.config.metrics.bandPrimaryDeg),
                episodes = rec?.detector?.liveCount ?: _state.value.episodes,
                madSoFarDeg = rec?.acc?.madSoFar ?: _state.value.madSoFarDeg,
                trackingLost = ValidityFlags.has(p.flags, ValidityFlags.TRACKING_LOST), mountShifted = ValidityFlags.has(p.flags, ValidityFlags.MOUNT_SHIFT),
                pitchOutOfRange = ValidityFlags.has(p.flags, ValidityFlags.PITCH_OUT_OF_RANGE), perfDegraded = perfDegraded, cameraStalled = cameraStalled,
                calibrationProgress = diag?.biasProgress ?: 0.0,
                validPct = rec?.acc?.snapshot()?.validSamplePct ?: 100.0,
            )
        }
    }

    private fun publish() {
        val eng = engine ?: return
        val sp = spec ?: return
        val now = clock.monotonicNanos()
        val s = eng.state
        var st = _state.value.copy(phase = s, blockCount = sp.protocol.blocks.size, sessionElapsedS = if (eng.sessionStartNs > 0) ((now - eng.sessionStartNs) / 1e9).toInt() else 0)
        st = when (s) {
            is SessionState.BlockRunning -> { val b = sp.protocol.blocks[s.blockIndex]; st.copy(blockIndex = s.blockIndex, blockName = b.blockId,
                blockRemainingS = (b.durationS - (now - s.blockStartNs - s.pausedNs) / 1e9).toInt().coerceAtLeast(0), restRemainingS = 0, restComplete = false) }
            is SessionState.Paused -> { val b = sp.protocol.blocks[s.block.blockIndex]; st.copy(blockIndex = s.block.blockIndex, blockName = b.blockId,
                blockRemainingS = (b.durationS - (s.pausedAtNs - s.block.blockStartNs - s.block.pausedNs) / 1e9).toInt().coerceAtLeast(0)) }
            is SessionState.Resting -> st.copy(blockIndex = s.completedBlockIndex, restRemainingS = ((s.restDurationNs - (now - s.restStartNs)) / 1e9).toInt().coerceAtLeast(0), restComplete = now - s.restStartNs >= s.restDurationNs)
            else -> st
        }
        _state.value = st
    }

    private suspend fun summarize(eng: ProtocolEngine) {
        val sp = spec ?: return
        val allEpisodes = blockResults.flatMap { it.episodeList }
        val summary = SessionSummarizer.summarize(
            sp.sessionId, blockResults.toList(), baseline, allEpisodes, sp.gain, sp.visualMode, eng.endReason, eng.balanceLossEvents,
            assistanceBefore, sp.config.metrics.mdcDeg, sp.config.session.minValidSecondsForConfidence, clock.nowUtcMillis(),
        )
        _state.value = _state.value.copy(summary = summary, blocks = blockResults.toList(), phase = eng.state)
    }

    /** Step last: therapist confirms notes; everything is persisted atomically. */
    suspend fun finalize(notes: String, assistanceAfter: AssistanceLevel?, ssqPost: SsqScore?): Result<CompletedSession> = runCatching {
        val eng = engine ?: error("no session")
        val sp = spec ?: error("no session")
        val sess = session ?: error("no session")
        poseJob?.cancel(); tickJob?.cancel()
        poses?.stop()
        val w = writer
        val closeReason = if (eng.endReason == EndReason.ABORTED) CloseReason.ABORTED else CloseReason.COMPLETED
        val ts = withContext(dispatchers.io) {
            w?.close(closeReason)
            val path = lpxPath
            if (w != null && path != null) {
                val parsed = com.lateropulsion.core.timeseries.LpxReader.read(File(path))
                TimeseriesFile(sp.sessionId, path, sp.config.metrics.storeRateHz, parsed.records.size.toLong(),
                    parsed.trailer?.sha256?.let { com.lateropulsion.core.common.Hashing.toHex(it) } ?: "",
                    blockResults.firstOrNull()?.filterParams ?: com.lateropulsion.core.model.FilterParams.summary(sp.config.metrics.storeRateHz.toDouble()), false)
            } else null
        }
        val summary = _state.value.summary ?: SessionSummarizer.summarize(sp.sessionId, blockResults, baseline, blockResults.flatMap { it.episodeList }, sp.gain, sp.visualMode,
            eng.endReason, eng.balanceLossEvents, assistanceAfter ?: assistanceBefore, sp.config.metrics.mdcDeg, sp.config.session.minValidSecondsForConfidence, clock.nowUtcMillis())
        val finalSummary = summary.copy(assistanceLevel = assistanceAfter ?: summary.assistanceLevel)
        val done = sess.copy(
            endedAtUtc = clock.nowUtcMillis(), endReason = eng.endReason, abortReason = eng.abortReason?.toString(), notes = notes,
            assistanceLevelAfter = assistanceAfter, ssqPost = ssqPost, gyroBias = gyroBias, driftDegPerMin = drift, thetaRefDeg = sp.thetaRefDeg,
        )
        val completed = CompletedSession(done, blockResults.toList(), finalSummary, synchronized(events) { events.toList() }, ts)
        sessions.complete(completed).getOrThrow()
        handle(SessionInput.Confirm)
        _state.value = _state.value.copy(saved = true, summary = finalSummary)
        LpLog.i(TAG, "session saved", "session_id" to Redaction.shortId(sp.sessionId.value), "end" to eng.endReason, "blocks" to blockResults.size, "records" to (ts?.sampleCount ?: 0L))
        completed
    }.onFailure { LpLog.e(TAG, "session save failed", it, "state" to engine?.state?.let { s -> s::class.simpleName }) }

    fun release() {
        scope?.cancel(); scope = null
        poseJob = null; tickJob = null
        poses?.stop()
        runCatching { writer?.close(CloseReason.ERROR) }
        writer = null
        renderStates.set(RenderState.NEUTRAL)
        engine = null; spec = null; session = null; current = null
    }

    /** Per-block raw capture + live statistics. Offline metrics are recomputed from the raw arrays at block end. */
    private class BlockRecorder(val index: Int, val block: BlockSpec, val startNs: Long, sp: SessionSpec) {
        private val t = ArrayList<Double>(block.durationS * sp.config.metrics.sampleRateHz + 1024)
        private val theta = ArrayList<Double>(t.size)
        private val flags = ArrayList<Int>(t.size)
        private val checkpointsS = ArrayList<Int>()
        val acc = MetricsAccumulator(block.targetDeg, block.toleranceDeg, sp.config.metrics.bandPrimaryDeg, sp.config.metrics.bandSecondaryDeg, 1.0 / sp.config.metrics.sampleRateHz)
        val detector = EpisodeDetector(sp.config.metrics.episodeEnterDeg, sp.config.metrics.episodeExitDeg, sp.config.metrics.episodeMinDurationS, sp.config.metrics.bandPrimaryDeg, sp.config.metrics.recoveryHoldS, block.targetDeg)
        private val rate = sp.config.metrics.sampleRateHz.toDouble()

        fun add(tS: Double, thetaRaw: Double, thetaLive: Double, f: Int) {
            t += tS; theta += thetaRaw; flags += f
            val valid = ValidityFlags.isValidForMetrics(f)
            acc.add(thetaLive, valid)
            detector.feed(tS, thetaLive, valid)
        }

        fun checkpoint(atS: Int) { checkpointsS += atS }

        fun finish(reason: BlockEndReason, elapsedS: Double, sp: SessionSpec): BlockResult {
            val trace = RawTrace(t.toDoubleArray(), theta.toDoubleArray(), flags.toIntArray())
            val processed = TraceProcessor.process(trace, rate, block.targetDeg, block.toleranceDeg, sp.config.metrics)
            val cps = TraceProcessor.checkpoints(trace, processed, checkpointsS, block.targetDeg, block.toleranceDeg)
            return BlockResult(
                id = Ids.blockResult(), sessionId = sp.sessionId, blockId = block.blockId, orderIndex = index, exercise = block.exercise,
                position = block.position ?: sp.protocol.positionRequired, startedMonoNs = startNs, durationS = elapsedS, targetDeg = block.targetDeg,
                toleranceDeg = block.toleranceDeg, gain = if (sp.visualMode == com.lateropulsion.core.model.VisualMode.VERTICAL_REFERENCE) 0.0 else (block.gain ?: sp.gain),
                cues = block.cues, metrics = processed.metrics, episodes = EpisodeDetector.stats(processed.episodes), episodeList = processed.episodes,
                checkpoints = cps, endReason = reason, filterParams = processed.filterParams,
            )
        }
    }

    private companion object { const val TAG = "Session" }
}

/** Seconds helper for UI. */
fun Long.nsToSeconds(): Double = TimeUnits.nanosToSeconds(this)
