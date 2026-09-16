package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.Calendar
import java.util.SimpleTimeZone
import kotlin.math.*
import kotlinx.coroutines.*
import net.osmand.plus.R

@Composable
internal fun FlightPlanningScreen(
    state: FlightUiState,
    onUpdate: (FlightPlan) -> Unit,
    onSave: (Boolean) -> Unit,
    onPreload: (FlightOfflineQuote) -> Unit,
    onCancelPreload: () -> Unit,
    onSimulate: () -> Unit,
    onStart: () -> Unit,
    onPermissions: () -> Unit,
    onJournals: () -> Unit,
    onUpdateStop: (Int, String) -> Unit,
    onSelectCity: (Int, FlightCitySuggestion) -> Unit,
    onDismissCity: (Int) -> Unit,
    onDisarm: () -> Unit,
    onDetails: () -> Unit,
    initialSection: Int = 0,
    bottomNavigation: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val prep = state.plan.preparation ?: FlightPreparation()
    var quote by remember { mutableStateOf<FlightOfflineQuote?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mapEditor by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(initialSection.coerceIn(0, 2)) }
    var showSaveError by remember { mutableStateOf(false) }
    val canSimulate = FlightOfflinePreparation.canSimulate(state.plan)
    var confirmStart by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(0) }
    var source by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var freeBytes by remember { mutableLongStateOf(0) }
    val repository =
        remember(context) {
            FlightTerrainRepository(context.applicationContext as net.osmand.plus.OsmandApplication)
        }
    DisposableEffect(repository) { onDispose { repository.close() } }
    FlightVisibilityEffect(repository) { repository.setSceneWorkEnabled(it) }
    FlightResumedEffect(quote, state.offlinePreloadStatus.phase, section) {
        if (section != 1) return@FlightResumedEffect
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
    // Names and schedules do not change the geographic download manifest.
    val planKey = state.plan.stops.map { it.latitude to it.longitude } to prep.bands
    FlightResumedEffect(planKey, section) {
        if (section != 1) return@FlightResumedEffect
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
    val coverage by
        produceState<List<Pair<TerrainTileId, Int>>>(emptyList(), quote, source) {
            value =
                withContext(Dispatchers.Default) { quote?.preview(!source, palette) ?: emptyList() }
        }
    fun change(next: FlightPreparation) = onUpdate(state.plan.copy(preparation = next))
    fun addVia() {
        val stops = state.plan.stops.toMutableList()
        stops.add(stops.lastIndex, FlightStop(context.getString(R.string.flight_plan_via)))
        selected = stops.lastIndex - 1
        onUpdate(state.plan.copy(stops = stops, preparation = prep))
        mapEditor = true
    }
    val backAction by rememberUpdatedState(onJournals)
    val dispatcher =
        (context as? androidx.activity.OnBackPressedDispatcherOwner)?.onBackPressedDispatcher
    DisposableEffect(dispatcher, mapEditor) {
        val callback =
            object : androidx.activity.OnBackPressedCallback(!mapEditor) {
                override fun handleOnBackPressed() {
                    backAction()
                }
            }
        dispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0F13))) {
        Row(Modifier.fillMaxWidth()) {
            PlanAction(stringResource(R.string.flight_workspace_future), onJournals)
            Text(
                state.journeyName.ifBlank { stringResource(R.string.flight_plan_title) },
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f).padding(8.dp),
                maxLines = 2,
            )
            PlanAction(stringResource(R.string.flight_detail_title), onDetails)
        }
        Row(Modifier.fillMaxWidth()) {
            PlanAction(
                stringResource(R.string.flight_plan_save),
                { onSave(false) },
                enabled = !state.savingPreparation && !state.savingJourney,
            )
            PlanAction(
                stringResource(
                    if (state.simulationLoading) R.string.flight_test_preparing
                    else R.string.flight_detail_view_plan
                ),
                onSimulate,
                enabled = canSimulate && !state.simulationLoading,
            )
            Text(
                stringResource(
                    when {
                        state.savingPreparation || state.savingJourney ->
                            R.string.flight_plan_saving
                        state.journeySaveError != null -> R.string.flight_plan_save_failed_short
                        state.journeyDirty || state.journeyId == null ->
                            R.string.flight_plan_unsaved
                        else -> R.string.flight_plan_saved_short
                    }
                ),
                color =
                    if (
                        state.journeySaveError != null ||
                            state.journeyDirty ||
                            state.journeyId == null
                    )
                        Color(0xFFFFBD39)
                    else Color(0xFF2CDBBE),
                fontSize = 11.sp,
                modifier = Modifier.weight(1f).padding(4.dp),
            )
        }
        if (!canSimulate)
            Text(
                stringResource(R.string.flight_test_route_required),
                color = Color(0xFFFFBD39),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        state.simulationError?.let { Text(it, color = Color(0xFFFFBD39), fontSize = 11.sp) }
        state.journeySaveError?.let { errorText ->
            TextButton(onClick = { showSaveError = !showSaveError }) {
                Text(
                    stringResource(R.string.flight_plan_save_error_details),
                    fontSize = 11.sp,
                    color = Color(0xFFFFBD39),
                )
            }
            if (showSaveError)
                Text(
                    errorText,
                    color = Color(0xFFFFBD39),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            val scheduled = state.scheduledPreparation
            Text(
                if (scheduled == null) stringResource(R.string.flight_plan_auto_off)
                else
                    stringResource(
                        R.string.flight_plan_auto_at,
                        FlightPreparation.dateText(
                            state.scheduledStartMillis ?: scheduled.startMillis,
                            scheduled.departureOffsetMinutes,
                        ),
                        FlightPreparation.offsetText(scheduled.departureOffsetMinutes),
                    ),
                color = if (scheduled == null) Color.LightGray else Color(0xFF2CDBBE),
                fontSize = 11.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (scheduled != null) PlanAction(stringResource(R.string.flight_plan_disarm), onDisarm)
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(
                    R.string.flight_plan_section_route,
                    R.string.flight_plan_section_offline,
                    R.string.flight_plan_section_auto,
                )
                .forEachIndexed { index, label ->
                    TextButton(onClick = { section = index }, modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(label),
                            fontSize = 12.sp,
                            color = if (section == index) Color.White else Color.Gray,
                        )
                    }
                }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (section == 0)
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        state.plan.stops.forEachIndexed { i, stop ->
                            Row {
                                OutlinedTextField(
                                    value = stop.name,
                                    onValueChange = { onUpdateStop(i, it) },
                                    textStyle =
                                        androidx.compose.ui.text.TextStyle(
                                            color = Color.White,
                                            fontSize = 13.sp,
                                        ),
                                    singleLine = true,
                                    label = {
                                        Text("${i + 1}${if (stop.latitude != null) " ✓" else ""}")
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                PlanAction(
                                    stringResource(R.string.flight_plan_place),
                                    {
                                        selected = i
                                        mapEditor = true
                                    },
                                )
                                if (
                                    state.plan.stops.size > 2 &&
                                        i > 0 &&
                                        i < state.plan.stops.lastIndex
                                )
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
                        if (state.citySearchStopIndex != null) {
                            val stopIndex = state.citySearchStopIndex
                            if (state.citySearchLoading)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.citySuggestions.forEach { city ->
                                PlanAction(
                                    city.name,
                                    {
                                        onSelectCity(stopIndex, city)
                                        onDismissCity(stopIndex)
                                    },
                                )
                            }
                        }
                        PlanAction(stringResource(R.string.flight_plan_add_via), { addVia() })
                        FlightDateField(
                            stringResource(R.string.flight_plan_departure),
                            prep.departureMillis,
                            prep.departureOffsetMinutes,
                        ) { millis, offset ->
                            change(
                                prep.copy(departureMillis = millis, departureOffsetMinutes = offset)
                            )
                        }
                        FlightDateField(
                            stringResource(R.string.flight_plan_arrival),
                            prep.arrivalMillis,
                            prep.arrivalOffsetMinutes,
                        ) { millis, offset ->
                            change(prep.copy(arrivalMillis = millis, arrivalOffsetMinutes = offset))
                        }
                    }
                }
            if (section == 1)
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text(
                            stringResource(R.string.flight_plan_display_quality),
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                        SatelliteQualitySelector(
                            state.plan.satelliteQuality,
                            state.plan.terrainCorridorKm,
                            state.terrainScene?.zoom,
                            state.plan.stops.firstOrNull()?.latitude,
                            { onUpdate(state.plan.copy(satelliteQuality = it)) },
                        )
                        Row {
                            PlanNumber(
                                stringResource(R.string.flight_plan_display_fine),
                                state.plan.terrainFineZoom,
                                9..14,
                                Modifier.weight(1f),
                            ) { z ->
                                onUpdate(
                                    state.plan.copy(
                                        terrainFineZoom = z,
                                        terrainMiddleZoom = minOf(z, state.plan.terrainMiddleZoom),
                                    )
                                )
                            }
                            PlanNumber(
                                stringResource(R.string.flight_plan_display_middle),
                                state.plan.terrainMiddleZoom,
                                9..14,
                                Modifier.weight(1f),
                            ) { z ->
                                onUpdate(
                                    state.plan.copy(
                                        terrainMiddleZoom = z,
                                        terrainFineZoom = maxOf(z, state.plan.terrainFineZoom),
                                    )
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.flight_plan_display_quality_hint),
                            color = Color.LightGray,
                            fontSize = 11.sp,
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
                                stringResource(
                                    R.string.flight_plan_band_label,
                                    i + 1,
                                    band.radiusKm,
                                ),
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
                                    156543.03 * cos(Math.toRadians(latitude)) /
                                        2.0.pow(band.satelliteZoom),
                                    156543.03 * cos(Math.toRadians(latitude)) /
                                        2.0.pow(band.terrainZoom),
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
                            PlanAction(
                                stringResource(R.string.flight_plan_edit_map),
                                { mapEditor = true },
                            )
                            PlanAction(stringResource(R.string.flight_plan_add_via), { addVia() })
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
                                    q.satelliteCount,
                                    q.terrainCount,
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
                                stringResource(
                                    R.string.flight_plan_free_space,
                                    freeBytes / 1_073_741_824.0,
                                ),
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
                                            (1.0 -
                                                count.toDouble() /
                                                    q.requests.size.coerceAtLeast(1)) /
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
                                enabled =
                                    state.offlinePreloadStatus.phase !=
                                        FlightTerrainPhase.DOWNLOADING,
                            )
                        }
                        val offline = state.offlinePreloadStatus
                        if (offline.phase != FlightTerrainPhase.IDLE) {
                            Text(
                                "${stringResource(when(offline.phase) { FlightTerrainPhase.READY -> R.string.flight_plan_ready
                        FlightTerrainPhase.ERROR -> R.string.flight_plan_partial
                        FlightTerrainPhase.PAUSED -> R.string.flight_plan_pause_label
                        else -> R.string.flight_plan_downloading })} · ${offline.message?:""} · %.1f MB · %.1f MB/s"
                                    .format(
                                        offline.bytesDownloaded / 1e6,
                                        offline.bytesPerSecond / 1e6,
                                    ),
                                color =
                                    if (offline.phase == FlightTerrainPhase.READY) Color(0xFF2CDBBE)
                                    else Color.White,
                                fontSize = 11.sp,
                            )
                            if (offline.phase == FlightTerrainPhase.DOWNLOADING)
                                PlanAction(
                                    stringResource(R.string.flight_plan_pause),
                                    onCancelPreload,
                                )
                            Text(
                                stringResource(R.string.flight_plan_missing_explanation),
                                color = Color.LightGray,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            stringResource(R.string.flight_plan_horizon),
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            if (section == 2)
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text(
                            stringResource(R.string.flight_plan_automatic),
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                        Text(
                            if (prep.departureMillis > 0)
                                stringResource(
                                    R.string.flight_plan_auto_candidate,
                                    FlightPreparation.dateText(
                                        prep.startMillis,
                                        prep.departureOffsetMinutes,
                                    ),
                                    FlightPreparation.offsetText(prep.departureOffsetMinutes),
                                )
                            else stringResource(R.string.flight_plan_set_departure),
                            color = Color.LightGray,
                            fontSize = 12.sp,
                        )
                        if (
                            state.scheduledPreparation != null &&
                                state.scheduledPreparation !=
                                    prep.copy(
                                        automatic = true,
                                        bands = state.scheduledPreparation.bands,
                                    )
                        )
                            Text(
                                stringResource(R.string.flight_plan_auto_changed),
                                color = Color(0xFFFFBD39),
                                fontSize = 11.sp,
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
                        FlightPermissionChecklist()
                        PlanAction(stringResource(R.string.flight_plan_permissions), onPermissions)
                        if (state.scheduledPreparation != null)
                            PlanAction(
                                stringResource(R.string.flight_plan_disarm),
                                onDisarm,
                                enabled = !state.savingPreparation,
                            )
                        state.scheduleError?.let {
                            Text(it, color = Color(0xFFFFBD39), fontSize = 12.sp)
                        }
                        val validSchedule =
                            prep.departureMillis > 0 &&
                                prep.arrivalMillis > prep.departureMillis &&
                                prep.arrivalMillis > System.currentTimeMillis()
                        if (!validSchedule)
                            Text(
                                stringResource(R.string.flight_plan_auto_dates_required),
                                color = Color(0xFFFFBD39),
                                fontSize = 11.sp,
                            )
                        Row {
                            PlanAction(
                                stringResource(R.string.flight_plan_arm),
                                { onSave(true) },
                                enabled = !state.savingPreparation && validSchedule && canSimulate,
                            )
                        }
                        PlanAction(
                            stringResource(R.string.flight_detail_start_real),
                            { confirmStart = true },
                            enabled = canSimulate && !state.activeRecording.running,
                        )
                    }
                }
        }
        bottomNavigation()
    }
    if (confirmStart)
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            text = { Text(stringResource(R.string.flight_start_confirm)) },
            confirmButton = {
                PlanAction(
                    stringResource(R.string.flight_mode_start_live),
                    {
                        confirmStart = false
                        onStart()
                    },
                )
            },
            dismissButton = {
                PlanAction(stringResource(R.string.shared_string_cancel), { confirmStart = false })
            },
        )
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
                    PlanAction(stringResource(R.string.flight_plan_add_via), { addVia() })
                    if (selected > 0 && selected < state.plan.stops.lastIndex)
                        PlanAction(
                            stringResource(R.string.flight_plan_remove_via),
                            {
                                onUpdate(
                                    state.plan.copy(
                                        stops =
                                            state.plan.stops.filterIndexed { i, _ -> i != selected }
                                    )
                                )
                                selected = (selected - 1).coerceAtLeast(0)
                            },
                        )
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
    val context = LocalContext.current
    val picker = remember(context) { FlightPhotoLandmarkView(context) }
    DisposableEffect(picker) { onDispose { picker.release() } }
    AndroidView(
        factory = { picker },
        modifier = modifier.clipToBounds(),
        update = { v ->
            v.routeOverview = true
            v.gesturesEnabled = editable
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
    val context = LocalContext.current
    var zone by
        remember(offset) { mutableStateOf(FlightPreparation.offsetText(offset).replace(":", "")) }
    fun calendar() =
        Calendar.getInstance(SimpleTimeZone(offset * 60_000, "flight-offset")).apply {
            timeInMillis = millis.takeIf { it > 0 } ?: System.currentTimeMillis()
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    val date = FlightPreparation.dateText(millis, offset)
    Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    Row {
        PlanAction(
            date.takeIf { it.isNotBlank() }?.substringBefore(' ')
                ?: stringResource(R.string.flight_plan_pick_date),
            {
                val c = calendar()
                android.app
                    .DatePickerDialog(
                        context,
                        { _, y, m, d ->
                            c.set(Calendar.YEAR, y)
                            c.set(Calendar.MONTH, m)
                            c.set(Calendar.DAY_OF_MONTH, d)
                            onChange(c.timeInMillis, offset)
                        },
                        c.get(Calendar.YEAR),
                        c.get(Calendar.MONTH),
                        c.get(Calendar.DAY_OF_MONTH),
                    )
                    .show()
            },
        )
        PlanAction(
            date.takeIf { it.isNotBlank() }?.substringAfter(' ')
                ?: stringResource(R.string.flight_plan_pick_time),
            {
                val c = calendar()
                android.app
                    .TimePickerDialog(
                        context,
                        { _, h, m ->
                            c.set(Calendar.HOUR_OF_DAY, h)
                            c.set(Calendar.MINUTE, m)
                            onChange(c.timeInMillis, offset)
                        },
                        c.get(Calendar.HOUR_OF_DAY),
                        c.get(Calendar.MINUTE),
                        true,
                    )
                    .show()
            },
        )
        OutlinedTextField(
            zone,
            {
                val digits = it.filter(Char::isDigit).take(4)
                zone = (if (it.startsWith("-")) "-" else "+") + digits
                val o = FlightPreparation.parseOffset(zone)
                // An incomplete edit must never erase the stored schedule or reset its simulation.
                if (o != null) onChange(FlightPreparation.parseDate(date, o) ?: millis, o)
            },
            label = { Text(stringResource(R.string.flight_plan_utc_compact), fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            leadingIcon = {
                TextButton(
                    onClick = {
                        zone =
                            (if (zone.startsWith("-")) "+" else "-") +
                                zone.removePrefix("+").removePrefix("-")
                        FlightPreparation.parseOffset(zone)?.let { o ->
                            onChange(FlightPreparation.parseDate(date, o) ?: millis, o)
                        }
                    }
                ) {
                    Text("±")
                }
            },
            singleLine = true,
            modifier = Modifier.weight(1f).padding(2.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
            isError = FlightPreparation.parseOffset(zone) == null,
        )
    }
}

@Composable
private fun FlightPermissionChecklist() {
    val context = LocalContext.current
    val view = LocalView.current
    var statuses by remember { mutableStateOf(FlightScheduleManager.permissionStatuses(context)) }
    DisposableEffect(view, context) {
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME)
                statuses = FlightScheduleManager.permissionStatuses(context)
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    statuses.forEach { status ->
        Text(
            stringResource(
                if (status.granted) R.string.flight_plan_permission_ok
                else R.string.flight_plan_permission_missing,
                stringResource(status.label),
            ),
            color = if (status.granted) Color(0xFF2CDBBE) else Color(0xFFFFBD39),
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 3.dp),
        )
    }
}
