package net.osmand.plus.plugins.flightmode

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import net.osmand.plus.R

@Composable
internal fun FlightGpsCountAndSignal(live: FlightLiveState, now: Long) {
    val age = live.fixAgeSeconds(now)
    val health = FlightLiveSafety.fixHealth(live.latest, live.lastFixElapsed, now)
    Text(
        stringResource(
            if (live.simulation) R.string.flight_live_simulated_count
            else R.string.flight_live_recorded_count,
            live.trip?.samples?.size ?: 0,
        ),
        color = Color.White,
        fontSize = 12.sp,
    )
    val signal =
        when {
            live.simulation -> stringResource(R.string.flight_library_gps_simulated)
            !live.running -> stringResource(R.string.flight_library_gps_off)
            age == null -> stringResource(R.string.flight_live_signal_waiting)
            health == FlightFixHealth.STALE -> stringResource(R.string.flight_live_no_new_fix, age)
            else -> stringResource(R.string.flight_live_recent_fix, age)
        }
    Text(
        signal +
            (live.latest
                ?.horizontalAccuracyMeters
                ?.takeIf { !live.simulation && it >= 0 }
                ?.let { stringResource(R.string.flight_live_accuracy_suffix, it) } ?: ""),
        color =
            if (health == FlightFixHealth.STALE && !live.simulation) Color(0xFFFFCC66)
            else Color.LightGray,
        fontSize = 10.sp,
    )
    live.error?.let { Text(it, color = Color(0xFFFFCC66), fontSize = 10.sp) }
}

/** Always visible in the live workspace, including while browsing an older/future point. */
@Composable
internal fun FlightLiveRecordingSummary(
    live: FlightLiveState,
    modifier: Modifier,
    onOpen: () -> Unit,
) {
    var timer by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    FlightResumedEffect(live.running) {
        while (live.running) {
            timer = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    Column(modifier.clickable(onClick = onOpen).padding(horizontal = 6.dp, vertical = 3.dp)) {
        FlightGpsCountAndSignal(live, flightDisplayElapsed(timer, SystemClock.elapsedRealtime()))
        if (live.running) FlightRecordingCadenceInfo(live)
    }
}

@Composable
internal fun FlightRecordingCadenceInfo(live: FlightLiveState, details: Boolean = false) {
    val decision = live.recordingDecision ?: return
    val cadence = stringResource(when (decision.cadence) {
        FlightRecordingCadence.FIXED_INTERVAL -> R.string.flight_cadence_fixed
        FlightRecordingCadence.DISTANCE_OR_MAXIMUM_INTERVAL -> R.string.flight_cadence_distance
        FlightRecordingCadence.TURN -> R.string.flight_cadence_turn
        FlightRecordingCadence.ROUTE_DEVIATION_ENTRY -> R.string.flight_cadence_deviation
    })
    Text(stringResource(R.string.flight_cadence_current, decision.intervalSeconds, cadence), color = Color.LightGray, fontSize = 10.sp)
    if (details) {
        Text(stringResource(R.string.flight_cadence_received, live.receivedFixesThisSession), color = Color.LightGray, fontSize = 11.sp)
        live.lastSavedReason?.let { reason ->
            Text(stringResource(when (reason) {
                FlightRecordingSaveReason.FIRST_FIX -> R.string.flight_cadence_first
                FlightRecordingSaveReason.LANDING -> R.string.flight_cadence_landing
                else -> R.string.flight_cadence_due
            }), color = Color.LightGray, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun FlightRecordedPoints(live: FlightLiveState) {
    val points = live.recentRecordedPoints()
    val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Text(
        stringResource(R.string.flight_live_recent_points),
        color = Color.White,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 3.dp),
    )
    if (points.isEmpty())
        Text(
            stringResource(R.string.flight_live_no_recorded_points),
            color = Color(0xFFFFCC66),
            fontSize = 11.sp,
        )
    points.forEach { point ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        R.string.flight_live_point_time,
                        point.index + 1,
                        point.timestampMillis.takeIf { it > 0 }?.let { time.format(Date(it)) }
                            ?: "—",
                    ),
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Text(
                    "%.6f, %.6f".format(point.latitude, point.longitude),
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Column {
                Text(
                    point.altitudeMeters?.let { "%.0f m".format(it) } ?: "—",
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Text(
                    point.horizontalAccuracyMeters?.let { "±%.0f m".format(it) } ?: "—",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                )
            }
        }
        HorizontalDivider(color = Color(0xFF293740))
    }
}

/**
 * Identical figures in Preparation, cached Tiles and the live cockpit. All byte units are decimal
 * GB.
 */
@Composable
internal fun FlightOfflineProgressPanel(
    state: FlightUiState,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val coverage = state.offlineCoverage
    val status = state.offlinePreloadStatus
    Column(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        if (coverage == null) {
            Text(
                stringResource(
                    if (state.offlineCoverageError != null) R.string.flight_offline_count_failed
                    else R.string.flight_offline_counting
                ),
                color = Color.LightGray,
                fontSize = 11.sp,
            )
            state.offlineCoverageError
                ?.takeIf { !compact }
                ?.let { Text(it, color = Color(0xFFFFCC66), fontSize = 10.sp) }
            return@Column
        }
        Text(
            stringResource(
                if (coverage.inventoried) R.string.flight_offline_stored_count
                else R.string.flight_offline_inventory_count,
                if (coverage.inventoried) coverage.stored else coverage.inspected,
                coverage.total,
            ),
            color = Color.White,
            fontSize = 12.sp,
        )
        if (coverage.inventoried) {
            Text(
                stringResource(
                    R.string.flight_offline_remaining,
                    coverage.missing,
                    coverage.storedBytes / 1e9,
                    coverage.estimatedRemainingBytes / 1e9,
                ),
                color = Color.LightGray,
                fontSize = 11.sp,
            )
        }
        if (!compact) {
            if (coverage.inventoried)
                LinearProgressIndicator(
                    progress = coverage.fraction,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            Text(
                stringResource(
                    R.string.flight_offline_by_source,
                    coverage.satelliteStored,
                    coverage.satelliteTotal,
                    coverage.terrainStored,
                    coverage.terrainTotal,
                ),
                color = Color.LightGray,
                fontSize = 11.sp,
            )
            if (status.phase == FlightTerrainPhase.DOWNLOADING) {
                Text(
                    stringResource(
                        R.string.flight_offline_transfer,
                        status.bytesDownloaded / 1e9,
                        status.bytesPerSecond / 1e6,
                        status.availableTiles + status.satelliteTiles,
                        status.requestedTiles + status.requestedSatelliteTiles,
                    ),
                    color = Color(0xFF88DEBF),
                    fontSize = 11.sp,
                )
            } else if (status.phase == FlightTerrainPhase.PAUSED) {
                Text(
                    stringResource(R.string.flight_plan_pause_label),
                    color = Color(0xFFFFCC66),
                    fontSize = 11.sp,
                )
            }
            val failures = status.failedTiles + status.satelliteFailedTiles
            if (failures > 0)
                Text(
                    stringResource(R.string.flight_offline_failed_count, failures),
                    color = Color(0xFFFFCC66),
                    fontSize = 11.sp,
                )
            val verified =
                !state.offlineCoverageRefreshing &&
                    state.offlineCoverageError == null &&
                    coverage.verifiedBy(status)
            if (coverage.inventoried && coverage.missing == 0)
                Text(
                    stringResource(
                        if (verified) R.string.flight_offline_verified
                        else R.string.flight_offline_present_unverified
                    ),
                    color = if (verified) Color(0xFF88DEBF) else Color.LightGray,
                    fontSize = 11.sp,
                )
            Text(
                stringResource(R.string.flight_offline_estimate_note),
                color = Color.LightGray,
                fontSize = 10.sp,
            )
        }
        if (coverage.inventoried && state.offlineCoverageRefreshing)
            Text(
                stringResource(R.string.flight_offline_refreshing),
                color = Color.LightGray,
                fontSize = 10.sp,
            )
        if (state.offlineCoverageError != null)
            Text(
                stringResource(R.string.flight_offline_count_failed),
                color = Color(0xFFFFCC66),
                fontSize = 11.sp,
            )
    }
}
