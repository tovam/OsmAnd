package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

@Composable
internal fun FlightWorkspaceHome(
    state: FlightUiState,
    onPage: (FlightPage) -> Unit,
    onOpen: (String) -> Unit,
    onNew: (Boolean) -> Unit,
    onClose: () -> Unit,
    onCloud: () -> Unit = {},
) {
    val planned = state.page == FlightPage.PLANS
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0F13))) {
        Row(Modifier.fillMaxWidth()) {
            PlanAction(stringResource(R.string.flight_workspace_home), { onPage(FlightPage.HOME) })
            PlanAction(stringResource(R.string.flight_cloud_library), onCloud)
            Spacer(Modifier.weight(1f))
            PlanAction(stringResource(R.string.flight_mode_close), onClose)
        }
        if (!planned) {
            WorkspaceEntry(R.string.flight_workspace_past, R.string.flight_workspace_past_hint) {
                onPage(FlightPage.JOURNEYS)
            }
            WorkspaceEntry(
                R.string.flight_workspace_future,
                R.string.flight_workspace_future_hint,
            ) {
                onPage(FlightPage.PLANS)
            }
            WorkspaceEntry(
                R.string.flight_workspace_current,
                R.string.flight_workspace_current_hint,
                state.activeRecording.running && state.activeRecording.journeyId != null,
            ) {
                state.activeRecording.journeyId?.let(onOpen)
            }
        } else {
            PlanAction(stringResource(R.string.flight_plan_new), { onNew(false) })
            if (state.savedJourneysLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                items(
                    state.savedJourneys.filter {
                        it.sampleCount == 0 &&
                            !(state.activeRecording.running &&
                                it.id == state.activeRecording.journeyId)
                    },
                    key = { it.id },
                ) { journey ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(journey.id) }.padding(12.dp)) {
                        Text(journey.name, color = Color.White, fontSize = 14.sp)
                    }
                    HorizontalDivider(color = Color(0xFF2A3842))
                }
            }
        }
        if (state.loadingTrip) LinearProgressIndicator(Modifier.fillMaxWidth())
        (state.tripLoadError ?: state.journeyMessage)?.let {
            Text(it, color = Color(0xFFFFCC66), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun WorkspaceEntry(title: Int, hint: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp)) {
        Text(
            stringResource(title),
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 20.sp,
        )
        Text(stringResource(hint), color = Color.Gray, fontSize = 12.sp)
    }
    HorizontalDivider(color = Color(0xFF2A3842))
}
