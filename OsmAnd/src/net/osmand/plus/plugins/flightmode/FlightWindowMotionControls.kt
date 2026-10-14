package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
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

private class FlightWindowMotionSensor(
    context: Context,
    private val look: () -> FlightWindowLook,
    private val placement: () -> FlightWindowPlacement,
    private val bearing: () -> Float?,
    private val aim: (FlightWindowLook) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    // A magnetic compass inside a cabin can change heading while the phone is motionless.
    val available
        get() = sensor != null

    private val orientation = FlightPhoneAimController()
    private val matrix = FloatArray(9)
    private val screenMatrix = FloatArray(9)
    private val display =
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    private var screenRotation = -1
    private var lastEvent = 0L
    private var running = false

    fun align() {
        val current = look()
        val course = bearing()
        orientation.reset(
            current,
            course,
            placement().viewAzimuthDegrees(course ?: 0f, current),
            Math.toDegrees(placement().geometry().elevationRadians.toDouble()).toFloat() +
                current.pitchDegrees,
        )
    }

    fun start() {
        if (running) return
        align()
        lastEvent = 0L
        screenRotation = -1
        running =
            sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ==
                true
    }

    fun stop() {
        running = false
        manager.unregisterListener(this)
        align()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.timestamp - lastEvent < 50_000_000L) return
        lastEvent = event.timestamp
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val rotation = display.rotation
        if (rotation != screenRotation) {
            align()
            screenRotation = rotation
        }
        val axes =
            when (rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
        if (SensorManager.remapCoordinateSystem(matrix, axes.first, axes.second, screenMatrix))
            orientation.update(screenMatrix, event.timestamp, bearing())?.let(aim)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

@Composable
internal fun FlightWindowMotionControls(
    look: FlightWindowLook,
    placement: FlightWindowPlacement,
    bearing: Float?,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onAim: (FlightWindowLook) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val action by rememberUpdatedState(onAim)
    val currentLook by rememberUpdatedState(look)
    val currentPlacement by rememberUpdatedState(placement)
    val currentBearing by rememberUpdatedState(bearing)
    val sensor =
        remember(context) {
            FlightWindowMotionSensor(
                context,
                { currentLook },
                { currentPlacement },
                { currentBearing },
            ) {
                action(it)
            }
        }
    FlightVisibilityEffect(sensor, enabled) { visible ->
        if (visible && enabled) sensor.start() else sensor.stop()
    }
    DisposableEffect(sensor) { onDispose { sensor.stop() } }
    Row(modifier) {
        TextButton(onClick = { onEnabled(!enabled) }, enabled = sensor.available) {
            Text(
                stringResource(
                    if (sensor.available) R.string.flight_window_motion
                    else R.string.flight_window_motion_unavailable
                ),
                fontSize = 11.sp,
                color = if (enabled) Color(0xFF7BE0A3) else Color.LightGray,
            )
        }
        if (enabled)
            TextButton(
                onClick = {
                    sensor.align()
                    Toast.makeText(
                            context,
                            R.string.flight_window_motion_reference,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            ) {
                Text(stringResource(R.string.flight_window_motion_align), fontSize = 11.sp)
            }
    }
}
