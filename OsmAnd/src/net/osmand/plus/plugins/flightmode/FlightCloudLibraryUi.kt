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
internal fun FlightLibraryFilters(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(R.string.flight_cloud_all, R.string.flight_cloud_phone, R.string.flight_cloud_server)
            .forEachIndexed { index, label ->
                TextButton(onClick = { onSelect(index) }) {
                    Text(
                        stringResource(label),
                        color = if (selected == index) Color.White else Color.Gray,
                        fontSize = 12.sp,
                    )
                }
            }
    }
}

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
    planned: Boolean,
): List<FlightLibraryRow> {
    return flightCloudRows(local, cloud?.remote.orEmpty(), cloud?.bindings.orEmpty())
        .filter { ((it.local?.sampleCount ?: it.remote!!.samples) == 0) == planned }
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
) {
    val cloud = LocalFlightCloudUi.current?.controller
    val offlineAction = LocalFlightOfflineAction.current
    val dirty = state.journeyId == row.local?.id && state.journeyDirty
    val selected = row.local?.id != null && state.journeyId == row.local.id
    val canOpen =
        row.canOpenLocal(state, row.local != null && cloud?.removingLocalId == row.local.id)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            row.name,
            color = Color.White,
            fontSize = 15.sp,
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(enabled = canOpen && row.local != null) {
                        row.local?.let { onOpen(it.id) }
                    }
                    .padding(vertical = 5.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (row.local != null) FlightStoragePill(stringResource(R.string.flight_cloud_phone))
            if (selected) FlightStoragePill(stringResource(R.string.flight_library_selected))
            if (row.remote != null)
                FlightStoragePill(
                    stringResource(
                        if (cloud?.serverVerified == true) R.string.flight_cloud_server
                        else R.string.flight_cloud_unverified
                    ),
                    cloud?.serverVerified != true,
                )
            if (row.local != null && row.remote != null)
                FlightStoragePill(
                    stringResource(row.versionState(dirty, cloud?.serverVerified == true).label()),
                    row.versionState(dirty, cloud?.serverVerified == true) !=
                        FlightVersionState.SENT,
                )
        }
        row.local?.let {
            Text(
                stringResource(
                    R.string.flight_sync_local_date,
                    flightVersionDate(it.updatedAtMillis),
                ),
                color = Color.Gray,
                fontSize = 10.sp,
            )
        }
        row.remote?.let {
            Text(
                stringResource(
                    R.string.flight_sync_remote_date,
                    flightVersionDate(it.updatedAt),
                    it.revision.take(8),
                    it.photoIds.size,
                ),
                color = Color.Gray,
                fontSize = 10.sp,
            )
        }
        FlowRow {
            row.local?.let {
                TextButton(onClick = { onOpen(it.id) }, enabled = canOpen) {
                    Text(
                        stringResource(
                            if (selected) R.string.flight_library_resume
                            else R.string.flight_cloud_open
                        ),
                        fontSize = 12.sp,
                    )
                }
                if (state.activeRecording.journeyId != it.id || !state.activeRecording.running)
                    TextButton(
                        onClick = {
                            offlineAction(true)
                            onOpen(it.id)
                        },
                        enabled = canOpen,
                    ) {
                        Text(stringResource(R.string.flight_test_start), fontSize = 12.sp)
                    }
            }
            if (row.local == null && row.remote != null) {
                TextButton(
                    onClick = { cloud?.download(row.remote, onOpen) },
                    enabled = cloud?.connection != null && cloud.busy == false && !state.loadingTrip,
                ) {
                    Text(stringResource(R.string.flight_cloud_download), fontSize = 12.sp)
                }
            }
            TextButton(onClick = { onManage(row.key) }) {
                Text(stringResource(R.string.flight_sync_manage), fontSize = 12.sp)
            }
        }
    }
    HorizontalDivider(color = Color(0xFF293740))
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
            .padding(horizontal = 8.dp, vertical = 3.dp)
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
                    TextButton(onClick = ui.save) {
                        Text(stringResource(R.string.flight_local_retry_save), fontSize = 11.sp)
                    }
                } else
                    TextButton(onClick = { ui.open(state.journeyId?.let { "local:$it" }) }) {
                        Text(stringResource(R.string.flight_sync_manage), fontSize = 11.sp)
                    }
        }
    }
}
