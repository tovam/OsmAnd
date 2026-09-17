package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.osmand.plus.R

/** Draft is isolated from incoming GPS updates until the user explicitly applies it. */
@Composable
internal fun FlightLiveRouteEditor(state: FlightUiState, onApply: (FlightPlan) -> Unit, onClose: () -> Unit) {
    val departureLabel = stringResource(R.string.flight_plan_departure)
    val arrivalLabel = stringResource(R.string.flight_plan_arrival)
    val original = remember(state.journeyId) {
        if (state.plan.stops.size >= 2) state.plan else state.plan.copy(stops = listOf(
            FlightStop(departureLabel, state.liveState.latest?.latitude, state.liveState.latest?.longitude),
            state.plan.stops.lastOrNull() ?: FlightStop(arrivalLabel)))
    }
    val firstEditable = remember(state.journeyId) {
        state.liveState.latest?.let { FlightRouteHypothesis.remainingStops(original, it).firstOrNull()?.index }
            ?.coerceAtLeast(1) ?: 1
    }
    var draft by remember(state.journeyId) { mutableStateOf(original) }
    var selected by remember { mutableIntStateOf(firstEditable.coerceAtMost(draft.stops.lastIndex)) }
    val waypointLabel = stringResource(R.string.flight_route_waypoint)
    val stopoverLabel = stringResource(R.string.flight_route_stopover)
    fun add(type: FlightStopType) {
        if (draft.stops.size >= 100) return
        val insertion = selected.coerceIn(firstEditable, draft.stops.lastIndex)
        draft = draft.copy(stops = draft.stops.toMutableList().apply {
            add(insertion, FlightStop(if (type == FlightStopType.WAYPOINT) waypointLabel else stopoverLabel, type = type))
        })
        selected = insertion
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0A0F13)).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.shared_string_cancel), fontSize = 12.sp) }
                TextButton(onClick = { add(FlightStopType.WAYPOINT) }, enabled = draft.stops.size < 100) {
                    Text(stringResource(R.string.flight_plan_add_via), fontSize = 12.sp)
                }
                TextButton(onClick = { add(FlightStopType.STOPOVER) }, enabled = draft.stops.size < 100) {
                    Text(stringResource(R.string.flight_route_add_stopover), fontSize = 12.sp)
                }
                TextButton(onClick = { onApply(draft); onClose() }, enabled = FlightOfflinePreparation.canSimulate(draft)) {
                    Text(stringResource(R.string.shared_string_apply), fontSize = 12.sp)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                draft.stops.forEachIndexed { index, stop ->
                    TextButton(onClick = { selected = index }, enabled = index >= firstEditable) {
                        Text("${index + 1} ${stop.name}", fontSize = 12.sp,
                            color = if (index == selected) Color(0xFFFF8B38) else Color.LightGray)
                    }
                }
            }
            val stop = draft.stops[selected]
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = stop.name, singleLine = true,
                    onValueChange = { name -> draft = draft.copy(stops = draft.stops.mapIndexed { i, s -> if (i == selected) s.copy(name = name) else s }) },
                    modifier = Modifier.weight(1f))
                if (selected in firstEditable until draft.stops.lastIndex) {
                    TextButton(onClick = { draft = draft.copy(stops = draft.stops.mapIndexed { i, s -> if (i == selected)
                        s.copy(type = if (s.type == FlightStopType.WAYPOINT) FlightStopType.STOPOVER else FlightStopType.WAYPOINT) else s }) }) {
                        Text(if (stop.type == FlightStopType.WAYPOINT) waypointLabel else stopoverLabel, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        draft = draft.copy(stops = draft.stops.filterIndexed { i, _ -> i != selected })
                        selected = selected.coerceAtMost(draft.stops.lastIndex)
                    }) { Text(stringResource(R.string.shared_string_delete), fontSize = 12.sp) }
                }
            }
            Text(stringResource(R.string.flight_route_edit_hint), color = Color.LightGray, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            FlightPlanMap(draft, selected, emptyList(), true, Modifier.weight(1f), { lat, lon ->
                draft = draft.copy(stops = draft.stops.mapIndexed { i, s -> if (i == selected)
                    s.copy(latitude = lat, longitude = lon) else s })
            }, { if (it >= firstEditable) selected = it })
        }
    }
}
