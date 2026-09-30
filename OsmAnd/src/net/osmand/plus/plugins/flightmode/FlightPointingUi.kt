package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import net.osmand.plus.R

@Composable
internal fun rememberTerrainPicker(scene: FlightTerrainScene?): FlightTerrainPicker? {
    val geometry = scene?.meshes?.map { it.vertices to it.indices }
    val result =
        produceState<FlightTerrainPicker?>(
                null,
                scene?.geometryGeneration,
                geometry,
                scene?.coordinateOriginLatitude,
                scene?.coordinateOriginLongitude,
            ) {
                value = null
                if (scene != null)
                    value = runInterruptible(Dispatchers.Default) { FlightTerrainPicker(scene) }
            }
            .value
    // Never combine a newly published camera/origin with the previous asynchronous index.
    return result?.takeIf {
        scene != null &&
            it.scene.geometryGeneration == scene.geometryGeneration &&
            it.scene.coordinateOriginLatitude == scene.coordinateOriginLatitude &&
            it.scene.coordinateOriginLongitude == scene.coordinateOriginLongitude &&
            it.scene.meshes.map { mesh -> mesh.vertices to mesh.indices } == geometry
    }
}

internal class FlightPointingState {
    var terrain by mutableStateOf<FlightTerrainPicker?>(null)
    var frame by mutableStateOf<FlightPickingFrame?>(null)
        private set

    var point by mutableStateOf<FlightPickedPoint?>(null)
        private set

    var missingTerrain by mutableStateOf(false)
        private set

    private var request: Job? = null

    fun acceptFrame(next: FlightPickingFrame) {
        if (frame?.sameView(next) != true) frame = next
    }

    fun resetFrame() {
        request?.cancel()
        frame = null
        terrain = null
        missingTerrain = false
    }

    fun select(scope: CoroutineScope, query: () -> FlightPickedPoint?) {
        request?.cancel()
        request =
            scope.launch {
                val selected = runInterruptible(Dispatchers.Default, block = query)
                missingTerrain = selected == null
                if (selected != null) point = selected
            }
    }
}

@Composable
internal fun FlightPointingButton(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconToggleButton(
        checked = enabled,
        onCheckedChange = onEnabled,
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_action_get_my_location),
            contentDescription = stringResource(R.string.flight_pointing_mode),
            tint = if (enabled) Color(0xFF9AD9FF) else Color(0xFFB1C2CF),
            modifier =
                Modifier.size(32.dp)
                    .background(
                        if (enabled) Color(0xD0284D65) else Color(0x8018252D),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(7.dp),
        )
    }
}

@Composable
internal fun FlightPointingStatus(missing: Boolean, ready: Boolean, modifier: Modifier = Modifier) {
    if (missing || !ready)
        Text(
            stringResource(
                if (missing) R.string.flight_pointing_no_terrain
                else R.string.flight_pointing_loading
            ),
            color = Color.LightGray,
            fontSize = 11.sp,
            modifier =
                modifier
                    .widthIn(max = 240.dp)
                    .background(Color(0xB018252D), RoundedCornerShape(8.dp))
                    .padding(8.dp),
        )
}

@Composable
internal fun FlightTerrainPointingOverlay(
    pointing: FlightPointingState,
    modifier: Modifier = Modifier,
) {
    val frame = pointing.frame
    val picker = pointing.terrain
    val point = pointing.point
    val visibility by
        produceState<Pair<FlightPickedPoint, Float>?>(null, frame, picker, point) {
            if (frame != null && picker != null && point != null)
                value =
                    runInterruptible(Dispatchers.Default) {
                        point to picker.opacity(frame.eye, picker.local(point))
                    }
            else value = null
        }
    Canvas(modifier) {
        if (frame != null && picker != null && point != null) {
            frame.project(picker.local(point))?.let { (x, y) ->
                drawFlightPickedMarker(
                    Offset((x * size.width).toFloat(), (y * size.height).toFloat()),
                    visibility?.takeIf { it.first == point }?.second ?: 1f,
                )
            }
        }
    }
}

/** A hollow screen-space ring keeps the selected feature visible in every pointing view. */
internal object FlightPickedMarkerStyle {
    const val RADIUS_DP = 3.5f
    const val OUTLINE_DP = 2.75f
    const val STROKE_DP = 1.25f
    const val COLOR_ARGB = 0xFFE3F5FF
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlightPickedMarker(
    at: Offset,
    opacity: Float,
) {
    drawContext.canvas.saveLayer(
        androidx.compose.ui.geometry.Rect(Offset.Zero, size),
        androidx.compose.ui.graphics.Paint().apply { alpha = opacity },
    )
    val radius = FlightPickedMarkerStyle.RADIUS_DP.dp.toPx()
    drawCircle(
        Color.Black,
        radius,
        at,
        style = Stroke(FlightPickedMarkerStyle.OUTLINE_DP.dp.toPx()),
    )
    drawCircle(
        Color(FlightPickedMarkerStyle.COLOR_ARGB),
        radius,
        at,
        style = Stroke(FlightPickedMarkerStyle.STROKE_DP.dp.toPx()),
    )
    drawContext.canvas.restore()
}
