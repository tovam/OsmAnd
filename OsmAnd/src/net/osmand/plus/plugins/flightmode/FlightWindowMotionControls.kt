package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import net.osmand.plus.R
import kotlin.math.atan2
import kotlin.math.hypot

private class FlightWindowMotionSensor(context: Context, private val look: () -> FlightWindowLook, private val bearing: () -> Float?, private val move: (Float, Float) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val available get() = sensor != null
    private val orientation = FlightPhoneAimController()
    private val matrix = FloatArray(9)
    private var lastEvent = 0L
    private var running = false

    fun align() = orientation.reset(look(), bearing())
    fun start() {
        if (running) return
        align()
        running = sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } == true
    }
    fun stop() { running = false; manager.unregisterListener(this); align() }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.timestamp - lastEvent < 50_000_000L) return
        lastEvent = event.timestamp
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // Camera optical axis is the phone's -Z axis; it is invariant to screen rotation.
        val east = -matrix[2]
        val north = -matrix[5]
        val up = -matrix[8]
        if (hypot(east, north) < 0.001f) return
        val yaw = Math.toDegrees(atan2(east, north).toDouble()).toFloat()
        val pitch = Math.toDegrees(atan2(up, hypot(east, north)).toDouble()).toFloat()
        orientation.update(yaw, pitch, look(), bearing())?.let { move(it.first, it.second) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

@Composable
internal fun FlightWindowMotionControls(look: FlightWindowLook, bearing: Float?, enabled: Boolean, onEnabled: (Boolean) -> Unit, onMove: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val action by rememberUpdatedState(onMove)
    val currentLook by rememberUpdatedState(look)
    val currentBearing by rememberUpdatedState(bearing)
    val sensor = remember(context) { FlightWindowMotionSensor(context, { currentLook }, { currentBearing }) { yaw, pitch -> action(yaw, pitch) } }
    FlightVisibilityEffect(sensor, enabled) { visible -> if (visible && enabled) sensor.start() else sensor.stop() }
    DisposableEffect(sensor) { onDispose { sensor.stop() } }
    Row(modifier) {
        TextButton(onClick = { onEnabled(!enabled) }, enabled = sensor.available) {
            Text(stringResource(if (sensor.available) R.string.flight_window_motion else R.string.flight_window_motion_unavailable),
                fontSize = 11.sp, color = if (enabled) Color(0xFF7BE0A3) else Color.LightGray)
        }
        if (enabled) TextButton(onClick = sensor::align) {
            Text(stringResource(R.string.flight_window_motion_align), fontSize = 11.sp)
        }
    }
}
