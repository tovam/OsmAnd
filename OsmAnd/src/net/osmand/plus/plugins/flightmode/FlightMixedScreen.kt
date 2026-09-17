package net.osmand.plus.plugins.flightmode

import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import net.osmand.plus.views.OsmandMapTileView
import kotlin.math.roundToInt

/** Both halves share one flight snapshot. Each owns a disjoint pointer surface. */
@Composable
internal fun FlightMixedScreen(
    state: FlightUiState,
    mapView: OsmandMapTileView?,
    onPage: (FlightPage) -> Unit,
    onMapExplore: () -> Unit,
    onMapBounds: (Rect?) -> Unit,
    onMoveLook: (Float, Float) -> Unit,
    onChangeZoom: (Float) -> Unit,
    onRecenterLook: () -> Unit,
    onRetry: () -> Unit,
    onRendererError: (String) -> Unit,
    onRenderStats: (FlightTerrainRenderStats) -> Unit,
) {
    val hostView = LocalView.current
    val latestBounds by rememberUpdatedState(onMapBounds)
    DisposableEffect(Unit) { onDispose { latestBounds(null) } }
    Column(Modifier.fillMaxSize()) {
        FlightWindowScene(
            placement = state.windowPlacement, look = state.windowLook, trip = state.trip,
            sample = state.snapshot?.sample, scene = state.terrainScene,
            terrainStatus = state.terrainStatus, rendererRecovery = state.terrainRendererRecovery,
            terrainRenderStats = state.terrainRenderStats, altitudeOverrideMeters = state.windowAltitudeOverrideMeters,
            shadingEnabled = state.plan.shadowsEnabled, shadowIntensity = state.plan.shadowIntensity,
            satelliteOpacity = state.satelliteOpacity, satelliteQuality = state.plan.satelliteQuality,
            showSatelliteQualityOverlay = state.showSatelliteQualityOverlay,
            photo = null, photoOverlay = FlightWindowPhotoOverlay(),
            onMoveLook = onMoveLook, onChangeZoom = onChangeZoom, onRecenterLook = onRecenterLook,
            onSetSide = {}, onTransformPhoto = { _, _, _ -> }, onTransformLinkedView = { _, _, _, _ -> },
            onInitializePhotoViewport = { _, _ -> }, onSetPhotoOpacity = {}, onSetGestureTarget = {},
            onResetPhotoTransform = {}, onRotatePhoto = { _, _ -> }, onClearPhoto = {}, onSetShadowsEnabled = {},
            onRetryTerrain = onRetry, onRendererError = onRendererError, onRenderStats = onRenderStats,
            gesturesOnly = true, modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { coordinates ->
            val location = IntArray(2)
            hostView.getLocationOnScreen(location)
            val offset = coordinates.positionInRoot()
            val left = location[0] + offset.x.roundToInt()
            val top = location[1] + offset.y.roundToInt()
            latestBounds(Rect(left, top, left + coordinates.size.width, top + coordinates.size.height))
        }) {
            if (mapView != null) AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { FlightMapGestureProxyView(it, mapView, onMapExplore) },
                update = { it.update(mapView, onMapExplore); it.lockedCenter = null },
            )
        }
        FlightBottomNavigation(state, onPage, minimalChrome = true)
    }
}
