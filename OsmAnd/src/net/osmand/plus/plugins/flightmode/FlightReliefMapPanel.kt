package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R

/** A second terrain surface owns its camera, but shares immutable downloaded geometry. */
@Composable
internal fun FlightReliefMapPanel(
    state: FlightUiState,
    pose: FlightReliefMapPose,
    onPose: (FlightReliefMapPose) -> Unit,
    onExplore: () -> Unit,
    onFollow: () -> Unit,
    onLockCenter: () -> Unit,
    onHeadingLocked: (Boolean) -> Unit,
    pointingEnabled: Boolean,
    point: FlightPickedPoint?,
    onPoint: (FlightPointingClick.Scene, FlightPointingContext) -> Unit,
    coneAspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var frame by remember { mutableStateOf<FlightPickingFrame?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingTap by remember { mutableStateOf<Offset?>(null) }
    val sample = state.snapshot?.sample
    val displayedPose =
        pose
            .copy(
                latitude =
                    sample?.latitude.takeIf { state.mapFollowing || state.mapCenterLocked }
                        ?: pose.latitude,
                longitude =
                    sample?.longitude.takeIf { state.mapFollowing || state.mapCenterLocked }
                        ?: pose.longitude,
                headingDegrees =
                    sample?.bearingDegrees.takeIf { state.mapHeadingLocked } ?: pose.headingDegrees,
            )
            .clamped()
    val aspect = size.width.toFloat() / size.height.coerceAtLeast(1)
    val textures = rememberReliefMapTextures(state.terrainScene, displayedPose, aspect)
    val scene =
        remember(state.terrainScene, textures.paths) {
            state.terrainScene?.let { flightReliefMapTexturedScene(it, textures.paths) }
        }
    // Floor sampling only touches the few loaded height fields at the geographic centre.
    val ground =
        remember(state.terrainScene, displayedPose.latitude, displayedPose.longitude) {
            state.terrainScene?.let {
                FlightTerrainFloor.elevationAt(
                    it.meshes,
                    displayedPose.latitude,
                    displayedPose.longitude,
                )
            } ?: 0f
        }
    val camera = remember(displayedPose, aspect, ground) { displayedPose.camera(aspect, ground) }
    val picker = rememberTerrainPicker(frame?.scene.takeIf { scene != null })
    val gesturePose = remember { arrayOf(displayedPose) }
    SideEffect { gesturePose[0] = displayedPose }
    val currentState by rememberUpdatedState(state)
    val changePose by rememberUpdatedState(onPose)
    val explore by rememberUpdatedState(onExplore)
    val select by rememberUpdatedState(onPoint)
    val currentFrame by rememberUpdatedState(frame)
    val currentPicker by rememberUpdatedState(picker)
    LaunchedEffect(scene == null) { if (scene == null) frame = null }
    LaunchedEffect(pointingEnabled) { if (!pointingEnabled) pendingTap = null }
    LaunchedEffect(pendingTap, frame, picker) {
        val tap = pendingTap
        val view = frame
        val terrain = picker
        if (pointingEnabled && tap != null && view != null && terrain?.hasTerrain == true) {
            pendingTap = null
            select(
                FlightPointingClick.Scene(tap.x.toDouble(), tap.y.toDouble(), view),
                FlightPointingContext(terrain, frame = view),
            )
        }
    }
    Box(modifier.clipToBounds().background(Color(0xFF18252D)).onSizeChanged { size = it }) {
        FlightTerrainSurface(
            scene = scene,
            sample = sample,
            windowPlacement = state.windowPlacement,
            windowLook = state.windowLook,
            altitudeOverrideMeters = null,
            shadingEnabled = true,
            shadowIntensity = 0.6f,
            satelliteOpacity = state.satelliteOpacity,
            showSatelliteQualityOverlay = false,
            terrainOpacity = 0f,
            nativeMapOpacity = 1f,
            spatialPhoto = null,
            onRendererError = { error = it },
            onRenderStats = {},
            modifier = Modifier.fillMaxSize(),
            reliefMapCamera = camera,
            onPickingFrame = { next ->
                if (frame?.sameView(next) != true) frame = next
                error = null
            },
        )
        FlightReliefMapOverlays(
            state,
            frame,
            displayedPose,
            coneAspectRatio,
            Modifier.fillMaxSize(),
        )
        if (pointingEnabled)
            FlightTerrainPickedOverlay(point, frame, picker, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize()
                .pointerInput(pointingEnabled) {
                    if (pointingEnabled)
                        detectTapGestures { at ->
                            val tap =
                                Offset(
                                    at.x / size.width.coerceAtLeast(1),
                                    at.y / size.height.coerceAtLeast(1),
                                )
                            val view = currentFrame
                            val terrain = currentPicker
                            if (view != null && terrain?.hasTerrain == true)
                                select(
                                    FlightPointingClick.Scene(
                                        tap.x.toDouble(),
                                        tap.y.toDouble(),
                                        view,
                                    ),
                                    FlightPointingContext(terrain, frame = view),
                                )
                            else pendingTap = tap
                        }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        pendingTap = null
                        val locked = currentState.mapCenterLocked
                        val moved = !locked && (pan.x != 0f || pan.y != 0f)
                        if (moved) explore()
                        val next =
                            gesturePose[0].transform(
                                if (locked) 0f else pan.x,
                                if (locked) 0f else pan.y,
                                zoom,
                                if (currentState.mapHeadingLocked) 0f else rotation,
                                size.width,
                            )
                        gesturePose[0] = next
                        changePose(next)
                    }
                }
        )
        Row(
            Modifier.align(Alignment.TopEnd).background(Color(0x9018252D)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapControl(
                R.drawable.ic_action_compass_north,
                stringResource(R.string.flight_mode_north_up),
                rotation = -displayedPose.headingDegrees,
                label = "%03d°".format(displayedPose.headingDegrees.roundToInt() % 360),
            ) {
                onHeadingLocked(false)
                onPose(displayedPose.copy(headingDegrees = 0f))
            }
            MapControl(
                R.drawable.ic_action_direction_arrow,
                stringResource(
                    if (state.mapHeadingLocked) R.string.flight_map_heading_unlock
                    else R.string.flight_map_heading_lock
                ),
                active = state.mapHeadingLocked,
                enabled = sample?.bearingDegrees != null,
            ) {
                onPose(displayedPose)
                onHeadingLocked(!state.mapHeadingLocked)
            }
            MapControl(
                R.drawable.ic_action_3d,
                stringResource(R.string.flight_map_top_view),
                label = "${(90 - displayedPose.tiltDegrees).roundToInt()}°",
            ) {
                onPose(
                    displayedPose.copy(
                        tiltDegrees = if (displayedPose.tiltDegrees == 0f) 35f else 0f
                    )
                )
            }
            MapControl(
                if (state.mapCenterLocked) R.drawable.ic_action_lock
                else R.drawable.ic_action_lock_open,
                stringResource(
                    if (state.mapCenterLocked) R.string.flight_map_center_unlock
                    else R.string.flight_map_center_lock
                ),
                active = state.mapCenterLocked,
                onClick = {
                    onPose(displayedPose)
                    onLockCenter()
                },
            )
            MapControl(
                R.drawable.ic_action_get_my_location,
                stringResource(
                    if (state.mapFollowing) R.string.flight_map_follow_off
                    else R.string.flight_map_follow_on
                ),
                active = state.mapFollowing,
                onClick = {
                    onPose(displayedPose)
                    onFollow()
                },
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color(0xB018252D), RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            val message =
                when {
                    error != null -> stringResource(R.string.flight_relief_map_error)
                    scene?.meshes?.any { it.terrainAvailable } != true ->
                        stringResource(R.string.flight_relief_map_waiting)
                    textures.requested == 0 -> stringResource(R.string.flight_relief_map_outside)
                    textures.loading ->
                        stringResource(
                            R.string.flight_relief_map_loading,
                            textures.paths.size,
                            textures.requested,
                        )
                    textures.failed > 0 -> stringResource(R.string.flight_relief_map_missing)
                    else -> null
                }
            if (message != null)
                Text(
                    message,
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 230.dp),
                )
            FlightReliefMapScale(frame, displayedPose, ground, size)
        }
    }
}

private data class ReliefMapTextures(
    val paths: Map<TerrainTileId, String> = emptyMap(),
    val requested: Int = 0,
    val failed: Int = 0,
    val loading: Boolean = false,
)

@Composable
private fun rememberReliefMapTextures(
    scene: FlightTerrainScene?,
    pose: FlightReliefMapPose,
    aspect: Float,
): ReliefMapTextures {
    val app = LocalContext.current.applicationContext as OsmandApplication
    val repository = remember(app) { FlightNativeMapTextureRepository(app) }
    val tiles =
        remember(scene, pose, aspect) {
            scene
                ?.let { flightReliefMapTextureTiles(it, pose, aspect) }
                .orEmpty()
                .sortedWith(compareBy({ it.zoom }, { it.x }, { it.y }))
        }
    var result by remember { mutableStateOf(ReliefMapTextures()) }
    FlightResumedEffect(repository, tiles) {
        result = result.copy(requested = tiles.size, loading = tiles.isNotEmpty(), failed = 0)
        delay(350)
        try {
            val rendered =
                repository.renderTextures(
                    tiles,
                    onTextures = { paths ->
                        result =
                            result.copy(paths = result.paths.filterKeys { it in tiles } + paths)
                    },
                    onProgress = { _, _, failed -> result = result.copy(failed = failed) },
                )
            result = ReliefMapTextures(rendered.texturePaths, tiles.size, rendered.failedTiles)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            result = result.copy(loading = false, failed = tiles.size)
        }
    }
    return result
}

@Composable
internal fun FlightReliefMapToggle(
    enabled: Boolean,
    change: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconToggleButton(enabled, change, modifier.size(48.dp)) {
        Icon(
            painterResource(R.drawable.ic_action_3d_relief),
            stringResource(
                if (enabled) R.string.flight_relief_map_disable
                else R.string.flight_relief_map_enable
            ),
            tint = if (enabled) Color(0xFF9AD9FF) else Color(0xFFB1C2CF),
            modifier =
                Modifier.size(32.dp)
                    .background(
                        if (enabled) Color(0xD0284D65) else Color(0x8018252D),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(6.dp),
        )
    }
}

@Composable
private fun FlightReliefMapOverlays(
    state: FlightUiState,
    frame: FlightPickingFrame?,
    pose: FlightReliefMapPose,
    coneAspectRatio: Float,
    modifier: Modifier,
) {
    val routes =
        remember(state.trip, state.sessionMode) {
            flightRoutePaths(
                state.trip?.samples.orEmpty(),
                recorded = state.sessionMode != FlightSessionMode.PREPARE,
                maximumMeasuredVertices = 1200,
            )
        }
    val cone =
        flightMapViewCone(
            state.snapshot?.sample,
            state.windowPlacement,
            state.windowLook,
            coneAspectRatio,
        )
    val ground =
        remember(state.terrainScene) { FlightReliefMapGround(state.terrainScene?.meshes.orEmpty()) }
    val predicted =
        remember(state.plan, state.liveState.latest, state.sessionMode) {
            if (state.sessionMode == FlightSessionMode.LIVE)
                state.liveState.latest
                    ?.let {
                        flightRouteDashes(FlightLiveTimeline.build(state.plan, null, it).samples)
                    }
                    .orEmpty()
            else emptyList()
        }
    Canvas(modifier) {
        val view = frame ?: return@Canvas
        val coordinates =
            FlightTerrainCoordinates(
                view.scene.coordinateOriginLatitude,
                view.scene.coordinateOriginLongitude,
            )
        fun project(latitude: Double, longitude: Double): Offset? =
            view
                .project(
                    coordinates
                        .toLocal(
                            latitude,
                            longitude,
                            (ground.elevation(latitude, longitude) ?: 0f).toDouble(),
                        )
                        .map { it.toDouble() }
                        .toDoubleArray(),
                    clipToViewport = false,
                )
                ?.let { (x, y) -> Offset((x * size.width).toFloat(), (y * size.height).toFloat()) }
        for (route in routes) {
            var previous: Offset? = null
            for (vertex in route.vertices) {
                val sample = vertex.sample
                val at = project(sample.latitude, sample.longitude)
                if (previous != null && at != null)
                    drawLine(
                        if (route.isGap) Color(0xFFE57171) else Color(0xFFEEA65E),
                        previous,
                        at,
                        1.5.dp.toPx(),
                    )
                previous = at
            }
        }
        for (dash in predicted) {
            dash.zipWithNext().forEach { (a, b) ->
                val from = project(a.latitude, a.longitude)
                val to = project(b.latitude, b.longitude)
                if (from != null && to != null) drawLine(Color(0xFFEEA65E), from, to, 1.5.dp.toPx())
            }
        }
        val sample = state.snapshot?.sample ?: return@Canvas
        val at = project(sample.latitude, sample.longitude) ?: return@Canvas
        cone?.let {
            val geo = FlightTerrainCoordinates(it.latitude, it.longitude)
            val radius = pose.widthMeters * 0.4
            val path =
                Path().apply {
                    moveTo(at.x, at.y)
                    it.boundaryBearings().forEach { bearing ->
                        val angle = Math.toRadians(bearing)
                        val target =
                            geo.toGeographic(
                                doubleArrayOf(sin(angle) * radius, 0.0, -cos(angle) * radius)
                            )
                        project(target[0], target[1])?.let { point -> lineTo(point.x, point.y) }
                    }
                    close()
                }
            drawPath(path, Color(0x405099DF))
        }
        val heading =
            Math.toRadians(((sample.bearingDegrees ?: 0f) - pose.headingDegrees).toDouble())
        fun offset(right: Float, forward: Float) =
            Offset(
                at.x + (right * cos(heading) + forward * sin(heading)).toFloat(),
                at.y + (right * sin(heading) - forward * cos(heading)).toFloat(),
            )
        val tip = offset(0f, 8.dp.toPx())
        val left = offset(-5.dp.toPx(), -5.dp.toPx())
        val right = offset(5.dp.toPx(), -5.dp.toPx())
        val arrow =
            Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(left.x, left.y)
                lineTo(right.x, right.y)
                close()
            }
        drawPath(arrow, Color.Black, style = Stroke(3.dp.toPx()))
        drawPath(arrow, Color(0xFF9AD9FF))
    }
}

@Composable
private fun FlightReliefMapScale(
    frame: FlightPickingFrame?,
    pose: FlightReliefMapPose,
    ground: Float,
    size: IntSize,
) {
    // Measure two rays through the centre plane; unlike the viewport width, this reflects tilt.
    val coordinates =
        remember(frame?.scene) {
            frame?.scene?.let {
                FlightTerrainCoordinates(it.coordinateOriginLatitude, it.coordinateOriginLongitude)
            }
        }
    val distance =
        remember(frame, pose, ground, size) {
            if (frame == null || coordinates == null) 0.0
            else {
                val target = coordinates.toLocal(pose.latitude, pose.longitude, ground.toDouble())
                val normal = coordinates.vectorToLocal(pose.latitude, pose.longitude, 0f, 1f, 0f)
                fun plane(x: Double): DoubleArray? {
                    val ray = frame.ray(x, 0.5) ?: return null
                    val denominator = ray.direction.indices.sumOf { ray.direction[it] * normal[it] }
                    if (abs(denominator) < 1e-8) return null
                    val t =
                        ray.eye.indices.sumOf { (target[it] - ray.eye[it]) * normal[it] } /
                            denominator
                    return if (t > 0) DoubleArray(3) { ray.eye[it] + ray.direction[it] * t }
                    else null
                }
                val a = plane(0.375)
                val b = plane(0.625)
                if (a != null && b != null) sqrt(a.indices.sumOf { (a[it] - b[it]).pow(2) })
                else 0.0
            }
        }
    if (distance > 0 && distance.isFinite()) {
        val step = flightScaleStep(distance)
        val label = if (step >= 1000) "%.0f km".format(step / 1000) else "%.0f m".format(step)
        Box(
            Modifier.width(
                (size.width * 0.25f / androidx.compose.ui.platform.LocalDensity.current.density).dp
            )
        ) {
            Column {
                Text(label, color = Color.White, fontSize = 10.sp)
                Canvas(Modifier.fillMaxWidth().height(7.dp)) {
                    val end = (this.size.width * step / distance).toFloat()
                    drawLine(Color.White, Offset(0f, 2f), Offset(end, 2f), 2f)
                    drawLine(Color.White, Offset.Zero, Offset(0f, this.size.height), 2f)
                    drawLine(Color.White, Offset(end, 0f), Offset(end, this.size.height), 2f)
                }
            }
        }
    }
}
