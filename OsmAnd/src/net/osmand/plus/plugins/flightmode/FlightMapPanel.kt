package net.osmand.plus.plugins.flightmode

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import net.osmand.core.android.MapRendererView
import net.osmand.plus.R
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.util.MapUtils

internal val LocalFlightMapBlend = staticCompositionLocalOf { FlightMapBlend(0f) {} }

internal data class FlightMapBlend(val fraction: Float, val change: (Float) -> Unit)

/** One renderer viewport, gesture surface and control layout for Map and Mixed. */
@Composable
internal fun FlightMapPanel(
    state: FlightUiState,
    mapView: OsmandMapTileView?,
    onExplore: () -> Unit,
    onBounds: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    showCone: Boolean = false,
    coneAspectRatio: Float = 1f,
    onFollow: (() -> Unit)? = null,
    controlsVisible: Boolean = true,
    onToggleControls: (() -> Unit)? = null,
    onLockCenter: (() -> Unit)? = null,
    onSetHeadingLocked: (Boolean) -> Unit = {},
    pickedPoints: List<FlightPickedPoint> = emptyList(),
    onMapPoint: ((Double, Double) -> Unit)? = null,
) {
    val host = LocalView.current
    val bounds by rememberUpdatedState(onBounds)
    var revision by remember { mutableIntStateOf(0) }
    val renderer = mapView?.mapRenderer
    val pendingFrame = remember { AtomicBoolean() }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val invalidateCamera =
        remember(mapView, host) {
            {
                if (pendingFrame.compareAndSet(false, true))
                    host.postOnAnimation {
                        pendingFrame.set(false)
                        revision++
                    }
            }
        }
    val listener =
        remember(renderer, host) {
            object : MapRendererView.MapRendererViewListener {
                override fun onUpdateFrame(mapRenderer: MapRendererView) {
                    invalidateCamera()
                }

                override fun onFrameReady(mapRenderer: MapRendererView) {
                    invalidateCamera()
                }
            }
        }
    val zoomListener =
        remember(mapView) { OsmandMapTileView.MapZoomChangeListener { invalidateCamera() } }
    val viewportListener =
        remember(mapView) { OsmandMapTileView.ViewportListener { invalidateCamera() } }
    FlightVisibilityEffect(mapView, zoomListener, viewportListener) { visible ->
        if (visible) {
            mapView?.addMapZoomChangeListener(zoomListener)
            mapView?.addViewportListener(viewportListener)
            invalidateCamera()
        } else {
            mapView?.removeMapZoomChangeListener(zoomListener)
            mapView?.removeViewportListener(viewportListener)
        }
    }
    FlightVisibilityEffect(renderer, listener) { visible ->
        if (visible) renderer?.addListener(listener) else renderer?.removeListener(listener)
    }
    DisposableEffect(Unit) { onDispose { bounds(null) } }
    if (showCone && mapView != null) {
        val coneLayer = remember(mapView) { FlightViewConeLayer(host.context) }
        SideEffect {
            coneLayer.update(
                flightMapViewCone(
                    state.snapshot?.sample,
                    state.windowPlacement,
                    state.windowLook,
                    coneAspectRatio,
                )
            )
        }
        FlightVisibilityEffect(mapView, coneLayer) { visible ->
            if (visible) mapView.addLayer(coneLayer, FlightViewConeLayer.Z_ORDER)
            else mapView.removeLayer(coneLayer)
            mapView.refreshMap()
        }
    }
    val blend = LocalFlightMapBlend.current
    Box(
        modifier.clipToBounds().onGloballyPositioned { coordinates ->
            val origin = IntArray(2)
            host.getLocationOnScreen(origin)
            val offset = coordinates.positionInRoot()
            val left = origin[0] + offset.x.roundToInt()
            val top = origin[1] + offset.y.roundToInt()
            panelOrigin = Offset(left.toFloat(), top.toFloat())
            bounds(Rect(left, top, left + coordinates.size.width, top + coordinates.size.height))
        }
    ) {
        if (mapView != null)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { FlightMapGestureProxyView(it, mapView, onExplore) },
                update = {
                    it.update(mapView, onExplore)
                    it.onMapPoint = onMapPoint
                    it.lockedCenter = state.snapshot?.sample.takeIf { state.mapCenterLocked }
                    it.lockedBearingDegrees =
                        state.snapshot?.sample?.bearingDegrees.takeIf { state.mapHeadingLocked }
                },
            )
        Canvas(Modifier.fillMaxSize()) {
            // Read in the drawing phase: map frames invalidate markers without rebuilding controls.
            @Suppress("UNUSED_VARIABLE") val frame = revision
            val targetView = mapView?.view
            if (mapView != null && targetView != null) {
                val location = IntArray(2)
                targetView.getLocationOnScreen(location)
                val box = mapView.currentRotatedTileBox
                pickedPoints.forEach { point ->
                    val pixel =
                        NativeUtilities.getElevatedPixelFromLatLon(
                            renderer,
                            box,
                            point.latitude,
                            point.longitude,
                        )
                    val at =
                        Offset(
                            pixel.x + location[0] - panelOrigin.x,
                            pixel.y + location[1] - panelOrigin.y,
                        )
                    drawFlightPickedMarker(at, 1f)
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).background(Color(0x9018252D)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Suppress("UNUSED_VARIABLE") val frame = revision
            MapControl(
                R.drawable.ic_action_compass_north,
                stringResource(R.string.flight_mode_north_up),
                rotation = mapView?.rotate ?: 0f,
                label = "%03d°".format(Math.floorMod((-(mapView?.rotate ?: 0f)).roundToInt(), 360)),
            ) {
                onSetHeadingLocked(false)
                mapView?.resetRotation()
            }
            val bearing = state.snapshot?.sample?.bearingDegrees?.takeIf { it.isFinite() }
            MapControl(
                R.drawable.ic_action_direction_arrow,
                stringResource(
                    if (state.mapHeadingLocked) R.string.flight_map_heading_unlock
                    else R.string.flight_map_heading_lock
                ),
                label = stringResource(R.string.flight_map_heading_short),
                enabled = bearing != null || state.mapHeadingLocked,
                active = state.mapHeadingLocked,
            ) {
                if (!state.mapHeadingLocked) mapView?.animatedDraggingThread?.stopAnimatingSync()
                onSetHeadingLocked(!state.mapHeadingLocked)
            }
            val topViewDescription = stringResource(R.string.flight_map_top_view)
            Box(
                Modifier.size(36.dp)
                    .semantics { contentDescription = topViewDescription }
                    .clickable(onClickLabel = topViewDescription) {
                        mapView?.setElevationAngle(90f)
                        mapView?.refreshMap()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (renderer != null) "GL" else "V1", color = Color.White, fontSize = 9.sp)
                    Text(
                        "${(mapView?.elevationAngle ?: 90f).roundToInt()}°",
                        color = Color.White,
                        fontSize = 8.sp,
                    )
                }
            }
            onLockCenter?.let { lock ->
                MapControl(
                    if (state.mapCenterLocked) R.drawable.ic_action_lock
                    else R.drawable.ic_action_lock_open,
                    stringResource(
                        if (state.mapCenterLocked) R.string.flight_map_center_unlock
                        else R.string.flight_map_center_lock
                    ),
                    active = state.mapCenterLocked,
                    onClick = {
                        if (!state.mapCenterLocked)
                            mapView?.animatedDraggingThread?.stopAnimatingSync()
                        lock()
                    },
                )
            }
            onFollow?.let { follow ->
                MapControl(
                    R.drawable.ic_action_get_my_location,
                    stringResource(
                        if (state.mapFollowing) R.string.flight_map_follow_off
                        else R.string.flight_map_follow_on
                    ),
                    label = stringResource(R.string.flight_map_follow_short),
                    active = state.mapFollowing,
                    onClick = follow,
                )
            }
            onToggleControls?.let { toggle ->
                MapControl(
                    R.drawable.ic_action_arrow_down_16,
                    stringResource(
                        if (controlsVisible) R.string.flight_controls_hide
                        else R.string.flight_controls_show
                    ),
                    rotation = if (controlsVisible) 0f else 180f,
                    onClick = toggle,
                )
            }
        }
        FlightMapScales(
            mapView,
            revision,
            state.snapshot?.sample?.speedMetersPerSecond?.toDouble() ?: 0.0,
            Modifier.align(Alignment.BottomStart),
        )
        Row(
            Modifier.align(Alignment.BottomEnd)
                .width(184.dp)
                .height(40.dp)
                .background(Color(0xDD18252D))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OSM", color = Color.White, fontSize = 10.sp)
            Slider(
                value = blend.fraction,
                onValueChange = blend.change,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.flight_map_blend, (blend.fraction * 100).roundToInt()),
                color = Color.White,
                fontSize = 10.sp,
            )
        }
    }
}

/** Camera revision is an explicit key, even when the flight sample and view identity stay fixed. */
@Composable
private fun FlightMapScales(
    mapView: OsmandMapTileView?,
    cameraRevision: Int,
    speed: Double,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val renderer = mapView?.mapRenderer
    val meters =
        remember(mapView, renderer, cameraRevision, density) {
            val box = mapView?.currentRotatedTileBox
            if (box != null) {
                val width = with(density) { 90.dp.toPx() }.roundToInt()
                val x = with(density) { 10.dp.toPx() }.roundToInt()
                val y = box.pixHeight - with(density) { 20.dp.toPx() }.roundToInt()
                val a = NativeUtilities.getLatLonFromPixel(renderer, box, x, y)
                val b = NativeUtilities.getLatLonFromPixel(renderer, box, x + width, y)
                if (a != null && b != null) MapUtils.getDistance(a, b) else 0.0
            } else 0.0
        }
    Column(modifier.padding(6.dp).background(Color(0x5018252D)).padding(4.dp)) {
        if (meters > 0 && meters.isFinite()) {
            val rounded = flightScaleStep(meters)
            FlightCompactScale(
                if (rounded >= 1000) "%.1f km".format(rounded / 1000) else "%.0f m".format(rounded),
                (rounded / meters).toFloat(),
            )
            if (speed > 0.5 && speed.isFinite()) {
                val visibleSeconds = meters / speed
                val seconds = flightTimeScaleStep(visibleSeconds)
                if (seconds > 0.0) {
                    val label = flightTimeScaleLabel(seconds)
                    val description = stringResource(R.string.flight_map_time_scale, label)
                    Column(Modifier.semantics { contentDescription = description }) {
                        FlightCompactScale("◷ $label", (seconds / visibleSeconds).toFloat())
                    }
                }
            }
        }
    }
}

@Composable
private fun MapControl(
    icon: Int,
    description: String,
    enabled: Boolean = true,
    active: Boolean = false,
    rotation: Float = 0f,
    label: String? = null,
    onClick: () -> Unit,
) {
    val tint = if (!enabled) Color.Gray else if (active) Color(0xFF75DFAB) else Color.White
    Box(
        Modifier.size(36.dp)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier =
                    Modifier.size(if (label == null) 21.dp else 17.dp).graphicsLayer {
                        rotationZ = rotation
                    },
            )
            if (label != null) Text(label, color = tint, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun FlightCompactScale(label: String, fraction: Float) {
    Text(label, color = Color.White, fontSize = 10.sp)
    Canvas(Modifier.width(90.dp).height(7.dp)) {
        val end = size.width * fraction.coerceIn(0f, 1f)
        drawLine(Color.White, Offset(0f, 2f), Offset(end, 2f), 2f)
        drawLine(Color.White, Offset(0f, 0f), Offset(0f, size.height), 2f)
        drawLine(Color.White, Offset(end, 0f), Offset(end, size.height), 2f)
    }
}
