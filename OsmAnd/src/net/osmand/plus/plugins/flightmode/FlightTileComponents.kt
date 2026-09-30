package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

internal object FlightTileColors {
    val background = Color(0xFF0B1219)
    val surface = Color(0xFF15222D)
    val border = Color(0xFF293D4C)
    val muted = Color(0xFFB1C2CF)
    val accent = Color(0xFF9AD9FF)
    val complete = Color(0xFF73D6AF)
    val missing = Color(0xFFFFBD75)
    val shared = Color(0xFF91BCFA)
    val checking = Color(0xFFB7ACD7)
}

@Composable
internal fun FlightTileCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(14.dp),
        color = FlightTileColors.surface,
        border = BorderStroke(1.dp, FlightTileColors.border),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun FlightTileChoices(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Surface(
                Modifier.weight(1f)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) }),
                shape = RoundedCornerShape(10.dp),
                color = if (active) Color(0xFF284D65) else FlightTileColors.surface,
                border =
                    BorderStroke(
                        1.dp,
                        if (active) FlightTileColors.accent else FlightTileColors.border,
                    ),
            ) {
                Box(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (active) Color.White else FlightTileColors.muted,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
internal fun FlightTileValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = FlightTileColors.muted, fontSize = 12.sp)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun flightTileStatusText(state: FlightUiState): String =
    stringResource(
        when (flightTileReadiness(state)) {
            FlightTileReadiness.EMPTY -> R.string.flight_tiles_empty_route
            FlightTileReadiness.CHECKING -> R.string.flight_tiles_checking_title
            FlightTileReadiness.DOWNLOADING -> R.string.flight_tiles_downloading_title
            FlightTileReadiness.PAUSED -> R.string.flight_plan_pause_label
            FlightTileReadiness.MISSING -> R.string.flight_tiles_missing_title
            FlightTileReadiness.PRESENT -> R.string.flight_tiles_present_title
            FlightTileReadiness.VERIFIED -> R.string.flight_offline_verified
            FlightTileReadiness.ERROR -> R.string.flight_tiles_error_title
        }
    )

@Composable
internal fun FlightTileDownloadBar(
    state: FlightUiState,
    onDownload: (FlightOfflineQuote) -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverage = state.offlineCoverage
    val quote = state.offlineQuote
    val action = flightTileAction(state)
    val label =
        when (action) {
            FlightTileAction.PAUSE -> stringResource(R.string.flight_plan_pause)
            FlightTileAction.OFFLINE_TEST -> stringResource(R.string.flight_files_offline_test)
            FlightTileAction.UNAVAILABLE -> stringResource(R.string.flight_offline_count_failed)
            FlightTileAction.CHECKING -> stringResource(R.string.flight_tiles_checking_title)
            FlightTileAction.EMPTY -> stringResource(R.string.flight_tiles_empty_route)
            FlightTileAction.VERIFY -> stringResource(R.string.flight_files_verify)
            FlightTileAction.RESUME ->
                stringResource(
                    R.string.flight_tiles_resume_count,
                    coverage!!.missing,
                    flightTileSize(coverage.estimatedRemainingBytes),
                )
            FlightTileAction.DOWNLOAD ->
                stringResource(
                    R.string.flight_tiles_download_size,
                    coverage!!.missing,
                    flightTileSize(coverage.estimatedRemainingBytes),
                )
        }
    Button(
        onClick = {
            if (action == FlightTileAction.PAUSE) onPause()
            else if (quote != null && action.enabled) onDownload(quote)
        },
        enabled = action.enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = FlightTileColors.accent,
                contentColor = Color(0xFF10222F),
            ),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
