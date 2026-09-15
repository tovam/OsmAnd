package net.osmand.plus.plugins.flightmode

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.*
import net.osmand.plus.R

internal data class FlightCloudUpload(
    val journey: FlightJourney,
    val remote: FlightCloudEntry?,
    val binding: FlightCloudBinding?,
    val photoBytes: Map<String, Long>,
)

/** Manual library operations; no polling uploads, silent merges, or writes to a live recording. */
internal class FlightCloudController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val store = FlightJourneyStore(context)
    private val settings = FlightCloudSettings(context)
    private var job: Job? = null
    private var client: FlightCloudClient? = null
    var connection by mutableStateOf<FlightCloudConnection?>(null)
        private set

    var local by mutableStateOf<List<FlightJourneySummary>>(emptyList())
        private set

    var remote by mutableStateOf<List<FlightCloudEntry>>(emptyList())
        private set

    var bindings by mutableStateOf<List<FlightCloudBinding>>(emptyList())
        private set

    var lease by mutableStateOf<FlightCloudLease?>(null)
        private set

    var upload by mutableStateOf<FlightCloudUpload?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var operation by mutableStateOf(R.string.flight_cloud_loading)
        private set

    var progress by mutableStateOf<Pair<Long, Long>?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var serverVerified by mutableStateOf(false)
        private set

    var serverSupportsAppend by mutableStateOf(false)
        private set

    fun acceptLocalSummaries(summaries: List<FlightJourneySummary>) {
        local = summaries
    }

    fun initialize() =
        task(R.string.flight_cloud_loading) {
            local = io { store.list() }
            connection = io { settings.load() }
            connection?.let { connectLoaded(it) }
        }

    private suspend fun connectLoaded(config: FlightCloudConnection) {
        client = FlightCloudClient(config)
        bindings = io { settings.bindings(config.scope) }
        serverVerified = false
        remote = io { client!!.list() }
        serverSupportsAppend = client!!.protocolVersion >= 2
        serverVerified = true
    }

    fun connect(url: String, token: String, done: () -> Unit) =
        task(R.string.flight_cloud_connecting) {
            val old = connection
            val actualToken =
                token.ifBlank { old?.takeIf { it.url == url.trim().trimEnd('/') }?.token ?: "" }
            val config =
                try {
                    FlightCloudConnection.validated(url, actualToken)
                } catch (e: Exception) {
                    throw FlightCloudFailure("invalid_connection")
                }
            lease = null
            serverVerified = false
            val candidate = FlightCloudClient(config)
            client = candidate
            val listing = io { candidate.list() }
            serverSupportsAppend = candidate.protocolVersion >= 2
            io { settings.save(config) }
            connection = config
            remote = listing
            bindings = io { settings.bindings(config.scope) }
            serverVerified = true
            done()
        }

    fun refresh() =
        task(R.string.flight_cloud_loading) {
            local = io { store.list() }
            connection?.let { connectLoaded(it) }
        }

    fun disconnect() =
        task(R.string.flight_cloud_loading) {
            io { settings.disconnect() }
            connection = null
            client = null
            lease = null
            remote = emptyList()
            bindings = emptyList()
            serverVerified = false
        }

    fun enableEditing() =
        task(R.string.flight_cloud_unlocking) {
            val config = requireNotNull(connection)
            client = FlightCloudClient(config)
            lease = io { client!!.editSession() }
        }

    fun lock() {
        lease = null
    }

    fun dismissUpload() {
        if (!busy) upload = null
    }

    fun prepareUpload(id: String) =
        task(R.string.flight_cloud_preparing) {
            val binding = bindings.firstOrNull { it.localId == id }
            val existing = remote.firstOrNull { it.id == (binding?.remoteId ?: id) }
            if (!serverVerified) throw FlightCloudFailure("refresh_required")
            if (!serverSupportsAppend) throw FlightCloudFailure("server_update_required")
            if (existing != null && binding?.revision != existing.revision)
                throw FlightCloudFailure("revision_conflict")
            val journey = io { store.load(id) }
            val sizes = io {
                journey.photos.associate {
                    it.id to
                        File(it.localPath).takeIf(File::isFile)?.length().let { size ->
                            size ?: -1L
                        }
                }
            }
            upload = FlightCloudUpload(journey, existing, binding, sizes)
        }

    fun publish(photoIds: Set<String>) =
        task(R.string.flight_cloud_packing) {
            val config = requireNotNull(connection)
            val selected = requireNotNull(upload)
            val permission =
                lease?.takeIf { it.expiresElapsed > android.os.SystemClock.elapsedRealtime() }
                    ?: throw FlightCloudFailure("edit_session_expired")
            val file = temporaryArchive()
            try {
                io { store.writeCloudArchive(selected.journey, photoIds, file) }
                operation = R.string.flight_cloud_sending
                client = FlightCloudClient(config)
                val result = io {
                    client!!.upload(
                        selected.binding?.remoteId ?: selected.journey.id,
                        file,
                        selected.binding?.revision,
                        permission,
                        ::publishProgress,
                    )
                }
                val binding =
                    FlightCloudBinding(
                        selected.journey.id,
                        result.id,
                        result.revision,
                        selected.journey.updatedAtMillis,
                        photoIds.containsAll(selected.journey.photos.map { it.id }),
                    )
                io { settings.bind(config.scope, binding) }
                bindings = io { settings.bindings(config.scope) }
                remote =
                    (remote.filterNot { it.id == result.id } + result).sortedByDescending {
                        it.updatedAt
                    }
                upload = null
                message = context.getString(R.string.flight_cloud_sent)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }

    fun download(entry: FlightCloudEntry, open: (String) -> Unit) =
        task(R.string.flight_cloud_receiving) {
            val config = requireNotNull(connection)
            val file = temporaryArchive()
            try {
                client = FlightCloudClient(config)
                io { client!!.download(entry, file, ::publishProgress) }
                operation = R.string.flight_cloud_importing
                progress = null
                val imported = io { store.importCloudArchive(file) }
                io {
                    settings.bind(
                        config.scope,
                        FlightCloudBinding(
                            imported.id,
                            entry.id,
                            entry.revision,
                            imported.updatedAtMillis,
                            true,
                        ),
                    )
                }
                bindings = io { settings.bindings(config.scope) }
                local = io { store.list() }
                open(imported.id)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }

    fun removeLocal(id: String, removed: (String) -> Unit) =
        task(R.string.flight_sync_verifying_removal) {
            val config = requireNotNull(connection)
            val binding =
                bindings.firstOrNull { it.localId == id }
                    ?: throw FlightCloudFailure("removal_unverified")
            if (!binding.allLocalPhotosIncluded) throw FlightCloudFailure("removal_unverified")
            client = FlightCloudClient(config)
            val listing = io { client!!.list() }
            val entry =
                listing.firstOrNull { it.id == binding.remoteId }
                    ?: throw FlightCloudFailure("removal_unverified")
            if (entry.revision != binding.revision) throw FlightCloudFailure("revision_conflict")
            val file = temporaryArchive()
            try {
                // Verify the downloadable bytes, not only the server listing, before local removal.
                io { client!!.download(entry, file, ::publishProgress) }
                currentCoroutineContext().ensureActive()
                // Once removal starts, reconcile the open editor even if the user cancels the task.
                withContext(NonCancellable) {
                    io { store.removeVerifiedCloudCopy(id, binding.localUpdatedAt, entry.photoIds) }
                    remote = listing
                    local = local.filterNot { it.id == id }
                    removed(id)
                    message = context.getString(R.string.flight_sync_removed)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }

    fun cancel() {
        job?.cancel()
        client?.cancel()
    }

    fun close() {
        cancel()
        lease = null
    }

    private suspend fun temporaryArchive(): File = io {
        val directory = File(context.cacheDir, "flight-cloud-transfers").apply { mkdirs() }
        File.createTempFile("transfer-", ".flightlog", directory)
    }

    private fun publishProgress(done: Long, total: Long) {
        scope.launch { progress = done to total }
    }

    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }

    private fun task(label: Int, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        operation = label
        progress = null
        message = null
        job =
            scope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    message = context.getString(R.string.flight_cloud_cancelled)
                    throw e
                } catch (e: Exception) {
                    if ((e as? FlightCloudFailure)?.code == "edit_session_expired") lease = null
                    val text =
                        when ((e as? FlightCloudFailure)?.code ?: e.message) {
                            "invalid_connection" -> R.string.flight_cloud_bad_connection
                            "invalid_token" -> R.string.flight_cloud_bad_token
                            "edit_session_expired" -> R.string.flight_cloud_expired
                            "revision_conflict" -> R.string.flight_cloud_conflict
                            "archive_too_large" -> R.string.flight_cloud_too_large
                            "cloud_photo_missing" -> R.string.flight_cloud_photo_missing
                            "refresh_required" -> R.string.flight_cloud_refresh_required
                            "server_update_required" -> R.string.flight_sync_upgrade_server
                            "removal_unverified" -> R.string.flight_sync_removal_unverified
                            "checksum_mismatch",
                            "invalid_archive",
                            "incomplete_transfer" -> R.string.flight_cloud_invalid_archive
                            else -> R.string.flight_cloud_network_error
                        }
                    message = context.getString(text)
                } finally {
                    busy = false
                    progress = null
                }
            }
    }
}
