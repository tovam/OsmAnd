package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

/** The compact summary prioritises readiness; detailed sizes live in the Files sheet. */
@Composable
internal fun FlightTileDashboard(
    state: FlightUiState,
    detailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val coverage = state.offlineCoverage
    val quote = state.offlineQuote
    val status = state.offlinePreloadStatus
    val readiness = flightTileReadiness(state)
    val color =
        when (readiness) {
            FlightTileReadiness.VERIFIED,
            FlightTileReadiness.PRESENT -> FlightTileColors.complete
            FlightTileReadiness.MISSING,
            FlightTileReadiness.PAUSED,
            FlightTileReadiness.ERROR -> FlightTileColors.missing
            else -> FlightTileColors.accent
        }
    FlightTileCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                flightTileStatusText(state),
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (coverage != null && coverage.total > 0 && coverage.inventoried)
                Text(
                    "${(coverage.fraction * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
        }
        if (
            readiness == FlightTileReadiness.CHECKING ||
                (readiness == FlightTileReadiness.DOWNLOADING && coverage == null)
        )
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = FlightTileColors.accent)
        else if (coverage != null && coverage.total > 0)
            LinearProgressIndicator(
                progress = { coverage.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = FlightTileColors.border,
            )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (coverage == null) stringResource(R.string.flight_tiles_inventory_pending)
                else
                    stringResource(
                        R.string.flight_tiles_progress_short,
                        coverage.stored,
                        coverage.total,
                    ),
                color = FlightTileColors.muted,
                fontSize = 12.sp,
            )
            Text(
                if (coverage == null) "—" else flightTileSize(coverage.storedBytes),
                color = Color.White,
                fontSize = 12.sp,
            )
        }
        if (status.phase == FlightTerrainPhase.DOWNLOADING)
            Text(
                stringResource(
                    R.string.flight_tiles_transfer_size,
                    flightTileSize(status.bytesDownloaded),
                    flightTileSize(status.bytesPerSecond) + "/s",
                ),
                color = FlightTileColors.accent,
                fontSize = 12.sp,
            )
        if (readiness == FlightTileReadiness.CHECKING && coverage != null)
            Text(
                stringResource(
                    R.string.flight_tiles_unknown_short,
                    coverage.inspected,
                    coverage.total,
                ),
                color = FlightTileColors.muted,
                fontSize = 12.sp,
            )
        if (detailed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FlightTileValue(
                    stringResource(R.string.flight_tiles_saved_size),
                    coverage?.let { flightTileSize(it.storedBytes) } ?: "—",
                    Modifier.weight(1f),
                )
                FlightTileValue(
                    stringResource(R.string.flight_tiles_remaining_size),
                    quote?.let {
                        "≈ " +
                            flightTileSize(coverage?.estimatedRemainingBytes ?: it.estimatedBytes)
                    } ?: "—",
                    Modifier.weight(1f),
                    FlightTileColors.missing,
                )
            }
            Text(
                stringResource(R.string.flight_offline_size_note),
                color = FlightTileColors.muted,
                fontSize = 12.sp,
            )
        }
        val failures = status.failedTiles + status.satelliteFailedTiles
        if (failures > 0)
            Text(
                stringResource(R.string.flight_offline_failed_count, failures),
                color = FlightTileColors.missing,
                fontSize = 12.sp,
            )
        if (state.offlineCoverageError != null)
            Text(
                stringResource(R.string.flight_offline_count_failed),
                color = FlightTileColors.missing,
                fontSize = 12.sp,
            )
    }
}
