package net.osmand.plus.plugins.flightmode

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.*
import net.osmand.plus.R

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FlightCloudScreen(
    state: FlightUiState,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onSave: () -> Unit,
    controller: FlightCloudController,
    selectedKey: String? = null,
    category: Int = 0,
    onRemoved: (String) -> Unit = {},
    initialSettings: Boolean = false,
) {
    var settings by remember { mutableStateOf(initialSettings) }
    var filter by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(selectedKey ?: state.journeyId?.let { "local:$it" }) }
    var downloadConfirmation by remember { mutableStateOf<FlightCloudEntry?>(null) }
    var cancelConfirmation by remember { mutableStateOf(false) }
    var removeConfirmation by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { controller.refresh() }
    FlightResumedEffect(Unit) {
        while (true) {
            delay(1000)
            now = android.os.SystemClock.elapsedRealtime()
        }
    }
    // Local summaries are updated by the parent. A local autosave never needs a network request.
    DisposableEffect(controller) {
        onDispose {
            controller.lock()
            controller.dismissUpload()
        }
    }
    val remaining = ((controller.lease?.expiresElapsed ?: 0L) - now).coerceAtLeast(0L)
    fun back() {
        when {
            controller.busy &&
                controller.operation in
                    listOf(
                        R.string.flight_cloud_sending,
                        R.string.flight_cloud_receiving,
                        R.string.flight_cloud_importing,
                        R.string.flight_cloud_packing,
                        R.string.flight_sync_verifying_removal,
                    ) -> cancelConfirmation = true
            settings ->
                if (initialSettings) onClose()
                else {
                    settings = false
                }
            controller.upload != null -> controller.dismissUpload()
            else -> onClose()
        }
    }
    Dialog(
        onDismissRequest = ::back,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Color(0xFF0A0F13))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = ::back) { Text(stringResource(R.string.shared_string_back)) }
                Text(
                    stringResource(
                        when {
                            settings -> R.string.flight_cloud_connection
                            controller.upload != null -> R.string.flight_cloud_choose_content
                            selectedKey != null -> R.string.flight_sync_manage
                            else -> R.string.flight_cloud_library
                        }
                    ),
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!settings && controller.upload == null)
                    TextButton(onClick = { settings = true }, enabled = !controller.busy) {
                        Text(stringResource(R.string.shared_string_settings), fontSize = 11.sp)
                    }
            }
            if (!settings && controller.connection != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            if (remaining > 0) R.string.flight_cloud_editing
                            else R.string.flight_cloud_readonly,
                            ((remaining + 59999) / 60000).toInt(),
                        ),
                        color = if (remaining > 0) Color(0xFFFFCC66) else Color.LightGray,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (remaining > 0) controller.lock() else controller.enableEditing()
                        },
                        enabled = !controller.busy,
                    ) {
                        Text(
                            stringResource(
                                if (remaining > 0) R.string.flight_cloud_lock
                                else R.string.flight_cloud_unlock
                            ),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (controller.busy) {
                controller.progress?.let { (done, total) ->
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(controller.operation),
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    controller.progress?.let { (done, total) ->
                        Text(
                            "${cloudSize(done)} / ${cloudSize(total)}",
                            color = Color.LightGray,
                            fontSize = 10.sp,
                        )
                    }
                    TextButton(onClick = { controller.cancel() }) {
                        Text(stringResource(R.string.shared_string_cancel), fontSize = 11.sp)
                    }
                }
            }
            controller.message?.let {
                Text(
                    it,
                    color = if (controller.messageIsError) Color(0xFFFFCC66) else Color(0xFF88DEBF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            when {
                settings ->
                    CloudConnectionForm(controller) {
                        if (initialSettings) onClose()
                        else {
                            settings = false
                        }
                    }
                controller.upload != null ->
                    CloudUploadSelection(controller, controller.upload!!, remaining > 0)
                else -> {
                    if (
                        state.journeyDirty &&
                            (selectedKey == null || selectedKey == "local:${state.journeyId}")
                    ) {
                        CloudHint(R.string.flight_cloud_save_first)
                        TextButton(onClick = onSave, enabled = !state.savingJourney) {
                            Text(stringResource(R.string.flight_local_retry_save))
                        }
                    }
                    if (controller.connection == null) {
                        TextButton(
                            onClick = { settings = true },
                            modifier = Modifier.padding(horizontal = 8.dp),
                            enabled = !controller.busy,
                        ) {
                            Text(stringResource(R.string.flight_cloud_connect))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedKey == null) {
                            listOf(
                                    R.string.flight_cloud_all,
                                    R.string.flight_cloud_phone,
                                    R.string.flight_cloud_server,
                                )
                                .forEachIndexed { i, label ->
                                    TextButton(onClick = { filter = i }) {
                                        Text(
                                            stringResource(label),
                                            color = if (filter == i) Color.White else Color.Gray,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { controller.refresh() }, enabled = !controller.busy) {
                            Text(stringResource(R.string.flight_cloud_refresh), fontSize = 11.sp)
                        }
                    }
                    val rows =
                        remember(
                            controller.local,
                            controller.remote,
                            controller.bindings,
                            filter,
                            category,
                            selectedKey,
                        ) {
                            flightCloudRows(
                                    controller.local,
                                    controller.remote,
                                    controller.bindings,
                                )
                                .filter {
                                    (selectedKey == null || it.key == selectedKey) &&
                                        (selectedKey != null ||
                                            category == 0 ||
                                            ((it.local?.sampleCount ?: it.remote!!.samples) == 0) ==
                                                (category == 1)) &&
                                        (selectedKey != null ||
                                            filter == 0 ||
                                            (filter == 1 && it.local != null) ||
                                            (filter == 2 && it.remote != null))
                                }
                                .sortedByDescending {
                                    it.local?.updatedAtMillis ?: it.remote!!.updatedAt
                                }
                        }
                    LazyColumn(Modifier.weight(1f)) {
                        if (rows.isEmpty() && !controller.busy)
                            item {
                                Text(
                                    stringResource(R.string.flight_cloud_empty),
                                    color = Color.Gray,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        items(rows, key = { it.key }) { row ->
                            val dirty = state.journeyDirty && state.journeyId == row.local?.id
                            val recording =
                                state.activeRecording.running &&
                                    state.activeRecording.journeyId == row.local?.id
                            val conflict =
                                row.remote != null && row.binding?.revision != row.remote.revision
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !controller.busy) {
                                        expanded = if (expanded == row.key) null else row.key
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    row.name,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(
                                            when {
                                                row.local != null && row.remote != null ->
                                                    R.string.flight_cloud_both
                                                row.local != null ->
                                                    R.string.flight_cloud_phone_only
                                                else -> R.string.flight_cloud_server_only
                                            }
                                        ),
                                        color = Color(0xFF7BE0A3),
                                        fontSize = 10.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        stringResource(
                                            R.string.flight_cloud_row_count,
                                            row.local?.photoCount ?: row.remote!!.photoIds.size,
                                        ),
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                    )
                                    row.remote?.let {
                                        Text(
                                            " · ${cloudSize(it.bytes)}",
                                            color = Color.Gray,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                                if (row.remote != null && !controller.serverVerified)
                                    CloudHint(R.string.flight_cloud_unverified)
                                row.local?.let {
                                    Text(
                                        stringResource(
                                            R.string.flight_sync_local_date,
                                            flightVersionDate(it.updatedAtMillis),
                                        ),
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                    )
                                }
                                row.remote?.let {
                                    Text(
                                        stringResource(
                                            R.string.flight_sync_remote_date,
                                            flightVersionDate(it.updatedAt),
                                            it.revision.take(8),
                                            it.photoIds.size,
                                        ),
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                    )
                                }
                                row.logicalCloudId?.let { cloudId ->
                                    Text(
                                        stringResource(
                                            R.string.flight_journey_logical_id,
                                            shortFlightJourneyId(cloudId),
                                        ),
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                    )
                                }
                                if (row.local != null && row.remote != null)
                                    Text(
                                        stringResource(
                                            row.versionState(dirty, controller.serverVerified)
                                                .label()
                                        ),
                                        color =
                                            if (
                                                row.versionState(
                                                    dirty,
                                                    controller.serverVerified,
                                                ) == FlightVersionState.SENT
                                            )
                                                Color(0xFF88DEBF)
                                            else Color(0xFFFFCC66),
                                        fontSize = 11.sp,
                                    )
                                // Opening the phone copy is always independent of remote transfers.
                                row.local?.let { local ->
                                    TextButton(
                                        onClick = {
                                            onClose()
                                            onOpen(local.id)
                                        },
                                        enabled =
                                            row.canOpenLocal(
                                                state,
                                                controller.removingLocalId == local.id,
                                            ),
                                    ) {
                                        Text(
                                            stringResource(
                                                if (state.journeyId == local.id)
                                                    R.string.flight_library_resume
                                                else R.string.flight_cloud_open
                                            ),
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                                if (expanded == row.key) {
                                    if (recording) CloudHint(R.string.flight_cloud_recording)
                                    if (conflict && row.local != null)
                                        CloudHint(R.string.flight_sync_conflict_actions)
                                    FlowRow(Modifier.fillMaxWidth()) {
                                        row.local?.let { local ->
                                            TextButton(
                                                onClick = { controller.prepareUpload(local.id) },
                                                enabled =
                                                    controller.connection != null &&
                                                        !controller.busy &&
                                                        !dirty &&
                                                        !recording &&
                                                        !conflict &&
                                                        controller.serverVerified,
                                            ) {
                                                Text(
                                                    stringResource(
                                                        if (row.remote == null)
                                                            R.string.flight_cloud_send
                                                        else R.string.flight_cloud_update
                                                    ),
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        }
                                        row.remote?.let { entry ->
                                            TextButton(
                                                onClick = {
                                                    if (row.local != null)
                                                        downloadConfirmation = entry
                                                    else
                                                        controller.download(entry) {
                                                            onClose()
                                                            onOpen(it)
                                                        }
                                                },
                                                enabled =
                                                    !controller.busy &&
                                                        !state.loadingTrip &&
                                                        controller.connection != null,
                                            ) {
                                                Text(
                                                    stringResource(
                                                        if (row.local == null)
                                                            R.string.flight_cloud_download
                                                        else R.string.flight_cloud_download_copy
                                                    ),
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        }
                                        if (row.local != null && row.remote != null)
                                            TextButton(
                                                onClick = { removeConfirmation = row.local.id },
                                                enabled =
                                                    !controller.busy &&
                                                        !dirty &&
                                                        !recording &&
                                                        !conflict &&
                                                        row.binding?.allLocalPhotosIncluded ==
                                                            true &&
                                                        row.binding.localUpdatedAt ==
                                                            row.local.updatedAtMillis,
                                            ) {
                                                Text(
                                                    stringResource(R.string.flight_sync_remove),
                                                    fontSize = 12.sp,
                                                )
                                            }
                                    }
                                }
                            }
                            HorizontalDivider(color = Color(0xFF2A3842))
                        }
                    }
                }
            }
        }
        removeConfirmation?.let { id ->
            AlertDialog(
                onDismissRequest = { removeConfirmation = null },
                title = { Text(stringResource(R.string.flight_sync_remove)) },
                text = { Text(stringResource(R.string.flight_sync_remove_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            removeConfirmation = null
                            controller.removeLocal(id, onRemoved)
                        }
                    ) {
                        Text(stringResource(R.string.flight_sync_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { removeConfirmation = null }) {
                        Text(stringResource(R.string.shared_string_cancel))
                    }
                },
            )
        }
        downloadConfirmation?.let { entry ->
            AlertDialog(
                onDismissRequest = { downloadConfirmation = null },
                title = { Text(stringResource(R.string.flight_cloud_download_copy)) },
                text = { Text(stringResource(R.string.flight_cloud_copy_notice)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            downloadConfirmation = null
                            controller.download(entry) {
                                onClose()
                                onOpen(it)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.flight_cloud_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { downloadConfirmation = null }) {
                        Text(stringResource(R.string.shared_string_cancel))
                    }
                },
            )
        }
        if (cancelConfirmation)
            AlertDialog(
                onDismissRequest = { cancelConfirmation = false },
                title = { Text(stringResource(R.string.flight_cloud_cancel_transfer)) },
                text = { Text(stringResource(R.string.flight_cloud_cancelled)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            controller.cancel()
                            cancelConfirmation = false
                            onClose()
                        }
                    ) {
                        Text(stringResource(R.string.shared_string_cancel))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { cancelConfirmation = false }) {
                        Text(stringResource(R.string.flight_cloud_continue))
                    }
                },
            )
    }
}

@Composable
private fun ColumnScope.CloudConnectionForm(controller: FlightCloudController, done: () -> Unit) {
    var url by remember { mutableStateOf(controller.connection?.url ?: "") }
    var token by remember { mutableStateOf("") }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
        item {
            OutlinedTextField(
                url,
                { url = it },
                label = { Text(stringResource(R.string.flight_cloud_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                enabled = !controller.busy,
            )
            OutlinedTextField(
                token,
                { token = it },
                label = {
                    Text(
                        stringResource(
                            if (controller.connection == null) R.string.flight_cloud_token
                            else R.string.flight_cloud_token_keep
                        )
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                enabled = !controller.busy,
            )
            CloudHint(R.string.flight_cloud_account_hint)
            Button(
                onClick = { controller.connect(url, token, done) },
                enabled = !controller.busy && url.isNotBlank(),
            ) {
                Text(stringResource(R.string.flight_cloud_test_connect))
            }
            if (controller.connection != null)
                TextButton(
                    onClick = {
                        controller.disconnect()
                        done()
                    },
                    enabled = !controller.busy,
                ) {
                    Text(stringResource(R.string.flight_cloud_disconnect))
                }
        }
    }
}

@Composable
private fun ColumnScope.CloudUploadSelection(
    controller: FlightCloudController,
    upload: FlightCloudUpload,
    editing: Boolean,
) {
    var selected by
        remember(upload) {
            mutableStateOf(
                upload.remote?.photoIds?.intersect(upload.journey.photos.map { it.id }.toSet())
                    ?: emptySet()
            )
        }
    var confirm by remember { mutableStateOf(false) }
    val bytes = selected.sumOf { upload.photoBytes[it]?.coerceAtLeast(0) ?: 0L }
    val missing = selected.any { (upload.photoBytes[it] ?: -1L) < 0 }
    Text(
        upload.journey.name,
        color = Color.White,
        fontSize = 15.sp,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Text(
        stringResource(
            if (upload.journey.trip.samples.isEmpty()) R.string.flight_sync_plan_payload
            else R.string.flight_cloud_payload_hint
        ),
        color = Color.LightGray,
        fontSize = 11.sp,
        modifier = Modifier.padding(12.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                selected =
                    upload.journey.photos
                        .filter { (upload.photoBytes[it.id] ?: -1L) >= 0 }
                        .map { it.id }
                        .toSet()
            },
            enabled = !controller.busy,
        ) {
            Text(stringResource(R.string.flight_cloud_select_all), fontSize = 11.sp)
        }
        TextButton(onClick = { selected = emptySet() }, enabled = !controller.busy) {
            Text(stringResource(R.string.flight_cloud_select_none), fontSize = 11.sp)
        }
        Text(
            stringResource(R.string.flight_cloud_selected, selected.size, cloudSize(bytes)),
            color = Color.LightGray,
            fontSize = 11.sp,
        )
    }
    LazyColumn(Modifier.weight(1f)) {
        items(upload.journey.photos, key = { it.id }) { photo ->
            val size = upload.photoBytes[photo.id] ?: -1L
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = !controller.busy && size >= 0) {
                        selected =
                            if (photo.id in selected) selected - photo.id else selected + photo.id
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(photo.id in selected, null, enabled = !controller.busy && size >= 0)
                CloudPhotoThumbnail(photo)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        photo.fileName,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (size < 0) stringResource(R.string.flight_cloud_photo_missing)
                        else cloudSize(size),
                        color = Color.Gray,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
    if (!editing) CloudHint(R.string.flight_cloud_unlock_to_send)
    Button(
        onClick = { confirm = true },
        enabled = editing && !controller.busy && !missing && bytes <= FLIGHT_CLOUD_MAX_BYTES,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Text(
            stringResource(
                if (upload.remote == null) R.string.flight_cloud_send
                else R.string.flight_cloud_update
            )
        )
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.flight_cloud_confirm_send)) },
            text = {
                Text(
                    stringResource(
                        R.string.flight_sync_confirm_append,
                        selected.size,
                        cloudSize(bytes),
                        (upload.remote?.photoIds.orEmpty() - selected).size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        controller.publish(selected)
                    }
                ) {
                    Text(stringResource(R.string.flight_cloud_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.shared_string_cancel))
                }
            },
        )
}

@Composable
private fun CloudPhotoThumbnail(photo: FlightPhotoAttachment) {
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, photo.localPath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { decodePhotoPreview(File(photo.localPath), 128) }.getOrNull()
                }
        }
    Box(Modifier.size(48.dp).background(Color(0xFF18232B))) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CloudHint(resource: Int) {
    Text(
        stringResource(resource),
        color = Color(0xFFFFCC66),
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp),
    )
}

@Composable
private fun cloudSize(bytes: Long): String =
    stringResource(R.string.flight_cloud_mebibytes, bytes / 1048576.0)
