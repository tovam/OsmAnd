package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.osmand.plus.R

@Composable
internal fun FlightReadinessDialog(state: FlightUiState, onDismiss: () -> Unit,
    onItinerary: () -> Unit, onPermissions: () -> Unit, onOffline: () -> Unit,
    onSchedule: () -> Unit, onStorage: () -> Unit) {
    val context = LocalContext.current
    var statuses by remember { mutableStateOf(FlightScheduleManager.permissionStatuses(context)) }
    var freeBytes by remember { mutableLongStateOf(-1L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    FlightResumedEffect(state.journeyId, state.offlinePreloadStatus.phase) {
        statuses = FlightScheduleManager.permissionStatuses(context)
        freeBytes = withContext(Dispatchers.IO) { context.filesDir.usableSpace }
        now = System.currentTimeMillis()
    }
    val automatic = state.scheduledStartMillis != null || state.plan.preparation?.automatic == true
    val permissionsReady = statuses.filter { automatic || it.label == R.string.flight_plan_permission_gps }.all { it.granted }
    val report = FlightReadiness.evaluate(state, permissionsReady, freeBytes, now)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (report.ready) R.string.flight_readiness_ready else R.string.flight_readiness_title)) },
        text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadinessLine(stringResource(R.string.flight_readiness_itinerary), report.itinerary,
                stringResource(if (report.itinerary) R.string.flight_readiness_itinerary_ok else R.string.flight_readiness_itinerary_missing),
                stringResource(R.string.flight_readiness_edit), onItinerary)
            ReadinessLine(stringResource(R.string.flight_readiness_permissions), report.permissions,
                stringResource(if (report.permissions) R.string.flight_readiness_permissions_ok else R.string.flight_readiness_permissions_missing),
                stringResource(R.string.flight_plan_permissions), onPermissions)
            statuses.filter { !it.granted }.forEach { Text(stringResource(it.label), fontSize = 11.sp) }
            ReadinessLine(stringResource(R.string.flight_readiness_offline), report.offlineVerified,
                state.offlinePreloadStatus.message ?: stringResource(if (report.offlineVerified)
                    R.string.flight_readiness_offline_ok else R.string.flight_readiness_offline_missing),
                stringResource(R.string.flight_readiness_verify), onOffline)
            ReadinessLine(stringResource(R.string.flight_readiness_storage), report.storage,
                if (freeBytes < 0) stringResource(R.string.flight_readiness_checking) else stringResource(R.string.flight_readiness_free, formatStorageBytes(freeBytes)),
                stringResource(R.string.flight_readiness_manage), onStorage)
            ReadinessLine(stringResource(R.string.flight_readiness_schedule), report.scheduled,
                stringResource(if (report.scheduled) R.string.flight_readiness_schedule_ok else R.string.flight_readiness_schedule_manual),
                stringResource(R.string.flight_readiness_schedule_action), onSchedule)
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shared_string_close)) } })
}

@Composable
private fun ReadinessLine(title: String, ready: Boolean, detail: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text("${if (ready) "✓" else "○"} $title", fontSize = 13.sp)
            Text(detail, fontSize = 12.sp)
        }
        TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
    }
}
