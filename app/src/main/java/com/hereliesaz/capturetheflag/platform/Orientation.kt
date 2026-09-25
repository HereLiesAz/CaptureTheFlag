package com.hereliesaz.capturetheflag.platform

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlin.math.asin
import kotlin.math.atan2

/**
 * The rotation-vector sensor, read as a camera pose: which way the back camera faces (true
 * north), how far above or below the horizon, and its roll. [start] on resume, [stop] on pause.
 */
class Orientation(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotation = FloatArray(9)
    @Volatile private var have = false

    fun start() {
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, e.values)
        have = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * The back camera looks along the device's −Z axis. Rotated into the world frame
     * (x east, y north, z up) that gives its compass heading and elevation. Heading is
     * magnetic from the sensor, corrected to true north with the local declination.
     */
    fun pose(fix: Location?): Pose? {
        if (!have) return null
        val r = rotation.copyOf()
        val east = -r[2]; val north = -r[5]; val up = -r[8]
        val magnetic = Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
        val declination = fix?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), it.time).declination } ?: 0f
        val azimuth = ((magnetic + declination) % 360 + 360) % 360
        val pitch = Math.toDegrees(asin(up.coerceIn(-1f, 1f).toDouble()))
        val roll = Math.toDegrees(atan2(r[6].toDouble(), r[7].toDouble()))
        return Pose(azimuth, pitch, roll, System.currentTimeMillis())
    }

    data class Pose(val azimuth: Double, val pitch: Double, val roll: Double, val at: Long)
}
