package com.lateropulsion.feature.report

import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.timeseries.LpxFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * CSV / JSON exporters, de-identified by default (REQ-SEC-006). Identified exports must be requested
 * explicitly and are audited by the caller. Parquet is produced off-device by tools/analysis/lpx_reader.py (ADR-011).
 */
public object CsvExporter {
    public fun sessionsCsv(patient: Patient, rows: List<Pair<Session, SessionSummary>>, studyCode: String = patient.displayId): String {
        val sb = StringBuilder()
        sb.appendLine(
            "study_code,session_number,started_at_utc,protocol_id,protocol_version,visual_mode,gain_k,position,end_reason,mad_deg,rms_deg,max_deg,sd_deg," +
                "tib5_pct,tib10_pct,episodes,recovery_mean_s,path_length_deg,symmetry_index,valid_sample_pct,valid_duration_s,baseline_mad_deg,delta_deg," +
                "improvement_pct,within_mdc,low_confidence,balance_loss_events,assistance_level,ssq_pre_total,ssq_post_total,crash_recovered,metrics_engine",
        )
        for ((s, m) in rows) {
            val d = m.metrics
            sb.append(csv(studyCode)).append(',').append(s.sessionNumber).append(',').append(s.startedAtUtc).append(',')
                .append(csv(s.protocolId)).append(',').append(s.protocolVersion).append(',').append(s.visualMode).append(',').append(s.gainUsed).append(',')
                .append(s.position).append(',').append(s.endReason ?: "").append(',')
                .append(num(d.madDeg)).append(',').append(num(d.rmsDeg)).append(',').append(num(d.maxDeg)).append(',').append(num(d.sdDeg)).append(',')
                .append(num(d.tib5Pct)).append(',').append(num(d.tib10Pct)).append(',').append(m.episodes.count).append(',').append(num(m.episodes.recoveryMeanS)).append(',')
                .append(num(d.pathLengthDeg)).append(',').append(num(d.symmetryIndex)).append(',').append(num(d.validSamplePct)).append(',').append(num(d.validDurationS)).append(',')
                .append(num(m.baselineMadDeg)).append(',').append(num(m.deltaDeg)).append(',').append(num(m.improvementPct)).append(',').append(m.withinMdc ?: "").append(',')
                .append(m.lowConfidence).append(',').append(m.balanceLossEvents).append(',').append(m.assistanceLevel?.level ?: "").append(',')
                .append(num(s.ssqPre?.total)).append(',').append(num(s.ssqPost?.total)).append(',').append(s.crashRecovered).append(',').append(csv(m.generatedByVersion))
                .appendLine()
        }
        return sb.toString()
    }

    public fun blocksCsv(studyCode: String, blocks: List<BlockResult>): String {
        val sb = StringBuilder()
        sb.appendLine("study_code,session_id,order,block_id,exercise,position,duration_s,target_deg,tolerance_deg,gain_k,mad_deg,rms_deg,max_deg,tib5_pct,tib10_pct,episodes,partial_episodes,recovery_mean_s,valid_sample_pct,end_reason")
        for (b in blocks) {
            sb.append(csv(studyCode)).append(',').append(b.sessionId.value).append(',').append(b.orderIndex).append(',').append(csv(b.blockId)).append(',')
                .append(b.exercise).append(',').append(b.position).append(',').append(num(b.durationS)).append(',').append(b.targetDeg).append(',').append(b.toleranceDeg).append(',')
                .append(b.gain).append(',').append(num(b.metrics.madDeg)).append(',').append(num(b.metrics.rmsDeg)).append(',').append(num(b.metrics.maxDeg)).append(',')
                .append(num(b.metrics.tib5Pct)).append(',').append(num(b.metrics.tib10Pct)).append(',').append(b.episodes.count).append(',').append(b.episodes.partialCount).append(',')
                .append(num(b.episodes.recoveryMeanS)).append(',').append(num(b.metrics.validSamplePct)).append(',').append(b.endReason).appendLine()
        }
        return sb.toString()
    }

    /** Raw time series from an `.lpx` file; one row per stored sample. */
    public fun traceCsv(lpx: LpxFile): String {
        val sb = StringBuilder(lpx.records.size * 64)
        sb.appendLine("t_ms,theta_raw_deg,theta_filt_deg,gx,gy,gz,wx_rad_s,wy_rad_s,wz_rad_s,flags")
        for (r in lpx.records) {
            sb.append(r.tDeltaMs).append(',').append(r.thetaRawDeg).append(',').append(r.thetaFiltDeg).append(',')
                .append(r.gx).append(',').append(r.gy).append(',').append(r.gz).append(',')
                .append(r.wx).append(',').append(r.wy).append(',').append(r.wz).append(',').append(r.flags).appendLine()
        }
        return sb.toString()
    }

    private fun num(v: Double?): String = when {
        v == null || v.isNaN() -> ""
        else -> String.format(java.util.Locale.ROOT, "%.4f", v)
    }

    public fun csv(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

@Serializable
public data class SessionBundle(
    val schema: String = "lateropulsion.session-bundle.v1",
    val studyCode: String,
    val deidentified: Boolean,
    val patient: PatientExport,
    val session: SessionExport,
    val summary: SessionSummary,
    val blocks: List<BlockExport>,
    val events: List<EventExport>,
    val baselineMadDeg: Double?,
    val notes: String,
)

@Serializable public data class PatientExport(val studyCode: String, val age: Int, val sex: String, val lesionSide: String, val affectedSide: String, val lateropulsionDirection: String, val diagnosis: String?)
@Serializable public data class SessionExport(val sessionId: String, val sessionNumber: Int, val startedAtUtc: Long, val endedAtUtc: Long?, val protocolId: String, val protocolVersion: Int,
    val visualMode: String, val gainUsed: Double, val thetaRefDeg: Double, val position: String, val endReason: String?, val abortReason: String?, val deviceProfileId: String,
    val appVersion: String, val crashRecovered: Boolean)
@Serializable public data class BlockExport(val blockId: String, val orderIndex: Int, val exercise: String, val position: String, val durationS: Double, val gain: Double,
    val metrics: com.lateropulsion.core.model.DeviationMetrics, val episodes: com.lateropulsion.core.model.EpisodeStats, val checkpoints: List<com.lateropulsion.core.model.Checkpoint>, val endReason: String)
@Serializable public data class EventExport(val tNs: Long, val type: String, val payload: String)

public object JsonExporter {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    // NaN is emitted as a bare NaN token (accepted by Python json and pandas); metrics that are undefined stay explicit rather than silently zero.
    private val json = Json { prettyPrint = true; namingStrategy = JsonNamingStrategy.SnakeCase; encodeDefaults = true; explicitNulls = false; allowSpecialFloatingPointValues = true }

    /** [deidentified] exports strip the free-text diagnosis and notes as well; identifiers are never included in any case. */
    public fun sessionBundle(data: SessionReportData, deidentified: Boolean, studyCode: String = data.patient.displayId): String {
        val p = data.patient
        val s = data.session
        val bundle = SessionBundle(
            studyCode = studyCode,
            deidentified = deidentified,
            patient = PatientExport(studyCode, p.age, p.sex.name, p.lesionSide.name, p.affectedSide.name, p.lateropulsionDirection.name, if (deidentified) null else p.diagnosis),
            session = SessionExport(s.id.value, s.sessionNumber, s.startedAtUtc, s.endedAtUtc, s.protocolId, s.protocolVersion, s.visualMode.name, s.gainUsed, s.thetaRefDeg, s.position.name,
                s.endReason?.name, s.abortReason, s.deviceProfileId, s.appVersion, s.crashRecovered),
            summary = data.summary,
            blocks = data.blocks.map { BlockExport(it.blockId, it.orderIndex, it.exercise.name, it.position.name, it.durationS, it.gain, it.metrics, it.episodes, it.checkpoints, it.endReason.name) },
            events = data.events.map { EventExport(it.tNanos, it.type.name, it.payloadJson) },
            baselineMadDeg = data.baseline?.measured?.madDeg,
            notes = if (deidentified) "" else s.notes,
        )
        return json.encodeToString(SessionBundle.serializer(), bundle)
    }
}
