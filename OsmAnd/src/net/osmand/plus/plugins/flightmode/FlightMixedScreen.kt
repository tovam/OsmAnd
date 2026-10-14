package net.osmand.plus.plugins.flightmode

import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.ln
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
    onRequestPointingTerrain: (Double, Double) -> Unit,
    onSetMixedTerrainMap: (Boolean) -> Unit,
) {
    var sceneAspectRatio by remember { mutableFloatStateOf(1f) }
    var pointingEnabled by remember { mutableStateOf(false) }
    var reliefPose by
        remember(state.journeyId) {
            mutableStateOf(
                FlightReliefMapPose(
                    state.snapshot?.sample?.latitude ?: 0.0,
                    state.snapshot?.sample?.longitude ?: 0.0,
                )
            )
        }
    val pointing = remember(state.journeyId) { FlightPointingState() }
    val scope = rememberCoroutineScope()
    SideEffect { pointing.selection.onGroundNeeded = onRequestPointingTerrain }
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
                FlightPointingStatus(
                    selection,
                    Modifier.align(Alignment.BottomStart).padding(8.dp),
                    state.terrainStatus,
                    {
                        onRetry()
                        pointing.selection.retry(scope)
                    },
                )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.mixedTerrainMap)
                FlightReliefMapPanel(
                    state = state,
                    pose = reliefPose,
                    onPose = { reliefPose = it },
                    onExplore = onMapExplore,
                    onFollow = onFollow,
                    onLockCenter = onLockCenter,
                    onHeadingLocked = onSetHeadingLocked,
                    pointingEnabled = pointingEnabled,
                    point = selection.point.takeIf { pointingEnabled },
                    onPoint = { click, context ->
                        pointing.selection.select(scope, click, context)
                    },
                    coneAspectRatio = sceneAspectRatio,
                    modifier = Modifier.fillMaxSize(),
                )
            else
                FlightMapPanel(
                    state,
                    mapView,
                    onMapExplore,
                    onMapBounds,
                    modifier = Modifier.fillMaxSize(),
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
            FlightReliefMapToggle(
                state.mixedTerrainMap,
                { enabled ->
                    if (enabled) {
                        val box = mapView?.currentRotatedTileBox
                        reliefPose =
                            reliefPose
                                .copy(
                                    latitude =
                                        box?.latitude
                                            ?: state.snapshot?.sample?.latitude
                                            ?: reliefPose.latitude,
                                    longitude =
                                        box?.longitude
                                            ?: state.snapshot?.sample?.longitude
                                            ?: reliefPose.longitude,
                                    widthMeters =
                                        box?.getDistance(
                                                0,
                                                box.pixHeight / 2,
                                                box.pixWidth,
                                                box.pixHeight / 2,
                                            )
                                            ?.takeIf { it.isFinite() && it > 0 }
                                            ?: reliefPose.widthMeters,
                                    headingDegrees = -(mapView?.rotate ?: 0f),
                                )
                                .clamped()
                        mapView?.animatedDraggingThread?.stopAnimatingSync()
                    } else {
                        mapView?.let { map ->
                            val sample = state.snapshot?.sample
                            map.setLatLon(
                                sample?.latitude.takeIf { state.mapFollowing }
                                    ?: reliefPose.latitude,
                                sample?.longitude.takeIf { state.mapFollowing }
                                    ?: reliefPose.longitude,
                            )
                            val box = map.currentRotatedTileBox
                            val width =
                                box.getDistance(
                                    0,
                                    box.pixHeight / 2,
                                    box.pixWidth,
                                    box.pixHeight / 2,
                                )
                            if (width.isFinite() && width > 0) {
                                val zoom =
                                    (map.zoom +
                                            map.zoomFloatPart +
                                            ln(width / reliefPose.widthMeters) / ln(2.0))
                                        .coerceIn(map.minZoom.toDouble(), map.maxZoom.toDouble())
                                map.setZoomWithFloatPart(
                                    floor(zoom).toInt(),
                                    (zoom - floor(zoom)).toFloat(),
                                )
                            }
                            val heading =
                                sample?.bearingDegrees.takeIf { state.mapHeadingLocked }
                                    ?: reliefPose.headingDegrees
                            map.setRotate(-heading, true)
                        }
                    }
                    onSetMixedTerrainMap(enabled)
                },
                Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
    }
}
