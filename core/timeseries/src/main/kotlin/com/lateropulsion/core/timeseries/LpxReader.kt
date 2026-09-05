package com.lateropulsion.core.timeseries

import com.lateropulsion.core.common.Hashing
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

public enum class TrailerStatus { VALID, MISSING, HASH_MISMATCH, COUNT_MISMATCH }

public data class LpxFile(
    val header: LpxHeader,
    val records: List<LpxRecord>,
    val trailer: LpxTrailer?,
    val status: TrailerStatus,
    /** Trailing bytes that did not form a whole record (power loss mid-write). */
    val truncatedBytes: Int,
) {
    public val isCrashRecovered: Boolean get() = status != TrailerStatus.VALID
}

public object LpxReader {
    public fun read(file: File): LpxFile {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            require(len >= LpxFormat.HEADER_LEN) { "file too short for an lpx header" }
            val headerBytes = ByteArray(LpxFormat.HEADER_LEN)
            raf.readFully(headerBytes)
            val header = LpxHeader.decode(headerBytes)

            // Try to find a trailer at the end.
            var trailer: LpxTrailer? = null
            var bodyEnd = len
            if (len >= LpxFormat.HEADER_LEN + LpxFormat.TRAILER_LEN) {
                raf.seek(len - LpxFormat.TRAILER_LEN)
                val tb = ByteArray(LpxFormat.TRAILER_LEN)
                raf.readFully(tb)
                trailer = LpxTrailer.decode(tb)
                if (trailer != null) bodyEnd = len - LpxFormat.TRAILER_LEN
            }

            val bodyLen = (bodyEnd - LpxFormat.HEADER_LEN).toInt()
            val whole = bodyLen / LpxFormat.RECORD_LEN
            val truncated = bodyLen - whole * LpxFormat.RECORD_LEN
            val body = ByteArray(whole * LpxFormat.RECORD_LEN)
            raf.seek(LpxFormat.HEADER_LEN.toLong())
            raf.readFully(body)
            val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            val records = ArrayList<LpxRecord>(whole)
            repeat(whole) { records += LpxRecord.decodeFrom(buf) }

            val status = when {
                trailer == null -> TrailerStatus.MISSING
                trailer.sampleCount != whole.toLong() -> TrailerStatus.COUNT_MISMATCH
                !Hashing.sha256(body).contentEquals(trailer.sha256) -> TrailerStatus.HASH_MISMATCH
                else -> TrailerStatus.VALID
            }
            return LpxFile(header, records, trailer, status, truncated)
        }
    }
}

/**
 * Crash recovery (ARCHITECTURE §15): a log without a valid trailer is truncated to the last whole
 * record, re-hashed, given a CRASH_RECOVERED trailer and never discarded.
 */
public object LpxRecovery {
    public data class Result(val recovered: Boolean, val recordCount: Long, val truncatedBytes: Int, val previousStatus: TrailerStatus)

    public fun recover(file: File): Result {
        val parsed = LpxReader.read(file)
        if (parsed.status == TrailerStatus.VALID) return Result(false, parsed.records.size.toLong(), 0, parsed.status)
        val bodyBytes = parsed.records.size * LpxFormat.RECORD_LEN
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength((LpxFormat.HEADER_LEN + bodyBytes).toLong())
            val body = ByteArray(bodyBytes)
            raf.seek(LpxFormat.HEADER_LEN.toLong())
            raf.readFully(body)
            raf.seek(raf.length())
            raf.write(LpxTrailer(parsed.records.size.toLong(), Hashing.sha256(body), CloseReason.CRASH_RECOVERED).encode())
            raf.fd.sync()
        }
        return Result(true, parsed.records.size.toLong(), parsed.truncatedBytes, parsed.status)
    }
}
