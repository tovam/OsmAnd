package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.*
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.util.PhotoCalibrationInput
import net.osmand.util.PhotoCalibrationInput.Action

private enum class PhotoAssociationAction {
    AUTOMATIC,
    HERE,
    REMOVE,
}

/** All calibration exploration is local to this modal; the journal replay cursor is untouched. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FlightPhotoEditor(
    photo: FlightPhotoAttachment,
    state: FlightUiState,
    onClose: () -> Unit,
    onSave: (FlightPhotoCalibration) -> Unit,
    onAutoAssociate: () -> Unit,
    onAssociateHere: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenWindow: () -> Unit,
    onOpenMap: () -> Unit,
    onClearAssociation: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember {
        FlightTerrainRepository(context.applicationContext as OsmandApplication)
    }
    DisposableEffect(repository) { onDispose { repository.close() } }
    var data by remember(photo.id) { mutableStateOf(photo.calibration) }
    var selected by remember(photo.id) { mutableStateOf(-1) }
    var action by remember(photo.id) { mutableStateOf(Action.EXPLORE) }
    var clearAllConfirmation by remember(photo.id) { mutableStateOf(false) }
    var associationAction by remember(photo.id) { mutableStateOf<PhotoAssociationAction?>(null) }
    var tab by remember(photo.id) { mutableStateOf(0) }
    var status by remember(photo.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var solveJob by remember { mutableStateOf<Job?>(null) }
    var solveProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showDiagnostics by remember(photo.id) { mutableStateOf(false) }
    var satellite by remember { mutableStateOf(true) }
    var opacity by remember { mutableStateOf(data.editorView.opacity) }
    var cameraMode by remember { mutableStateOf(data.editorView.mode) }
    var yaw by remember { mutableStateOf(data.editorView.yaw) }
    var pitch by remember { mutableStateOf(data.editorView.pitch) }
    var zoom by remember { mutableStateOf(data.editorView.zoom) }
    // A confirmation must not silently apply to a different replay position.
    LaunchedEffect(state.replayProgress, photo.matchedSamplePosition) {
        if (associationAction != null) {
            associationAction = null
            status = context.getString(R.string.flight_cal_association_target_changed)
        }
    }
    val mapView = remember(context, photo.id) { FlightPhotoLandmarkView(context) }
    DisposableEffect(mapView) { onDispose { mapView.release() } }
    var imageLoading by remember(photo.localPath) { mutableStateOf(true) }
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, photo.localPath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { decodePhotoPreview(File(photo.localPath)) }.getOrNull()
                }
            imageLoading = false
        }
    fun save(next: FlightPhotoCalibration) {
        solveJob?.cancel()
        busy = false
        data = next
        onSave(next)
    }
    fun placePoint(mode: Int, update: (FlightPhotoControlPoint) -> FlightPhotoControlPoint) {
        if (tab != mode) return
        val edited =
            PhotoCalibrationInput.placementIndex(action, selected, data.points.size, mode == 0)
        if (edited < 0) return
        val points =
            if (edited == data.points.size) data.points + FlightPhotoControlPoint() else data.points
        val next =
            data.copy(
                points =
                    points.mapIndexed { i, point -> if (i == edited) update(point) else point },
                fit = null,
            )
        save(next)
        selected = edited
        if (action == Action.MOVE) action = Action.EXPLORE
    }
    fun changeTab(next: Int) {
        action = Action.EXPLORE
        if (next == 1) satellite = true
        tab = next
    }
    LaunchedEffect(bitmap) {
        bitmap?.let { b ->
            if (data.imageWidth != b.width || data.imageHeight != b.height)
                save(
                    data.copy(
                        imageWidth = b.width,
                        imageHeight = b.height,
                        fit = null,
                        verticalFov =
                            if (data.imageWidth == 0)
                                photo.cameraVerticalFieldOfViewDegrees?.toDouble()
                                    ?: data.verticalFov
                            else data.verticalFov,
                    )
                )
        }
    }
    val sample = FlightSampleInterpolator.sampleAt(state.trip, photo.matchedSamplePosition)
    val associationHerePosition =
        state.recordedPhotoPositionAtCursor()?.let(FlightSampleInterpolator::quantizePosition)
    val reference =
        sample?.let { s ->
            val existing = photo.windowAlignment?.spatialPose
            (existing
                    ?: FlightPhotoSpatialPose(
                        photo.matchedSamplePosition ?: 0.0,
                        s.timestampMillis,
                        s.latitude,
                        s.longitude,
                        s.altitudeMeters?.toFloat(),
                        s.bearingDegrees ?: 0f,
                        (s.bearingDegrees ?: 0f) - 90f,
                        -20f,
                        photo.cameraVerticalFieldOfViewDegrees ?: 60f,
                    ))
                .copy(
                    // The recorded position remains the track association, never a previous fit.
                    eyeLatitude = s.latitude,
                    eyeLongitude = s.longitude,
                    eyeAltitudeMeters = s.altitudeMeters?.toFloat(),
                    referenceAspectRatio =
                        if (data.imageHeight > 0) data.imageWidth.toFloat() / data.imageHeight
                        else 1f,
                )
        }
    val estimate =
        data.fit?.let { f ->
            reference?.let {
                f.pose(it, data.imageWidth.toFloat() / data.imageHeight.coerceAtLeast(1))
            }
        }
    val currentOnSave by rememberUpdatedState(onSave)
    val currentData by rememberUpdatedState(data)
    val currentReference by rememberUpdatedState(reference)
    LaunchedEffect(cameraMode, yaw, pitch, zoom, opacity) {
        val settings = FlightPhotoEditorView(cameraMode, yaw, pitch, zoom, opacity)
        if (data.editorView != settings) {
            data = data.copy(editorView = settings)
            currentOnSave(data)
        }
    }
    val ready =
        data.points.count {
            it.x != null && it.y != null && it.latitude != null && it.longitude != null
        }
    val readiness =
        PhotoCalibrationInput.readiness(
            bitmap != null && data.imageWidth > 0 && data.imageHeight > 0,
            reference != null,
            reference?.eyeAltitudeMeters?.isFinite() == true,
            ready,
        )
    val canCalculate = readiness == PhotoCalibrationInput.Readiness.READY
    if (clearAllConfirmation)
        AlertDialog(
            onDismissRequest = { clearAllConfirmation = false },
            title = { Text(stringResource(R.string.flight_cal_delete_all)) },
            text = { Text(stringResource(R.string.flight_cal_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        save(data.copy(points = emptyList(), fit = null))
                        selected = -1
                        action = Action.EXPLORE
                        clearAllConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.flight_cal_confirm_change))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAllConfirmation = false }) {
                    Text(stringResource(R.string.shared_string_cancel))
                }
            },
        )
    val photoPointCount = data.points.count { it.x != null && it.y != null }
    @Composable
    fun CalculationActions() {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                data.fitFocal,
                { save(data.copy(fitFocal = it, fit = null)) },
                modifier = Modifier.size(36.dp),
            )
            Text(
                stringResource(R.string.flight_cal_fit_focal),
                fontSize = 10.sp,
                color = Color.LightGray,
                modifier = Modifier.weight(1f),
            )
            EditorAction(
                if (busy) stringResource(R.string.flight_cal_cancel)
                else if (tab == 0) stringResource(R.string.flight_cal_continue_map, photoPointCount)
                else stringResource(R.string.flight_cal_solve, ready),
                {
                    if (busy) {
                        solveJob?.cancel()
                        busy = false
                    } else if (tab == 0) {
                        changeTab(1)
                    } else if (canCalculate && reference != null) {
                        val submitted = data
                        busy = true
                        status = ""
                        solveProgress = null
                        solveJob =
                            scope.launch {
                                try {
                                    val result =
                                        solveFlightPhotoCalibration(
                                            submitted,
                                            reference,
                                            repository,
                                            onProgress = { completed, total ->
                                                solveProgress = completed to total
                                            },
                                        )
                                    if (
                                        currentData.points == submitted.points &&
                                            currentReference == reference &&
                                            currentData.fitFocal == submitted.fitFocal &&
                                            currentData.verticalFov == submitted.verticalFov
                                    ) {
                                        data =
                                            result.copy(
                                                editorView = currentData.editorView,
                                                pickerRotation = currentData.pickerRotation,
                                            )
                                        currentOnSave(data)
                                        changeTab(2)
                                        showDiagnostics = true
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    status =
                                        context.getString(
                                            R.string.flight_cal_failed,
                                            e.message ?: e.javaClass.simpleName,
                                        )
                                } finally {
                                    if (solveJob == currentCoroutineContext()[Job]) busy = false
                                }
                            }
                    }
                },
                enabled = busy || if (tab == 0) photoPointCount >= 4 else canCalculate,
            )
        }
        if (busy) {
            Text(
                solveProgress?.let { (completed, total) ->
                    stringResource(R.string.flight_cal_diagnostics_progress, completed, total)
                } ?: stringResource(R.string.flight_cal_diagnostics_altitudes),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    Dialog(
        onDismissRequest = {
            val previous = PhotoCalibrationInput.backTab(tab)
            if (previous < 0) onClose() else changeTab(previous)
        },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Color(0xFF0A0F13))
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    photo.fileName,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                EditorAction(stringResource(R.string.flight_mode_close), onClose)
            }
            FlightStorageStatusStrip(state, compact = true)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf(
                        R.string.flight_cal_photo,
                        R.string.flight_cal_map,
                        R.string.flight_cal_compare,
                        R.string.flight_cal_window,
                        R.string.flight_mode_photo_details,
                    )
                    .forEachIndexed { i, res ->
                        EditorAction(stringResource(res), { changeTab(i) }, tab == i)
                    }
            }
            if (tab <= 1) {
                FlowRow(Modifier.fillMaxWidth()) {
                    EditorAction(
                        stringResource(R.string.flight_cal_explore),
                        { action = Action.EXPLORE },
                        action == Action.EXPLORE,
                    )
                    EditorAction(
                        stringResource(R.string.flight_cal_add),
                        {
                            tab = 0
                            action = Action.ADD
                        },
                        selected = action == Action.ADD,
                    )
                    EditorAction(
                        stringResource(
                            if (tab == 1 && data.points.getOrNull(selected)?.latitude == null)
                                R.string.flight_cal_place
                            else R.string.flight_cal_edit
                        ),
                        { action = Action.MOVE },
                        selected = action == Action.MOVE,
                        enabled = selected in data.points.indices,
                    )
                    EditorAction(
                        stringResource(R.string.flight_cal_delete),
                        {
                            save(
                                data.copy(
                                    points = data.points.filterIndexed { i, _ -> i != selected },
                                    fit = null,
                                )
                            )
                            selected = -1
                            action = Action.EXPLORE
                        },
                        enabled = selected in data.points.indices,
                    )
                    EditorAction(
                        stringResource(R.string.flight_cal_delete_all),
                        { clearAllConfirmation = true },
                        enabled = data.points.isNotEmpty(),
                    )
                    if (tab == 0)
                        EditorAction(
                            stringResource(R.string.flight_cal_rotation_reset),
                            { save(data.copy(pickerRotation = 0f)) },
                        )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    data.points.forEachIndexed { i, p ->
                        EditorAction(
                            "${i+1} ${if (if (tab == 0) p.x != null else p.latitude != null) "✓" else "·"}",
                            {
                                selected = i
                                action = Action.EXPLORE
                            },
                            selected == i,
                        )
                    }
                }
            }
            if (tab == 1 || tab == 2) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    EditorAction(
                        stringResource(R.string.flight_cal_satellite),
                        { satellite = true },
                        satellite,
                    )
                    if (tab == 2)
                        EditorAction(
                            stringResource(R.string.flight_cal_osmand),
                            { satellite = false },
                            !satellite,
                        )
                    EditorAction(stringResource(R.string.flight_cal_fit), { mapView.fit() })
                }
            }
            if (tab == 3) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    listOf(
                            R.string.flight_cal_original,
                            R.string.flight_cal_estimated,
                            R.string.flight_cal_overview,
                        )
                        .forEachIndexed { i, res ->
                            EditorAction(
                                stringResource(res),
                                {
                                    if (i != 0 && estimate == null) return@EditorAction
                                    cameraMode = i
                                    yaw = 0f
                                    pitch = 0f
                                    zoom = 1f
                                },
                                cameraMode == i,
                            )
                        }
                }
            }
            if (tab <= 1) CalculationActions()
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    update = { v ->
                        v.visibility =
                            if (tab < 3) android.view.View.VISIBLE else android.view.View.INVISIBLE
                        if (tab < 3) {
                            v.dragToPlace = action == Action.MOVE
                            v.onImagePoint = { x, y -> placePoint(0) { it.copy(x = x, y = y) } }
                            v.onMapPoint = { lat, lon ->
                                placePoint(1) {
                                    it.copy(latitude = lat, longitude = lon, altitude = null)
                                }
                            }
                            v.onStatus = { status = it }
                            v.onRotation = {
                                data = data.copy(pickerRotation = it)
                                currentOnSave(data)
                            }
                            v.update(
                                bitmap,
                                data,
                                selected,
                                tab,
                                reference,
                                estimate,
                                state.trip,
                                satellite,
                            )
                        }
                    },
                )
                if (tab == 4) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        FlightPhotoMetadata(photo, sample, state.trip)
                    }
                } else if (tab < 3) {
                    if (tab == 0 && bitmap == null)
                        Text(
                            stringResource(
                                if (imageLoading) R.string.flight_mode_photo_preview_loading
                                else R.string.flight_mode_photo_preview_unavailable
                            ),
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                } else if (reference != null && state.terrainScene != null) {
                    val target = if (cameraMode == 1) estimate ?: reference else reference
                    val base =
                        if (cameraMode == 2) overviewPhotoCamera(reference, estimate) else target
                    val camera =
                        base.copy(
                            viewAzimuthDegrees = base.viewAzimuthDegrees + yaw,
                            viewElevationDegrees =
                                (base.viewElevationDegrees + pitch).coerceIn(-89f, 89f),
                            verticalFieldOfViewDegrees =
                                (base.verticalFieldOfViewDegrees / zoom).coerceIn(1f, 170f),
                        )
                    val latestCamera by rememberUpdatedState(camera)
                    val latestBase by rememberUpdatedState(base)
                    val overlayPose = estimate ?: reference
                    FlightTerrainSurface(
                        state.terrainScene,
                        sample,
                        FlightWindowPlacement(),
                        FlightWindowLook(),
                        null,
                        state.plan.shadowsEnabled,
                        state.plan.shadowIntensity,
                        1f,
                        false,
                        0f,
                        0f,
                        FlightSpatialPhotoOverlay(
                            photo.id,
                            photo.localPath,
                            overlayPose,
                            opacity,
                            1f,
                            0f,
                            0f,
                            data.fit?.imageRotationDegrees() ?: photo.rotationDegrees,
                            photo.imageAdjustments,
                        ),
                        { status = it },
                        {},
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectFineFlightTransforms { _, pan, factor, _ ->
                                yaw -=
                                    pan.x / size.width.coerceAtLeast(1) *
                                        latestCamera.verticalFieldOfViewDegrees *
                                        size.width / size.height.coerceAtLeast(1)
                                pitch +=
                                    pan.y / size.height.coerceAtLeast(1) *
                                        latestCamera.verticalFieldOfViewDegrees
                                yaw = ((yaw + 180f) % 360f + 360f) % 360f - 180f
                                pitch =
                                    pitch.coerceIn(
                                        -89f - latestBase.viewElevationDegrees,
                                        89f - latestBase.viewElevationDegrees,
                                    )
                                zoom = (zoom * sqrt(factor)).coerceIn(0.3f, 8f)
                            }
                        },
                        FlightPhotoInspection(camera, reference, estimate, state.trip),
                    )
                } else
                    Text(
                        stringResource(R.string.flight_cal_need_scene),
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
            }
            if (tab == 3) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.flight_cal_opacity),
                        color = Color.LightGray,
                        fontSize = 10.sp,
                    )
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (tab >= 2) {
                Text(
                    stringResource(R.string.flight_cal_legend),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (tab >= 2)
                data.fit?.let { fit ->
                    val horizontal =
                        if (reference != null && estimate != null)
                            FlightTerrainTilePlanner.distanceKm(
                                reference.eyeLatitude,
                                reference.eyeLongitude,
                                estimate.eyeLatitude,
                                estimate.eyeLongitude,
                            ) * 1000
                        else 0.0
                    val vertical =
                        (estimate?.eyeAltitudeMeters ?: 0f) - (reference?.eyeAltitudeMeters ?: 0f)
                    Text(
                        stringResource(R.string.flight_cal_result, horizontal, vertical, fit.rms),
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(
                        stringResource(
                            if (fit.weak || fit.rms > 5) R.string.flight_cal_weak
                            else R.string.flight_cal_uncertainty
                        ),
                        color = if (fit.weak || fit.rms > 5) Color(0xFFFFCC66) else Color.LightGray,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    val completeIndices =
                        fit.pointIndices.ifEmpty {
                            data.points.indices.filter {
                                val p = data.points[it]
                                p.x != null &&
                                    p.y != null &&
                                    p.latitude != null &&
                                    p.longitude != null
                            }
                        }
                    Text(
                        fit.errors
                            .mapIndexed { i, e ->
                                "${(completeIndices.getOrNull(i)?:i)+1}: %.1f px".format(e)
                            }
                            .joinToString(" · "),
                        color = Color.LightGray,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    EditorAction(
                        stringResource(R.string.flight_cal_diagnostics_title),
                        { showDiagnostics = true },
                    )
                }
            if (status.isNotBlank())
                Text(
                    status,
                    color = Color(0xFFFFCC66),
                    fontSize = 9.sp,
                    maxLines = 3,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            if (reference == null)
                Text(
                    stringResource(R.string.flight_cal_need_association),
                    color = Color(0xFFFFCC66),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            if (tab >= 2) CalculationActions()
            if (!busy && !canCalculate && tab != 0)
                Text(
                    stringResource(
                        when (readiness) {
                            PhotoCalibrationInput.Readiness.IMAGE_MISSING ->
                                R.string.flight_cal_need_image
                            PhotoCalibrationInput.Readiness.ASSOCIATION_MISSING ->
                                R.string.flight_cal_need_association
                            PhotoCalibrationInput.Readiness.ALTITUDE_MISSING ->
                                R.string.flight_cal_need_altitude
                            else -> R.string.flight_cal_need_pairs
                        }
                    ),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            if (tab >= 2 && !data.fitFocal) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.flight_cal_fov, data.verticalFov),
                        color = Color.LightGray,
                        fontSize = 10.sp,
                    )
                    Slider(
                        kotlin.math.ln(data.verticalFov.toFloat()),
                        {
                            save(
                                data.copy(verticalFov = kotlin.math.exp(it).toDouble(), fit = null)
                            )
                        },
                        valueRange = 0f..kotlin.math.ln(170f),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (tab == 4)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    EditorAction(
                        photo.timestampMillis?.let {
                            stringResource(
                                R.string.flight_mode_photo_match_at,
                                android.text.format.DateFormat.getTimeFormat(context)
                                    .format(java.util.Date(it)),
                            )
                        } ?: stringResource(R.string.flight_mode_photo_match_automatically),
                        { associationAction = PhotoAssociationAction.AUTOMATIC },
                        enabled = state.trip?.samples?.isNotEmpty() == true,
                    )
                    EditorAction(
                        stringResource(R.string.flight_mode_photo_match_here),
                        { associationAction = PhotoAssociationAction.HERE },
                        enabled = associationHerePosition != null,
                    )
                    EditorAction(
                        stringResource(R.string.flight_mode_photo_open_fullscreen),
                        onOpenPhoto,
                    )
                    EditorAction(
                        stringResource(R.string.flight_mode_photo_open_window),
                        onOpenWindow,
                    )
                    EditorAction(stringResource(R.string.flight_mode_photo_open_map), onOpenMap)
                    EditorAction(
                        stringResource(R.string.flight_mode_photo_unmatch),
                        { associationAction = PhotoAssociationAction.REMOVE },
                        enabled = photo.matchedSamplePosition != null,
                    )
                }
        }
        if (showDiagnostics)
            data.fit?.let { fit ->
                FlightPhotoFitDiagnosticsScreen(
                    fit,
                    data.points.indices.filter { i ->
                        val p = data.points[i]
                        p.x != null && p.y != null && p.latitude != null && p.longitude != null
                    },
                    data.imageWidth,
                    data.imageHeight,
                    onClose = { showDiagnostics = false },
                )
            }
        associationAction?.let { action ->
            AlertDialog(
                onDismissRequest = { associationAction = null },
                title = { Text(stringResource(R.string.flight_cal_confirm_association)) },
                text = {
                    Text(
                        stringResource(
                            when (action) {
                                PhotoAssociationAction.AUTOMATIC -> R.string.flight_cal_confirm_auto
                                PhotoAssociationAction.HERE -> R.string.flight_cal_confirm_here
                                PhotoAssociationAction.REMOVE -> R.string.flight_cal_confirm_remove
                            },
                            photo.fileName,
                            associationHerePosition?.let { "%.2f".format(it + 1) } ?: "—",
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            associationAction = null
                            when (action) {
                                PhotoAssociationAction.AUTOMATIC -> onAutoAssociate()
                                PhotoAssociationAction.HERE -> onAssociateHere()
                                PhotoAssociationAction.REMOVE -> onClearAssociation()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.flight_cal_confirm_change))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { associationAction = null }) {
                        Text(stringResource(R.string.flight_mode_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun EditorAction(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        Modifier.heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .background(if (selected) Color(0xFF234656) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color =
                if (!enabled) Color(0xFF6D7379)
                else if (selected) Color.White else Color(0xFF74D8FF),
            maxLines = 1,
        )
    }
}

private fun overviewPhotoCamera(
    a: FlightPhotoSpatialPose,
    b: FlightPhotoSpatialPose?,
): FlightPhotoSpatialPose {
    if (b == null) return a
    val coordinates = FlightTerrainCoordinates(a.eyeLatitude, a.eyeLongitude)
    val x =
        coordinates.toLocal(a.eyeLatitude, a.eyeLongitude, (a.eyeAltitudeMeters ?: 0f).toDouble())
    val y =
        coordinates.toLocal(b.eyeLatitude, b.eyeLongitude, (b.eyeAltitudeMeters ?: 0f).toDouble())
    val distance = sqrt(x.indices.sumOf { ((x[it] - y[it]) * (x[it] - y[it])).toDouble() })
    val middle = DoubleArray(3) { (x[it] + y[it]) / 2.0 }
    val radius = max(50.0, distance * 2.5)
    val eye = doubleArrayOf(middle[0], middle[1] + radius * 0.7, middle[2] + radius)
    val geo = coordinates.toGeographic(eye)
    val direction =
        coordinates.vectorFromLocal(
            geo[0],
            geo[1],
            FloatArray(3) { (middle[it] - eye[it]).toFloat() },
        )
    return a.copy(
        eyeLatitude = geo[0],
        eyeLongitude = geo[1],
        eyeAltitudeMeters = geo[2].toFloat(),
        viewAzimuthDegrees =
            Math.toDegrees(atan2(direction[0].toDouble(), -direction[2].toDouble())).toFloat(),
        viewElevationDegrees =
            Math.toDegrees(
                    atan2(
                        direction[1].toDouble(),
                        hypot(direction[0].toDouble(), direction[2].toDouble()),
                    )
                )
                .toFloat(),
        verticalFieldOfViewDegrees = 55f,
    )
}
