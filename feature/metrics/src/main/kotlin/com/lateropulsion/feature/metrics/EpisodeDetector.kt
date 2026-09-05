package com.lateropulsion.feature.metrics

import com.lateropulsion.core.model.Episode
import com.lateropulsion.core.model.EpisodeStats
import com.lateropulsion.core.model.Side
import kotlin.math.abs

/**
 * Hysteresis episode detector (ARCHITECTURE §8.3).
 *
 * ```
 * InBand --|d|>enter--> Candidate --sustained>=minDuration--> Episode --|d|<exit--> Recovering
 * Candidate --|d|<exit before minDuration--> InBand
 * Recovering --|d|>enter--> Episode
 * Recovering --|d|<=inBand held for holdS--> InBand   (recovery time = t - onset)
 * ```
 * Invalid samples do not advance the machine but mark the current episode `partial`.
 * A block ending mid-episode finalises it as `partial`; a block ending mid-candidate discards it.
 */
public class EpisodeDetector(
    private val enterDeg: Double = 10.0,
    private val exitDeg: Double = 7.0,
    private val minDurationS: Double = 1.0,
    private val inBandDeg: Double = 5.0,
    private val holdS: Double = 0.5,
    private val targetDeg: Double = 0.0,
) {
    init {
        require(exitDeg < enterDeg) { "exit must be below enter" }
        require(inBandDeg <= exitDeg) { "inBand must not exceed exit" }
    }

    public enum class State { IN_BAND, CANDIDATE, EPISODE, RECOVERING }

    public var state: State = State.IN_BAND
        private set

    private val episodes = ArrayList<Episode>()
    private var onsetS = 0.0
    private var peak = 0.0
    private var direction = Side.RIGHT
    private var exitS: Double? = null
    private var holdStartS: Double? = null
    private var sawInvalid = false
    private var lastT = 0.0

    public val completed: List<Episode> get() = episodes

    /** Count including the one in progress, for live display and abort conditions. */
    public val liveCount: Int get() = episodes.size + if (state == State.EPISODE || state == State.RECOVERING) 1 else 0

    public fun feed(tS: Double, thetaDeg: Double, valid: Boolean) {
        lastT = tS
        if (!valid) {
            if (state != State.IN_BAND) sawInvalid = true
            return
        }
        val d = thetaDeg - targetDeg
        val ad = abs(d)
        when (state) {
            State.IN_BAND -> if (ad > enterDeg) {
                state = State.CANDIDATE
                onsetS = tS; peak = ad; direction = if (d >= 0) Side.RIGHT else Side.LEFT
                exitS = null; holdStartS = null; sawInvalid = false
            }
            State.CANDIDATE -> {
                if (ad > peak) peak = ad
                if (ad < exitDeg) {
                    state = State.IN_BAND
                } else if (tS - onsetS >= minDurationS) {
                    state = State.EPISODE
                }
            }
            State.EPISODE -> {
                if (ad > peak) peak = ad
                if (ad < exitDeg) {
                    state = State.RECOVERING
                    exitS = tS
                    holdStartS = if (ad <= inBandDeg) tS else null
                }
            }
            State.RECOVERING -> {
                if (ad > peak) peak = ad
                if (ad > enterDeg) {
                    state = State.EPISODE
                    exitS = null; holdStartS = null
                } else if (ad <= inBandDeg) {
                    val hs = holdStartS ?: tS.also { holdStartS = it }
                    if (tS - hs >= holdS) {
                        episodes += Episode(onsetS, exitS, peak, sawInvalid, tS - onsetS, direction)
                        state = State.IN_BAND
                    }
                } else {
                    holdStartS = null
                }
            }
        }
    }

    /** Call at block end. */
    public fun finish(): List<Episode> {
        when (state) {
            State.EPISODE -> episodes += Episode(onsetS, lastT, peak, partial = true, recoveryS = null, direction = direction)
            State.RECOVERING -> episodes += Episode(onsetS, exitS, peak, partial = true, recoveryS = null, direction = direction)
            State.CANDIDATE, State.IN_BAND -> Unit
        }
        state = State.IN_BAND
        return episodes
    }

    public fun reset() {
        episodes.clear(); state = State.IN_BAND; sawInvalid = false; exitS = null; holdStartS = null; peak = 0.0
    }

    public companion object {
        public fun stats(episodes: List<Episode>): EpisodeStats {
            if (episodes.isEmpty()) return EpisodeStats.NONE
            val complete = episodes.filter { !it.partial }
            val durations = complete.mapNotNull { it.durationS }
            val recoveries = complete.mapNotNull { it.recoveryS }
            return EpisodeStats(
                count = episodes.size,
                partialCount = episodes.count { it.partial },
                meanDurationS = if (durations.isEmpty()) Double.NaN else durations.average(),
                maxDurationS = if (durations.isEmpty()) Double.NaN else durations.max(),
                recoveryMeanS = if (recoveries.isEmpty()) Double.NaN else recoveries.average(),
                recoveryMaxS = if (recoveries.isEmpty()) Double.NaN else recoveries.max(),
            )
        }
    }
}
