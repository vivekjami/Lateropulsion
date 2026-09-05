package com.lateropulsion.core.model

/** Bit flags carried by every pose sample and stored in the `.lpx` record. */
public object ValidityFlags {
    public const val VALID: Int = 1 shl 0
    public const val PITCH_OUT_OF_RANGE: Int = 1 shl 1
    public const val TRACKING_LOST: Int = 1 shl 2
    public const val MOUNT_SHIFT: Int = 1 shl 3
    public const val IN_BAND: Int = 1 shl 4
    public const val PREDICTED: Int = 1 shl 5
    public const val FUSION_DISAGREEMENT: Int = 1 shl 6
    public const val CALIBRATING: Int = 1 shl 7
    public const val PAUSED: Int = 1 shl 8

    private const val INVALIDATING = PITCH_OUT_OF_RANGE or TRACKING_LOST or MOUNT_SHIFT or CALIBRATING or PAUSED

    /** A sample contributes to midline metrics only when VALID is set and no invalidating flag is set. */
    public fun isValidForMetrics(flags: Int): Boolean = (flags and VALID) != 0 && (flags and INVALIDATING) == 0

    public fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0

    public fun describe(flags: Int): List<String> = buildList {
        if (has(flags, VALID)) add("VALID")
        if (has(flags, PITCH_OUT_OF_RANGE)) add("PITCH_OUT_OF_RANGE")
        if (has(flags, TRACKING_LOST)) add("TRACKING_LOST")
        if (has(flags, MOUNT_SHIFT)) add("MOUNT_SHIFT")
        if (has(flags, IN_BAND)) add("IN_BAND")
        if (has(flags, PREDICTED)) add("PREDICTED")
        if (has(flags, FUSION_DISAGREEMENT)) add("FUSION_DISAGREEMENT")
        if (has(flags, CALIBRATING)) add("CALIBRATING")
        if (has(flags, PAUSED)) add("PAUSED")
    }
}

@kotlinx.serialization.Serializable
public data class Vec3(val x: Double, val y: Double, val z: Double) {
    public companion object { public val ZERO: Vec3 = Vec3(0.0, 0.0, 0.0) }
}

/**
 * One fused pose. Immutable; the real-time path uses [MutablePose] and converts only at the
 * metrics/logging boundary.
 *
 * @property tNanos monotonic timestamp (elapsedRealtimeNanos on Android)
 * @property thetaHeadDeg roll of the head relative to gravity after device profile correction
 * @property thetaDeg deviation from the patient midline (thetaHead - thetaRef); positive = toward patient's right
 */
public data class PoseSample(
    val tNanos: Long,
    val qw: Double,
    val qx: Double,
    val qy: Double,
    val qz: Double,
    val thetaHeadDeg: Double,
    val thetaDeg: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val wx: Double,
    val wy: Double,
    val wz: Double,
    val flags: Int,
) {
    public val isValidForMetrics: Boolean get() = ValidityFlags.isValidForMetrics(flags)
}

/** Allocation-free carrier used by the fusion and render threads. */
public class MutablePose {
    public var tNanos: Long = 0L
    public var qw: Double = 1.0
    public var qx: Double = 0.0
    public var qy: Double = 0.0
    public var qz: Double = 0.0
    public var thetaHeadDeg: Double = 0.0
    public var thetaDeg: Double = 0.0
    public var gx: Double = 0.0
    public var gy: Double = 1.0
    public var gz: Double = 0.0
    public var wx: Double = 0.0
    public var wy: Double = 0.0
    public var wz: Double = 0.0
    public var flags: Int = 0

    public fun copyFrom(o: MutablePose) {
        tNanos = o.tNanos; qw = o.qw; qx = o.qx; qy = o.qy; qz = o.qz
        thetaHeadDeg = o.thetaHeadDeg; thetaDeg = o.thetaDeg
        gx = o.gx; gy = o.gy; gz = o.gz; wx = o.wx; wy = o.wy; wz = o.wz; flags = o.flags
    }

    public fun toSample(): PoseSample = PoseSample(tNanos, qw, qx, qy, qz, thetaHeadDeg, thetaDeg, gx, gy, gz, wx, wy, wz, flags)
}
