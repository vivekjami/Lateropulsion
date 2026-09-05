package com.lateropulsion.engine.sensor

import com.lateropulsion.core.model.MutablePose
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free single-writer / single-reader triple buffer (ARCHITECTURE §5). The writer fills the back
 * slot and publishes; the reader swaps the freshest slot to the front. Neither side ever blocks or
 * allocates, and the reader can never observe a partially written pose.
 */
public class PoseTripleBuffer {
    private val slots = Array(3) { MutablePose() }
    // bits 0-1 front, 2-3 middle, 4-5 back, bit 6 dirty
    private val state = AtomicInteger(encode(0, 1, 2, false))

    /** Writer: the slot to fill next. Only the writer thread may touch it until [publish]. */
    public fun backSlot(): MutablePose = slots[back(state.get())]

    /** Writer: makes the back slot the freshest. */
    public fun publish() {
        while (true) {
            val s = state.get()
            val next = encode(front(s), back(s), middle(s), true)
            if (state.compareAndSet(s, next)) return
        }
    }

    /** Reader: copies the freshest pose into [into]. Returns true if it is newer than the last read. */
    public fun read(into: MutablePose): Boolean {
        var fresh = false
        while (true) {
            val s = state.get()
            if (!dirty(s)) break
            val next = encode(middle(s), front(s), back(s), false)
            if (state.compareAndSet(s, next)) { fresh = true; break }
        }
        into.copyFrom(slots[front(state.get())])
        return fresh
    }

    private companion object {
        fun encode(front: Int, middle: Int, back: Int, dirty: Boolean) = front or (middle shl 2) or (back shl 4) or (if (dirty) 1 shl 6 else 0)
        fun front(s: Int) = s and 0x3
        fun middle(s: Int) = (s shr 2) and 0x3
        fun back(s: Int) = (s shr 4) and 0x3
        fun dirty(s: Int) = (s and (1 shl 6)) != 0
    }
}
