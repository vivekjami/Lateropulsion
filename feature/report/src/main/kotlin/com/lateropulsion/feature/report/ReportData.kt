package com.lateropulsion.feature.report

import com.lateropulsion.core.model.Assessment
import com.lateropulsion.core.model.Baseline
import com.lateropulsion.core.model.BlockResult
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionEvent
import com.lateropulsion.core.model.SessionSummary

/** Downsampled deviation trace for plotting: seconds from session start and filtered θ. */
public data class TracePoint(val tS: Double, val thetaDeg: Double, val valid: Boolean)

/** Everything one session report needs; assembled by the app from the repositories. */
public data class SessionReportData(
    val patient: Patient,
    val clinicianName: String,
    val session: Session,
    val blocks: List<BlockResult>,
    val summary: SessionSummary,
    val baseline: Baseline?,
    val events: List<SessionEvent>,
    val trace: List<TracePoint>,
    val protocolName: String,
    val deviceQualification: com.lateropulsion.core.model.DeviceQualification,
    val appVersion: String,
    val siteName: String,
    val generatedAtUtc: Long,
    val screenshotPaths: List<String> = emptyList(),
)

public data class ProgressReportData(
    val patient: Patient,
    val baseline: Baseline?,
    val sessions: List<Pair<Session, SessionSummary>>,
    val assessments: List<Assessment>,
    val mdcDeg: Double,
    val appVersion: String,
    val siteName: String,
    val generatedAtUtc: Long,
)

/** Fixed wording reviewed by the clinical lead; centralised so every surface says the same thing. */
public object ReportText {
    public const val PROXY_LIMITATION: String =
        "Limitation: this device measures HEAD roll from a headset-mounted sensor. Head tilt is a proxy for trunk lateropulsion and the two can dissociate. " +
            "Device metrics are internal progress indicators, not validated clinical outcomes; SCP/BLS/FAC remain the clinical outcomes."
    public const val IMPROVEMENT_CAVEAT: String =
        "Improvement % compares mean absolute deviation with the immutable baseline captured in the same position and exercise family. " +
            "Read it together with the gain k and the assistance level: a falling deviation under a constant high gain or more hands-on help is not progress."
    public const val MDC_NOTE: String = "Changes smaller than the minimal detectable change (MDC) are within measurement noise."
    public const val UNQUALIFIED_DEVICE: String = "UNQUALIFIED DEVICE: this phone has no roll calibration. Angles carry unverified error."
    public const val FIELD_CALIBRATED_DEVICE: String = "FIELD-CALIBRATED DEVICE: roll sign and mount were set in-app and cross-checked against the phone's own sensor fusion; no rotary-jig accuracy report exists for this phone model (REQ-SEN-040 pending)."
    public const val ABORTED_NOTE: String = "This session was aborted. Aborted sessions are always saved and shown so the trend is not biased toward good sessions."
    public const val CRASH_RECOVERED_NOTE: String = "Session log was recovered after an app crash; metrics were recomputed from the recovered raw data."
    public const val DEIDENTIFIED: String = "De-identified export: patient display ID only; no name, MRN or contact data included."
    public const val NOT_A_DIAGNOSIS: String = "This report does not constitute a diagnosis. Lateropulsion is a pre-clinical research prototype and not a certified medical device."
}
