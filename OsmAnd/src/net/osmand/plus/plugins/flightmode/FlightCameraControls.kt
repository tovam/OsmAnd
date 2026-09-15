package net.osmand.plus.plugins.flightmode

import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CaptureRequest as Rq
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    enabled: Boolean,
    onManual: (Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val info = remember(camera) { Camera2CameraInfo.from(camera.cameraInfo) }
    val isoRange = remember(info) { info.getCameraCharacteristic(C.SENSOR_INFO_SENSITIVITY_RANGE) }
    val timeRange =
        remember(info) { info.getCameraCharacteristic(C.SENSOR_INFO_EXPOSURE_TIME_RANGE) }
    val manualSupported =
        remember(info) {
            info
                .getCameraCharacteristic(C.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true &&
                isoRange != null &&
                timeRange != null
        }
    val focusMax =
        remember(info) { info.getCameraCharacteristic(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f }
    val wbModes =
        remember(info) {
            info.getCameraCharacteristic(C.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
        }
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
    val errorAction by rememberUpdatedState(onError)
    LaunchedEffect(manual, camera) { onManual(manual) }
    DisposableEffect(camera) { onDispose { onManual(false) } }
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
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        if (manualSupported)
            PlanAction(
                stringResource(
                    if (manual) R.string.flight_camera_manual else R.string.flight_camera_auto
                ),
                { manual = !manual },
                manual,
                enabled,
            )
        if (focusMax > 0f)
            PlanAction(
                stringResource(
                    if (infinity) R.string.flight_camera_focus_manual
                    else R.string.flight_camera_focus_auto
                ),
                {
                    infinity = !infinity
                    focus = 0f
                },
                infinity,
                enabled,
            )
        listOf(
                Rq.CONTROL_AWB_MODE_AUTO to R.string.flight_camera_wb_auto,
                Rq.CONTROL_AWB_MODE_DAYLIGHT to R.string.flight_camera_wb_day,
                Rq.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to R.string.flight_camera_wb_cloud,
                Rq.CONTROL_AWB_MODE_INCANDESCENT to R.string.flight_camera_wb_warm,
            )
            .filter { it.first in wbModes }
            .forEach { (mode, label) ->
                PlanAction(stringResource(label), { wb = mode }, wb == mode, enabled)
            }
    }
    if (manual && isoRange != null && timeRange != null) {
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
    if (infinity && focusMax > 0f)
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
