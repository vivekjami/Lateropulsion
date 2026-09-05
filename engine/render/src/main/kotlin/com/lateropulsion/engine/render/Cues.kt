package com.lateropulsion.engine.render

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.sin

/**
 * Audio pan cue (ARCHITECTURE §7.4): a soft tone panned toward the tilt, louder with |θ|. Useful for
 * patients with visual neglect. Runs its own low-priority streaming thread.
 */
public class AudioPanCue(private val sampleRate: Int = 44_100, private val toneHz: Double = 440.0) {
    @Volatile private var pan = 0f
    @Volatile private var loud = 0f
    @Volatile private var running = false
    private var thread: Thread? = null

    public fun update(pan: Float, loudness: Float) { this.pan = pan.coerceIn(-1f, 1f); this.loud = loudness.coerceIn(0f, 1f) }

    public fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "lp-audio-cue").also { it.priority = Thread.NORM_PRIORITY; it.start() }
    }

    public fun stop() { running = false; thread?.join(500); thread = null }

    private fun loop() {
        val frames = sampleRate / 50 // 20 ms
        val buf = ShortArray(frames * 2)
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf, buf.size * 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        var phase = 0.0
        val step = 2 * PI * toneHz / sampleRate
        var curL = 0f; var curR = 0f
        while (running) {
            val p = pan; val l = loud * 0.35f
            val targetL = l * (1f - p) / 2f * 2f
            val targetR = l * (1f + p) / 2f * 2f
            for (i in 0 until frames) {
                curL += (targetL - curL) * 0.002f; curR += (targetR - curR) * 0.002f // click-free ramps
                val s = sin(phase)
                phase += step; if (phase > 2 * PI) phase -= 2 * PI
                buf[i * 2] = (s * curL * Short.MAX_VALUE).toInt().toShort()
                buf[i * 2 + 1] = (s * curR * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(buf, 0, buf.size)
        }
        track.stop(); track.release()
    }
}

/** Short pulse on band exit and on return. */
public class HapticCue(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    public fun pulseExit(): Unit = pulse(60)
    public fun pulseReturn(): Unit = pulse(25)

    private fun pulse(ms: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/**
 * Thermal status (ARCHITECTURE §15): MODERATE → reduce camera resolution, SEVERE or worse → abort with
 * PERF_DEGRADED. Exposed as a StateFlow of PowerManager.THERMAL_STATUS_* values.
 */
public class ThermalMonitor(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _status = MutableStateFlow(pm.currentThermalStatus)
    public val status: StateFlow<Int> get() = _status
    private val listener = PowerManager.OnThermalStatusChangedListener { _status.value = it }
    private val executor = Executor { it.run() }

    public fun start(): Unit = pm.addThermalStatusListener(executor, listener)
    public fun stop(): Unit = pm.removeThermalStatusListener(listener)

    public companion object {
        public fun shouldReduceResolution(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_MODERATE
        public fun shouldAbort(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_SEVERE
    }
}
