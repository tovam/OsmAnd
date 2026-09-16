package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import net.osmand.plus.R

/** Geometry belongs to a fixed photo, never to the current replay/viewing camera. */
@Composable
internal fun rememberPhotoDepthGuidance(
    photo: FlightPhotoAttachment,
    scene: FlightTerrainScene?,
    retry: Int,
    onSet: (FlightPhotoImageAdjustments) -> Unit,
): Boolean {
    val projection = photo.dehazeProjection()
    val signature = remember(projection) { projection?.signature() }
    val latestPhoto by rememberUpdatedState(photo)
    val latestSet by rememberUpdatedState(onSet)
    var busy by remember(photo.id) { mutableStateOf(false) }
    val enabled = photo.imageAdjustments.depthEnabled && photo.imageAdjustments.dehaze > 0f
    FlightResumedEffect(photo.id, signature, scene?.geometryGeneration, enabled, retry) {
        busy = false
        if (!enabled || projection == null || scene == null) return@FlightResumedEffect
        delay(700)
        busy = true
        try {
            val profile =
                runInterruptible(Dispatchers.Default) {
                    FlightPhotoDepth.calculate(projection, scene)
                }
            // A calibration or slider may have changed while the worker was running.
            val current = latestPhoto
            if (
                current.id == photo.id &&
                    current.dehazeProjection()?.signature() == signature &&
                    current.imageAdjustments.depthEnabled &&
                    current.imageAdjustments.dehaze > 0f
            ) {
                val previous = current.effectiveImageAdjustments().depthProfile
                val complete = profile.includingPrevious(previous)
                if (complete.coveragePercent > 0 && previous != complete) {
                    latestSet(current.imageAdjustments.copy(depthProfile = complete))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Image-only dehazing remains available when terrain guidance cannot be computed.
        } finally {
            busy = false
        }
    }
    return busy
}

@Composable
internal fun FlightPhotoTreatmentControls(
    photo: FlightPhotoAttachment,
    preview: FlightDehazePreview,
    depthBusy: Boolean,
    showOriginal: Boolean,
    onShowOriginal: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenWindow: () -> Unit,
    onSet: (FlightPhotoImageAdjustments) -> Unit,
) {
    val settings = photo.imageAdjustments.clamped()
    val depth = photo.effectiveImageAdjustments().depthProfile
    var information by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Color(0xFF172128)).padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            TreatmentAction(stringResource(R.string.flight_dehaze_title), settings.dehaze > 0f) {
                onShowOriginal(false)
                onSet(settings.copy(dehaze = if (settings.dehaze > 0f) 0f else .55f))
            }
            Slider(
                settings.dehaze,
                {
                    onShowOriginal(false)
                    onSet(settings.copy(dehaze = it))
                },
                modifier = Modifier.weight(1f).height(28.dp),
            )
            Text("${(settings.dehaze * 100).toInt()} %", fontSize = 10.sp, color = Color.White)
            TreatmentAction(stringResource(R.string.flight_dehaze_original), showOriginal) {
                onShowOriginal(!showOriginal)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TreatmentAction(stringResource(R.string.flight_dehaze_depth), settings.depthEnabled) {
                onSet(settings.copy(depthEnabled = !settings.depthEnabled))
            }
            TreatmentAction(stringResource(R.string.flight_dehaze_recalculate)) { onRetry() }
            if (depth == null && settings.depthEnabled)
                TreatmentAction(stringResource(R.string.flight_dehaze_load_window)) {
                    onOpenWindow()
                }
            TreatmentAction(stringResource(R.string.flight_dehaze_info)) { information = true }
        }
        Text(
            when {
                preview.failed -> stringResource(R.string.flight_dehaze_failed)
                preview.busy || depthBusy -> stringResource(R.string.flight_dehaze_working)
                settings.dehaze == 0f -> stringResource(R.string.flight_dehaze_disabled)
                depth != null ->
                    stringResource(
                        R.string.flight_dehaze_coverage,
                        depth.coveragePercent,
                        depth.minimumDistanceKm,
                        depth.maximumDistanceKm,
                    )
                !settings.depthEnabled -> stringResource(R.string.flight_dehaze_image_only)
                photo.dehazeProjection() == null ->
                    stringResource(R.string.flight_dehaze_no_calibration)
                else -> stringResource(R.string.flight_dehaze_no_terrain)
            },
            fontSize = 10.sp,
            color = Color.LightGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
    if (information)
        AlertDialog(
            onDismissRequest = { information = false },
            title = { Text(stringResource(R.string.flight_dehaze_title)) },
            text = { Text(stringResource(R.string.flight_dehaze_explanation)) },
            confirmButton = {
                TextButton(onClick = { information = false }) {
                    Text(stringResource(R.string.shared_string_close))
                }
            },
        )
}

@Composable
private fun TreatmentAction(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 28.dp)
            .background(if (selected) Color(0xFF234656) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 10.sp, color = if (selected) Color(0xFF66C7FF) else Color.LightGray)
    }
}
