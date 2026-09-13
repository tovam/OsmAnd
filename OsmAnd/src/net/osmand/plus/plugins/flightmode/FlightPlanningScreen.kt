package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.*
import kotlinx.coroutines.*
import net.osmand.plus.R

@Composable
internal fun FlightPlanningScreen(
    state: FlightUiState,
    onClose: () -> Unit,
    onUpdate: (FlightPlan) -> Unit,
    onSave: (Boolean) -> Unit,
    onPreload: (FlightOfflineQuote) -> Unit,
    onCancelPreload: () -> Unit,
    onSimulate: () -> Unit,
    onStart: () -> Unit,
    onPermissions: () -> Unit,
    onImport: () -> Unit,
    onInternal: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prep = state.plan.preparation ?: FlightPreparation()
    var quote by remember { mutableStateOf<FlightOfflineQuote?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mapEditor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(0) }
    var source by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var freeBytes by remember { mutableLongStateOf(0) }
    val repository =
        remember(context) {
            FlightTerrainRepository(context.applicationContext as net.osmand.plus.OsmandApplication)
        }
    LaunchedEffect(quote, state.offlinePreloadStatus.phase) {
        freeBytes = withContext(Dispatchers.IO) { context.filesDir.usableSpace }
        existing = null
        quote?.let { existing = repository.existingPreparationBytes(it) }
    }
    val palette =
        listOf(
            0x808D5CFF.toInt(),
            0x80FFBD39.toInt(),
            0x802CDBBE.toInt(),
            0x806CB6FF.toInt(),
            0x80F45F8E.toInt(),
            0x80FFFFFF.toInt(),
        )
    val planKey = state.plan.stops to prep.bands
    LaunchedEffect(planKey) {
        quote = null
        error = null
        quoting = true
        delay(400)
        try {
            quote =
                withContext(Dispatchers.Default) {
                    FlightOfflinePreparation.quote(state.plan.copy(preparation = prep))
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            quoting = false
        }
    }
    val coverage =
        remember(quote, source) {
            quote
                ?.requests
                ?.filter { it.band >= 0 && it.satellite == !source }
                ?.sortedByDescending { it.band }
                ?.map { r ->
                    // Overview aggregation bounds Canvas work; download counts use the full exact
                    // manifest.
                    val z = min(r.tile.zoom, 8)
                    val shift = r.tile.zoom - z
                    TerrainTileId(z, r.tile.x shr shift, r.tile.y shr shift) to
                        palette[r.band % palette.size]
                }
                ?.distinct() ?: emptyList()
        }
    fun change(next: FlightPreparation) = onUpdate(state.plan.copy(preparation = next))
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0F13))) {
        Row {
            PlanAction(stringResource(R.string.flight_plan_new), { onNew(false) })
            PlanAction(stringResource(R.string.flight_plan_repeat), { onNew(true) })
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.flight_plan_title),
                color = Color.White,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f).padding(8.dp),
            )
            PlanAction(stringResource(R.string.flight_mode_close), onClose)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)
        ) {
            Text(
                stringResource(R.string.flight_plan_route_hint),
                color = Color.LightGray,
                fontSize = 11.sp,
            )
            state.plan.stops.forEachIndexed { i, stop ->
                Row {
                    Text(
                        "${i+1} · ${stop.name}  ${stop.latitude?.let { "%.3f".format(it) }?:"—"}, ${stop.longitude?.let { "%.3f".format(it) }?:"—"}",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                    )
                    PlanAction(
                        stringResource(R.string.flight_plan_place),
                        {
                            selected = i
                            mapEditor = true
                        },
                    )
                    if (state.plan.stops.size > 2 && i > 0 && i < state.plan.stops.lastIndex)
                        PlanAction(
                            "−",
                            {
                                onUpdate(
                                    state.plan.copy(
                                        stops =
                                            state.plan.stops.filterIndexed { index, _ ->
                                                index != i
                                            },
                                        preparation = prep,
                                    )
                                )
                            },
                        )
                }
            }
            PlanAction(
                stringResource(R.string.flight_plan_add_via),
                {
                    val stops = state.plan.stops.toMutableList()
                    stops.add(
                        stops.lastIndex,
                        FlightStop(context.getString(R.string.flight_plan_via)),
                    )
                    selected = stops.lastIndex - 1
                    onUpdate(state.plan.copy(stops = stops, preparation = prep))
                    mapEditor = true
                },
            )
            FlightDateField(
                stringResource(R.string.flight_plan_departure),
                prep.departureMillis,
                prep.departureOffsetMinutes,
            ) { millis, offset ->
                change(prep.copy(departureMillis = millis, departureOffsetMinutes = offset))
            }
            FlightDateField(
                stringResource(R.string.flight_plan_arrival),
                prep.arrivalMillis,
                prep.arrivalOffsetMinutes,
            ) { millis, offset ->
                change(prep.copy(arrivalMillis = millis, arrivalOffsetMinutes = offset))
            }
            Text(
                stringResource(R.string.flight_plan_time_hint),
                color = Color.LightGray,
                fontSize = 10.sp,
            )
            Text(
                stringResource(R.string.flight_plan_bands),
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.flight_plan_quality_hint),
                color = Color.LightGray,
                fontSize = 10.sp,
            )
            prep.bands.forEachIndexed { i, band ->
                val color = Color(palette[i % palette.size]).copy(alpha = 1f)
                Text(
                    stringResource(R.string.flight_plan_band_label, i + 1, band.radiusKm),
                    color = color,
                    fontSize = 12.sp,
                )
                Row {
                    PlanNumber(
                        "km",
                        band.radiusKm,
                        ((prep.bands.getOrNull(i - 1)?.radiusKm ?: 0) + 1)..((prep.bands
                                .getOrNull(i + 1)
                                ?.radiusKm ?: 601) - 1),
                        Modifier.weight(1f),
                    ) { n ->
                        change(
                            prep.copy(
                                bands =
                                    prep.bands.mapIndexed { k, b ->
                                        if (k == i) b.copy(radiusKm = n) else b
                                    }
                            )
                        )
                    }
                    PlanNumber(
                        stringResource(R.string.flight_plan_satellite_z),
                        band.satelliteZoom,
                        3..14,
                        Modifier.weight(1f),
                    ) { n ->
                        change(
                            prep.copy(
                                bands =
                                    prep.bands.mapIndexed { k, b ->
                                        if (k == i) b.copy(satelliteZoom = n) else b
                                    }
                            )
                        )
                    }
                    PlanNumber(
                        stringResource(R.string.flight_plan_terrain_z),
                        band.terrainZoom,
                        3..14,
                        Modifier.weight(1f),
                    ) { n ->
                        change(
                            prep.copy(
                                bands =
                                    prep.bands.mapIndexed { k, b ->
                                        if (k == i) b.copy(terrainZoom = n) else b
                                    }
                            )
                        )
                    }
                }
                val latitude = state.plan.stops.firstOrNull()?.latitude ?: 45.0
                Text(
                    stringResource(
                        R.string.flight_plan_resolution,
                        156543.03 * cos(Math.toRadians(latitude)) / 2.0.pow(band.satelliteZoom),
                        156543.03 * cos(Math.toRadians(latitude)) / 2.0.pow(band.terrainZoom),
                    ),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                )
            }
            Row {
                PlanAction(
                    stringResource(R.string.flight_plan_preview_sat),
                    { source = false },
                    !source,
                )
                PlanAction(
                    stringResource(R.string.flight_plan_preview_dem),
                    { source = true },
                    source,
                )
                PlanAction(stringResource(R.string.flight_plan_edit_map), { mapEditor = true })
            }
            FlightPlanMap(
                state.plan,
                selected,
                coverage,
                false,
                Modifier.fillMaxWidth().height(260.dp),
                { _, _ -> },
                {},
            )
            Text(
                stringResource(R.string.flight_plan_preview_hint),
                color = Color.LightGray,
                fontSize = 10.sp,
            )
            if (quoting) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = Color(0xFFFFBD39), fontSize = 11.sp) }
            quote?.let { q ->
                Text(
                    stringResource(
                        R.string.flight_plan_estimate,
                        q.requests.count { it.satellite },
                        q.requests.count { !it.satellite },
                        q.estimatedBytes / 1_073_741_824.0,
                    ),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Text(
                    stringResource(R.string.flight_plan_estimate_hint),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                )
                Text(
                    stringResource(R.string.flight_plan_free_space, freeBytes / 1_073_741_824.0),
                    color = Color.LightGray,
                    fontSize = 11.sp,
                )
                existing?.let { (count, bytes) ->
                    Text(
                        stringResource(
                            R.string.flight_plan_cached_estimate,
                            count,
                            bytes / 1_073_741_824.0,
                            q.estimatedBytes *
                                (1.0 - count.toDouble() / q.requests.size.coerceAtLeast(1)) /
                                1_073_741_824.0,
                        ),
                        color = Color.LightGray,
                        fontSize = 11.sp,
                    )
                }
                PlanAction(
                    stringResource(R.string.flight_plan_download),
                    {
                        onUpdate(state.plan.copy(preparation = prep))
                        onPreload(q)
                    },
                    enabled = state.offlinePreloadStatus.phase != FlightTerrainPhase.DOWNLOADING,
                )
            }
            val offline = state.offlinePreloadStatus
            if (offline.phase != FlightTerrainPhase.IDLE) {
                Text(
                    "${stringResource(when(offline.phase) { FlightTerrainPhase.READY -> R.string.flight_plan_ready
                        FlightTerrainPhase.ERROR -> R.string.flight_plan_partial
                        else -> R.string.flight_plan_downloading })} · ${offline.message?:""} · %.1f MB · %.1f MB/s"
                        .format(offline.bytesDownloaded / 1e6, offline.bytesPerSecond / 1e6),
                    color =
                        if (offline.phase == FlightTerrainPhase.READY) Color(0xFF2CDBBE)
                        else Color.White,
                    fontSize = 11.sp,
                )
                if (offline.phase == FlightTerrainPhase.DOWNLOADING)
                    PlanAction(stringResource(R.string.flight_plan_pause), onCancelPreload)
            }
            Text(
                stringResource(R.string.flight_plan_horizon),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                stringResource(R.string.flight_plan_automatic),
                color = Color.White,
                fontSize = 14.sp,
            )
            Text(
                stringResource(R.string.flight_plan_automatic_hint),
                color = Color.LightGray,
                fontSize = 10.sp,
            )
            Row {
                PlanNumber(
                    stringResource(R.string.flight_plan_before),
                    prep.startMinutesBefore,
                    0..180,
                    Modifier.weight(1f),
                ) {
                    change(prep.copy(startMinutesBefore = it))
                }
                PlanNumber(
                    stringResource(R.string.flight_plan_gain),
                    prep.airborneGainMeters,
                    100..3000,
                    Modifier.weight(1f),
                ) {
                    change(prep.copy(airborneGainMeters = it))
                }
                PlanNumber(
                    stringResource(R.string.flight_plan_takeoff_speed),
                    prep.airborneSpeedKmh,
                    100..400,
                    Modifier.weight(1f),
                ) {
                    change(prep.copy(airborneSpeedKmh = it))
                }
            }
            Row {
                PlanNumber(
                    stringResource(R.string.flight_plan_stop_speed),
                    prep.stopSpeedKmh,
                    5..100,
                    Modifier.weight(1f),
                ) {
                    change(prep.copy(stopSpeedKmh = it))
                }
                PlanNumber(
                    stringResource(R.string.flight_plan_stop_minutes),
                    prep.stopMinutes,
                    5..120,
                    Modifier.weight(1f),
                ) {
                    change(prep.copy(stopMinutes = it))
                }
            }
            PlanAction(stringResource(R.string.flight_plan_permissions), onPermissions)
            state.journeyMessage?.let { Text(it, color = Color(0xFFFFBD39), fontSize = 12.sp) }
            Row {
                PlanAction(
                    stringResource(R.string.flight_plan_save),
                    {
                        change(prep.copy(automatic = false))
                        onSave(false)
                    },
                )
                PlanAction(
                    stringResource(R.string.flight_plan_arm),
                    {
                        change(prep.copy(automatic = true))
                        onSave(true)
                    },
                    enabled =
                        prep.departureMillis > 0 &&
                            prep.arrivalMillis > prep.departureMillis &&
                            state.plan.stops.all { it.latitude != null && it.longitude != null },
                )
            }
            PlanAction(
                stringResource(R.string.flight_plan_rehearse),
                onSimulate,
                enabled = quote != null,
            )
            if (state.savedJourneys.isNotEmpty())
                Text(
                    stringResource(R.string.flight_mode_saved_journeys, state.savedJourneys.size),
                    color = Color.White,
                    fontSize = 13.sp,
                )
            state.savedJourneys.forEach { j -> PlanAction(j.name, { onOpen(j.id) }) }
            Row {
                PlanAction(stringResource(R.string.flight_mode_load_gpx_file), onImport)
                PlanAction(stringResource(R.string.flight_mode_load_osmand_track), onInternal)
            }
        }
        PlanAction(stringResource(R.string.flight_mode_start_live), onStart)
    }
    if (mapEditor)
        Dialog(
            onDismissRequest = { mapEditor = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(Color(0xFF0A0F13))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    state.plan.stops.forEachIndexed { i, s ->
                        PlanAction("${i+1} ${s.name}", { selected = i }, selected == i)
                    }
                }
                Text(
                    stringResource(R.string.flight_plan_map_hint, selected + 1),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                FlightPlanMap(
                    state.plan,
                    selected,
                    coverage,
                    true,
                    Modifier.weight(1f),
                    { lat, lon ->
                        onUpdate(
                            state.plan.copy(
                                preparation = prep,
                                stops =
                                    state.plan.stops.mapIndexed { i, s ->
                                        if (i == selected) s.copy(latitude = lat, longitude = lon)
                                        else s
                                    },
                            )
                        )
                    },
                    { selected = it },
                )
                PlanAction(stringResource(R.string.shared_string_done), { mapEditor = false })
            }
        }
}

@Composable
private fun FlightPlanMap(
    plan: FlightPlan,
    selected: Int,
    coverage: List<Pair<TerrainTileId, Int>>,
    editable: Boolean,
    modifier: Modifier,
    onPoint: (Double, Double) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val points =
        plan.stops.map { FlightPhotoControlPoint(latitude = it.latitude, longitude = it.longitude) }
    val first = plan.stops.firstOrNull()
    val origin =
        FlightPhotoSpatialPose(
            0.0,
            null,
            first?.latitude ?: 45.0,
            first?.longitude ?: 0.0,
            0f,
            0f,
            0f,
            -90f,
            60f,
        )
    val route =
        remember(plan.stops) {
            val coordinates =
                plan.stops.mapNotNull { s ->
                    s.latitude?.let { lat -> s.longitude?.let { lat to it } }
                }
            val samples =
                coordinates.zipWithNext().flatMap { (a, b) ->
                    (0..48).map { i ->
                        val p = FlightTerrainTilePlanner.greatCircleInterpolate(a, b, i / 48.0)
                        FlightSample(i, 0, 0, p.first, p.second, null, null, null, null)
                    }
                }
            recordedFlightTrip("", samples)
        }
    AndroidView(
        factory = { FlightPhotoLandmarkView(it) },
        modifier = modifier,
        update = { v ->
            v.routeOverview = true
            v.autoFitRoute = !editable
            v.routePaddingKm =
                (plan.preparation?.bands?.maxOfOrNull { it.radiusKm } ?: 300).toDouble()
            v.coverage = coverage
            v.onMapPoint = { lat, lon -> if (editable) onPoint(lat, lon) }
            v.update(
                null,
                FlightPhotoCalibration(points = points),
                selected,
                1,
                origin,
                null,
                route,
                true,
            )
        },
    )
}

@Composable
internal fun PlanAction(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            color =
                if (!enabled) Color.Gray
                else if (selected) Color(0xFFFFBD39) else Color(0xFF88CFFF),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun PlanNumber(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier,
    onValue: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        {
            text = it
            it.toIntOrNull()?.takeIf { n -> n in range }?.let(onValue)
        },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        modifier = modifier.padding(2.dp),
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
        isError = text.toIntOrNull()?.let { it !in range } != false,
    )
}

@Composable
private fun FlightDateField(
    label: String,
    millis: Long,
    offset: Int,
    onChange: (Long, Int) -> Unit,
) {
    var text by remember { mutableStateOf(FlightPreparation.dateText(millis, offset)) }
    var zone by remember { mutableStateOf(FlightPreparation.offsetText(offset)) }
    Row {
        OutlinedTextField(
            text,
            {
                text = it
                onChange(FlightPreparation.parseDate(it, offset) ?: 0, offset)
            },
            label = { Text(label, fontSize = 10.sp) },
            placeholder = { Text("yyyy-MM-dd HH:mm", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.weight(2f).padding(2.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
            isError = text.isNotBlank() && FlightPreparation.parseDate(text, offset) == null,
        )
        OutlinedTextField(
            zone,
            {
                zone = it
                val o = FlightPreparation.parseOffset(it)
                onChange(
                    if (o == null) 0 else FlightPreparation.parseDate(text, o) ?: 0,
                    o ?: offset,
                )
            },
            label = { Text("UTC ±HH:mm", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).padding(2.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
            isError = FlightPreparation.parseOffset(zone) == null,
        )
    }
}
