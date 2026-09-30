package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

    var points by mutableStateOf(emptyList<FlightPickedPoint>())
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
    }

    fun select(scope: CoroutineScope, query: () -> FlightPickedPoint?) {
        request?.cancel()
        request =
            scope.launch {
                val point = runInterruptible(Dispatchers.Default, block = query)
                missingTerrain = point == null
                if (point != null) points = points + point
            }
    }

    fun clear() {
        request?.cancel()
        points = emptyList()
        missingTerrain = false
    }
}

@Composable
internal fun FlightPointingControls(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.flight_pointing_mode),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(enabled, onEnabled)
        TextButton(onClick = onClear) { Text(stringResource(R.string.flight_pointing_clear)) }
    }
}

@Composable
internal fun FlightPointingStatus(missing: Boolean, ready: Boolean) {
    if (missing || !ready)
        Text(
            stringResource(
                if (missing) R.string.flight_pointing_no_terrain
                else R.string.flight_pointing_loading
            ),
            color = Color.LightGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
}

@Composable
internal fun FlightTerrainPointingOverlay(
    pointing: FlightPointingState,
    modifier: Modifier = Modifier,
) {
    val frame = pointing.frame
    val picker = pointing.terrain
    val points = pointing.points
    val opacities by
        produceState<Map<FlightPickedPoint, Float>>(emptyMap(), frame, picker, pointing.points) {
            if (frame != null && picker != null)
                value =
                    runInterruptible(Dispatchers.Default) {
                        points.associateWith { point ->
                            val local = picker.local(point)
                            picker.opacity(frame.eye, local)
                        }
                    }
            else value = emptyMap()
        }
    Canvas(modifier) {
        if (frame != null && picker != null)
            for (point in points) {
                frame.project(picker.local(point))?.let { (x, y) ->
                    drawFlightPickedMarker(
                        Offset((x * size.width).toFloat(), (y * size.height).toFloat()),
                        opacities[point] ?: 1f,
                    )
                }
            }
    }
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlightPickedMarker(
    at: Offset,
    opacity: Float,
) {
    drawContext.canvas.saveLayer(
        androidx.compose.ui.geometry.Rect(Offset.Zero, size),
        androidx.compose.ui.graphics.Paint().apply { alpha = opacity },
    )
    val radius = 8.dp.toPx()
    drawCircle(Color.Black, radius + 2.dp.toPx(), at, style = Stroke(3.dp.toPx()))
    drawCircle(Color.Yellow, radius, at, style = Stroke(2.dp.toPx()))
    drawLine(
        Color.Yellow,
        at - Offset(radius + 4.dp.toPx(), 0f),
        at + Offset(radius + 4.dp.toPx(), 0f),
        2.dp.toPx(),
    )
    drawLine(
        Color.Yellow,
        at - Offset(0f, radius + 4.dp.toPx()),
        at + Offset(0f, radius + 4.dp.toPx()),
        2.dp.toPx(),
    )
    drawContext.canvas.restore()
}
