package net.osmand.plus.plugins.flightmode

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import net.osmand.plus.R

@Composable
internal fun FlightIgnoredTimesDialog(
    state: FlightUiState,
    onChange: (List<FlightTimeRange>, Boolean, List<FlightTimeRange>) -> Unit,
    onDismiss: () -> Unit,
) {
    val source = state.recordingForSelectedFlight().trip ?: state.trip?.recording() ?: return
    val first = source.samples.firstOrNull()?.timestampMillis ?: return
    val last = source.samples.last().timestampMillis
    val cursor = state.snapshot?.sample?.timestampMillis?.coerceIn(first, last)
    var from by remember(state.journeyId) { mutableStateOf(cursor ?: first) }
    var to by remember(state.journeyId) { mutableStateOf(last) }
    val format = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss XXX", Locale.getDefault()) }
    val context = LocalContext.current
    val zone = remember { TimeZone.getDefault() }
    val busy = state.ignoredTimeRangesBusy
    val automaticRanges =
        remember(
            source,
            state.plan.preparation,
            state.automaticFlightTimes,
            state.restoredTimeRanges,
            state.ignoredTimeRanges,
        ) {
            if (state.automaticFlightTimes)
                subtractFlightTimeRanges(
                    FlightAutomaticTimes.excluded(
                        source,
                        state.plan.preparation ?: FlightPreparation(),
                    ),
                    state.restoredTimeRanges + state.ignoredTimeRanges,
                )
            else emptyList()
        }
    fun change(ranges: List<FlightTimeRange>) =
        onChange(ranges, state.automaticFlightTimes, state.restoredTimeRanges)
    val valid = from <= to && from in first..last && to in first..last
    fun pick(time: Long, end: Boolean, apply: (Long) -> Unit) {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = time }
        DatePickerDialog(
                context,
                { _, year, month, day ->
                    calendar.set(Calendar.YEAR, year)
                    calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, day)
                    TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                calendar.set(Calendar.HOUR_OF_DAY, hour)
                                calendar.set(Calendar.MINUTE, minute)
                                calendar.set(Calendar.SECOND, if (end) 59 else 0)
                                calendar.set(Calendar.MILLISECOND, if (end) 999 else 0)
                                apply(calendar.timeInMillis.coerceIn(first, last))
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            true,
                        )
                        .show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            )
            .apply {
                datePicker.minDate = first
                datePicker.maxDate = last
            }
            .show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flight_ignored_times_title)) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.flight_automatic_times),
                            Modifier.weight(1f),
                            fontSize = 12.sp,
                        )
                        Switch(
                            checked = state.automaticFlightTimes,
                            enabled = !busy,
                            onCheckedChange = {
                                onChange(state.ignoredTimeRanges, it, state.restoredTimeRanges)
                            },
                        )
                    }
                    Text(stringResource(R.string.flight_automatic_times_help), fontSize = 12.sp)
                    Text(stringResource(R.string.flight_ignored_times_help), fontSize = 12.sp)
                    Text(stringResource(R.string.flight_ignored_times_zone), fontSize = 11.sp)
                    Text(
                        stringResource(
                            R.string.flight_ignored_times_count,
                            state.trip?.samples?.size ?: 0,
                            source.samples.size,
                        ),
                        fontSize = 12.sp,
                    )
                    if (state.trip?.samples.isNullOrEmpty())
                        Text(
                            stringResource(R.string.flight_ignored_times_all_hidden),
                            fontSize = 12.sp,
                        )
                    Text(
                        "${format.format(Date(first))} → ${format.format(Date(last))}",
                        fontSize = 11.sp,
                    )
                    TextButton(
                        enabled = !busy && cursor != null && cursor > first,
                        onClick = {
                            change(state.ignoredTimeRanges + FlightTimeRange(first, cursor!! - 1))
                        },
                    ) {
                        Text(stringResource(R.string.flight_ignored_times_before), fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = !busy && cursor != null && cursor < last,
                        onClick = {
                            change(state.ignoredTimeRanges + FlightTimeRange(cursor!! + 1, last))
                        },
                    ) {
                        Text(stringResource(R.string.flight_ignored_times_after), fontSize = 12.sp)
                    }
                    HorizontalDivider()
                    TextButton(enabled = !busy, onClick = { pick(from, false) { from = it } }) {
                        Text(
                            "${stringResource(R.string.flight_ignored_times_from)}: ${format.format(Date(from))}",
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(enabled = !busy, onClick = { pick(to, true) { to = it } }) {
                        Text(
                            "${stringResource(R.string.flight_ignored_times_to)}: ${format.format(Date(to))}",
                            fontSize = 12.sp,
                        )
                    }
                    if (!valid)
                        Text(
                            stringResource(R.string.flight_ignored_times_invalid),
                            fontSize = 11.sp,
                        )
                    TextButton(
                        enabled = !busy && valid,
                        onClick = { change(state.ignoredTimeRanges + FlightTimeRange(from, to)) },
                    ) {
                        Text(stringResource(R.string.flight_ignored_times_add), fontSize = 12.sp)
                    }
                    if (busy)
                        Text(
                            stringResource(R.string.flight_ignored_times_working),
                            fontSize = 11.sp,
                        )
                    state.ignoredTimeRangesError?.let { Text(it, fontSize = 11.sp) }
                    HorizontalDivider()
                    if (state.ignoredTimeRanges.isEmpty())
                        Text(stringResource(R.string.flight_ignored_times_none), fontSize = 12.sp)
                }
                itemsIndexed(automaticRanges) { _, range ->
                    Column {
                        Text(stringResource(R.string.flight_automatic_hidden), fontSize = 11.sp)
                        Text(
                            "${format.format(Date(range.startMillis))} → ${format.format(Date(range.endMillis))}",
                            fontSize = 12.sp,
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                onChange(
                                    state.ignoredTimeRanges,
                                    true,
                                    state.restoredTimeRanges + range,
                                )
                            },
                        ) {
                            Text(
                                stringResource(R.string.flight_ignored_times_restore),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                item {
                    if (state.restoredTimeRanges.isNotEmpty())
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                onChange(
                                    state.ignoredTimeRanges,
                                    state.automaticFlightTimes,
                                    emptyList(),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.flight_automatic_reset), fontSize = 12.sp)
                        }
                }
                itemsIndexed(state.ignoredTimeRanges) { index, range ->
                    Column {
                        Text(
                            "${format.format(Date(range.startMillis))}\n→ ${format.format(Date(range.endMillis))}",
                            fontSize = 12.sp,
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                onChange(
                                    state.ignoredTimeRanges.filterIndexed { i, _ -> i != index },
                                    state.automaticFlightTimes,
                                    if (state.automaticFlightTimes) state.restoredTimeRanges + range
                                    else state.restoredTimeRanges,
                                )
                            },
                        ) {
                            Text(
                                stringResource(R.string.flight_ignored_times_restore),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shared_string_done)) }
        },
    )
}
