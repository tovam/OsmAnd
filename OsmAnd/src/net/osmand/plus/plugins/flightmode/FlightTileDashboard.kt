package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

/** Only manifest source files count here, never meshes, render jobs or unrelated journeys. */
@Composable
internal fun FlightTileDashboard(state: FlightUiState) {
    val c = state.offlineCoverage
    val q = state.offlineQuote
    val s = state.offlinePreloadStatus
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        FlightOfflineSizeSummary(state, compact = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(
                    R.string.flight_tiles_progress_short,
                    c?.stored ?: 0,
                    q?.requests?.size ?: 0,
                ),
                color = Color.White,
                fontSize = 12.sp,
            )
            when (s.phase) {
                FlightTerrainPhase.DOWNLOADING ->
                    Text(
                        stringResource(R.string.flight_tiles_rate_short, s.bytesPerSecond / 1e6),
                        color = Color(0xFF88DEBF),
                        fontSize = 12.sp,
                    )
                FlightTerrainPhase.PAUSED ->
                    Text(
                        stringResource(R.string.flight_plan_pause_label),
                        color = Color.Yellow,
                        fontSize = 12.sp,
                    )
                else -> {}
            }
        }
        if (c != null) {
            LinearProgressIndicator(
                progress = c.fraction,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            Text(
                stringResource(
                    R.string.flight_offline_by_source,
                    c.satelliteStored,
                    c.satelliteTotal,
                    c.terrainStored,
                    c.terrainTotal,
                ),
                color = Color.LightGray,
                fontSize = 11.sp,
            )
            if (!c.inventoried)
                Text(
                    stringResource(R.string.flight_tiles_unknown_short, c.inspected, c.total),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                )
            if (c.inventoried && c.missing == 0)
                Text(
                    stringResource(
                        if (c.verifiedBy(s)) R.string.flight_offline_verified
                        else R.string.flight_offline_present_unverified
                    ),
                    color = Color(0xFF88DEBF),
                    fontSize = 11.sp,
                )
        }
        val failures = s.failedTiles + s.satelliteFailedTiles
        if (failures > 0)
            Text(
                stringResource(R.string.flight_offline_failed_count, failures),
                color = Color(0xFFFFCC66),
                fontSize = 11.sp,
            )
        if (state.offlineCoverageError != null)
            Text(
                stringResource(R.string.flight_offline_count_failed),
                color = Color(0xFFFFCC66),
                fontSize = 11.sp,
            )
    }
}
