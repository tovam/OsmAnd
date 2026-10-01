package net.osmand.plus.plugins.flightmode

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.osmand.plus.R

internal data class FlightStorageCleanupPlan(val librarySignature: String, val catalogRevision: Long,
    val candidates: List<FlightStorageCandidate>, val summary: FlightStorageCleanupSummary)

internal class FlightStorageManager(private val context: Context, private val journeys: FlightJourneyStore) {
    private val store = FlightSharedTileStore.get(context)

    suspend fun inspect(): FlightStorageCleanupPlan {
        store.ensureIndexed()
        val library = withContext(Dispatchers.IO) { journeys.storageProtectionSnapshot() }
        val protection = withContext(Dispatchers.Default) {
            val references = mutableSetOf<FlightOfflineTileKey>()
            val regions = mutableListOf<FlightStorageRegion>()
            for (journey in library.second) {
                for (assets in listOf(journey.offlineAssets, journey.offlineRequest)) {
                    references += assets.terrainTiles.map { FlightOfflineTileKey(it, false) }
                    references += assets.standardSatelliteTiles.map { FlightOfflineTileKey(it, true) }
                }
                if (journey.trip.samples.size >= 2) {
                    references += FlightOfflinePreparation.corridorQuote(journey.plan.copy(preparation = null), journey.trip).requests.map {
                        FlightOfflineTileKey(it.tile, it.satellite)
                    }
                }
                if (FlightOfflinePreparation.canSimulate(journey.plan)) {
                    references += FlightOfflinePreparation.corridorQuote(journey.plan, null).requests.map {
                        FlightOfflineTileKey(it.tile, it.satellite)
                    }
                }
                journey.photos.forEach { photo ->
                    val pose = photo.dehazeProjection()?.pose
                    val sample = FlightSampleInterpolator.sampleAt(journey.trip, photo.matchedSamplePosition)
                    if (pose != null) regions += FlightStorageRegion(pose.eyeLatitude, pose.eyeLongitude, 500.0)
                    else if (sample != null) regions += FlightStorageRegion(sample.latitude, sample.longitude, 500.0)
                }
            }
            FlightStorageProtection(references, regions)
        }
        return runInterruptible(Dispatchers.IO) {
            FlightTileStorageGate.read {
                val snapshot = store.catalog.snapshot()
                var protectedBytes = 0L
                val candidates = snapshot.files.mapNotNull { (key, record) ->
                    if (protection.protects(key)) { protectedBytes += record.bytes; null }
                    else {
                        val file = store.file(key)
                        val candidate = FlightStorageCandidate(key, record.bytes, file.lastModified())
                        candidate.takeIf { it.matches(context.filesDir, file) }
                    }
                }
                FlightStorageCleanupPlan(library.first, snapshot.revision, candidates,
                    FlightStorageCleanupSummary(candidates.size, candidates.sumOf { it.bytes }, protectedBytes))
            }
        }
    }

    suspend fun clean(plan: FlightStorageCleanupPlan): Long = runInterruptible(Dispatchers.IO) {
        // Always take the tile gate before the journal lock, matching archive import order.
        FlightTileStorageGate.write {
            journeys.withUnchangedStorageLibrary(plan.librarySignature) {
                if (FlightRecordingService.state.value.running || store.catalog.snapshot().revision != plan.catalogRevision)
                    throw IOException(context.getString(R.string.flight_storage_scan_changed))
                var reclaimed = 0L
                for (candidate in plan.candidates) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (FlightRecordingService.state.value.running) throw IOException(context.getString(R.string.flight_storage_active))
                    val file = store.file(candidate.key)
                    if (!candidate.matches(context.filesDir, file)) continue
                    if (file.delete()) {
                        reclaimed += candidate.bytes
                        FlightOfflineTileChanges.publish(candidate.key.tile, candidate.key.satellite, 0)
                    }
                }
                reclaimed
            }
        }
    }
}
