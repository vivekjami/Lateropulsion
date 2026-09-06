package com.lateropulsion.core.model

import kotlinx.serialization.Serializable

/** Mirrors config/app.json (README §13). Defaults here are the shipped defaults. */
@Serializable
public data class AppConfig(
    val session: SessionConfig = SessionConfig(),
    val visual: VisualConfig = VisualConfig(),
    val metrics: MetricsConfig = MetricsConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val privacy: PrivacyConfig = PrivacyConfig(),
    val security: SecurityConfig = SecurityConfig(),
) {
    public fun validate(): List<String> = buildList {
        if (session.maxDurationMin !in 1..60) add("session.max_duration_min must be 1..60")
        if (session.mandatoryRestEveryS < 60) add("session.mandatory_rest_every_s must be >= 60")
        if (session.baselineCaptureS !in 20..180) add("session.baseline_capture_s must be 20..180")
        if (session.autoStartDelayS !in 0..60) add("session.auto_start_delay_s must be 0..60")
        if (visual.gainInitial !in GainLimits.MIN_ERROR_AUGMENTATION..GainLimits.MAX) add("visual.gain_initial out of range")
        if (visual.gainMin < GainLimits.MIN_ERROR_AUGMENTATION) add("visual.gain_min below error-augmentation floor")
        if (visual.gainFadeStep <= 0.0) add("visual.gain_fade_step must be positive")
        if (visual.tiltFadeStep !in 0.0..1.0) add("visual.tilt_fade_step must be 0..1")
        if (metrics.sampleRateHz < 50) add("metrics.sample_rate_hz must be >= 50")
        if (metrics.storeRateHz > metrics.sampleRateHz) add("metrics.store_rate_hz cannot exceed sample rate")
        if (metrics.lowpassCutoffHz <= 0.0 || metrics.lowpassCutoffHz >= metrics.storeRateHz / 2.0) add("metrics.lowpass_cutoff_hz must be below Nyquist")
        if (metrics.episodeExitDeg >= metrics.episodeEnterDeg) add("metrics.episode_exit_deg must be below episode_enter_deg")
        if (metrics.bandPrimaryDeg >= metrics.bandSecondaryDeg) add("metrics.band_primary_deg must be below band_secondary_deg")
        if (safety.motionToPhotonAbortMs < 30) add("safety.motion_to_photon_abort_ms unrealistically low")
        if (privacy.retentionDays < 1) add("privacy.retention_days must be positive")
        if (security.autoLockSeconds !in 60..3600) add("security.auto_lock_seconds must be 60..3600")
    }
}

@Serializable
public data class SessionConfig(
    val maxDurationMin: Int = 20,
    val defaultBlockDurationS: Int = 120,
    val mandatoryRestEveryS: Int = 300,
    val mandatoryRestS: Int = 60,
    val autoStopOnSsqFlag: Boolean = true,
    val ssqFlagThresholdTotal: Double = 20.0,
    val ssqFlagsToLockModeA: Int = 2,
    val minValidSecondsForConfidence: Double = 60.0,
    /** Length of the baseline head-tilt capture. 60 s is the clinical spec; a site may shorten it for screening. */
    val baselineCaptureS: Int = 60,
    /**
     * Single-phone operation (ADR-020): once the patient view is open and the session is ready, the first block
     * (and each block after a completed rest) starts by itself after this countdown, so the operator can mount the
     * visor and step back. 0 disables the countdown; Volume Up starts immediately.
     */
    val autoStartDelayS: Int = 15,
)

@Serializable
public data class VisualConfig(
    val defaultMode: VisualMode = VisualMode.VERTICAL_REFERENCE,
    val gainInitial: Double = 0.6,
    val gainMin: Double = 0.0,
    val gainFadeRule: String = "PERFORMANCE_DRIVEN",
    val gainFadeStep: Double = 0.1,
    val gainFadeGateTib5: Double = 60.0,
    val allowErrorAugmentation: Boolean = false,
    val slewLimitDegPerS: Double = 30.0,
    val overscan: Double = 1.2,
    val predictionClampMs: Double = 50.0,
    /** Headset profile used when the site has not chosen one in Settings (ADR-019: the visor is the shipped kit). */
    val defaultHeadsetProfileId: String = "phone-visor-mono-v1",
    /** Lock exposure/white balance a few seconds after the camera starts. Off: the patient walks through changing light (ADR-022). */
    val lockExposure: Boolean = false,
    /**
     * Fraction of the entered baseline error removed from the applied picture tilt at each new session (ADR-022):
     * 0.15 → 100 %, 85 %, 70 %, … of the error, reaching 0 after seven sessions. The operator can override per session.
     */
    val tiltFadeStep: Double = 0.15,
)

@Serializable
public data class MetricsConfig(
    val sampleRateHz: Int = 100,
    val storeRateHz: Int = 50,
    val lowpassCutoffHz: Double = 5.0,
    val bandPrimaryDeg: Double = 5.0,
    val bandSecondaryDeg: Double = 10.0,
    val episodeEnterDeg: Double = 10.0,
    val episodeExitDeg: Double = 7.0,
    val episodeMinDurationS: Double = 1.0,
    val recoveryHoldS: Double = 0.5,
    val pitchGuardGz: Double = 0.85,
    val mdcDeg: Double = 2.0,
)

@Serializable
public data class SafetyConfig(
    val requireSupervisionAttestation: Boolean = true,
    val requireHarnessForStanding: Boolean = true,
    val walkingUnlockRequiresStandingPass: Boolean = true,
    val motionToPhotonAbortMs: Int = 60,
    val watchdogFrames: Int = 3,
    val minBatteryPct: Int = 20,
    val minFreeStorageMb: Int = 500,
    val mountShiftStepDeg: Double = 8.0,
    val mountShiftWindowMs: Int = 100,
    val imuDropoutMs: Int = 100,
    val cameraStallMs: Int = 200,
    val driftRecalibrateDegPerMin: Double = 0.5,
    val fusionDisagreementDeg: Double = 3.0,
    val modeBFirstExposureMaxGain: Double = 0.4,
    val modeBFirstExposureMaxBlockS: Int = 180,
)

@Serializable
public data class PrivacyConfig(
    val mediaCaptureDefault: String = "off",
    val retentionDays: Int = 1825,
    val mediaRetentionDays: Int = 365,
    val exportDeidentifiedByDefault: Boolean = true,
)

@Serializable
public data class SecurityConfig(
    val autoLockSeconds: Int = 1800,
    val pinMinLength: Int = 6,
    val maxFailedAttempts: Int = 5,
    val lockoutSeconds: Int = 300,
)
