package com.lateropulsion.core.timeseries

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

class LpxRoundTripTest {
    @TempDir lateinit var dir: File

    private val header = LpxHeader(
        sessionUuid = "123e4567-e89b-12d3-a456-426614174000", sampleRateHz = 50, t0UtcMs = 1_725_000_000_000L,
        t0MonoNs = 987_654_321L, deviceProfileId = "redmi-note-10s", thetaRefDeg = -2.25,
    )

    private fun record(i: Int) = LpxRecord(
        tDeltaMs = i * 20L, thetaRawDeg = 12.34 - i * 0.01, thetaFiltDeg = 12.0, gx = 0.1234, gy = 0.9876, gz = -0.05,
        wx = 0.5, wy = -0.25, wz = 1.75, flags = 0x11,
    )

    @Test
    fun `REQ-SES-030 header record and trailer round-trip exactly`() {
        val f = File(dir, "s.lpx")
        LpxWriter(f, header).use { w ->
            for (i in 0 until 500) w.append(record(i))
            w.sync()
            w.close(CloseReason.COMPLETED)
        }
        assertEquals((LpxFormat.HEADER_LEN + 500 * LpxFormat.RECORD_LEN + LpxFormat.TRAILER_LEN).toLong(), f.length())
        val r = LpxReader.read(f)
        assertEquals(header, r.header)
        assertEquals(TrailerStatus.VALID, r.status)
        assertEquals(500, r.records.size)
        assertEquals(CloseReason.COMPLETED, r.trailer!!.closeReason)
        assertEquals(record(7).copy(thetaRawDeg = 12.27), r.records[7])
        assertFalse(r.isCrashRecovered)
    }

    @Test
    fun `REQ-SES-031 file without trailer is recovered by truncating to the last whole record`() {
        val f = File(dir, "crash.lpx")
        val w = LpxWriter(f, header)
        for (i in 0 until 300) w.append(record(i))
        w.flush()
        // simulate power loss: append 7 garbage bytes and never write the trailer
        RandomAccessFile(f, "rw").use { raf -> raf.seek(raf.length()); raf.write(ByteArray(7) { 0x55 }) }

        val before = LpxReader.read(f)
        assertEquals(TrailerStatus.MISSING, before.status)
        assertEquals(7, before.truncatedBytes)

        val res = LpxRecovery.recover(f)
        assertTrue(res.recovered)
        assertEquals(300L, res.recordCount)
        val after = LpxReader.read(f)
        assertEquals(TrailerStatus.VALID, after.status)
        assertEquals(CloseReason.CRASH_RECOVERED, after.trailer!!.closeReason)
        assertEquals(300, after.records.size)
        assertFalse(LpxRecovery.recover(f).recovered) // idempotent
    }

    @Test
    fun `tampered body is detected by the trailer hash`() {
        val f = File(dir, "tamper.lpx")
        LpxWriter(f, header).use { w -> for (i in 0 until 50) w.append(record(i)); w.close(CloseReason.ABORTED) }
        RandomAccessFile(f, "rw").use { raf -> raf.seek((LpxFormat.HEADER_LEN + 10 * LpxFormat.RECORD_LEN + 4).toLong()); raf.write(0x7F) }
        assertEquals(TrailerStatus.HASH_MISMATCH, LpxReader.read(f).status)
    }

    @Test
    fun `ring overflow is counted not silently lost`() {
        val f = File(dir, "small.lpx")
        val w = LpxWriter(f, header, ringCapacityRecords = 10)
        for (i in 0 until 15) w.append(record(i))
        assertEquals(5L, w.droppedRecords)
        assertEquals(10L, w.sampleCount)
        w.close(CloseReason.COMPLETED)
        assertEquals(10, LpxReader.read(f).records.size)
    }

    @Test
    fun `angles beyond int16 range are clamped rather than wrapped`() {
        val f = File(dir, "clamp.lpx")
        LpxWriter(f, header).use { w -> w.append(record(0).copy(thetaRawDeg = 400.0)); w.close(CloseReason.COMPLETED) }
        assertEquals(327.67, LpxReader.read(f).records[0].thetaRawDeg, 1e-9)
    }
}
