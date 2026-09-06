package com.lateropulsion.core.model

/**
 * One headset session. `thetaRefDeg` and `gainUsed` are copied in, never referenced, so later
 * recalibration cannot rewrite history (ARCHITECTURE §10 schema notes).
 */
public data class Session(
    val id: SessionId,
    val patientId: PatientId,
    val clinicianId: ClinicianId,
    val protocolId: String,
    val protocolVersion: Int,
    val sessionNumber: Int,
    val visualMode: VisualMode,
    val gainUsed: Double,
    val thetaRefDeg: Double,
    val baselineId: BaselineId?,
    val deviceProfileId: String,
    val headsetProfileId: String,
    val position: BodyPosition,
    val startedAtUtc: Long,
    val startedMonoNs: Long,
    val deviceTimezone: String,
    val endedAtUtc: Long? = null,
    val endReason: EndReason? = null,
    val abortReason: String? = null,
    val ssqPre: SsqScore? = null,
    val ssqPost: SsqScore? = null,
    val notes: String = "",
    val assistanceLevelBefore: AssistanceLevel? = null,
    val assistanceLevelAfter: AssistanceLevel? = null,
    val gyroBias: Vec3? = null,
    val driftDegPerMin: Double? = null,
    val appVersion: String,
    val crashRecovered: Boolean = false,
    val overrideReason: String? = null,
    /** Picture tilt applied in this session (degrees, + right), faded from the entered baseline error over sessions (ADR-022). */
    val appliedTiltDeg: Double = 0.0,
) {
    public val isFinished: Boolean get() = endReason != null
}

@kotlinx.serialization.Serializable
public data class Checkpoint(
    val atS: Double,
    val thetaDeg: Double,
    val madSoFarDeg: Double,
    val inBand: Boolean,
)

public data class BlockResult(
    val id: BlockResultId,
    val sessionId: SessionId,
    val blockId: String,
    val orderIndex: Int,
    val exercise: ExerciseType,
    val position: BodyPosition,
    val startedMonoNs: Long,
    val durationS: Double,
    val targetDeg: Double,
    val toleranceDeg: Double,
    val gain: Double,
    val cues: List<CueType>,
    val metrics: DeviationMetrics,
    val episodes: EpisodeStats,
    val episodeList: List<Episode>,
    val checkpoints: List<Checkpoint>,
    val endReason: BlockEndReason,
    val filterParams: FilterParams,
)

/**
 * Session-level summary. [improvementPct] is an internal progress metric on a proxy signal and is
 * always presented alongside [gainUsed] and [withinMdc] (README §7, ARCHITECTURE §7.3).
 */
@kotlinx.serialization.Serializable
public data class SessionSummary(
    val sessionId: SessionId,
    val baselineId: BaselineId?,
    val baselineMadDeg: Double?,
    val metrics: DeviationMetrics,
    val episodes: EpisodeStats,
    val deltaDeg: Double?,
    val improvementPct: Double?,
    val withinMdc: Boolean?,
    val mdcDeg: Double?,
    val lowConfidence: Boolean,
    /** Human-readable reason when the baseline comparison was refused (e.g. position mismatch). */
    val comparisonRefusedReason: String?,
    val balanceLossEvents: Int,
    val assistanceLevel: AssistanceLevel?,
    val gainUsed: Double,
    val visualMode: VisualMode,
    val endReason: EndReason,
    val generatedByVersion: String,
    val generatedAt: Long,
) {
    public val tib5Pct: Double get() = metrics.tib5Pct
    public val madDeg: Double get() = metrics.madDeg
}

public data class SessionEvent(
    val id: EventId,
    val sessionId: SessionId,
    /** Nanoseconds since session t0 on the monotonic clock. */
    val tNanos: Long,
    val type: SessionEventType,
    val payloadJson: String = "{}",
)

public data class TimeseriesFile(
    val sessionId: SessionId,
    val path: String,
    val sampleRateHz: Int,
    val sampleCount: Long,
    val sha256: String,
    val filterParams: FilterParams,
    val crashRecovered: Boolean,
)

public data class MediaAsset(
    val id: AssetId,
    val sessionId: SessionId,
    val type: MediaType,
    val pathEncrypted: String,
    val capturedAt: Long,
    val retentionUntil: Long,
)

/** Everything produced when a session ends; persisted atomically. */
public data class CompletedSession(
    val session: Session,
    val blocks: List<BlockResult>,
    val summary: SessionSummary,
    val events: List<SessionEvent>,
    val timeseries: TimeseriesFile?,
)
