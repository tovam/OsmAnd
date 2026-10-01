package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

@Composable
internal fun FlightPrecisionSelector(plan: FlightPlan, onUpdate: (FlightPlan) -> Unit,
    advanced: Boolean, onAdvanced: (Boolean) -> Unit) {
    val selected = FlightPrecisionProfile.selected(plan)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlightPrecisionProfile.entries.forEach { preset ->
                FilterChip(selected == preset, { onUpdate(preset.apply(plan)) },
                    label = { Text(stringResource(when (preset) {
                        FlightPrecisionProfile.ECONOMY -> R.string.flight_precision_economy
                        FlightPrecisionProfile.BALANCED -> R.string.flight_precision_balanced
                        FlightPrecisionProfile.DETAILED -> R.string.flight_precision_detailed
                    }), fontSize = 12.sp) }, modifier = Modifier.weight(1f).heightIn(min = 40.dp))
            }
        }
        Text(stringResource(when (selected) {
            FlightPrecisionProfile.ECONOMY -> R.string.flight_precision_economy_help
            FlightPrecisionProfile.BALANCED -> R.string.flight_precision_balanced_help
            FlightPrecisionProfile.DETAILED -> R.string.flight_precision_detailed_help
            null -> R.string.flight_precision_custom
        }), fontSize = 12.sp)
        TextButton(onClick = { onAdvanced(!advanced) }) {
            Text(stringResource(if (advanced) R.string.flight_precision_hide_advanced else R.string.flight_precision_advanced), fontSize = 12.sp)
        }
    }
}
