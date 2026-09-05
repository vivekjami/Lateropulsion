package com.lateropulsion.core.model

import kotlinx.serialization.Serializable

/**
 * Per phone model calibration (ARCHITECTURE §6.4). Produced by the rotary jig; the app refuses to run
 * a clinical session on a device whose profile is not [qualified] unless research mode is on (REQ-SAF-020).
 */
@Serializable
public data class DeviceProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    /** Regex matched against Build.MODEL; several SKUs may share one profile. */
    val modelPattern: String,
    /** +1 or -1 from calibration; 0 = unresolved, which blocks session start. */
    val rollSign: Int,
    val thetaMountDeg: Double,
    val scaleError: Double = 1.0,
    val residualRmsDeg: Double? = null,
    val maxErrorDeg: Double? = null,
    val dynamicRmsDeg1Hz: Double? = null,
    val driftDegPerMin: Double? = null,
    val imuRateHz: Double? = null,
    val cameraMaxFps: Int? = null,
    val displayHz: Int? = null,
    val motionToPhotonMs: Double? = null,
    val qualified: Boolean = false,
    val qualifiedAt: String? = null,
    val qualifiedBy: String? = null,
    val notes: String = "",
) {
    public val signResolved: Boolean get() = rollSign == 1 || rollSign == -1

    /** Qualification thresholds from ARCHITECTURE §16. */
    public fun meetsAccuracyTargets(): Boolean =
        signResolved &&
            (residualRmsDeg ?: Double.MAX_VALUE) <= ACCURACY_RMS_DEG &&
            (maxErrorDeg ?: Double.MAX_VALUE) <= ACCURACY_MAX_DEG &&
            (imuRateHz ?: 0.0) >= MIN_POSE_RATE_HZ

    public companion object {
        public const val ACCURACY_RMS_DEG: Double = 1.0
        public const val ACCURACY_MAX_DEG: Double = 2.0
        public const val DYNAMIC_RMS_DEG: Double = 2.0
        public const val MIN_POSE_RATE_HZ: Double = 100.0
        public const val MAX_DRIFT_DEG_PER_MIN: Double = 0.5
        public const val TARGET_MOTION_TO_PHOTON_MS: Double = 45.0
    }
}

/** Optics of one headset model (ARCHITECTURE §7.1). Distortion coefficients are in normalised eye radius units. */
@Serializable
public data class HeadsetProfile(
    val id: String,
    val name: String,
    val ipdMm: Double = 63.0,
    val ipdMinMm: Double = 56.0,
    val ipdMaxMm: Double = 72.0,
    val lensSeparationMm: Double = 63.0,
    val screenToLensMm: Double = 40.0,
    val distortionK1: Double = 0.22,
    val distortionK2: Double = 0.24,
    val chromaticRedScale: Double = 0.996,
    val chromaticBlueScale: Double = 1.004,
    val fovDeg: Double = 90.0,
    val overscan: Double = 1.2,
    val eyeCenterOffsetY: Double = 0.0,
    val openBottom: Boolean = true,
    val notes: String = "",
) {
    public fun validate(): List<String> = buildList {
        if (ipdMm !in ipdMinMm..ipdMaxMm) add("ipd_mm outside headset range")
        if (fovDeg !in 40.0..120.0) add("fov_deg unrealistic")
        if (overscan < 1.0 || overscan > 1.5) add("overscan must be 1.0..1.5")
    }
}
