package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
    val geometry = scene?.let(::FlightPickingGeometryKey)
    val result by
        produceState<FlightTerrainPicker?>(null, geometry) {
            val previous = value
            value =
                if (scene == null) null
                else runInterruptible(Dispatchers.Default) { FlightTerrainPicker(scene, previous) }
        }
    // Texture changes reuse the index; a newly rendered shape must wait for its own index.
    return remember(scene, result) {
        result
            ?.takeIf { it.geometryKey == geometry }
            ?.let { if (it.scene === scene) it else FlightTerrainPicker(requireNotNull(scene), it) }
    }
}

internal class FlightPointingState {
    var terrain by mutableStateOf<FlightTerrainPicker?>(null)
    var frame by mutableStateOf<FlightPickingFrame?>(null)
        private set

    val selection = FlightPointingSelection()

    fun acceptFrame(next: FlightPickingFrame) {
        if (frame?.sameView(next) != true) frame = next
    }

    fun resetFrame() {
        frame = null
        terrain = null
        selection.reset()
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
internal fun FlightPointingStatus(
    state: FlightPointingSelectionState,
    modifier: Modifier = Modifier,
) {
    var showReady by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        showReady = state.phase == FlightPointingPhase.READY
        if (showReady) {
            delay(2000)
            showReady = false
        }
    }
    if (state.phase == FlightPointingPhase.READY && !showReady) return
    val working =
        state.phase in
            listOf(
                FlightPointingPhase.LOADING,
                FlightPointingPhase.PREPARING,
                FlightPointingPhase.SELECTING,
                FlightPointingPhase.WAITING_GROUND,
            )
    val text =
        stringResource(
            when (state.phase) {
                FlightPointingPhase.LOADING -> R.string.flight_pointing_loading
                FlightPointingPhase.PREPARING -> R.string.flight_pointing_preparing
                FlightPointingPhase.READY -> R.string.flight_pointing_ready
                FlightPointingPhase.SELECTING -> R.string.flight_pointing_selecting
                FlightPointingPhase.WAITING_GROUND -> R.string.flight_pointing_waiting_ground
                FlightPointingPhase.NO_INTERSECTION -> R.string.flight_pointing_no_terrain
            }
        )
    Row(
        modifier
            .widthIn(max = 260.dp)
            .background(Color(0xB018252D), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (working)
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = Color(0xFF9AD9FF),
            )
        Text(
            if (
                state.pendingClick &&
                    state.phase in
                        listOf(FlightPointingPhase.LOADING, FlightPointingPhase.PREPARING)
            )
                stringResource(R.string.flight_pointing_click_queued, text)
            else text,
            color =
                if (state.phase == FlightPointingPhase.READY) Color(0xFF9AD9FF)
                else Color.LightGray,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun FlightTerrainPointingOverlay(
    pointing: FlightPointingState,
    modifier: Modifier = Modifier,
) {
    val frame = pointing.frame
    val picker = pointing.terrain
    val selection by pointing.selection.state.collectAsState()
    val point = selection.point
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
