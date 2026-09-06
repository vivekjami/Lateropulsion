package com.lateropulsion.feature.protocol

import kotlin.math.abs
import kotlin.math.sign

/**
 * How much of the patient's entered baseline error the picture counters in a session (ADR-022). The first session
 * applies the whole error; every session after that removes [stepFraction] of the error from what the previous
 * session applied, so the patient is weaned toward the true world. An operator override in one session carries
 * forward. Re-entering the error never makes the tilt exceed it.
 */
public object TiltScheduler {
    public fun proposeNext(errorDeg: Double, history: List<SessionHistoryEntry>, stepFraction: Double): Double {
        val magnitude = abs(errorDeg)
        if (magnitude == 0.0) return 0.0
        val last = history.lastOrNull() ?: return errorDeg
        val next = (abs(last.appliedTiltDeg) - stepFraction.coerceIn(0.0, 1.0) * magnitude).coerceIn(0.0, magnitude)
        return sign(errorDeg) * next
    }

    /** Session index → fraction of the error, for the operator's information: 1.0, 1 − step, 1 − 2·step, … */
    public fun fractionForSession(sessionNumber: Int, stepFraction: Double): Double = (1.0 - (sessionNumber - 1) * stepFraction).coerceIn(0.0, 1.0)
}
