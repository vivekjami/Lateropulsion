package com.lateropulsion.core.timeseries

import com.lateropulsion.core.common.Hashing
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Append-only writer with an in-memory ring of records that the IO thread flushes every ~200 ms
 * (ARCHITECTURE §11.2). The running SHA-256 covers exactly the bytes written, so the trailer can be
 * produced on close without re-reading the file.
 *
 * Thread-safety: [append] may be called from the fusion/metrics thread, [flush]/[sync]/[close] from the
 * IO thread; the buffer swap is guarded by a lock held only for the memcpy.
 */
public class LpxWriter(
    public val file: File,
    header: LpxHeader,
    ringCapacityRecords: Int = 1024,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private val channel = raf.channel
    private val digest = Hashing.Sha256Stream()
    private val lock = Any()
    private var ring: ByteBuffer = LpxFormat.newBuffer(ringCapacityRecords * LpxFormat.RECORD_LEN)
    private var spare: ByteBuffer = LpxFormat.newBuffer(ringCapacityRecords * LpxFormat.RECORD_LEN)
    private var dropped = 0L
    @Volatile private var closed = false

    public var sampleCount: Long = 0L
        private set

    /** Records discarded because the ring was full and the IO thread had not flushed; should be zero. */
    public val droppedRecords: Long get() = dropped

    init {
        raf.setLength(0)
        val h = header.encode()
        channel.write(ByteBuffer.wrap(h))
    }

    public fun append(record: LpxRecord) {
        if (closed) return
        synchronized(lock) {
            if (ring.remaining() < LpxFormat.RECORD_LEN) {
                dropped++
                return
            }
            record.encodeInto(ring)
            sampleCount++
        }
    }

    /** Writes buffered records to disk. Returns bytes written. */
    public fun flush(): Int {
        if (closed) return 0
        val toWrite: ByteBuffer
        synchronized(lock) {
            if (ring.position() == 0) return 0
            toWrite = ring
            ring = spare
            spare = toWrite
        }
        toWrite.flip()
        val n = toWrite.remaining()
        digest.update(toWrite.array(), toWrite.position(), n)
        while (toWrite.hasRemaining()) channel.write(toWrite)
        toWrite.clear()
        return n
    }

    /** fsync; call on block boundaries and on session end. */
    public fun sync() {
        flush()
        channel.force(true)
    }

    /** Writes the trailer and closes. Idempotent. */
    public fun close(reason: CloseReason) {
        if (closed) return
        flush()
        val trailer = LpxTrailer(sampleCount, digest.digest(), reason)
        channel.write(ByteBuffer.wrap(trailer.encode()))
        channel.force(true)
        closed = true
        raf.close()
    }

    override fun close(): Unit = close(CloseReason.UNKNOWN)
}
