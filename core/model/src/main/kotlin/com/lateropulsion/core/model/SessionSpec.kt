package com.lateropulsion.core.model

/**
 * Everything fixed for one session before the first block starts. Immutable, so the runtime,
 * the log writer and the report all describe the same session.
 */
public data class SessionSpec(
    val sessionId: SessionId,
    val patientId: PatientId,
    val patientDisplayId: String,
    val clinicianId: ClinicianId,
    val protocol: ProtocolSpec,
    val visualMode: VisualMode,
    val gain: Double,
    val thetaRefDeg: Double,
    val thetaRefSetBy: ClinicianId,
    val baselineId: BaselineId?,
    val deviceProfile: DeviceProfile,
    val headsetProfile: HeadsetProfile,
    val sessionNumber: Int,
    val advancedProtocol: Boolean,
    val config: AppConfig,
    val position: BodyPosition = protocol.positionRequired,
    val overrideReason: String? = null,
    /**
     * The patient's baseline tilt error as entered in the disease details (degrees, + right; ADR-021). The primary
     * input of every session: the camera picture is tilted to counter it. The sensor only follows head sway on top.
     */
    val baselineErrorDeg: Double = 0.0,
    /** Tilt actually applied to the picture in this session (degrees, + right): the error faded per session, or the operator's choice (ADR-022). */
    val appliedTiltDeg: Double = baselineErrorDeg,
)

/** Pre-session checklist state (REQ-SAF-001, REQ-SAF-002). Every item must be true to start. */
public data class PreSessionChecklist(
    val supervisionAttested: Boolean = false,
    val harnessConfirmed: Boolean = false,
    val hygieneConfirmed: Boolean = false,
    val batteryOk: Boolean = false,
    val thermalOk: Boolean = false,
    val storageOk: Boolean = false,
    val contraindicationsRechecked: Boolean = false,
    val identityConfirmed: Boolean = false,
    val deviceQualified: Boolean = false,
) {
    public fun missing(position: BodyPosition, requireHarness: Boolean): List<String> = buildList {
        if (!supervisionAttested) add("supervision")
        if (requireHarness && position.requiresHarness && !harnessConfirmed) add("harness")
        if (!hygieneConfirmed) add("hygiene")
        if (!batteryOk) add("battery")
        if (!thermalOk) add("thermal")
        if (!storageOk) add("storage")
        if (!contraindicationsRechecked) add("contraindications")
        if (!identityConfirmed) add("identity")
        if (!deviceQualified) add("device_qualification")
    }

    public fun isComplete(position: BodyPosition, requireHarness: Boolean): Boolean = missing(position, requireHarness).isEmpty()
}

/** Device self-check shown on the dashboard and re-run before every session (REQ-SAF-012). */
public data class DeviceSelfCheck(
    val cameraOk: Boolean,
    val imuOk: Boolean,
    val imuRateHz: Double,
    val batteryPct: Int,
    val thermalStatus: Int,
    val freeStorageMb: Long,
    val deviceProfileId: String?,
    val deviceQualification: DeviceQualification,
) {
    public val deviceQualified: Boolean get() = deviceQualification != DeviceQualification.NONE

    public fun passes(config: SafetyConfig): Boolean =
        cameraOk && imuOk && batteryPct >= config.minBatteryPct && thermalStatus <= THERMAL_MODERATE &&
            freeStorageMb >= config.minFreeStorageMb

    public companion object {
        /** PowerManager.THERMAL_STATUS_MODERATE. */
        public const val THERMAL_MODERATE: Int = 2
    }
}
