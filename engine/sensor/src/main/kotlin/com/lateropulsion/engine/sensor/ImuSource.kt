package com.lateropulsion.engine.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/** What this phone can do; drives device qualification and graceful degradation (REQ-SAF-020). */
public data class ImuCapabilities(
    val hasGyro: Boolean,
    val hasAccel: Boolean,
    val hasGameRotationVector: Boolean,
    val gyroMaxRateHz: Double,
    val accelMaxRateHz: Double,
    val gyroName: String,
) {
    /** A gyroscope is required for measurement; without it the app runs for data entry only. */
    public val measurementCapable: Boolean get() = hasGyro && hasAccel

    public companion object {
        public fun probe(sm: SensorManager): ImuCapabilities {
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val grv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            fun rate(s: Sensor?): Double = if (s == null || s.minDelay <= 0) 0.0 else 1e6 / s.minDelay
            return ImuCapabilities(gyro != null, accel != null, grv != null, rate(gyro), rate(accel), gyro?.name ?: "none")
        }
    }
}

public interface ImuListener {
    public fun onGyro(tNs: Long, wx: Double, wy: Double, wz: Double)
    public fun onAccel(tNs: Long, ax: Double, ay: Double, az: Double)
    /** Vendor game-rotation-vector as a unit quaternion (x, y, z, w). */
    public fun onGameRotation(tNs: Long, x: Double, y: Double, z: Double, w: Double)
}

/**
 * Raw IMU acquisition on the caller-supplied fusion [Handler] at the fastest rate the device offers
 * (IMPLEMENTATION Phase 2). Timestamps are `SensorEvent.timestamp` (elapsedRealtimeNanos on API 29+).
 */
public class ImuSource(private val sensorManager: SensorManager) {
    private var listener: SensorEventListener? = null
    public val capabilities: ImuCapabilities = ImuCapabilities.probe(sensorManager)

    public fun start(handler: Handler, target: ImuListener, requestedRateHz: Int = 200): Boolean {
        if (!capabilities.measurementCapable) return false
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val grv = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> target.onGyro(e.timestamp, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                    Sensor.TYPE_ACCELEROMETER -> target.onAccel(e.timestamp, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        val w = if (e.values.size > 3) e.values[3].toDouble() else Math.sqrt((1.0 - e.values[0] * e.values[0] - e.values[1] * e.values[1] - e.values[2] * e.values[2]).coerceAtLeast(0.0))
                        target.onGameRotation(e.timestamp, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(), w)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val periodUs = (1_000_000 / requestedRateHz).coerceAtLeast(gyro.minDelay.coerceAtLeast(1))
        sensorManager.registerListener(l, gyro, periodUs, 0, handler)
        sensorManager.registerListener(l, accel, periodUs, 0, handler)
        if (grv != null) sensorManager.registerListener(l, grv, periodUs, 0, handler)
        listener = l
        return true
    }

    public fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
    }
}
