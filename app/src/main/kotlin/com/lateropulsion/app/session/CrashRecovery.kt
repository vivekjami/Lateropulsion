package com.lateropulsion.app.session

import android.content.Context
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.EngineVersions
import com.lateropulsion.core.common.Hashing
import com.lateropulsion.core.common.LpLog
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.BlockEndReason
import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.CompletedSession
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.FilterParams
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionEventType
import com.lateropulsion.core.model.SessionRepository
import com.lateropulsion.core.model.TimeseriesFile
import com.lateropulsion.core.timeseries.LpxReader
import com.lateropulsion.core.timeseries.LpxRecovery
import com.lateropulsion.feature.metrics.EpisodeDetector
import com.lateropulsion.feature.metrics.RawTrace
import com.lateropulsion.feature.metrics.SessionSummarizer
import com.lateropulsion.feature.metrics.TraceProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On launch: every session without an end reason is rebuilt from its `.lpx` log and its incrementally
 * persisted events, then saved as CRASH_RECOVERED (ARCHITECTURE §15, REQ-SES-031). Nothing is discarded.
 */
@Singleton
class CrashRecovery @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val sessions: SessionRepository,
    private val baselines: BaselineRepository,
    private val config: ConfigRepository,
    private val appConfig: AppConfig,
    private val clock: Clock,
) {
    suspend fun recoverAll(): List<Session> {
        val recovered = ArrayList<Session>()
        for (s in sessions.unfinished()) {
            runCatching { recover(s) }.onSuccess { recovered += it }.onFailure { LpLog.e(TAG, "recovery failed", it, "session_id" to s.id.value) }
        }
        return recovered
    }

    private suspend fun recover(s: Session): Session {
        val file = File(File(ctx.filesDir, "sessions"), "${s.id.value}.lpx")
        val events = sessions.events(s.id)
        val protocol = config.protocol(s.protocolId)
        val blocks = ArrayList<BlockResult>()
        var ts: TimeseriesFile? = null
        if (file.exists()) {
            LpxRecovery.recover(file)
            val lpx = LpxReader.read(file)
            ts = TimeseriesFile(s.id, file.absolutePath, lpx.header.sampleRateHz, lpx.records.size.toLong(), lpx.trailer?.sha256?.let { Hashing.toHex(it) } ?: "", FilterParams.summary(lpx.header.sampleRateHz.toDouble()), crashRecovered = true)
            // Block boundaries from BLOCK_START / BLOCK_END events (ns since t0).
            val starts = events.filter { it.type == SessionEventType.BLOCK_START }
            for (st in starts) {
                val idx = runCatching { Json.parseToJsonElement(st.payloadJson).jsonObject["index"]!!.jsonPrimitive.content.toInt() }.getOrNull() ?: continue
                val end = events.firstOrNull { it.type == SessionEventType.BLOCK_END && it.tNanos > st.tNanos }?.tNanos ?: (lpx.records.lastOrNull()?.tDeltaMs?.times(1_000_000L) ?: st.tNanos)
                val block = protocol?.blocks?.getOrNull(idx) ?: continue
                val recs = lpx.records.filter { it.tDeltaMs * 1_000_000L in st.tNanos..end }
                if (recs.isEmpty()) continue
                val t0 = recs.first().tDeltaMs
                val trace = RawTrace(DoubleArray(recs.size) { (recs[it].tDeltaMs - t0) / 1000.0 }, DoubleArray(recs.size) { recs[it].thetaRawDeg }, IntArray(recs.size) { recs[it].flags })
                val processed = TraceProcessor.process(trace, lpx.header.sampleRateHz.toDouble(), block.targetDeg, block.toleranceDeg, appConfig.metrics)
                blocks += BlockResult(
                    Ids.blockResult(), s.id, block.blockId, idx, block.exercise, block.position ?: protocol.positionRequired, s.startedMonoNs + st.tNanos,
                    (end - st.tNanos) / 1e9, block.targetDeg, block.toleranceDeg, block.gain ?: s.gainUsed, block.cues, processed.metrics,
                    EpisodeDetector.stats(processed.episodes), processed.episodes, emptyList(), BlockEndReason.ERROR, processed.filterParams,
                )
            }
        }
        val baseline = s.baselineId?.let { baselines.findById(it) }
        val summary = SessionSummarizer.summarize(
            s.id, blocks, baseline, blocks.flatMap { it.episodeList }, s.gainUsed, s.visualMode, EndReason.CRASH_RECOVERED,
            events.count { it.type == SessionEventType.BALANCE_LOSS }, s.assistanceLevelBefore, appConfig.metrics.mdcDeg,
            appConfig.session.minValidSecondsForConfidence, clock.nowUtcMillis(),
        ).copy(generatedByVersion = EngineVersions.METRICS_ENGINE)
        val done = s.copy(endedAtUtc = clock.nowUtcMillis(), endReason = EndReason.CRASH_RECOVERED, crashRecovered = true, abortReason = "app terminated mid-session")
        sessions.complete(CompletedSession(done, blocks, summary, emptyList(), ts)).getOrThrow()
        LpLog.w(TAG, "session recovered", "session_id" to s.id.value, "blocks" to blocks.size)
        return done
    }

    private companion object { const val TAG = "Recovery" }
}
