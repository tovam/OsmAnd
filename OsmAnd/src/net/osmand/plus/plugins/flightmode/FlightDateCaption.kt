package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

/** A second title line, not an extra map overlay or a new card. */
@Composable
internal fun FlightDateCaption(
    state: FlightUiState,
    compact: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ongoing =
        state.journeyId != null &&
            (state.activeRecording.running && state.activeRecording.journeyId == state.journeyId ||
                state.liveState.running && state.liveState.journeyId == state.journeyId)
    val planned = state.sessionMode == FlightSessionMode.PREPARE || state.previewingPlan
    // Scrubbing/rotating the view does not rescan a GPX or create new formatters on every frame.
    val dates =
        remember(state.trip, state.plan.preparation, planned, state.simulatedJourney, ongoing) {
            flightDateRange(
                state.trip,
                state.plan.preparation,
                planned,
                state.simulatedJourney,
                ongoing,
            )
        }
    val locale = LocalConfiguration.current.locales[0]
    val text = remember(dates, locale) { dates?.let { formatFlightDates(it, locale) } }
    val color = Color(0xFF9CAAB4)
    Column(modifier) {
        if (dates == null || text == null) {
            Text(stringResource(R.string.flight_dates_unknown), color = color, fontSize = 10.sp)
        } else if (compact) {
            val prefix =
                when (dates.source) {
                    FlightDateSource.RECORDED -> ""
                    FlightDateSource.PLANNED ->
                        stringResource(R.string.flight_dates_planned) + " · "
                    FlightDateSource.SIMULATED ->
                        stringResource(R.string.flight_dates_simulated) + " · "
                }
            val suffix =
                if (dates.ongoing) " · " + stringResource(R.string.flight_dates_ongoing) else ""
            Text(
                prefix + text.compact + suffix,
                color = color,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                stringResource(R.string.flight_dates_start, text.start),
                color = color,
                fontSize = 11.sp,
            )
            if (dates.ongoing)
                Text(stringResource(R.string.flight_dates_ongoing), color = color, fontSize = 11.sp)
            else
                text.end?.let {
                    Text(
                        stringResource(R.string.flight_dates_end, it),
                        color = color,
                        fontSize = 11.sp,
                    )
                }
        }
    }
}
