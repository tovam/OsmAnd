package net.osmand.plus.plugins.flightmode

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*
import kotlinx.coroutines.delay
import net.osmand.plus.R

/** A compact cockpit observes the recorder; disposing this page never stops the service. */
@Composable
internal fun FlightLiveScreen(
    state: FlightUiState,
    onPage: (FlightPage) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMicrophone: () -> Unit,
    onPhoto: () -> Unit,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val live = state.liveState
    val fix = if (live.running) live.latest else state.snapshot?.sample
    val age =
        if (live.lastFixElapsed > 0) ((now - live.lastFixElapsed) / 1000).coerceAtLeast(0) else null
    val phase =
        when (live.tracking.phase) {
            FlightTrackingPhase.WAITING -> R.string.flight_live_waiting
            FlightTrackingPhase.AIRBORNE -> R.string.flight_live_airborne
            FlightTrackingPhase.LANDED -> R.string.flight_live_landed
            FlightTrackingPhase.STOPPED -> R.string.flight_live_stopped
        }
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0F13)).padding(horizontal = 8.dp)) {
        Row {
            PlanAction(stringResource(R.string.flight_live_title), {})
            PlanAction(stringResource(R.string.flight_mode_map), { onPage(FlightPage.MAP) })
            PlanAction(stringResource(R.string.flight_mode_window), { onPage(FlightPage.WINDOW) })
            PlanAction(stringResource(R.string.flight_live_camera), onPhoto, enabled = live.running)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(state.journeyName, color = Color.White, fontSize = 16.sp)
            Text(
                if (live.journeyId != null && live.journeyId == state.journeyId)
                    stringResource(phase)
                else stringResource(R.string.flight_live_not_running),
                color = if (live.running) Color(0xFF2CDBBE) else Color.LightGray,
                fontSize = 13.sp,
            )
            LiveRow(
                stringResource(R.string.flight_live_speed),
                fix?.speedMetersPerSecond?.let { "%.0f km/h".format(it * 3.6) } ?: "—",
            )
            LiveRow(
                stringResource(R.string.flight_live_altitude),
                fix?.altitudeMeters?.let { "%.0f m".format(it) } ?: "—",
            )
            LiveRow(
                stringResource(R.string.flight_live_bearing),
                fix?.bearingDegrees?.let { "%.0f°".format(it) } ?: "—",
            )
            LiveRow(
                stringResource(R.string.flight_live_accuracy),
                fix?.horizontalAccuracyMeters?.let { "±%.0f m".format(it) } ?: "—",
            )
            LiveRow(
                stringResource(R.string.flight_live_fix_age),
                age?.let { "$it s" } ?: "—",
                age == null || age > 15,
            )
            LiveRow(
                stringResource(R.string.flight_live_satellites),
                "${fix?.satellitesUsed?:"—"} / ${fix?.satellitesFound?:"—"}",
            )
            LiveRow(stringResource(R.string.flight_live_points), "${state.trip?.samples?.size?:0}")
            Text(
                stringResource(R.string.flight_live_prediction_hint),
                color = Color.LightGray,
                fontSize = 10.sp,
            )
            val base = live.tracking.baselineAltitude
            base?.let {
                LiveRow(
                    stringResource(R.string.flight_live_gain),
                    "%+.0f m".format((fix?.altitudeMeters ?: it) - it),
                )
            }
            live.tracking.slowSinceMillis?.let { since ->
                val remaining =
                    ((state.plan.preparation ?: FlightPreparation()).stopMinutes * 60L -
                            ((fix?.timestampMillis ?: since) - since) / 1000)
                        .coerceAtLeast(0)
                LiveRow(
                    stringResource(R.string.flight_live_stop_countdown),
                    "${remaining/60}:${(remaining%60).toString().padStart(2,'0')}",
                )
            }
            if (fix != null) {
                val radius =
                    state.plan.preparation?.bands?.maxOfOrNull { it.radiusKm }
                        ?: state.plan.terrainCorridorKm
                val remaining =
                    remember(state.plan, fix) {
                        FlightRouteHypothesis.distanceToPlanKm(state.plan, fix)
                    }
                LiveRow(
                    stringResource(R.string.flight_live_corridor),
                    remaining?.let { "%.1f / %d km".format(it, radius) } ?: "—",
                    remaining?.let { it > radius } == true,
                )
                Text(
                    stringResource(R.string.flight_live_coverage_hint),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                )
                if (fix.altitudeMeters != null && fix.altitudeMeters >= 1000)
                    LiveRow(
                        stringResource(R.string.flight_live_horizon),
                        "%.0f km".format(sqrt(2 * 6371.0 * fix.altitudeMeters / 1000)),
                    )
            }
            live.error?.let { Text(it, color = Color(0xFFFFBD39), fontSize = 12.sp) }
            FlightBatteryChart(state.batteryHistory)
            Row {
                PlanAction(
                    stringResource(
                        if (live.microphone) R.string.flight_live_mic_off
                        else R.string.flight_live_mic_on
                    ),
                    onMicrophone,
                    enabled = live.running,
                )
                PlanAction(
                    stringResource(R.string.flight_plan_title),
                    { onPage(FlightPage.PREPARE) },
                )
            }
            if (live.running)
                Text(
                    stringResource(R.string.flight_live_background),
                    color = Color.LightGray,
                    fontSize = 11.sp,
                )
        }
        if (live.running)
            PlanAction(stringResource(R.string.flight_live_stop), { confirmStop = true })
        else PlanAction(stringResource(R.string.flight_mode_start_live), onStart)
    }
    if (confirmStop)
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.flight_live_stop)) },
            text = { Text(stringResource(R.string.flight_live_stop_confirm)) },
            confirmButton = {
                PlanAction(
                    stringResource(R.string.flight_live_stop),
                    {
                        confirmStop = false
                        onStop()
                    },
                )
            },
            dismissButton = {
                PlanAction(stringResource(R.string.shared_string_cancel), { confirmStop = false })
            },
        )
}

@Composable
private fun LiveRow(label: String, value: String, warning: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (warning) Color(0xFFFFBD39) else Color.White, fontSize = 13.sp)
    }
}

@Composable
internal fun FlightBatteryChart(history: List<FlightBatteryPoint>) {
    val last = history.lastOrNull()
    val drain = remember(history) { flightBatteryDrainPerHour(history) }
    LiveRow(
        stringResource(R.string.flight_live_battery),
        last?.let { "%.0f%%".format(it.percent) } ?: "—",
    )
    LiveRow(
        stringResource(R.string.flight_live_drain),
        drain?.let { "−%.1f %%/h".format(it) } ?: "—",
    )
    if (last?.charging == true)
        Text(
            stringResource(R.string.flight_live_charging),
            color = Color.LightGray,
            fontSize = 11.sp,
        )
    if (history.size < 2)
        Text(
            stringResource(R.string.flight_live_battery_wait),
            color = Color.LightGray,
            fontSize = 10.sp,
        )
    else {
        Text(
            stringResource(R.string.flight_live_battery_graph),
            color = Color.LightGray,
            fontSize = 10.sp,
        )
        Canvas(Modifier.fillMaxWidth().height(85.dp)) {
            val first = history.first().timeMillis
            val duration = (history.last().timeMillis - first).coerceAtLeast(1)
            fun xy(p: FlightBatteryPoint) =
                Offset(
                    (p.timeMillis - first).toFloat() / duration * size.width,
                    size.height * (1 - p.percent / 100),
                )
            for (percent in listOf(0, 25, 50, 75, 100)) {
                val y = size.height * (1 - percent / 100f)
                drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            history.zipWithNext().forEach { (a, b) ->
                drawLine(
                    if (b.charging) Color(0xFF88CFFF) else Color(0xFF2CDBBE),
                    xy(a),
                    xy(b),
                    2.dp.toPx(),
                )
            }
        }
        Text(
            stringResource(
                R.string.flight_live_battery_duration,
                (history.last().timeMillis - history.first().timeMillis) / 60_000,
            ),
            color = Color.LightGray,
            fontSize = 10.sp,
        )
    }
}
