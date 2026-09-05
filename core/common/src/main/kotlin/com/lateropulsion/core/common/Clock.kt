package com.lateropulsion.core.common

/**
 * Two clocks, deliberately separate (REQ-SES-021):
 *  - [nowUtcMillis] is wall-clock time, recorded once per session for humans.
 *  - [monotonicNanos] never jumps and is the only clock used for timing inside a session.
 */
public interface Clock {
    public fun nowUtcMillis(): Long
    public fun monotonicNanos(): Long
}

/** JVM clock. Android modules substitute `SystemClock.elapsedRealtimeNanos` so sensor timestamps share a time base. */
public object JvmClock : Clock {
    override fun nowUtcMillis(): Long = System.currentTimeMillis()
    override fun monotonicNanos(): Long = System.nanoTime()
}

/** Deterministic clock for tests and simulation. */
public class ManualClock(
    private var utcMillis: Long = 1_700_000_000_000L,
    private var nanos: Long = 0L,
) : Clock {
    override fun nowUtcMillis(): Long = utcMillis
    override fun monotonicNanos(): Long = nanos

    public fun advanceMillis(ms: Long) {
        utcMillis += ms
        nanos += ms * NANOS_PER_MILLI
    }

    public fun advanceNanos(ns: Long) {
        nanos += ns
        utcMillis += ns / NANOS_PER_MILLI
    }

    /** Simulates a wall-clock change (user edits the time) without touching the monotonic clock. */
    public fun jumpWallClock(deltaMs: Long) {
        utcMillis += deltaMs
    }

    public companion object {
        public const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}

public object TimeUnits {
    public const val NANOS_PER_SECOND: Double = 1e9
    public const val NANOS_PER_MILLI: Long = 1_000_000L
    public const val MILLIS_PER_SECOND: Long = 1_000L
    public fun nanosToSeconds(ns: Long): Double = ns / NANOS_PER_SECOND
    public fun secondsToNanos(s: Double): Long = (s * NANOS_PER_SECOND).toLong()
}
