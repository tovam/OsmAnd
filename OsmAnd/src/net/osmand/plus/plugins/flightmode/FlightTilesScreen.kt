package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import net.osmand.plus.R

/** Imagery is the primary surface; storage and per-file diagnostics have their own sheet. */
@Composable
internal fun FlightTilesScreen(
    state: FlightUiState,
    onPageChange: (FlightPage) -> Unit,
    onPreload: (FlightOfflineQuote) -> Unit,
    onPausePreload: () -> Unit,
) {
    var tileView by remember { mutableStateOf<FlightSatelliteCacheView?>(null) }
    var tileLayer by rememberSaveable(state.journeyId) { mutableIntStateOf(0) }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showGrid by rememberSaveable { mutableStateOf(false) }
    var showLevels by remember { mutableStateOf(false) }
    var selectedCell by remember { mutableStateOf<FlightTileMapCell?>(null) }
    var visibleGrid by remember { mutableIntStateOf(8) }
    var requestedGrid by rememberSaveable(state.journeyId) { mutableIntStateOf(-1) }
    val levels =
        ((3..14).toList() +
                state.offlineQuote?.zoomLevels.orEmpty() +
                state.offlineCoverage?.sharedLevels.orEmpty().map { it.zoom })
            .distinct()
            .sorted()
    val grid = requestedGrid.takeIf { it >= 0 } ?: state.offlineQuote?.defaultPreviewZoom ?: 8
    Column(Modifier.fillMaxSize().background(FlightTileColors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.flight_tiles_title),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showHelp = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.flight_tiles_help))
            }
            TextButton(onClick = { showFiles = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.flight_tiles_manage))
            }
        }
        FlightTileChoices(
            listOf(
                stringResource(R.string.flight_offline_size_satellite),
                stringResource(R.string.flight_offline_size_terrain),
                stringResource(R.string.flight_tiles_coverage),
            ),
            tileLayer,
            { tileLayer = it },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    FlightSatelliteCacheView(context).also {
                        tileView = it
                        it.onCellSelected = { cell -> selectedCell = cell }
                        it.onCellUpdated = { cell -> if (selectedCell != null) selectedCell = cell }
                        it.onGridChanged = { zoom -> visibleGrid = zoom }
                    }
                },
                onRelease = {
                    it.release()
                    tileView = null
                },
                update = { view ->
                    view.setQuote(state.offlineQuote)
                    view.setZoom(grid)
                    view.setLayer(tileLayer)
                    view.setGridVisible(showGrid)
                    view.setPosition(state.snapshot?.sample)
                },
            )
            Surface(
                Modifier.align(Alignment.TopStart).padding(10.dp).widthIn(max = 420.dp),
                color = FlightTileColors.surface.copy(alpha = .96f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { tileView?.fitContent() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.flight_tiles_fit), fontSize = 12.sp)
                    }
                    Box {
                        TextButton(
                            onClick = { showLevels = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(R.string.flight_tiles_grid_level, visibleGrid),
                                fontSize = 12.sp,
                            )
                        }
                        DropdownMenu(
                            showLevels,
                            { showLevels = false },
                            modifier = Modifier.heightIn(max = 320.dp),
                        ) {
                            levels.forEach { zoom ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                R.string.flight_tiles_source_level,
                                                zoom,
                                            ),
                                            color =
                                                if (zoom == grid) FlightTileColors.accent
                                                else Color.White,
                                            fontWeight =
                                                if (zoom == grid) FontWeight.SemiBold
                                                else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        requestedGrid = zoom
                                        showLevels = false
                                        showGrid = true
                                    },
                                )
                            }
                        }
                    }
                    Checkbox(
                        showGrid || tileLayer == 2,
                        { showGrid = it },
                        enabled = tileLayer != 2,
                        modifier =
                            Modifier.size(48.dp)
                                .tileDescription(stringResource(R.string.flight_tiles_grid_short)),
                    )
                    Text(
                        stringResource(R.string.flight_tiles_grid_short),
                        color = FlightTileColors.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Column(
                Modifier.align(Alignment.CenterEnd).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(2.0 to R.string.flight_tiles_zoom_in, .5 to R.string.flight_tiles_zoom_out)
                    .forEach { (factor, label) ->
                        Surface(
                            color = FlightTileColors.surface.copy(alpha = .96f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            TextButton(
                                onClick = { tileView?.zoomBy(factor) },
                                modifier = Modifier.size(48.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    if (factor > 1) "+" else "−",
                                    fontSize = 24.sp,
                                    modifier = Modifier.tileDescription(stringResource(label)),
                                )
                            }
                        }
                    }
            }
            Column(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(FlightTileColors.background.copy(alpha = .92f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (tileLayer == 2) FlightTileLegend()
                else if (tileLayer == 1)
                    Text(
                        stringResource(R.string.flight_tiles_terrain_legend),
                        color = FlightTileColors.muted,
                        fontSize = 12.sp,
                    )
                Text(
                    if (showGrid || tileLayer == 2) {
                        if (visibleGrid == grid) stringResource(R.string.flight_tiles_tap_cell)
                        else
                            stringResource(
                                R.string.flight_tiles_grouped_readable,
                                visibleGrid,
                                grid,
                            )
                    } else stringResource(R.string.flight_tiles_explore_hint),
                    color = FlightTileColors.muted,
                    fontSize = 12.sp,
                )
                Text(
                    stringResource(R.string.flight_mode_satellite_attribution_short),
                    color = FlightTileColors.muted,
                    fontSize = 10.sp,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    flightTileStatusText(state),
                    color = FlightTileColors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                state.offlineCoverage?.let {
                    Text(
                        stringResource(R.string.flight_tiles_progress_short, it.stored, it.total),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
            val coverage = state.offlineCoverage
            if (flightTileReadiness(state) == FlightTileReadiness.CHECKING)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = FlightTileColors.accent)
            else if (coverage != null && coverage.total > 0)
                LinearProgressIndicator(
                    progress = { coverage.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = FlightTileColors.accent,
                    trackColor = FlightTileColors.border,
                )
            FlightTileDownloadBar(state, onPreload, onPausePreload)
        }
        FlightBottomNavigation(state, onPageChange, minimalChrome = true)
    }
    if (showFiles) FlightTileFilesDialog(state, { showFiles = false }, onPreload, onPausePreload)
    selectedCell?.let { cell ->
        FlightTileCellDialog(
            cell,
            {
                selectedCell = null
                tileView?.clearSelection()
            },
        )
    }
    if (showHelp)
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.flight_tiles_help_title)) },
            text = {
                Text(
                    stringResource(R.string.flight_tiles_help_redesign),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(R.string.shared_string_ok))
                }
            },
        )
}

private fun Modifier.tileDescription(text: String) =
    this.then(Modifier.semantics { contentDescription = text })

@Composable
private fun FlightTileLegend() {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
                FlightTileColors.complete to R.string.flight_tiles_legend_complete,
                FlightTileColors.missing to R.string.flight_tiles_legend_missing,
                FlightTileColors.shared to R.string.flight_tiles_legend_shared,
                FlightTileColors.checking to R.string.flight_tiles_legend_checking,
            )
            .forEach { (color, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(3.dp)))
                    Text(stringResource(label), color = FlightTileColors.muted, fontSize = 12.sp)
                }
            }
    }
}

@Composable
private fun FlightTileCellDialog(cell: FlightTileMapCell, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FlightTileColors.background,
        title = {
            Column {
                Text(stringResource(R.string.flight_tiles_cell_details), color = Color.White)
                Text(
                    stringResource(
                        R.string.flight_tiles_cell_title,
                        cell.id.zoom,
                        cell.id.x,
                        cell.id.y,
                    ),
                    color = FlightTileColors.muted,
                    fontSize = 12.sp,
                )
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (cell.required > 0)
                    Text(
                        stringResource(
                            R.string.flight_tiles_cell_required_progress,
                            cell.presentRequired,
                            cell.required,
                        ),
                        color = FlightTileColors.muted,
                    )
                for (satellite in listOf(true, false)) {
                    FlightTileCard(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (satellite) R.string.flight_offline_size_satellite
                                else R.string.flight_offline_size_terrain
                            ),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val stored = if (satellite) cell.satellite else cell.terrain
                        val needed =
                            if (satellite) cell.requestedSatellite else cell.requestedTerrain
                        val levels =
                            (stored.map { it.zoom } + needed.map { it.zoom }).distinct().sorted()
                        if (levels.isEmpty())
                            Text(
                                stringResource(
                                    if (cell.inventoryComplete)
                                        R.string.flight_tiles_no_source_files
                                    else R.string.flight_tiles_inventory_pending
                                ),
                                color = FlightTileColors.muted,
                                fontSize = 13.sp,
                            )
                        levels.forEach { zoom ->
                            val existing = stored.firstOrNull { it.zoom == zoom }
                            Text(
                                stringResource(R.string.flight_tiles_source_level, zoom),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                when {
                                    existing?.full == true ->
                                        stringResource(R.string.flight_tiles_cell_full)
                                    existing != null ->
                                        stringResource(
                                            R.string.flight_tiles_cell_partial,
                                            existing.files,
                                            existing.capacity,
                                        )
                                    !cell.inventoryComplete ->
                                        stringResource(R.string.flight_tiles_inventory_pending)
                                    else -> stringResource(R.string.flight_tiles_cell_absent)
                                },
                                color =
                                    when {
                                        existing?.full == true -> FlightTileColors.complete
                                        existing == null && !cell.inventoryComplete ->
                                            FlightTileColors.checking
                                        else -> FlightTileColors.missing
                                    },
                                fontSize = 13.sp,
                            )
                            Text(
                                stringResource(
                                    if (needed.any { it.zoom == zoom })
                                        R.string.flight_tiles_cell_needed
                                    else R.string.flight_tiles_cell_shared
                                ),
                                color = FlightTileColors.muted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shared_string_close)) }
        },
    )
}
