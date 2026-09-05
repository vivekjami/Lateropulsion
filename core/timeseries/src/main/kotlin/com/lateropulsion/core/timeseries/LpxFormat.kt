package com.lateropulsion.core.timeseries

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * `.lpx` append-only session log (ARCHITECTURE §11.1).
 *
 * ```
 * Header  256 B  "LPX1" | u16 version | u16 header_len | u16 record_len | u16 sample_rate_hz
 *                | 36 B session uuid | i64 t0_utc_ms | i64 t0_mono_ns | 32 B device_profile_id
 *                | u32 flags | f64 theta_ref_deg | reserved
 * Record   22 B  u32 t_delta_ms | i16 theta_raw | i16 theta_filt | i16 gx gy gz | i16 wx wy wz | u16 flags
 * Trailer  64 B  "LPXE" | u64 sample_count | 32 B sha256(records) | u16 close_reason | reserved
 * ```
 * Little-endian throughout. Angles in 0.01°, gravity in 1/10000, angular velocity in 0.01 rad/s.
 * (The architecture text says 20 B per record; the listed fields sum to 22 B, which is what is implemented.)
 */
public object LpxFormat {
    public const val MAGIC_HEADER: String = "LPX1"
    public const val MAGIC_TRAILER: String = "LPXE"
    public const val VERSION: Int = 1
    public const val HEADER_LEN: Int = 256
    public const val RECORD_LEN: Int = 22
    public const val TRAILER_LEN: Int = 64
    public const val UUID_LEN: Int = 36
    public const val DEVICE_ID_LEN: Int = 32

    public const val ANGLE_SCALE: Double = 100.0
    public const val GRAVITY_SCALE: Double = 10_000.0
    public const val OMEGA_SCALE: Double = 100.0

    public fun newBuffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    public fun clampI16(v: Double): Short = v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()

    internal fun putFixedAscii(buf: ByteBuffer, s: String, len: Int) {
        val bytes = s.toByteArray(StandardCharsets.US_ASCII)
        for (i in 0 until len) buf.put(if (i < bytes.size) bytes[i] else 0)
    }

    internal fun getFixedAscii(buf: ByteBuffer, len: Int): String {
        val bytes = ByteArray(len)
        buf.get(bytes)
        var end = bytes.indexOf(0)
        if (end < 0) end = len
        return String(bytes, 0, end, StandardCharsets.US_ASCII)
    }
}

public enum class CloseReason(public val code: Int) {
    COMPLETED(1), ABORTED(2), ERROR(3), CRASH_RECOVERED(4), UNKNOWN(0);

    public companion object {
        public fun fromCode(code: Int): CloseReason = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

public data class LpxHeader(
    val sessionUuid: String,
    val sampleRateHz: Int,
    val t0UtcMs: Long,
    val t0MonoNs: Long,
    val deviceProfileId: String,
    val flags: Int = 0,
    val thetaRefDeg: Double,
    val version: Int = LpxFormat.VERSION,
) {
    init {
        require(sessionUuid.length == LpxFormat.UUID_LEN) { "session uuid must be 36 chars" }
        require(deviceProfileId.length <= LpxFormat.DEVICE_ID_LEN) { "device profile id too long" }
        require(sampleRateHz in 1..65535)
    }

    public fun encode(): ByteArray {
        val b = LpxFormat.newBuffer(LpxFormat.HEADER_LEN)
        LpxFormat.putFixedAscii(b, LpxFormat.MAGIC_HEADER, 4)
        b.putShort(version.toShort())
        b.putShort(LpxFormat.HEADER_LEN.toShort())
        b.putShort(LpxFormat.RECORD_LEN.toShort())
        b.putShort(sampleRateHz.toShort())
        LpxFormat.putFixedAscii(b, sessionUuid, LpxFormat.UUID_LEN)
        b.putLong(t0UtcMs)
        b.putLong(t0MonoNs)
        LpxFormat.putFixedAscii(b, deviceProfileId, LpxFormat.DEVICE_ID_LEN)
        b.putInt(flags)
        b.putDouble(thetaRefDeg)
        return b.array()
    }

    public companion object {
        public fun decode(bytes: ByteArray): LpxHeader {
            require(bytes.size >= LpxFormat.HEADER_LEN) { "header truncated" }
            val b = ByteBuffer.wrap(bytes, 0, LpxFormat.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
            val magic = LpxFormat.getFixedAscii(b, 4)
            require(magic == LpxFormat.MAGIC_HEADER) { "not an lpx file (magic=$magic)" }
            val version = b.getShort().toInt() and 0xFFFF
            val headerLen = b.getShort().toInt() and 0xFFFF
            val recordLen = b.getShort().toInt() and 0xFFFF
            require(headerLen == LpxFormat.HEADER_LEN && recordLen == LpxFormat.RECORD_LEN) { "unsupported lpx layout" }
            val rate = b.getShort().toInt() and 0xFFFF
            val uuid = LpxFormat.getFixedAscii(b, LpxFormat.UUID_LEN)
            val t0Utc = b.getLong()
            val t0Mono = b.getLong()
            val dev = LpxFormat.getFixedAscii(b, LpxFormat.DEVICE_ID_LEN)
            val flags = b.getInt()
            val thetaRef = b.getDouble()
            return LpxHeader(uuid, rate, t0Utc, t0Mono, dev, flags, thetaRef, version)
        }
    }
}

/** Decoded record in engineering units. */
public data class LpxRecord(
    val tDeltaMs: Long,
    val thetaRawDeg: Double,
    val thetaFiltDeg: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val wx: Double,
    val wy: Double,
    val wz: Double,
    val flags: Int,
) {
    public fun encodeInto(b: ByteBuffer) {
        b.putInt(tDeltaMs.toInt())
        b.putShort(LpxFormat.clampI16(Math.rint(thetaRawDeg * LpxFormat.ANGLE_SCALE)))
        b.putShort(LpxFormat.clampI16(Math.rint(thetaFiltDeg * LpxFormat.ANGLE_SCALE)))
        b.putShort(LpxFormat.clampI16(Math.rint(gx * LpxFormat.GRAVITY_SCALE)))
        b.putShort(LpxFormat.clampI16(Math.rint(gy * LpxFormat.GRAVITY_SCALE)))
        b.putShort(LpxFormat.clampI16(Math.rint(gz * LpxFormat.GRAVITY_SCALE)))
        b.putShort(LpxFormat.clampI16(Math.rint(wx * LpxFormat.OMEGA_SCALE)))
        b.putShort(LpxFormat.clampI16(Math.rint(wy * LpxFormat.OMEGA_SCALE)))
        b.putShort(LpxFormat.clampI16(Math.rint(wz * LpxFormat.OMEGA_SCALE)))
        b.putShort(flags.toShort())
    }

    public companion object {
        public fun decodeFrom(b: ByteBuffer): LpxRecord = LpxRecord(
            tDeltaMs = b.getInt().toLong() and 0xFFFF_FFFFL,
            thetaRawDeg = b.getShort() / LpxFormat.ANGLE_SCALE,
            thetaFiltDeg = b.getShort() / LpxFormat.ANGLE_SCALE,
            gx = b.getShort() / LpxFormat.GRAVITY_SCALE,
            gy = b.getShort() / LpxFormat.GRAVITY_SCALE,
            gz = b.getShort() / LpxFormat.GRAVITY_SCALE,
            wx = b.getShort() / LpxFormat.OMEGA_SCALE,
            wy = b.getShort() / LpxFormat.OMEGA_SCALE,
            wz = b.getShort() / LpxFormat.OMEGA_SCALE,
            flags = b.getShort().toInt() and 0xFFFF,
        )
    }
}

public data class LpxTrailer(val sampleCount: Long, val sha256: ByteArray, val closeReason: CloseReason) {
    public fun encode(): ByteArray {
        val b = LpxFormat.newBuffer(LpxFormat.TRAILER_LEN)
        LpxFormat.putFixedAscii(b, LpxFormat.MAGIC_TRAILER, 4)
        b.putLong(sampleCount)
        b.put(sha256)
        b.putShort(closeReason.code.toShort())
        return b.array()
    }

    public companion object {
        public fun decode(bytes: ByteArray): LpxTrailer? {
            if (bytes.size < LpxFormat.TRAILER_LEN) return null
            val b = ByteBuffer.wrap(bytes, 0, LpxFormat.TRAILER_LEN).order(ByteOrder.LITTLE_ENDIAN)
            if (LpxFormat.getFixedAscii(b, 4) != LpxFormat.MAGIC_TRAILER) return null
            val count = b.getLong()
            val sha = ByteArray(32); b.get(sha)
            val reason = CloseReason.fromCode(b.getShort().toInt())
            return LpxTrailer(count, sha, reason)
        }
    }
}
