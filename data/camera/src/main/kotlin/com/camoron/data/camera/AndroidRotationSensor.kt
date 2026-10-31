package com.camoron.data.camera

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.camoron.domain.model.GeoPoint
import com.camoron.domain.repository.RotationSensor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2

/**
 * Heading from the gyroscope-based game rotation vector, which turns smoothly and isn't thrown off
 * by nearby metal the way the compass is (falling back to the full rotation vector where missing);
 * or, for Sky view, from the full rotation vector, which is tied to north.
 */
@Singleton
internal class AndroidRotationSensor @Inject constructor(
    @ApplicationContext context: Context,
) : RotationSensor, SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val compass: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val matrix = FloatArray(9)

    @Volatile
    private var latest: FloatArray? = null

    @Volatile
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    /** The rotation vector's own estimate of its heading error, in radians; NaN where not given. */
    @Volatile
    private var headingError = Float.NaN

    override fun start(compass: Boolean): Boolean {
        val sensor = (if (compass) this.compass else gyro ?: this.compass) ?: return false
        manager.unregisterListener(this)
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

    override fun orientation(): FloatArray? = latest

    override fun compassUnreliable(): Boolean =
        accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
            headingError > MAX_HEADING_ERROR

    override fun declinationDegrees(point: GeoPoint, epochMillis: Long): Float =
        GeomagneticField(point.latitude.toFloat(), point.longitude.toFloat(), (point.altitude ?: 0.0).toFloat(), epochMillis).declination

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        latest = matrix.copyOf()
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) headingError = event.values.getOrElse(4) { Float.NaN }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) this.accuracy = accuracy
    }

    private companion object {
        /** About 25°: beyond this the labels would point at the wrong part of the sky. */
        const val MAX_HEADING_ERROR = 0.44f
    }
}
