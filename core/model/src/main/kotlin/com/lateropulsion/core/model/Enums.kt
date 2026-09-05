package com.lateropulsion.core.model

import kotlinx.serialization.Serializable

@Serializable public enum class Sex { FEMALE, MALE, OTHER, UNSPECIFIED }

@Serializable public enum class Side { LEFT, RIGHT }

@Serializable public enum class LesionSide { LEFT, RIGHT, BILATERAL, BRAINSTEM, UNKNOWN }

@Serializable public enum class Severity { MINIMAL, MILD, MODERATE, SEVERE, VERY_SEVERE }

@Serializable public enum class SittingBalance { UNABLE, SUPPORTED_ONLY, UNSUPPORTED_STATIC, UNSUPPORTED_DYNAMIC }

@Serializable public enum class StandingBalance { UNABLE, TWO_PERSON, ONE_PERSON, SUPERVISED, INDEPENDENT }

@Serializable public enum class WalkingAbility { NON_AMBULANT, ASSISTED_TWO, ASSISTED_ONE, SUPERVISED, INDEPENDENT }

/** 0 = independent … 3 = two-person assistance. Therapist-entered, printed on every report. */
@Serializable public enum class AssistanceLevel(public val level: Int) {
    INDEPENDENT(0), SUPERVISION(1), ONE_PERSON(2), TWO_PERSON(3);

    public companion object {
        public fun fromLevel(level: Int): AssistanceLevel = entries.first { it.level == level }
    }
}

@Serializable public enum class MidlineAwareness { ABSENT, PARTIAL, PRESENT }

@Serializable public enum class CorrectionAbility { RESISTS_CORRECTION, TOLERATES_PASSIVE, ACTIVE_WITH_CUE, ACTIVE_INDEPENDENT }

@Serializable public enum class FallRisk { LOW, MODERATE, HIGH }

@Serializable public enum class BodyPosition {
    SUPPORTED_SITTING, SITTING_UNSUPPORTED, STANDING, WALKING, ANY;

    /** Ordering used by progression gating: sitting before standing before walking (REQ-SAF-003). */
    public val stage: Int
        get() = when (this) {
            SUPPORTED_SITTING -> 0
            SITTING_UNSUPPORTED -> 1
            STANDING -> 2
            WALKING -> 3
            ANY -> 0
        }

    public val requiresHarness: Boolean get() = this == STANDING || this == WALKING
}

@Serializable public enum class VisualMode {
    /** Mode A — truthful passthrough with gravity-locked cues. Default. */
    VERTICAL_REFERENCE,
    /** Mode B — counter-rotated passthrough by k·θ. Experimental; fading schedule mandatory. */
    COMPENSATED_VIEW,
}

@Serializable public enum class ExerciseType {
    MIDLINE_TRAINING, SITTING_HOLD, WEIGHT_SHIFT, STANDING_HOLD, REACH_TO_TARGET,
    WALKING_TO_TARGET, FUNCTIONAL_TASK, SVV_TEST, BASELINE_CAPTURE,
}

@Serializable public enum class CueType {
    PLUMB_LINE, HORIZON, TOLERANCE_BAND, TARGET, DEVIATION_READOUT, PROGRESS_RING, AUDIO_PAN, HAPTIC,
}

@Serializable public enum class EndReason { COMPLETED, ABORTED, ERROR, CRASH_RECOVERED }

@Serializable public enum class BlockEndReason { COMPLETED, ABORTED, ENDED_EARLY, STOP_RULE, ERROR }

@Serializable public enum class SessionEventType {
    SESSION_START, SESSION_END, BLOCK_START, BLOCK_END, REST_START, REST_END, PAUSE, RESUME,
    CHECKPOINT, ABORT, STOP_RULE, PERF_DEGRADED, TRACKING_LOST, TRACKING_RECOVERED, MOUNT_SHIFT,
    FUSION_DISAGREEMENT, PITCH_OUT_OF_RANGE, THERAPIST_MARK, BALANCE_LOSS, GAIN_CHANGE, OVERRIDE,
    CALIBRATION, THERMAL, BATTERY, CAMERA_STALL, CRASH_RECOVERED, IDENTITY_CONFIRMED,
}

@Serializable public enum class AuditAction { VIEW, CREATE, EDIT, EXPORT, DELETE, ERASE, OVERRIDE, LOGIN, LOGOUT, LOCK, UNLOCK, CALIBRATE }

@Serializable public enum class MediaType { VIDEO, SCREENSHOT }

@Serializable public enum class ClinicianRole { PHYSIOTHERAPIST, NURSE, PHYSICIAN, RESEARCHER, ADMIN }

@Serializable public enum class ScoringRule { SUM, MEAN, SSQ_WEIGHTED }
