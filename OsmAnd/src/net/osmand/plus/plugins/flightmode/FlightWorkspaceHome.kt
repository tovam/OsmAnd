package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

/**
 * One library, filtered by purpose. Opening a local row never performs a transfer or starts GPS.
 */
@Composable
internal fun FlightWorkspaceHome(
    state: FlightUiState,
    onPage: (FlightPage) -> Unit,
    onOpen: (String) -> Unit,
    onDetails: (String) -> Unit,
    onNew: (Boolean) -> Unit,
    onImport: () -> Unit,
    onInternalTrack: () -> Unit,
    onClose: () -> Unit,
    onCloud: (String?) -> Unit,
) {
    val cloud = LocalFlightCloudUi.current?.controller
    var locationFilter by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    val planned =
        when (state.page) {
            FlightPage.PLANS -> true
            FlightPage.JOURNEYS -> false
            else -> null
        }
    val rows =
        flightLibraryRows(state.savedJourneys, cloud, planned).filter {
            it.matchesLocation(locationFilter) &&
                !(state.activeRecording.running && it.local?.id == state.activeRecording.journeyId)
        }
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0F13))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.flight_library_title),
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onNew(false) }) {
                Text(stringResource(R.string.flight_plan_new), fontSize = 12.sp)
            }
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(stringResource(R.string.shared_string_more), fontSize = 12.sp)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.flight_mode_load_osmand_track)) },
                        onClick = {
                            menu = false
                            onInternalTrack()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.flight_mode_import_journey_or_gpx)) },
                        onClick = {
                            menu = false
                            onImport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.flight_cloud_connection)) },
                        onClick = {
                            menu = false
                            onCloud(null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.flight_cloud_refresh)) },
                        onClick = {
                            menu = false
                            cloud?.refresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.flight_mode_close)) },
                        onClick = {
                            menu = false
                            onClose()
                        },
                    )
                }
            }
        }
        FlightActiveRecordingStrip(state, onOpen, onDetails)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                    FlightPage.HOME to R.string.flight_cloud_all,
                    FlightPage.PLANS to R.string.flight_library_planned,
                    FlightPage.JOURNEYS to R.string.flight_library_past,
                )
                .forEach { (page, label) ->
                    TextButton(onClick = { onPage(page) }, modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(label),
                            fontSize = 12.sp,
                            color = if (page == state.page) Color(0xFFFF8B38) else Color.LightGray,
                        )
                    }
                }
            Box {
                IconButton(onClick = { filterMenu = true }) {
                    Icon(
                        painterResource(R.drawable.ic_action_filter),
                        contentDescription =
                            stringResource(
                                when (locationFilter) {
                                    1 -> R.string.flight_cloud_phone
                                    2 -> R.string.flight_cloud_server
                                    else -> R.string.flight_library_location
                                }
                            ),
                        tint = if (locationFilter == 0) Color.LightGray else Color(0xFFFF8B38),
                    )
                }
                DropdownMenu(filterMenu, { filterMenu = false }) {
                    listOf(
                            R.string.flight_cloud_all,
                            R.string.flight_cloud_phone,
                            R.string.flight_cloud_server,
                        )
                        .forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    locationFilter = i
                                    filterMenu = false
                                },
                            )
                        }
                }
            }
        }
        FlightLibraryServerNotice()
        if (state.savedJourneysLoading || state.loadingTrip)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            if (rows.isEmpty() && !state.savedJourneysLoading)
                item {
                    Text(
                        stringResource(R.string.flight_library_empty),
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            items(rows, key = { it.key }) { row ->
                FlightCloudListRow(row, state, onOpen, onCloud, onDetails)
            }
        }
        (state.tripLoadError ?: state.journeySaveError ?: state.journeyMessage)?.let {
            Text(it, color = Color(0xFFFFCC66), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun FlightActiveRecordingStrip(
    state: FlightUiState,
    onOpen: (String) -> Unit,
    onDetails: (String) -> Unit,
) {
    val live = state.activeRecording
    val id = live.journeyId ?: return
    if (!live.running && live.error == null) return
    val name =
        state.savedJourneys.firstOrNull { it.id == id }?.name
            ?: live.trip?.name
            ?: stringResource(R.string.flight_workspace_current)
    val cloud = LocalFlightCloudUi.current?.controller
    val binding = cloud?.bindings?.firstOrNull { it.localId == id }
    val onServer = cloud?.remote?.any { it.id == (binding?.remoteId ?: id) } == true
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF15302F)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable { onOpen(id) }.padding(vertical = 6.dp)) {
            Text(
                name,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlightLibraryGpsLabel(id, state)
            Text(
                stringResource(
                    if (onServer && cloud?.serverVerified == true) R.string.flight_library_both
                    else if (onServer) R.string.flight_library_both_unverified
                    else R.string.flight_cloud_phone
                ) +
                    stringResource(
                        R.string.flight_library_active_points,
                        live.trip?.samples?.size ?: 0,
                    ),
                fontSize = 10.sp,
                color = Color.LightGray,
            )
        }
        TextButton(onClick = { onOpen(id) }, enabled = !state.loadingTrip) {
            Text(
                stringResource(
                    if (live.running) R.string.flight_library_show_current
                    else R.string.flight_cloud_open
                ),
                fontSize = 11.sp,
            )
        }
        TextButton(onClick = { onDetails(id) }, enabled = !state.loadingTrip) {
            Text(stringResource(R.string.flight_detail_title), fontSize = 11.sp)
        }
    }
}
