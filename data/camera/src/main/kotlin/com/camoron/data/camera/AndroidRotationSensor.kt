package com.camoron.data.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.camoron.domain.repository.RotationSensor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2

/**
 * Heading from the gyroscope-based game rotation vector, which turns smoothly and isn't thrown off
 * by nearby metal the way the compass is (falling back to the full rotation vector where missing).
 */
@Singleton
internal class AndroidRotationSensor @Inject constructor(
    @ApplicationContext context: Context,
) : RotationSensor, SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val matrix = FloatArray(9)

    @Volatile
    private var latest: FloatArray? = null

    override fun start(): Boolean {
        val sensor = sensor ?: return false
        latest = null
        return manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        manager.unregisterListener(this)
    }

    override fun cameraYaw(front: Boolean): Float? {
        val m = latest ?: return null
        // The matrix turns phone axes into east/north/up. The back camera looks along the phone's −Z
        // (its third column, negated), the front one along +Z; the heading of that direction holds
        // whether the phone is upright or sideways.
        val sign = if (front) 1f else -1f
        return atan2(sign * m[2], sign * m[5])
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        latest = matrix.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
