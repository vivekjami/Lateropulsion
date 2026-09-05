package com.lateropulsion.feature.protocol

import com.lateropulsion.core.common.EngineVersions
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.BlockSpec
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.DeviationMetrics
import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.EpisodeStats
import com.lateropulsion.core.model.ExerciseType
import com.lateropulsion.core.model.GainSchedule
import com.lateropulsion.core.model.HeadsetProfile
import com.lateropulsion.core.model.LesionSide
import com.lateropulsion.core.model.LpJson
import com.lateropulsion.core.model.MetricCondition
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.PreSessionChecklist
import com.lateropulsion.core.model.ProgressionGate
import com.lateropulsion.core.model.ProtocolSpec
import com.lateropulsion.core.model.GateRequirement
import com.lateropulsion.core.model.SessionId
import com.lateropulsion.core.model.SessionSpec
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.Sex
import com.lateropulsion.core.model.Side
import com.lateropulsion.core.model.VisualMode
import java.io.File

object Fixtures {
    val configDir: File = File(System.getProperty("lp.configDir"))

    fun loadProtocols(): List<ProtocolSpec> = File(configDir, "protocols").listFiles { f -> f.extension == "json" }!!
        .sortedBy { it.name }
        .map { LpJson.strict.decodeFromString(ProtocolSpec.serializer(), it.readText()) }

    fun loadAppConfig(): AppConfig = LpJson.strict.decodeFromString(AppConfig.serializer(), File(configDir, "app.json").readText())

    val sittingProtocol = ProtocolSpec(
        protocolId = "test-sitting", version = 1, name = "Test sitting", positionRequired = BodyPosition.SITTING_UNSUPPORTED,
        visualMode = VisualMode.VERTICAL_REFERENCE, gainSchedule = GainSchedule.PerformanceDriven(),
        blocks = listOf(
            BlockSpec("b1", ExerciseType.MIDLINE_TRAINING, durationS = 60, toleranceDeg = 10.0, cues = listOf(CueType.PLUMB_LINE), checkpointsS = listOf(20, 40, 60), restAfterS = 30, instructionKey = "i1"),
            BlockSpec("b2", ExerciseType.SITTING_HOLD, durationS = 90, toleranceDeg = 5.0, cues = listOf(CueType.HORIZON), checkpointsS = listOf(45, 90), abortIf = MetricCondition("episodes", gt = 3.0)),
        ),
        progressionGate = ProgressionGate("test-standing", listOf(GateRequirement("TIB_5", gte = 60.0, consecutiveSessions = 2), GateRequirement("balance_loss_events", eq = 0.0))),
    )

    val standingProtocol = sittingProtocol.copy(
        protocolId = "test-standing", positionRequired = BodyPosition.STANDING, requiresGateFrom = "test-sitting", progressionGate = null,
        blocks = listOf(BlockSpec("s1", ExerciseType.STANDING_HOLD, durationS = 90, toleranceDeg = 10.0, cues = listOf(CueType.PLUMB_LINE))),
    )

    val modeBProtocol = sittingProtocol.copy(
        protocolId = "test-mode-b", visualMode = VisualMode.COMPENSATED_VIEW, gainInitial = 0.4, progressionGate = null,
        blocks = listOf(BlockSpec("c1", ExerciseType.SITTING_HOLD, durationS = 120, toleranceDeg = 5.0, cues = listOf(CueType.PLUMB_LINE))),
    )

    val device = DeviceProfile("dev", "X", "M", "M", rollSign = 1, thetaMountDeg = -90.0, residualRmsDeg = 0.7, maxErrorDeg = 1.4, imuRateHz = 200.0, qualified = true)
    val headset = HeadsetProfile("hs", "HS")

    val patient = Patient(
        id = PatientId("p1"), displayId = "LP-2026-0001", age = 60, sex = Sex.FEMALE, diagnosis = "stroke", lesionSide = LesionSide.RIGHT,
        affectedSide = Side.LEFT, lateropulsionDirection = Side.LEFT, onsetDate = null, consentMedia = false, consentResearch = false,
        consentRecordedAt = null, createdAt = 0, updatedAt = 0,
    )

    val fullChecklist = PreSessionChecklist(
        supervisionAttested = true, harnessConfirmed = true, hygieneConfirmed = true, batteryOk = true, thermalOk = true,
        storageOk = true, contraindicationsRechecked = true, identityConfirmed = true, deviceQualified = true,
    )

    fun spec(
        protocol: ProtocolSpec = sittingProtocol,
        mode: VisualMode = protocol.visualMode,
        gain: Double = if (mode == VisualMode.COMPENSATED_VIEW) 0.4 else 0.0,
        config: AppConfig = AppConfig(),
        advanced: Boolean = false,
        override: String? = null,
        device: DeviceProfile = this.device,
    ) = SessionSpec(
        sessionId = SessionId("s1"), patientId = patient.id, patientDisplayId = patient.displayId, clinicianId = ClinicianId("c1"),
        protocol = protocol, visualMode = mode, gain = gain, thetaRefDeg = 1.5, thetaRefSetBy = ClinicianId("c1"), baselineId = null,
        deviceProfile = device, headsetProfile = headset, sessionNumber = 1, advancedProtocol = advanced, config = config, overrideReason = override,
    )

    fun summary(tib5: Double, lowConfidence: Boolean = false, balanceLoss: Int = 0) = SessionSummary(
        sessionId = SessionId("x"), baselineId = null, baselineMadDeg = null,
        metrics = DeviationMetrics.EMPTY.copy(tib5Pct = tib5, validSamples = 3000, totalSamples = 3000, validDurationS = 120.0, madDeg = 5.0),
        episodes = EpisodeStats.NONE, deltaDeg = null, improvementPct = null, withinMdc = null, mdcDeg = 2.0, lowConfidence = lowConfidence,
        comparisonRefusedReason = null, balanceLossEvents = balanceLoss, assistanceLevel = null, gainUsed = 0.0,
        visualMode = VisualMode.VERTICAL_REFERENCE, endReason = EndReason.COMPLETED, generatedByVersion = EngineVersions.METRICS_ENGINE, generatedAt = 0,
    )

    fun entry(protocolId: String, tib5: Double, mode: VisualMode = VisualMode.VERTICAL_REFERENCE, gain: Double = 0.0, ssq: Boolean = false, lowConfidence: Boolean = false, balanceLoss: Int = 0) =
        SessionHistoryEntry(protocolId, mode, BodyPosition.SITTING_UNSUPPORTED, gain, summary(tib5, lowConfidence, balanceLoss).copy(gainUsed = gain, visualMode = mode), ssq)

    const val S: Long = 1_000_000_000L
}
