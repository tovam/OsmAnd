package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import net.osmand.plus.R

internal data class FlightCloudUi(
    val controller: FlightCloudController,
    val open: (String?) -> Unit,
    val save: () -> Unit,
)

internal val LocalFlightCloudUi = staticCompositionLocalOf<FlightCloudUi?> { null }

@Composable
internal fun FlightLibraryServerNotice() {
    val cloud = LocalFlightCloudUi.current?.controller ?: return
    if (cloud.busy)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(cloud.operation),
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = cloud::cancel) {
                Text(stringResource(R.string.shared_string_cancel), fontSize = 11.sp)
            }
        }
    cloud.message?.let { message ->
        Text(
            message,
            color = if (cloud.messageIsError) Color(0xFFFFCC66) else Color(0xFF88DEBF),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
    if (cloud.connection != null && !cloud.serverVerified && !cloud.busy) {
        Text(
            stringResource(R.string.flight_library_server_offline),
            color = Color(0xFFFFCC66),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

internal fun flightLibraryRows(
    local: List<FlightJourneySummary>,
    cloud: FlightCloudController?,
    planned: Boolean?,
): List<FlightLibraryRow> {
    return flightCloudRows(local, cloud?.remote.orEmpty(), cloud?.bindings.orEmpty())
        .filter { planned == null || it.isPlan() == planned }
        .sortedByDescending { it.local?.updatedAtMillis ?: it.remote!!.updatedAt }
}

@Composable
internal fun FlightStoragePill(label: String, warning: Boolean = false) {
    Text(
        label,
        color = if (warning) Color(0xFFFFCC66) else Color(0xFF88DEBF),
        fontSize = 10.sp,
        modifier =
            Modifier.background(
                    if (warning) Color(0xFF352C18) else Color(0xFF17342E),
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FlightCloudListRow(
    row: FlightLibraryRow,
    state: FlightUiState,
    onOpen: (String) -> Unit,
    onManage: (String) -> Unit,
    onDetails: (String) -> Unit = { onManage("local:$it") },
) {
    val cloud = LocalFlightCloudUi.current?.controller
    val local = row.local
    val dirty = state.journeyId == local?.id && state.journeyDirty
    val canOpen = row.canOpenLocal(state, local != null && cloud?.removingLocalId == local.id)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f)
                .clickable(enabled = canOpen) { local?.let { onOpen(it.id) } }
                .padding(vertical = 7.dp)
        ) {
            Text(
                row.name,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FlightStoragePill(
                    stringResource(
                        when {
                            local != null && row.remote != null && cloud?.serverVerified == true ->
                                R.string.flight_library_both
                            local != null && row.remote != null ->
                                R.string.flight_library_both_unverified
                            local != null -> R.string.flight_cloud_phone
                            cloud?.serverVerified == true -> R.string.flight_cloud_server
                            else -> R.string.flight_cloud_unverified
                        }
                    ),
                    row.remote != null && cloud?.serverVerified != true,
                )
                FlightLibraryGpsLabel(local?.id, state)
            }
            row.logicalCloudId?.let { cloudId ->
                Text(
                    stringResource(R.string.flight_journey_logical_id, shortFlightJourneyId(cloudId)),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                )
            }
            Text(
                stringResource(
                    when {
                        local?.simulation == true -> R.string.flight_library_test_contents
                        row.isPlan() -> R.string.flight_library_plan_contents
                        else -> R.string.flight_library_record_contents
                    },
                    state.activeRecording
                        .takeIf { it.journeyId == local?.id && it.running }
                        ?.trip
                        ?.samples
                        ?.size ?: local?.sampleCount ?: row.remote?.samples ?: 0,
                    local?.photoCount ?: row.remote?.photoIds?.size ?: 0,
                ) +
                    if (local != null)
                        stringResource(
                            R.string.flight_library_tile_total,
                            local.terrainTileCount + local.satelliteTileCount,
                        )
                    else "",
                color = Color.LightGray,
                fontSize = 11.sp,
            )
            val version = row.versionState(dirty, cloud?.serverVerified == true)
            if (
                dirty || local != null && row.remote != null && version != FlightVersionState.SENT
            ) {
                Text(
                    stringResource(
                        if (dirty) R.string.flight_sync_local_pending else version.shortLabel()
                    ),
                    fontSize = 10.sp,
                    color =
                        if (version == FlightVersionState.SENT && !dirty) Color(0xFF88DEBF)
                        else Color(0xFFFFCC66),
                )
            }
        }
        if (local != null) {
            TextButton(onClick = { onDetails(local.id) }, enabled = canOpen) {
                Text(stringResource(R.string.flight_detail_title), fontSize = 12.sp)
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { row.remote?.let { cloud?.download(it, onOpen) } },
                    enabled = cloud?.connection != null && cloud.busy == false && !state.loadingTrip,
                ) {
                    Text(stringResource(R.string.flight_library_get), fontSize = 11.sp)
                }
                TextButton(onClick = { onManage(row.key) }) {
                    Text(stringResource(R.string.flight_detail_title), fontSize = 11.sp)
                }
            }
        }
    }
    HorizontalDivider(color = Color(0xFF293740))
}

@Composable
internal fun FlightLibraryGpsLabel(id: String?, state: FlightUiState) {
    var elapsed by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var wall by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = state.activeRecording
    val activeHere = active.running && id != null && active.journeyId == id
    val alarm = state.localSchedules[id]
    FlightResumedEffect(activeHere, alarm) {
        do {
            elapsed = android.os.SystemClock.elapsedRealtime()
            wall = System.currentTimeMillis()
            kotlinx.coroutines.delay(if (activeHere) 1000 else 30_000)
        } while (activeHere || alarm != null)
    }
    // The one-second timer can precede a just-received fix. Read the clock at display time.
    val displayElapsed = flightDisplayElapsed(elapsed, android.os.SystemClock.elapsedRealtime())
    if (activeHere && !active.simulation) {
        Column {
            Text(stringResource(R.string.flight_live_recording_active), color = Color(0xFF88DEBF), fontSize = 11.sp)
            Text(stringResource(when (active.tracking.phase) {
                FlightTrackingPhase.WAITING -> R.string.flight_live_takeoff_undetected
                FlightTrackingPhase.AIRBORNE -> R.string.flight_live_airborne
                FlightTrackingPhase.LANDED -> R.string.flight_live_landed
                FlightTrackingPhase.STOPPED -> R.string.flight_live_stopped
            }), color = Color.LightGray, fontSize = 10.sp)
            FlightGpsCountAndSignal(active, displayElapsed)
        }
        return
    }
    val status =
        flightLibraryGpsState(
            id,
            active,
            state.localSchedules,
            state.localSchedulesLoaded,
            state.scheduleRequirementsMet,
            displayElapsed,
            wall,
        )
    val label =
        when (status) {
            FlightLibraryGpsState.RECORDING -> stringResource(R.string.flight_library_gps_recording)
            FlightLibraryGpsState.WAITING -> stringResource(R.string.flight_library_gps_waiting)
            FlightLibraryGpsState.STALE -> stringResource(R.string.flight_library_gps_lost)
            FlightLibraryGpsState.SIMULATING ->
                stringResource(R.string.flight_library_gps_simulated)
            FlightLibraryGpsState.SIMULATION_PAUSED ->
                stringResource(R.string.flight_library_gps_sim_paused)
            FlightLibraryGpsState.SCHEDULED ->
                stringResource(
                    R.string.flight_library_gps_scheduled,
                    FlightPreparation.dateText(alarm!!.startMillis, alarm.offsetMinutes),
                    FlightPreparation.offsetText(alarm.offsetMinutes),
                )
            FlightLibraryGpsState.SCHEDULE_BLOCKED ->
                stringResource(R.string.flight_library_gps_blocked)
            FlightLibraryGpsState.SCHEDULE_OVERDUE ->
                stringResource(R.string.flight_library_gps_overdue)
            FlightLibraryGpsState.UNCHECKED -> stringResource(R.string.flight_library_gps_unchecked)
            FlightLibraryGpsState.ERROR ->
                stringResource(
                    if (active.running) R.string.flight_library_gps_active_error
                    else R.string.flight_library_gps_error
                )
            FlightLibraryGpsState.OFF -> stringResource(R.string.flight_library_gps_off)
        }
    Text(
        label,
        fontSize = 11.sp,
        color =
            when (status) {
                FlightLibraryGpsState.RECORDING -> Color(0xFF88DEBF)
                FlightLibraryGpsState.SIMULATING,
                FlightLibraryGpsState.SIMULATION_PAUSED -> Color(0xFF5DD8FF)
                FlightLibraryGpsState.ERROR,
                FlightLibraryGpsState.STALE,
                FlightLibraryGpsState.SCHEDULE_BLOCKED,
                FlightLibraryGpsState.SCHEDULE_OVERDUE -> Color(0xFFFFCC66)
                else -> Color.LightGray
            },
    )
}

private fun FlightVersionState.shortLabel(): Int =
    when (this) {
        FlightVersionState.SENT -> R.string.flight_library_version_same
        FlightVersionState.BOTH_CHANGED -> R.string.flight_library_version_both
        FlightVersionState.SERVER_CHANGED -> R.string.flight_library_version_server
        FlightVersionState.LOCAL_CHANGED -> R.string.flight_library_version_phone
        FlightVersionState.PARTIAL -> R.string.flight_library_version_partial
        else -> label()
    }

internal fun FlightVersionState.label(): Int =
    when (this) {
        FlightVersionState.UNVERIFIED -> R.string.flight_cloud_unverified
        FlightVersionState.NOT_SENT -> R.string.flight_sync_not_uploaded
        FlightVersionState.SERVER_ONLY -> R.string.flight_cloud_server_only
        FlightVersionState.BOTH_CHANGED -> R.string.flight_sync_both_changed
        FlightVersionState.SERVER_CHANGED -> R.string.flight_cloud_server_changed
        FlightVersionState.LOCAL_CHANGED -> R.string.flight_cloud_local_changed
        FlightVersionState.PARTIAL -> R.string.flight_sync_partial
        FlightVersionState.SENT -> R.string.flight_sync_sent
    }

internal fun flightVersionDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** Local autosave and server publication are deliberately two separate states. */
@Composable
internal fun FlightStorageStatusStrip(state: FlightUiState, compact: Boolean = false) {
    val ui = LocalFlightCloudUi.current ?: return
    val cloud = ui.controller
    val binding = cloud.bindings.firstOrNull { it.localId == state.journeyId }
    val remote = cloud.remote.firstOrNull { it.id == (binding?.remoteId ?: state.journeyId) }
    val local = state.savedJourneys.firstOrNull { it.id == state.journeyId }
    val pending = state.journeyDirty || local == null
    val serverLabel =
        when {
            cloud.connection == null -> R.string.flight_sync_not_connected
            else ->
                FlightLibraryRow(local, remote, binding)
                    .versionState(pending, cloud.serverVerified)
                    .label()
        }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF101B22))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when {
                            state.journeySaveError != null -> R.string.flight_sync_local_failed
                            state.savingJourney -> R.string.flight_local_saving
                            pending -> R.string.flight_sync_local_pending
                            else -> R.string.flight_sync_local_saved
                        }
                    ),
                    color =
                        if (pending || state.journeySaveError != null) Color(0xFFFFCC66)
                        else Color(0xFF88DEBF),
                    fontSize = 10.sp,
                )
                Text(
                    stringResource(R.string.flight_sync_server_state, stringResource(serverLabel)),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                )
            }
            if (!compact)
                if (state.journeySaveError != null) {
                    TextButton(onClick = ui.save, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.flight_local_retry_save), fontSize = 11.sp)
                    }
                } else
                    TextButton(onClick = { ui.open(state.journeyId?.let { "local:$it" }) },
                        modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.flight_sync_manage), fontSize = 11.sp)
                    }
        }
    }
}
