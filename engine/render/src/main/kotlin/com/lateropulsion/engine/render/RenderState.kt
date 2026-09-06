package com.lateropulsion.engine.render

import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.VisualMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable per-frame instructions from the session layer to the renderer. Replaced atomically at a
 * low rate; the render thread never blocks on it.
 */
public data class RenderState(
    val mode: VisualMode = VisualMode.VERTICAL_REFERENCE,
    val gain: Double = 0.0,
    val cues: Set<CueType> = emptySet(),
    val toleranceDeg: Double = 5.0,
    val bandPrimaryDeg: Double = 5.0,
    val bandSecondaryDeg: Double = 10.0,
    val targetDeg: Double = 0.0,
    val progress01: Double = 0.0,
    val showReadout: Boolean = false,
    val lateralShift: Double = 0.0,
    /** Cues hidden and correction off (rest, pause, pre-start). Passthrough stays truthful. */
    val idle: Boolean = true,
    val messageKey: String? = null,
    /** Seconds left before the next block starts by itself (ADR-020); 0 = no countdown shown. Drawn even when idle. */
    val countdownS: Int = 0,
    /**
     * Constant picture rotation (degrees, before the device's render-rotation sign) that counters the patient's entered
     * baseline error (ADR-021). Applied in every mode while a block runs; 0 when idle or neutral.
     */
    val staticOffsetDeg: Double = 0.0,
) {
    public companion object {
        public val NEUTRAL: RenderState = RenderState()
    }
}

public class RenderStateHolder {
    private val ref = AtomicReference(RenderState.NEUTRAL)
    public fun set(state: RenderState) { ref.set(state) }
    public fun get(): RenderState = ref.get()
    public fun update(block: (RenderState) -> RenderState) { while (true) { val cur = ref.get(); if (ref.compareAndSet(cur, block(cur))) return } }
}

/**
 * The abort path (ARCHITECTURE §1 driver 1, REQ-SAF-004). A single atomic flag checked first thing on
 * every frame; setting it forces neutral passthrough on the very next frame, bypassing the protocol layer.
 */
public class AbortController {
    private val neutral = AtomicBoolean(false)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
    @Volatile public var reason: String = ""
        private set

    public val isNeutral: Boolean get() = neutral.get()

    public fun abort(reason: String) {
        this.reason = reason
        if (neutral.compareAndSet(false, true)) listeners.forEach { it(reason) }
    }

    /** Only the session layer may clear, after the therapist acknowledges. */
    public fun reset() { neutral.set(false); reason = "" }

    public fun addListener(l: (String) -> Unit) { listeners += l }
}
