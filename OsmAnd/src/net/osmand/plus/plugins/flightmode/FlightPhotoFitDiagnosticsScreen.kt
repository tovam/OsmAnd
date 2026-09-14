package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.osmand.plus.R

/** A separate scrolling report keeps arbitrarily many points out of the camera controls. */
@Composable
internal fun FlightPhotoFitDiagnosticsScreen(
    fit: FlightPhotoFit,
    legacyPointIndices: List<Int>,
    imageWidth: Int,
    imageHeight: Int,
    onClose: () -> Unit,
) {
    var byImpact by remember { mutableStateOf(true) }
    val indices = fit.pointIndices.ifEmpty { legacyPointIndices }
    val full = remember(fit) { FlightPhotoErrorMetrics.of(fit.errors) }
    val fullErrors = remember(fit, indices) { indices.zip(fit.errors).toMap() }
    val rows =
        remember(fit, byImpact) {
            if (byImpact) fit.rankedInfluences()
            else fit.influences.orEmpty().sortedByDescending { fullErrors[it.pointIndex] ?: 0.0 }
        }
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Color(0xFF0A0F13))
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.flight_cal_diagnostics_title),
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.shared_string_close)) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                item {
                    DiagnosticText(
                        stringResource(
                            R.string.flight_cal_diagnostics_full,
                            fit.errors.size,
                            full.rms,
                            full.cumulative,
                        ),
                        prominent = true,
                    )
                    DiagnosticText(
                        stringResource(
                            R.string.flight_cal_diagnostics_units,
                            imageWidth,
                            imageHeight,
                        )
                    )
                    DiagnosticText(
                        stringResource(
                            R.string.flight_cal_diagnostics_largest,
                            fullErrors.entries
                                .sortedByDescending { it.value }
                                .take(5)
                                .joinToString(" · ") { "#${it.key + 1}: %.1f px".format(it.value) },
                        )
                    )
                    DiagnosticText(stringResource(R.string.flight_cal_diagnostics_warning))
                    if (fit.weak)
                        DiagnosticText(
                            stringResource(R.string.flight_cal_diagnostics_full_weak),
                            warning = true,
                        )
                    if (rows.isEmpty()) {
                        DiagnosticText(
                            stringResource(
                                if (fit.errors.size < 5) R.string.flight_cal_diagnostics_need_five
                                else R.string.flight_cal_diagnostics_recalculate
                            ),
                            warning = true,
                        )
                    } else {
                        DiagnosticText(stringResource(R.string.flight_cal_diagnostics_comparison))
                        Row {
                            TextButton(onClick = { byImpact = true }) {
                                Text(
                                    stringResource(R.string.flight_cal_diagnostics_sort_impact),
                                    color = if (byImpact) Color.White else Color.Gray,
                                    fontSize = 12.sp,
                                )
                            }
                            TextButton(onClick = { byImpact = false }) {
                                Text(
                                    stringResource(R.string.flight_cal_diagnostics_sort_error),
                                    color = if (!byImpact) Color.White else Color.Gray,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
                items(rows, key = { it.pointIndex }) { row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        DiagnosticText(
                            stringResource(
                                R.string.flight_cal_diagnostics_point,
                                row.pointIndex + 1,
                                fullErrors[row.pointIndex] ?: 0.0,
                            ),
                            prominent = true,
                        )
                        val after = row.after
                        if (after == null) {
                            DiagnosticText(
                                stringResource(R.string.flight_cal_diagnostics_failed),
                                warning = true,
                            )
                        } else {
                            val before = row.before(fit)
                            DiagnosticText(
                                stringResource(
                                    R.string.flight_cal_diagnostics_rms,
                                    fit.errors.size - 1,
                                    before.rms,
                                    after.rms,
                                )
                            )
                            DiagnosticText(
                                stringResource(
                                    R.string.flight_cal_diagnostics_sum,
                                    before.cumulative,
                                    after.cumulative,
                                )
                            )
                            row.rmsReductionPercent(fit)?.let { gain ->
                                DiagnosticText(
                                    stringResource(R.string.flight_cal_diagnostics_gain, gain)
                                )
                            }
                            DiagnosticText(
                                row.excludedError?.let {
                                    stringResource(R.string.flight_cal_diagnostics_held_out, it)
                                } ?: stringResource(R.string.flight_cal_diagnostics_behind)
                            )
                            if (row.weak)
                                DiagnosticText(
                                    stringResource(R.string.flight_cal_diagnostics_subset_weak),
                                    warning = true,
                                )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticText(text: String, prominent: Boolean = false, warning: Boolean = false) {
    Text(
        text,
        color = if (warning) Color(0xFFFFCC66) else if (prominent) Color.White else Color.LightGray,
        fontSize = if (prominent) 13.sp else 11.sp,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
