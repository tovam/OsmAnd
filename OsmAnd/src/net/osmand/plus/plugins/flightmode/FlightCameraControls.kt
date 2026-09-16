package net.osmand.plus.plugins.flightmode

import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CaptureRequest as Rq
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.*
import net.osmand.plus.R

/** Request only advertised hardware capabilities; CameraX still owns preview and capture. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
@Composable
internal fun FlightCameraControls(
    camera: Camera,
    lens: FlightCameraLens,
    enabled: Boolean,
    onManualFocus: (Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val info = lens.characteristics
    val isoRange = remember(info) { info[C.SENSOR_INFO_SENSITIVITY_RANGE] }
    val timeRange = remember(info) { info[C.SENSOR_INFO_EXPOSURE_TIME_RANGE] }
    val manualSupported =
        remember(info) {
            info[C.REQUEST_AVAILABLE_CAPABILITIES]?.contains(
                C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ) == true && isoRange != null && timeRange != null
        }
    val focusMax = remember(info) { info[C.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f }
    val wbModes = remember(info) { info[C.CONTROL_AWB_AVAILABLE_MODES]?.toSet().orEmpty() }
    var manual by remember(camera) { mutableStateOf(false) }
    var iso by
        remember(camera) {
            mutableIntStateOf(isoRange?.let { 200.coerceIn(it.lower, it.upper) } ?: 200)
        }
    var exposureNs by
        remember(camera) {
            mutableLongStateOf(
                timeRange?.let { 1_000_000L.coerceIn(it.lower, it.upper) } ?: 1_000_000
            )
        }
    var infinity by remember(camera) { mutableStateOf(false) }
    var focus by remember(camera) { mutableFloatStateOf(0f) }
    var wb by remember(camera) { mutableIntStateOf(Rq.CONTROL_AWB_MODE_AUTO) }
    var tab by remember { mutableIntStateOf(0) }
    var exposure by
        remember(camera) {
            mutableIntStateOf(camera.cameraInfo.exposureState.exposureCompensationIndex)
        }
    val errorAction by rememberUpdatedState(onError)
    LaunchedEffect(infinity, camera) { onManualFocus(infinity) }
    DisposableEffect(camera) { onDispose { onManualFocus(false) } }
    LaunchedEffect(camera, manual, iso, exposureNs, infinity, focus, wb) {
        val options =
            CaptureRequestOptions.Builder().setCaptureRequestOption(Rq.CONTROL_AWB_MODE, wb)
        if (manual) {
            options
                .setCaptureRequestOption(Rq.CONTROL_AE_MODE, Rq.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(Rq.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(Rq.SENSOR_EXPOSURE_TIME, exposureNs)
        }
        if (infinity && focusMax > 0f)
            options
                .setCaptureRequestOption(Rq.CONTROL_AF_MODE, Rq.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(Rq.LENS_FOCUS_DISTANCE, focus)
        try {
            val future =
                Camera2CameraControl.from(camera.cameraControl)
                    .setCaptureRequestOptions(options.build())
            future.addListener(
                {
                    try {
                        future.get()
                    } catch (e: Exception) {
                        if (
                            e.cause
                                !is androidx.camera.core.CameraControl.OperationCanceledException
                        )
                            errorAction(
                                e.cause?.message
                                    ?: e.message
                                    ?: context.getString(R.string.flight_camera_settings_failed)
                            )
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        } catch (e: Exception) {
            errorAction(e.message ?: context.getString(R.string.flight_camera_settings_failed))
        }
    }
    TabRow(selectedTabIndex = tab, containerColor = Color.Black, contentColor = Color.White) {
        listOf(
                R.string.flight_camera_exposure_tab,
                R.string.flight_camera_focus_tab,
                R.string.flight_camera_color_tab,
            )
            .forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(label), fontSize = 12.sp) },
                )
            }
    }
    if (tab == 0 && manualSupported)
        CameraSingleChoice(
            listOf(
                stringResource(R.string.flight_camera_mode_auto),
                stringResource(R.string.flight_camera_mode_manual),
            ),
            if (manual) 1 else 0,
            enabled,
        ) {
            manual = it == 1
        }
    if (tab == 1) {
        if (focusMax > 0f)
            CameraSingleChoice(
                listOf(
                    stringResource(R.string.flight_camera_mode_auto),
                    stringResource(R.string.flight_camera_mode_manual),
                ),
                if (infinity) 1 else 0,
                enabled,
            ) {
                infinity = it == 1
            }
        Text(
            stringResource(
                if (focusMax > 0f) R.string.flight_camera_focus_limit
                else if (info[C.LENS_INFO_MINIMUM_FOCUS_DISTANCE] == 0f)
                    R.string.flight_camera_focus_fixed
                else R.string.flight_camera_focus_unknown,
                100f / focusMax.coerceAtLeast(0.001f),
            ),
            color = Color.LightGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (tab == 2) {
        val modes =
            listOf(
                    Rq.CONTROL_AWB_MODE_AUTO to R.string.flight_camera_wb_auto,
                    Rq.CONTROL_AWB_MODE_DAYLIGHT to R.string.flight_camera_wb_day,
                    Rq.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to R.string.flight_camera_wb_cloud,
                    Rq.CONTROL_AWB_MODE_INCANDESCENT to R.string.flight_camera_wb_warm,
                )
                .filter { it.first in wbModes }
        CameraSingleChoice(
            modes.map { stringResource(it.second) },
            modes.indexOfFirst { it.first == wb },
            enabled,
        ) {
            wb = modes[it].first
        }
    }
    if (tab == 0 && manual && isoRange != null && timeRange != null) {
        Row(Modifier.height(40.dp).padding(horizontal = 8.dp)) {
            Text(
                "ISO $iso",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.width(75.dp),
            )
            if (isoRange.upper > isoRange.lower)
                Slider(
                    iso.toFloat(),
                    { iso = it.roundToInt() },
                    valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
        }
        val lower = ln(timeRange.lower.coerceAtLeast(1).toDouble()).toFloat()
        // Long exposures remain available but never exceed the hardware limit or one second.
        val upper =
            ln(
                    timeRange.upper
                        .coerceAtMost(1_000_000_000)
                        .coerceAtLeast(timeRange.lower)
                        .toDouble()
                )
                .toFloat()
        Row(Modifier.height(40.dp).padding(horizontal = 8.dp)) {
            Text(
                if (exposureNs < 100_000_000) "1/${(1e9/exposureNs).roundToInt()} s"
                else "%.2f s".format(exposureNs / 1e9),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.width(75.dp),
            )
            if (upper > lower)
                Slider(
                    ln(exposureNs.toDouble()).toFloat().coerceIn(lower, upper),
                    {
                        exposureNs =
                            exp(it.toDouble()).toLong().coerceIn(timeRange.lower, timeRange.upper)
                    },
                    valueRange = lower..upper,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
        }
    }
    if (tab == 0 && !manual) {
        val e = camera.cameraInfo.exposureState
        if (e.isExposureCompensationSupported)
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%+.1f EV".format(exposure * e.exposureCompensationStep.toFloat()),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.width(75.dp),
                )
                Slider(
                    exposure.toFloat(),
                    {
                        exposure = it.roundToInt()
                        camera.cameraControl.setExposureCompensationIndex(exposure)
                    },
                    valueRange =
                        e.exposureCompensationRange.lower.toFloat()..e.exposureCompensationRange
                                .upper
                                .toFloat(),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
    }
    if (tab == 1 && infinity && focusMax > 0f)
        Row(Modifier.height(40.dp).padding(horizontal = 8.dp)) {
            Text(
                if (focus < 0.01f) "∞" else "%.2f m".format(1 / focus),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.width(75.dp),
            )
            Slider(
                focus,
                { focus = it },
                valueRange = 0f..focusMax,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
}

/** Radio semantics make exclusive choices explicit; these are not independent toggles. */
@Composable
private fun CameraSingleChoice(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            Row(
                Modifier.heightIn(min = 48.dp)
                    .selectable(
                        selected == index,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected == index, onClick = null, enabled = enabled)
                Text(
                    label,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
