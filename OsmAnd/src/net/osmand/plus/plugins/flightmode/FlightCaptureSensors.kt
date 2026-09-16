package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/** Sensors belong to the camera modal; keep real sample timestamps, never pretend simultaneity. */
internal class FlightCaptureSensors(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var magnetic: Pair<Long, List<Float>>? = null
    private var rotation: Pair<Long, List<Float>>? = null
    private var accuracy: Int? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        listOf(Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ROTATION_VECTOR).forEach { type ->
            manager.getDefaultSensor(type)?.let {
                manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    fun stop() {
        running = false
        manager.unregisterListener(this)
        magnetic = null
        rotation = null
        accuracy = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val values = event.values.toList()
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetic = event.timestamp to values
            accuracy = event.accuracy
        } else if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR)
            rotation = event.timestamp to values
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun snapshot(fix: FlightSample?): FlightPhotoCapture {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        return FlightPhotoCapture(
            now,
            elapsed,
            fix,
            magnetic?.second,
            magnetic?.first,
            accuracy,
            rotation?.second,
            rotation?.first,
        )
    }
}
