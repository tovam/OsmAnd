package net.osmand.plus.plugins.flightmode

import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
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
    var pointingEnabled by remember { mutableStateOf(false) }
    val pointing = remember(state.journeyId) { FlightPointingState() }
    val scope = rememberCoroutineScope()
    val selection by pointing.selection.state.collectAsState()
    val picker =
        rememberTerrainPicker(
            pointing.frame?.scene.takeIf { pointingEnabled && state.terrainScene != null }
        )
    LaunchedEffect(pointingEnabled, state.terrainScene == null) {
        if (!pointingEnabled || state.terrainScene == null) pointing.resetFrame()
    }
    SideEffect { pointing.terrain = picker }
    LaunchedEffect(pointingEnabled, picker, pointing.frame) {
        if (pointingEnabled)
            pointing.selection.update(
                scope,
                picker?.let { terrain ->
                    pointing.frame?.let { FlightPointingContext(terrain, frame = it) }
                },
                preparing = pointing.frame?.scene?.meshes?.any { it.terrainAvailable } == true,
            )
    }
    DisposableEffect(pointing) { onDispose { pointing.resetFrame() } }
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged {
                sceneAspectRatio = it.width.toFloat() / it.height.coerceAtLeast(1)
            }
        ) {
            FlightWindowScene(
                placement = state.windowPlacement,
                look = state.windowLook,
                trip = state.trip,
                sample = state.snapshot?.sample,
                scene = state.terrainScene,
                terrainStatus = state.terrainStatus,
                rendererRecovery = state.terrainRendererRecovery,
                terrainRenderStats = state.terrainRenderStats,
                altitudeOverrideMeters = state.windowAltitudeOverrideMeters,
                shadingEnabled = state.plan.shadowsEnabled,
                shadowIntensity = state.plan.shadowIntensity,
                satelliteOpacity = state.satelliteOpacity,
                satelliteQuality = state.plan.satelliteQuality,
                showSatelliteQualityOverlay = state.showSatelliteQualityOverlay,
                photo = null,
                photoOverlay = FlightWindowPhotoOverlay(),
                onMoveLook = onMoveLook,
                onChangeZoom = onChangeZoom,
                onRecenterLook = onRecenterLook,
                onSetSide = {},
                onTransformPhoto = { _, _, _ -> },
                onTransformLinkedView = { _, _, _, _ -> },
                onInitializePhotoViewport = { _, _ -> },
                onSetPhotoOpacity = {},
                onSetGestureTarget = {},
                onResetPhotoTransform = {},
                onRotatePhoto = { _, _ -> },
                onClearPhoto = {},
                onSetShadowsEnabled = {},
                onRetryTerrain = onRetry,
                onRendererError = onRendererError,
                onRenderStats = onRenderStats,
                gesturesOnly = true,
                modifier = Modifier.fillMaxSize(),
                pointing = pointing.takeIf { pointingEnabled },
                onTerrainTap =
                    if (pointingEnabled)
                        { at ->
                            pointing.selection.select(
                                scope,
                                FlightPointingClick.Scene(
                                    at.x.toDouble(),
                                    at.y.toDouble(),
                                    pointing.frame,
                                ),
                            )
                        }
                    else null,
            )
            FlightPointingButton(
                pointingEnabled,
                { pointingEnabled = it },
                Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
            if (pointingEnabled)
                FlightPointingStatus(selection, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        FlightMapPanel(
            state,
            mapView,
            onMapExplore,
            onMapBounds,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            showCone = true,
            coneAspectRatio = sceneAspectRatio,
            onFollow = onFollow,
            onLockCenter = onLockCenter,
            onSetHeadingLocked = onSetHeadingLocked,
            pickedPoint = selection.point.takeIf { pointingEnabled },
            onMapPoint =
                if (pointingEnabled)
                    { lat, lon ->
                        pointing.selection.select(scope, FlightPointingClick.Map(lat, lon))
                    }
                else null,
        )
        FlightBottomNavigation(state, onPage, minimalChrome = true)
    }
}
