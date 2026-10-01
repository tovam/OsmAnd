package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.osmand.plus.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Import confirmation and batch correction share the same numeric-offset editor. */
@Composable
internal fun FlightPhotoTimeDialog(
    photos: List<FlightPhotoAttachment>? = null,
    onDismiss: () -> Unit,
    onApply: (Int, Int, Set<String>) -> Unit,
) {
    val initial = remember { FlightPhotoTime.formatOffset(FlightPhotoTime.currentOffsetMinutes()) }
    var previous by remember { mutableStateOf(initial) }
    var target by remember { mutableStateOf(initial) }
    var selected by remember(photos) { mutableStateOf(photos.orEmpty().filter {
        it.timestampSource != FlightPhotoTimestampSource.LIVE_CAPTURE &&
            (it.timestampMillis != null || FlightPhotoTimestampParser.parseLocal(it.fileName) != null)
    }.map { it.id }.toSet()) }
    val oldOffset = FlightPhotoTime.parseOffset(previous)
    val newOffset = FlightPhotoTime.parseOffset(target)
    val previewFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (photos == null) R.string.flight_photo_time_confirm else R.string.flight_photo_time_batch)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                Text(stringResource(if (photos == null) R.string.flight_photo_time_import_help else R.string.flight_photo_time_batch_help))
                if (photos != null) OutlinedTextField(previous, { previous = it }, singleLine = true,
                    label = { Text(stringResource(R.string.flight_photo_time_previous_offset)) },
                    isError = oldOffset == null)
                OutlinedTextField(target, { target = it }, singleLine = true,
                    label = { Text(stringResource(R.string.flight_photo_time_capture_offset)) },
                    supportingText = { Text(stringResource(R.string.flight_photo_time_offset_help)) },
                    isError = newOffset == null)
                }
                items(photos.orEmpty(), key = { it.id }) { photo ->
                    val eligible = photo.timestampSource != FlightPhotoTimestampSource.LIVE_CAPTURE &&
                        (photo.timestampMillis != null || FlightPhotoTimestampParser.parseLocal(photo.fileName) != null)
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(photo.id in selected, { checked -> selected = if (checked) selected + photo.id else selected - photo.id }, enabled = eligible)
                        Column(Modifier.weight(1f).padding(top = 8.dp)) {
                            Text(photo.fileName, fontSize = 12.sp, maxLines = 1)
                            if (eligible && oldOffset != null && newOffset != null) {
                                val result = runCatching { FlightPhotoTime.correctedTimestamp(photo, oldOffset, newOffset) }.getOrNull()
                                Text("${photo.timestampMillis?.let { previewFormat.format(Date(it)) } ?: "—"} → ${result?.let { previewFormat.format(Date(it)) } ?: "—"}", fontSize = 11.sp)
                            } else Text(stringResource(R.string.flight_photo_time_unavailable), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = newOffset != null && (photos == null || oldOffset != null && selected.isNotEmpty()),
            onClick = { onApply(oldOffset ?: newOffset!!, newOffset!!, selected) }) {
            Text(stringResource(R.string.flight_photo_time_apply))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shared_string_cancel)) } },
    )
}
