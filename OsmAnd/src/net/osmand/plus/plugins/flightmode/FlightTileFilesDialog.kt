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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.osmand.plus.R

/** One row per resolution. Download precision is an action, never an inventory filter. */
@Composable
internal fun FlightTileFilesDialog(
    state: FlightUiState,
    onDismiss: () -> Unit,
    onDownload: (FlightOfflineQuote) -> Unit,
    onPause: () -> Unit,
    onPrecision: (Boolean, Int) -> Unit,
) {
    val context = LocalContext.current
    val coverage = state.offlineCoverage
    val rows = coverage?.levels ?: state.offlineQuote?.levelRequirements.orEmpty()
    var scope by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var precisionMenu by remember { mutableStateOf(false) }
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
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.flight_tiles_manage),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { help = true },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(stringResource(R.string.flight_tiles_help), fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(stringResource(R.string.shared_string_close), fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf(R.string.flight_files_this_flight, R.string.flight_files_shared)
                    .forEachIndexed { index, label ->
                        TextButton(
                            onClick = { scope = index },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                stringResource(label),
                                fontSize = 11.sp,
                                maxLines = 1,
                                color =
                                    if (scope == index) FlightTileColors.accent
                                    else FlightTileColors.muted,
                            )
                        }
                    }
                Spacer(Modifier.weight(1f))
                listOf(R.string.flight_offline_size_satellite, R.string.flight_offline_size_terrain)
                    .forEachIndexed { index, label ->
                        TextButton(
                            onClick = { source = index },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(
                                stringResource(label),
                                fontSize = 11.sp,
                                maxLines = 1,
                                color =
                                    if (source == index) FlightTileColors.accent
                                    else FlightTileColors.muted,
                            )
                        }
                    }
            }
            if (scope == 0) {
                val target =
                    (state.plan.preparation ?: FlightPreparation()).bands.maxOfOrNull {
                        if (source == 0) it.satelliteZoom else it.terrainZoom
                    }
                Box {
                    TextButton(
                        onClick = { precisionMenu = true },
                        enabled = FlightOfflinePreparation.canSimulate(state.plan),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.flight_files_download_resolution,
                                target?.toString() ?: "—",
                            ),
                            fontSize = 11.sp,
                        )
                    }
                    DropdownMenu(
                        expanded = precisionMenu,
                        onDismissRequest = { precisionMenu = false },
                    ) {
                        (3..14).forEach { zoom ->
                            DropdownMenuItem(
                                text = { Text("z$zoom") },
                                onClick = {
                                    precisionMenu = false
                                    onPrecision(source == 0, zoom)
                                },
                            )
                        }
                    }
                }
            }
            if (
                state.offlineCoverageRefreshing ||
                    coverage?.sharedInventoryComplete == false && scope == 1
            )
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = FlightTileColors.accent,
                )
            FlightTileInventoryRow(
                stringResource(R.string.flight_files_level),
                stringResource(R.string.flight_files_present),
                stringResource(R.string.flight_files_missing),
                "?",
                stringResource(R.string.flight_files_size_short),
                stringResource(R.string.flight_files_remaining_short),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (scope == 0) {
                    val levels = rows.filter { it.satellite == (source == 0) }.sortedBy { it.zoom }
                    if (levels.isEmpty())
                        item {
                            Text(
                                stringResource(
                                    if (state.offlineCoverageRefreshing)
                                        R.string.flight_tiles_inventory_pending
                                    else R.string.flight_tiles_no_requirements
                                ),
                                color = FlightTileColors.muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    items(levels, key = { it.zoom }) { level ->
                        FlightTileInventoryRow(
                            "z${level.zoom}",
                            level.present.toString(),
                            level.missing.toString(),
                            level.unknown.toString(),
                            flightTileSize(level.storedBytes),
                            if (level.estimatedRemainingBytes > 0)
                                "≈ " + flightTileSize(level.estimatedRemainingBytes)
                            else "—",
                            if (level.present > 0) FlightTileColors.complete
                            else FlightTileColors.muted,
                            if (level.missing > 0) FlightTileColors.missing
                            else FlightTileColors.muted,
                        )
                    }
                } else {
                    val levels =
                        coverage
                            ?.sharedLevels
                            .orEmpty()
                            .filter { it.satellite == (source == 0) }
                            .sortedBy { it.zoom }
                    if (levels.isEmpty())
                        item {
                            Text(
                                stringResource(
                                    if (coverage?.sharedInventoryComplete == true)
                                        R.string.flight_tiles_no_source_files
                                    else R.string.flight_tiles_inventory_pending
                                ),
                                color = FlightTileColors.muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    items(levels, key = { it.zoom }) { level ->
                        FlightTileInventoryRow(
                            "z${level.zoom}",
                            level.files.toString(),
                            "—",
                            "—",
                            flightTileSize(level.bytes),
                            "—",
                            FlightTileColors.complete,
                        )
                    }
                }
            }
            free?.let {
                Text(
                    stringResource(R.string.flight_tiles_free_size, flightTileSize(it)),
                    color = FlightTileColors.muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (scope == 0) FlightTileDownloadBar(state, onDownload, onPause)
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
private fun FlightTileInventoryRow(
    zoom: String,
    present: String,
    missing: String,
    unknown: String,
    stored: String,
    remaining: String,
    presentColor: Color = FlightTileColors.muted,
    missingColor: Color = FlightTileColors.muted,
) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
                zoom to 0.65f,
                present to 1f,
                missing to 1f,
                unknown to 0.55f,
                stored to 1.2f,
                remaining to 1.25f,
            )
            .forEachIndexed { index, (value, weight) ->
                Text(
                    value,
                    color =
                        when (index) {
                            1 -> presentColor
                            2 -> missingColor
                            else -> FlightTileColors.muted
                        },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(weight),
                )
            }
    }
    HorizontalDivider(color = FlightTileColors.muted.copy(alpha = 0.12f))
}
