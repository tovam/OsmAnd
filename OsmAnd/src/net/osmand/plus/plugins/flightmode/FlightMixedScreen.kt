package net.osmand.plus.plugins.flightmode

import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import net.osmand.plus.views.OsmandMapTileView

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
    onFollow: () -> Unit,
    onLockCenter: () -> Unit,
    onSetHeadingLocked: (Boolean) -> Unit,
) {
    var sceneAspectRatio by remember { mutableFloatStateOf(1f) }
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
            gesturesOnly = true, modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged {
                sceneAspectRatio = it.width.toFloat() / it.height.coerceAtLeast(1)
            },
        )
        FlightMapPanel(state, mapView, onMapExplore, onMapBounds,
            modifier = Modifier.weight(1f).fillMaxWidth(), showCone = true,
            coneAspectRatio = sceneAspectRatio,
            onFollow = onFollow, onLockCenter = onLockCenter, onSetHeadingLocked = onSetHeadingLocked)
        FlightBottomNavigation(state, onPage, minimalChrome = true)
    }
}
