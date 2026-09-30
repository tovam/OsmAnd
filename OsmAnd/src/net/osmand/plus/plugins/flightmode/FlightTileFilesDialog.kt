package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.osmand.plus.R

/** Source and flight scope remain explicit; phone widths no longer squeeze a six-column table. */
@Composable
internal fun FlightTileFilesDialog(
    state: FlightUiState,
    onDismiss: () -> Unit,
    onDownload: (FlightOfflineQuote) -> Unit,
    onPause: () -> Unit,
) {
    val context = LocalContext.current
    val coverage = state.offlineCoverage
    val rows = coverage?.levels ?: state.offlineQuote?.levelRequirements.orEmpty()
    var scope by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var help by remember { mutableStateOf(false) }
    var free by remember { mutableStateOf<Long?>(null) }
    FlightResumedEffect(state.offlinePreloadStatus.phase) {
        free = withContext(Dispatchers.IO) { context.filesDir.usableSpace }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(FlightTileColors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.flight_tiles_manage),
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { help = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.flight_tiles_help))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.shared_string_close))
                }
            }
            FlightTileChoices(
                listOf(
                    stringResource(R.string.flight_files_this_flight),
                    stringResource(R.string.flight_files_shared),
                ),
                scope,
                { scope = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            FlightTileChoices(
                listOf(
                    stringResource(R.string.flight_offline_size_satellite),
                    stringResource(R.string.flight_offline_size_terrain),
                ),
                source,
                { source = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    if (scope == 0) FlightTileDashboard(state, detailed = true)
                    else
                        FlightTileCard(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.flight_tiles_shared_note),
                                color = FlightTileColors.muted,
                                fontSize = 13.sp,
                            )
                            if (coverage == null) {
                                Text(
                                    stringResource(R.string.flight_tiles_inventory_pending),
                                    color = FlightTileColors.muted,
                                )
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth(),
                                    color = FlightTileColors.accent,
                                )
                            } else {
                                val shared =
                                    coverage.sharedLevels.filter { it.satellite == (source == 0) }
                                val files = shared.sumOf { it.files }
                                val size = shared.sumOf { it.bytes }
                                Row(Modifier.fillMaxWidth()) {
                                    FlightTileValue(
                                        stringResource(R.string.flight_files_present),
                                        if (coverage.sharedInventoryComplete) files.toString()
                                        else if (files > 0) "≥ $files" else "—",
                                        Modifier.weight(1f),
                                    )
                                    FlightTileValue(
                                        stringResource(R.string.flight_tiles_saved_size),
                                        if (coverage.sharedInventoryComplete) flightTileSize(size)
                                        else if (size > 0) "≥ " + flightTileSize(size) else "—",
                                        Modifier.weight(1f),
                                    )
                                }
                                if (!coverage.sharedInventoryComplete) {
                                    Text(
                                        stringResource(R.string.flight_files_indexing),
                                        color = FlightTileColors.muted,
                                        fontSize = 12.sp,
                                    )
                                    LinearProgressIndicator(
                                        Modifier.fillMaxWidth(),
                                        color = FlightTileColors.accent,
                                    )
                                }
                            }
                        }
                }
                if (scope == 0) {
                    val levels = rows.filter { it.satellite == (source == 0) }
                    if (levels.isEmpty())
                        item {
                            FlightTileEmptyState(
                                stringResource(
                                    if (
                                        state.offlineQuote == null &&
                                            state.offlineCoverageRefreshing
                                    )
                                        R.string.flight_tiles_inventory_pending
                                    else R.string.flight_tiles_no_requirements
                                )
                            )
                        }
                    items(levels, key = { it.zoom }) { level -> FlightTileLevelCard(level) }
                } else {
                    val levels =
                        coverage?.sharedLevels.orEmpty().filter { it.satellite == (source == 0) }
                    if (levels.isEmpty())
                        item {
                            FlightTileEmptyState(
                                stringResource(
                                    if (coverage?.sharedInventoryComplete == true)
                                        R.string.flight_tiles_no_source_files
                                    else R.string.flight_tiles_inventory_pending
                                )
                            )
                        }
                    items(levels, key = { it.zoom }) { level ->
                        FlightTileCard(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.flight_tiles_source_level, level.zoom),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(Modifier.fillMaxWidth()) {
                                FlightTileValue(
                                    stringResource(R.string.flight_files_present),
                                    level.files.toString(),
                                    Modifier.weight(1f),
                                    FlightTileColors.complete,
                                )
                                FlightTileValue(
                                    stringResource(R.string.flight_tiles_saved_size),
                                    flightTileSize(level.bytes),
                                    Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                free?.let {
                    Text(
                        stringResource(R.string.flight_tiles_free_size, flightTileSize(it)),
                        color = FlightTileColors.muted,
                        fontSize = 12.sp,
                    )
                }
                if (scope == 0) FlightTileDownloadBar(state, onDownload, onPause)
                else
                    Text(
                        stringResource(R.string.flight_tiles_shared_action_note),
                        color = FlightTileColors.muted,
                        fontSize = 12.sp,
                    )
            }
        }
        if (help)
            AlertDialog(
                onDismissRequest = { help = false },
                title = { Text(stringResource(R.string.flight_tiles_manage)) },
                text = {
                    Text(
                        stringResource(R.string.flight_tiles_files_help),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { help = false }) {
                        Text(stringResource(R.string.shared_string_ok))
                    }
                },
            )
    }
}

@Composable
private fun FlightTileEmptyState(text: String) {
    FlightTileCard(Modifier.fillMaxWidth()) {
        Text(text, color = FlightTileColors.muted, fontSize = 14.sp)
    }
}

@Composable
private fun FlightTileLevelCard(level: FlightOfflineLevelCoverage) {
    FlightTileCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.flight_tiles_source_level, level.zoom),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.flight_tiles_level_present, level.present, level.required),
                color = FlightTileColors.complete,
                fontSize = 13.sp,
            )
        }
        LinearProgressIndicator(
            progress = {
                if (level.required == 0) 0f else level.present.toFloat() / level.required
            },
            modifier = Modifier.fillMaxWidth(),
            color = FlightTileColors.complete,
            trackColor = FlightTileColors.border,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlightTileValue(
                stringResource(R.string.flight_files_missing),
                level.missing.toString(),
                Modifier.weight(1f),
                if (level.missing > 0) FlightTileColors.missing else Color.White,
            )
            FlightTileValue(
                stringResource(R.string.flight_tiles_unchecked_label),
                level.unknown.toString(),
                Modifier.weight(1f),
                if (level.unknown > 0) FlightTileColors.checking else Color.White,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlightTileValue(
                stringResource(R.string.flight_tiles_saved_size),
                flightTileSize(level.storedBytes),
                Modifier.weight(1f),
            )
            FlightTileValue(
                stringResource(R.string.flight_tiles_remaining_size),
                "≈ " + flightTileSize(level.estimatedRemainingBytes),
                Modifier.weight(1f),
                FlightTileColors.muted,
            )
        }
    }
}
