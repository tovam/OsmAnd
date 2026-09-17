package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

/** A flight's status and actions, without conflating viewing, testing, recording and publishing. */
@Composable
internal fun FlightDetailScreen(
    state: FlightUiState,
    onPage: (FlightPage) -> Unit,
    onPlanSection: (Int) -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onRename: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDisarm: () -> Unit,
    onSimulate: () -> Unit,
    onOffline: (Boolean) -> Unit,
    onPolicy: (FlightRecordingPolicy) -> Unit,
    onClone: () -> Unit,
) {
    val prepared = state.sessionMode == FlightSessionMode.PREPARE && !state.simulatedJourney
    val active = state.activeRecording.takeIf { it.journeyId == state.journeyId && it.running }
    val canView =
        if (prepared) FlightOfflinePreparation.canSimulate(state.plan)
        else !state.trip?.samples.isNullOrEmpty() || active != null
    val summary = state.savedJourneys.firstOrNull { it.id == state.journeyId }
    var tests by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }
    val cloud = LocalFlightCloudUi.current
    LazyColumn(
        Modifier.fillMaxSize().background(Color(0xFF0A0F13)),
        contentPadding = PaddingValues(8.dp),
    ) {
        item {
            if (editingName)
                OutlinedTextField(
                    value = state.journeyName,
                    onValueChange = onRename,
                    label = { Text(stringResource(R.string.flight_detail_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            else Text(state.journeyName, color = Color.White, fontSize = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlightLibraryGpsLabel(state.journeyId, state)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editingName = !editingName }) {
                    Text(
                        stringResource(
                            if (editingName) R.string.shared_string_done
                            else R.string.flight_detail_rename
                        ),
                        fontSize = 11.sp,
                    )
                }
            }
            if (state.simulatedJourney)
                Text(
                    stringResource(R.string.flight_detail_simulated),
                    color = Color(0xFF5DD8FF),
                    fontSize = 11.sp,
                )
            Text(
                stringResource(
                    R.string.flight_detail_contents,
                    if (state.previewingPlan) summary?.sampleCount ?: 0
                    else state.trip?.samples?.size ?: 0,
                    state.photos.size,
                    state.offlineAssets.terrainTileCount,
                    state.offlineAssets.standardSatelliteTileCount,
                ),
                color = Color.LightGray,
                fontSize = 12.sp,
            )
            Text(
                stringResource(R.string.flight_detail_assets_hint),
                color = Color.Gray,
                fontSize = 11.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onPage(FlightPage.MAP) },
                    enabled = canView && !state.simulationLoading && !state.loadingTrip,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            when {
                                active != null -> R.string.flight_library_show_current
                                prepared -> R.string.flight_detail_view_plan
                                else -> R.string.flight_detail_replay
                            }
                        ),
                        fontSize = 13.sp,
                    )
                }
                if (active == null)
                    Box {
                        TextButton(
                            onClick = { tests = true },
                            enabled = canView && !state.loadingTrip,
                        ) {
                            Text(stringResource(R.string.flight_detail_test), fontSize = 12.sp)
                        }
                        DropdownMenu(tests, { tests = false }) {
                            DropdownMenuItem(
                                text = {
                                    DetailMenuLabel(
                                        R.string.flight_detail_test_offline,
                                        R.string.flight_detail_test_offline_hint,
                                    )
                                },
                                onClick = {
                                    tests = false
                                    onOffline(true)
                                    onPage(FlightPage.MAP)
                                },
                                enabled = !state.activeRecording.running,
                            )
                            DropdownMenuItem(
                                text = {
                                    DetailMenuLabel(
                                        R.string.flight_detail_test_live,
                                        R.string.flight_detail_test_live_hint,
                                    )
                                },
                                onClick = {
                                    tests = false
                                    onSimulate()
                                },
                                enabled = !state.activeRecording.running,
                            )
                        }
                    }
            }
            if (prepared && !canView)
                Text(
                    stringResource(R.string.flight_test_route_required),
                    color = Color(0xFFFFCC66),
                    fontSize = 11.sp,
                )
            state.simulationError?.let { Text(it, color = Color(0xFFFFCC66), fontSize = 11.sp) }
            state.liveState.error
                ?.takeIf { state.liveState.journeyId == state.journeyId }
                ?.let { Text(it, color = Color(0xFFFFCC66), fontSize = 11.sp) }
        }
        if (prepared)
            item {
                Row {
                    TextButton(onClick = { onPlanSection(0) }) {
                        Text(stringResource(R.string.flight_detail_route), fontSize = 12.sp)
                    }
                    TextButton(onClick = { onPlanSection(1) }) {
                        Text(stringResource(R.string.flight_detail_downloads), fontSize = 12.sp)
                    }
                }
                DetailSection(R.string.flight_detail_departure)
                val alarm = state.localSchedules[state.journeyId]
                Text(
                    if (alarm == null)
                        stringResource(
                            if (state.localSchedulesLoaded) R.string.flight_plan_auto_off
                            else R.string.flight_library_gps_unchecked
                        )
                    else
                        stringResource(
                            R.string.flight_plan_auto_at,
                            FlightPreparation.dateText(alarm.startMillis, alarm.offsetMinutes),
                            FlightPreparation.offsetText(alarm.offsetMinutes),
                        ),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                val programmed = state.scheduledPreparation
                if (
                    programmed != null &&
                        flightScheduleSettingsDiffer(programmed, state.plan.preparation)
                )
                    Text(
                        stringResource(R.string.flight_detail_schedule_changed),
                        color = Color(0xFFFFCC66),
                        fontSize = 11.sp,
                    )
                Row {
                    TextButton(onClick = { onPlanSection(2) }) {
                        Text(
                            stringResource(R.string.flight_detail_schedule_manage),
                            fontSize = 12.sp,
                        )
                    }
                    if (alarm != null)
                        TextButton(onClick = onDisarm) {
                            Text(stringResource(R.string.flight_plan_disarm), fontSize = 12.sp)
                        }
                }
                TextButton(
                    onClick = { confirmStart = true },
                    enabled = canView && !state.activeRecording.running && !state.loadingTrip,
                ) {
                    Text(stringResource(R.string.flight_detail_start_real), fontSize = 12.sp)
                }
                if (state.activeRecording.running)
                    Text(
                        stringResource(R.string.flight_detail_other_recording),
                        color = Color(0xFFFFCC66),
                        fontSize = 11.sp,
                    )
                state.scheduleError?.let { Text(it, color = Color(0xFFFFCC66), fontSize = 11.sp) }
            }
        if (active != null)
            item {
                DetailSection(R.string.flight_detail_recording)
                Text(
                    stringResource(
                        when (active.tracking.phase) {
                            FlightTrackingPhase.WAITING -> R.string.flight_live_waiting
                            FlightTrackingPhase.AIRBORNE -> R.string.flight_live_airborne
                            FlightTrackingPhase.LANDED -> R.string.flight_live_landed
                            FlightTrackingPhase.STOPPED -> R.string.flight_live_stopped
                        }
                    ),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                TextButton(onClick = { onPage(FlightPage.LIVE) }) {
                    Text(stringResource(R.string.flight_detail_live_controls), fontSize = 12.sp)
                }
                TextButton(onClick = { confirmStop = true }) {
                    Text(stringResource(R.string.flight_detail_stop), fontSize = 12.sp)
                }
            }
        if (active != null || prepared)
            item {
                TextButton(
                    onClick = { showPolicy = !showPolicy },
                    enabled = active != null || !state.activeRecording.running,
                ) {
                    Text(
                        stringResource(
                            if (active != null) R.string.flight_detail_cadence_live
                            else R.string.flight_detail_cadence_defaults
                        ),
                        fontSize = 12.sp,
                    )
                }
                if (showPolicy && (active != null || !state.activeRecording.running))
                    FlightRecordingPolicyControls(
                        active?.policy ?: state.recordingPolicy,
                        onPolicy,
                        active,
                    )
            }
        item {
            DetailSection(R.string.flight_detail_storage)
            FlightStorageStatusStrip(state, compact = true)
            Row {
                TextButton(onClick = onSave, enabled = !state.savingJourney) {
                    Text(stringResource(R.string.flight_plan_save), fontSize = 12.sp)
                }
                TextButton(onClick = { cloud?.open(state.journeyId?.let { "local:$it" }) }) {
                    Text(stringResource(R.string.flight_detail_server_versions), fontSize = 12.sp)
                }
            }
            TextButton(onClick = { onPage(FlightPage.JOURNAL) }) {
                Text(stringResource(R.string.flight_mode_storage), fontSize = 12.sp)
            }
            Row {
                TextButton(
                    onClick = onExport,
                    enabled = !state.previewingPlan && !state.trip?.samples.isNullOrEmpty(),
                ) {
                    Text(stringResource(R.string.flight_mode_export_journey), fontSize = 12.sp)
                }
                if (active == null && !state.simulatedJourney)
                    TextButton(onClick = onClone) {
                        Text(stringResource(R.string.flight_detail_clone), fontSize = 12.sp)
                    }
            }
            Text(
                stringResource(R.string.flight_export_separate_track_note),
                color = Color.LightGray,
                fontSize = 10.sp,
            )
        }
    }
    if (confirmStart)
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text(stringResource(R.string.flight_detail_start_real)) },
            text = { Text(stringResource(R.string.flight_start_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStart = false
                        onStart()
                    }
                ) {
                    Text(stringResource(R.string.flight_detail_start_real))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) {
                    Text(stringResource(R.string.shared_string_cancel))
                }
            },
        )
    if (confirmStop)
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.flight_detail_stop)) },
            text = { Text(stringResource(R.string.flight_detail_stop_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        onStop()
                    }
                ) {
                    Text(stringResource(R.string.flight_detail_stop))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.shared_string_cancel))
                }
            },
        )
}

@Composable
private fun DetailMenuLabel(title: Int, hint: Int) {
    Column {
        Text(stringResource(title), fontSize = 13.sp)
        Text(stringResource(hint), fontSize = 11.sp, color = Color.LightGray)
    }
}

@Composable
private fun DetailSection(title: Int) {
    HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 6.dp), color = Color(0xFF2A3842))
    Text(stringResource(title), color = Color.White, fontSize = 14.sp)
}
