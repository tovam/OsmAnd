package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.osmand.plus.R

/** One pre-download/file-management surface for planned, recorded and live flights. */
@Composable
internal fun FlightTileFilesDialog(
    state: FlightUiState,
    onDismiss: () -> Unit,
    onDownload: (FlightOfflineQuote) -> Unit,
    onPause: () -> Unit,
) {
    val context = LocalContext.current
    val q = state.offlineQuote
    val c = state.offlineCoverage
    val s = state.offlinePreloadStatus
    val rows = c?.levels ?: q?.levelRequirements.orEmpty()
    var allFlights by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var free by remember { mutableStateOf<Long?>(null) }
    FlightResumedEffect(s.phase) {
        free = withContext(Dispatchers.IO) { context.filesDir.usableSpace }
    }
    val busy = s.phase == FlightTerrainPhase.DOWNLOADING || s.phase == FlightTerrainPhase.PLANNING
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .fillMaxHeight(.94f)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(6.dp)
                .background(Color(0xFF121A21))
                .padding(horizontal = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.flight_files_title),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { help = !help }) { Text("?") }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.shared_string_close))
                }
            }
            Row {
                TextButton(onClick = { allFlights = false }) {
                    Text(
                        stringResource(R.string.flight_files_this_flight),
                        color = if (!allFlights) Color(0xFF88DEBF) else Color.LightGray,
                    )
                }
                TextButton(onClick = { allFlights = true }) {
                    Text(
                        stringResource(R.string.flight_files_shared),
                        color = if (allFlights) Color(0xFF88DEBF) else Color.LightGray,
                    )
                }
            }
            if (help)
                Text(
                    stringResource(R.string.flight_files_help),
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            if (!allFlights) FlightOfflineSizeSummary(state, compact = true)
            else
                Text(
                    stringResource(
                        R.string.flight_files_shared_total,
                        c?.sharedLevels?.sumOf { it.files } ?: 0,
                        flightOfflineGb(c?.sharedLevels?.sumOf { it.bytes } ?: 0L),
                    ),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            if (c?.inventoried != true || (allFlights && !c.sharedInventoryComplete)) {
                Text(
                    stringResource(
                        R.string.flight_tiles_unknown_short,
                        c?.inspected ?: 0,
                        q?.requests?.size ?: 0,
                    ),
                    color = Color(0xFFFFCC66),
                    fontSize = 11.sp,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                for (satellite in listOf(true, false)) {
                    item {
                        Text(
                            stringResource(
                                if (satellite) R.string.flight_offline_size_satellite
                                else R.string.flight_offline_size_terrain
                            ),
                            color = Color(0xFF88DEBF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 5.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth()
                                .background(Color(0xFF24313B))
                                .padding(vertical = 4.dp)
                        ) {
                            FileCell(stringResource(R.string.flight_files_level), .65f)
                            if (!allFlights)
                                FileCell(stringResource(R.string.flight_files_required))
                            FileCell(stringResource(R.string.flight_files_present))
                            if (!allFlights) FileCell(stringResource(R.string.flight_files_missing))
                            FileCell(stringResource(R.string.flight_files_stored), 1.5f)
                            if (!allFlights)
                                FileCell(
                                    stringResource(R.string.flight_files_download_estimate),
                                    1.6f,
                                )
                        }
                    }
                    if (allFlights) {
                        val levels = c?.sharedLevels.orEmpty().filter { it.satellite == satellite }
                        items(levels.size) { i ->
                            val level = levels[i]
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                FileCell("z${level.zoom}", .65f)
                                FileCell("${level.files}")
                                FileCell(flightOfflineGb(level.bytes), 1.5f)
                            }
                        }
                    } else {
                        val levels = rows.filter { it.satellite == satellite }
                        items(levels.size) { i ->
                            val level = levels[i]
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FileCell("z${level.zoom}", .65f)
                                FileCell("${level.required}")
                                FileCell("${level.present}", color = Color(0xFF88DEBF))
                                FileCell(
                                    if (level.unknown == 0) "${level.missing}"
                                    else "${level.missing}\n?${level.unknown}",
                                    color =
                                        if (level.missing > 0 || level.unknown > 0)
                                            Color(0xFFFFCC66)
                                        else Color.LightGray,
                                )
                                FileCell(flightOfflineGb(level.storedBytes), 1.5f)
                                FileCell(flightOfflineGb(level.estimatedRemainingBytes), 1.6f)
                            }
                            LinearProgressIndicator(
                                progress = {
                                    if (level.required == 0) 0f
                                    else level.present.toFloat() / level.required
                                },
                                modifier = Modifier.fillMaxWidth().height(1.dp),
                            )
                        }
                    }
                }
                item {
                    if (c != null && c.unknown > 0)
                        Text(
                            stringResource(R.string.flight_files_unchecked, c.unknown),
                            color = Color(0xFFFFCC66),
                            fontSize = 11.sp,
                        )
                    if (state.offlineCoverageError != null)
                        Text(
                            stringResource(R.string.flight_offline_count_failed),
                            color = Color(0xFFFFCC66),
                            fontSize = 11.sp,
                        )
                    val failures = s.failedTiles + s.satelliteFailedTiles
                    if (failures > 0)
                        Text(
                            stringResource(R.string.flight_offline_failed_count, failures),
                            color = Color(0xFFFFCC66),
                            fontSize = 11.sp,
                        )
                }
            }
            free?.let {
                Text(
                    stringResource(R.string.flight_plan_free_space, it / 1e9),
                    color = Color.LightGray,
                    fontSize = 11.sp,
                )
            }
            if (busy) {
                Text(
                    stringResource(
                        R.string.flight_files_verified_count,
                        s.availableTiles + s.satelliteTiles,
                        q?.requests?.size ?: 0,
                    ),
                    color = Color.LightGray,
                    fontSize = 11.sp,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.flight_tiles_rate_short, s.bytesPerSecond / 1e6),
                        color = Color(0xFF88DEBF),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPause) {
                        Text(stringResource(R.string.flight_plan_pause))
                    }
                }
            } else {
                TextButton(
                    onClick = { if (q != null && c?.canDownloadMissing == true) onDownload(q) },
                    enabled =
                        q != null && c?.canDownloadMissing == true && !state.offlineSimulation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.offlineSimulation ->
                                stringResource(R.string.flight_files_offline_test)
                            c?.inventoried != true ->
                                stringResource(R.string.flight_offline_counting)
                            c.missing == 0 -> stringResource(R.string.flight_files_complete)
                            else ->
                                stringResource(
                                    R.string.flight_files_download_count,
                                    c.missing,
                                    flightOfflineGb(c.estimatedRemainingBytes),
                                )
                        },
                        fontSize = 13.sp,
                    )
                }
                if (
                    q != null &&
                        c?.inventoried == true &&
                        c.missing == 0 &&
                        !state.offlineSimulation
                ) {
                    TextButton(onClick = { onDownload(q) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.flight_files_verify), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.FileCell(text: String, weight: Float = 1f, color: Color = Color.LightGray) {
    Text(
        text,
        modifier = Modifier.weight(weight).padding(horizontal = 2.dp),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 13.sp,
    )
}
