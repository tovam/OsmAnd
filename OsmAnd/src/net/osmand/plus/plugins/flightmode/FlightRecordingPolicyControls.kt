package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.osmand.plus.R

private val PolicyPanel = Color(0xFF11181E)
private val PolicyLine = Color(0xFF2A3842)
private val PolicyText = Color(0xFFF4F7F8)
private val PolicyMuted = Color(0xFFA5B0B8)
private val PolicyOrange = Color(0xFFFF8B38)
private val PolicyBlue = Color(0xFF5DD8FF)

/**
 * Compact live recording policy editor. Changes are emitted only for mode/preset taps or when a
 * valid numeric form is explicitly applied, so a text edit or gesture cannot flood the service.
 */
@Composable
internal fun FlightRecordingPolicyControls(
    policy: FlightRecordingPolicy,
    onChange: (FlightRecordingPolicy) -> Unit,
    live: FlightLiveState? = null,
) {
    val safe = policy.clamped()
    var fixedText by
        remember(safe.fixedIntervalSeconds) {
            mutableStateOf(policyNumber(safe.fixedIntervalSeconds))
        }
    var distanceText by
        remember(safe.cruisePointDistanceMeters) {
            mutableStateOf(policyNumber(safe.cruisePointDistanceMeters))
        }
    var maximumText by
        remember(safe.maximumStraightIntervalSeconds) {
            mutableStateOf(policyNumber(safe.maximumStraightIntervalSeconds))
        }
    var turnText by
        remember(safe.turnAcceleration) { mutableStateOf(policyNumber(safe.turnAcceleration)) }
    var deviationText by
        remember(safe.routeDeviationAcceleration) {
            mutableStateOf(policyNumber(safe.routeDeviationAcceleration))
        }
    var adaptiveDetails by remember(safe.mode) { mutableStateOf(true) }

    val currentSpeed = live?.latest?.speedMetersPerSecond
    val effectiveText =
        when {
            safe.mode == FlightRecordingMode.FIXED ->
                stringResource(
                    R.string.flight_recording_policy_effective_fixed,
                    if (safe.fixedIntervalSeconds % 60f == 0f)
                        stringResource(
                            R.string.flight_recording_policy_preset_minutes,
                            safe.fixedIntervalSeconds / 60f,
                        )
                    else
                        stringResource(
                            R.string.flight_recording_policy_value_seconds,
                            policyNumber(safe.fixedIntervalSeconds),
                        ),
                )
            currentSpeed != null ->
                stringResource(
                    R.string.flight_recording_policy_effective_adaptive_speed,
                    safe.intervalSeconds(currentSpeed),
                )
            else ->
                stringResource(
                    R.string.flight_recording_policy_effective_adaptive_waiting,
                    safe.maximumStraightIntervalSeconds,
                )
        }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(PolicyPanel)
                .border(1.dp, PolicyLine)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            stringResource(R.string.flight_recording_policy_saved_points),
            color = PolicyText,
            fontSize = 12.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PolicyModeButton(
                text = stringResource(R.string.flight_recording_policy_mode_adaptive),
                selected = safe.mode == FlightRecordingMode.ADAPTIVE,
                onClick = { onChange(safe.copy(mode = FlightRecordingMode.ADAPTIVE)) },
                modifier = Modifier.weight(1f),
            )
            PolicyModeButton(
                text = stringResource(R.string.flight_recording_policy_mode_fixed),
                selected = safe.mode == FlightRecordingMode.FIXED,
                onClick = { onChange(safe.copy(mode = FlightRecordingMode.FIXED)) },
                modifier = Modifier.weight(1f),
            )
        }

        if (safe.mode == FlightRecordingMode.FIXED) {
            Text(
                stringResource(R.string.flight_recording_policy_interval_seconds),
                color = PolicyMuted,
                fontSize = 11.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(10f, 60f, 600f).forEach { seconds ->
                    PolicyModeButton(
                        text =
                            stringResource(
                                if (seconds >= 60f) R.string.flight_recording_policy_preset_minutes
                                else R.string.flight_recording_policy_preset_seconds,
                                if (seconds >= 60f) seconds / 60f else seconds,
                            ),
                        selected = safe.fixedIntervalSeconds == seconds,
                        onClick = {
                            fixedText = policyNumber(seconds)
                            onChange(safe.copy(fixedIntervalSeconds = seconds))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PolicyNumberField(
                    label = stringResource(R.string.flight_recording_policy_interval_seconds),
                    unit = stringResource(R.string.flight_recording_policy_seconds_unit),
                    hint = stringResource(R.string.flight_recording_policy_range_seconds),
                    value = fixedText,
                    onValueChange = { fixedText = it },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(5.dp))
                val fixed = fixedText.toFloatOrNull()
                Button(
                    onClick = { onChange(safe.copy(fixedIntervalSeconds = fixed!!)) },
                    enabled =
                        fixed.inPolicyRange(
                            FlightRecordingPolicy.MIN_INTERVAL_SECONDS,
                            FlightRecordingPolicy.MAX_INTERVAL_SECONDS,
                        ),
                    contentPadding = PaddingValues(horizontal = 9.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PolicyOrange),
                ) {
                    Text(
                        stringResource(R.string.flight_recording_policy_apply),
                        color = Color.Black,
                        fontSize = 11.sp,
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                    adaptiveDetails = !adaptiveDetails
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.flight_recording_policy_adaptive_details),
                    color = PolicyBlue,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(if (adaptiveDetails) "−" else "+", color = PolicyBlue, fontSize = 16.sp)
            }
            if (adaptiveDetails) {
                PolicyNumberField(
                    label = stringResource(R.string.flight_recording_policy_cruise_distance),
                    unit = stringResource(R.string.flight_recording_policy_meters_unit),
                    hint = stringResource(R.string.flight_recording_policy_range_meters),
                    value = distanceText,
                    onValueChange = { distanceText = it },
                )
                PolicyNumberField(
                    label = stringResource(R.string.flight_recording_policy_maximum_interval),
                    unit = stringResource(R.string.flight_recording_policy_seconds_unit),
                    hint = stringResource(R.string.flight_recording_policy_range_seconds),
                    value = maximumText,
                    onValueChange = { maximumText = it },
                )
                PolicyNumberField(
                    label = stringResource(R.string.flight_recording_policy_turn_boost),
                    unit = stringResource(R.string.flight_recording_policy_multiplier_unit),
                    hint = stringResource(R.string.flight_recording_policy_range_multiplier),
                    value = turnText,
                    onValueChange = { turnText = it },
                )
                PolicyNumberField(
                    label = stringResource(R.string.flight_recording_policy_deviation_boost),
                    unit = stringResource(R.string.flight_recording_policy_multiplier_unit),
                    hint = stringResource(R.string.flight_recording_policy_range_multiplier),
                    value = deviationText,
                    onValueChange = { deviationText = it },
                )
                val distance = distanceText.toFloatOrNull()
                val maximum = maximumText.toFloatOrNull()
                val turn = turnText.toFloatOrNull()
                val deviation = deviationText.toFloatOrNull()
                Button(
                    onClick = {
                        onChange(
                            safe.copy(
                                cruisePointDistanceMeters = distance!!,
                                maximumStraightIntervalSeconds = maximum!!,
                                turnAcceleration = turn!!,
                                routeDeviationAcceleration = deviation!!,
                            )
                        )
                    },
                    enabled =
                        distance.inPolicyRange(
                            FlightRecordingPolicy.MIN_CRUISE_DISTANCE_METERS,
                            FlightRecordingPolicy.MAX_CRUISE_DISTANCE_METERS,
                        ) &&
                            maximum.inPolicyRange(
                                FlightRecordingPolicy.MIN_INTERVAL_SECONDS,
                                FlightRecordingPolicy.MAX_INTERVAL_SECONDS,
                            ) &&
                            turn.inPolicyRange(
                                FlightRecordingPolicy.MIN_ACCELERATION,
                                FlightRecordingPolicy.MAX_ACCELERATION,
                            ) &&
                            deviation.inPolicyRange(
                                FlightRecordingPolicy.MIN_ACCELERATION,
                                FlightRecordingPolicy.MAX_ACCELERATION,
                            ),
                    contentPadding = PaddingValues(horizontal = 9.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PolicyOrange),
                ) {
                    Text(
                        stringResource(R.string.flight_recording_policy_apply),
                        color = Color.Black,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        Text(effectiveText, color = PolicyBlue, fontSize = 10.sp)
        Text(
            stringResource(R.string.flight_recording_policy_gps_note),
            color = PolicyMuted,
            fontSize = 9.sp,
            lineHeight = 11.sp,
        )
    }
}

@Composable
private fun PolicyModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.heightIn(min = 48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth()
                .height(30.dp)
                .border(1.dp, if (selected) PolicyOrange else PolicyLine, RoundedCornerShape(2.dp))
                .background(if (selected) PolicyOrange.copy(alpha = 0.16f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = if (selected) PolicyOrange else PolicyText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PolicyNumberField(
    label: String,
    unit: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 5.dp)) {
            Text(label, color = PolicyText, fontSize = 11.sp, lineHeight = 13.sp)
            Text(hint, color = PolicyMuted, fontSize = 8.sp, lineHeight = 10.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(normalizePolicyInput(it)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = androidx.compose.ui.text.TextStyle(color = PolicyText, fontSize = 11.sp),
            modifier =
                Modifier.width(96.dp)
                    .heightIn(min = 48.dp)
                    .border(1.dp, PolicyLine, RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { inner() }
                    Text(unit, color = PolicyMuted, fontSize = 9.sp)
                }
            },
        )
    }
}

private fun normalizePolicyInput(value: String): String = value.replace(',', '.')

private fun Float?.inPolicyRange(minimum: Float, maximum: Float): Boolean =
    this?.takeIf(Float::isFinite)?.let { it in minimum..maximum } == true

private fun policyNumber(value: Float): String = value.toString().removeSuffix(".0")
