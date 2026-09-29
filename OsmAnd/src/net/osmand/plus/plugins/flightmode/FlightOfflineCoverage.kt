package net.osmand.plus.plugins.flightmode

import java.util.concurrent.CopyOnWriteArrayList

internal data class FlightOfflineTileKey(val tile: TerrainTileId, val satellite: Boolean)

/** Preserve coarse-first order within each lane; do not postpone gaps behind cached decodes. */
internal fun flightMissingTilesFirst(
    requests: List<FlightOfflineRequest>,
    present: (FlightOfflineTileKey) -> Boolean,
): List<FlightOfflineRequest> {
    val (missing, stored) =
        requests.partition { !present(FlightOfflineTileKey(it.tile, it.satellite)) }
    return missing + stored
}

/** A level is a source-file resolution, not the camera zoom or a GPU texture. */
data class FlightOfflineLevelCoverage(
    val satellite: Boolean,
    val zoom: Int,
    val required: Int,
    val inspected: Int,
    val present: Int,
    val storedBytes: Long,
    val estimatedRemainingBytes: Long,
) {
    val unknown: Int
        get() = required - inspected

    val missing: Int
        get() = inspected - present
}

data class FlightStoredTileLevel(
    val satellite: Boolean,
    val zoom: Int,
    val files: Int,
    val bytes: Long,
)

/** Small, immutable UI snapshot. Counts refer to source files, never GPU meshes or composites. */
data class FlightOfflineCoverage(
    val terrainTotal: Int,
    val satelliteTotal: Int,
    val inspected: Int = 0,
    val terrainStored: Int = 0,
    val satelliteStored: Int = 0,
    val storedBytes: Long = 0,
    val estimatedRemainingBytes: Long = 0,
    val satelliteStoredBytes: Long = 0,
    val terrainStoredBytes: Long = 0,
    val satelliteRemainingBytes: Long = 0,
    val terrainRemainingBytes: Long = 0,
    val levels: List<FlightOfflineLevelCoverage> = emptyList(),
    val sharedLevels: List<FlightStoredTileLevel> = emptyList(),
    val sharedInventoryComplete: Boolean = false,
) {
    val total: Int
        get() = terrainTotal + satelliteTotal

    val stored: Int
        get() = terrainStored + satelliteStored

    val inventoried: Boolean
        get() = inspected == total

    val missing: Int
        get() = total - stored

    val confirmedMissing: Int
        get() = inspected - stored

    val unknown: Int
        get() = total - inspected

    val canDownloadMissing: Boolean
        get() = inventoried && missing > 0

    val fraction: Float
        get() = if (total == 0) 0f else stored.toFloat() / total

    fun verifiedBy(status: FlightTerrainStatus): Boolean =
        inventoried &&
            missing == 0 &&
            total > 0 &&
            status.offlineFilesVerified &&
            status.phase == FlightTerrainPhase.READY &&
            status.requestedTiles == terrainTotal &&
            status.requestedSatelliteTiles == satelliteTotal &&
            status.availableTiles == terrainStored &&
            status.satelliteTiles == satelliteStored &&
            status.failedTiles == 0 &&
            status.satelliteFailedTiles == 0
}

/** Only source-file changes are broadcast. No polling of the whole cache on GPS updates. */
internal object FlightOfflineTileChanges {
    private val listeners = CopyOnWriteArrayList<(FlightOfflineTileKey, Long) -> Unit>()

    fun observe(listener: (FlightOfflineTileKey, Long) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun publish(tile: TerrainTileId, satellite: Boolean, readableBytes: Long) {
        if (listeners.isEmpty()) return
        val key = FlightOfflineTileKey(tile, satellite)
        listeners.forEach { it(key, readableBytes.coerceAtLeast(0)) }
    }
}

/** Single initial inventory, then O(1) updates per completed/removed file. Thread safe. */
internal class FlightOfflineInventory(requests: List<FlightOfflineRequest>) {
    private class Entry(
        var revision: Long = 0,
        var inspected: Boolean = false,
        var bytes: Long = 0,
    )

    private val entries =
        requests.associate { FlightOfflineTileKey(it.tile, it.satellite) to Entry() }
    private val satelliteTotal = entries.keys.count { it.satellite }
    private val terrainTotal = entries.size - satelliteTotal
    private var inspected = 0
    private var satelliteStored = 0
    private var terrainStored = 0
    private var satelliteBytes = 0L
    private var terrainBytes = 0L

    private class SizeGroup(
        val total: Int,
        var inspected: Int = 0,
        var stored: Int = 0,
        var bytes: Long = 0,
    )

    // A small overview/ocean tile must not set the price of every detailed tile.
    private val sizes =
        entries.keys
            .groupingBy { it.satellite to it.tile.zoom }
            .eachCount()
            .mapValues { SizeGroup(it.value) }

    @Synchronized
    fun revision(key: FlightOfflineTileKey): Long? =
        entries[key]?.takeUnless { it.inspected }?.revision

    /** A write racing the initial disk read wins, even if the old read finishes later. */
    @Synchronized
    fun inspected(key: FlightOfflineTileKey, revision: Long, bytes: Long) {
        val entry = entries[key] ?: return
        if (entry.revision == revision) update(key, entry, bytes)
    }

    @Synchronized
    fun changed(key: FlightOfflineTileKey, bytes: Long) {
        val entry = entries[key] ?: return
        entry.revision++
        update(key, entry, bytes)
    }

    private fun update(key: FlightOfflineTileKey, entry: Entry, bytes: Long) {
        val next = bytes.coerceAtLeast(0)
        val delta = (if (next > 0) 1 else 0) - (if (entry.bytes > 0) 1 else 0)
        sizes.getValue(key.satellite to key.tile.zoom).let {
            if (!entry.inspected) it.inspected++
            it.stored += delta
            it.bytes += next - entry.bytes
        }
        if (key.satellite) {
            satelliteStored += delta
            satelliteBytes += next - entry.bytes
        } else {
            terrainStored += delta
            terrainBytes += next - entry.bytes
        }
        if (!entry.inspected) {
            inspected++
            entry.inspected = true
        }
        entry.bytes = next
    }

    @Synchronized
    fun snapshot(
        sharedLevels: List<FlightStoredTileLevel> = emptyList(),
        sharedInventoryComplete: Boolean = false,
    ): FlightOfflineCoverage {
        // Use actual compression sizes once enough examples exist; the remainder is still an
        // estimate.
        val levels =
            sizes
                .map { (key, group) ->
                    val shared =
                        sharedLevels.firstOrNull {
                            it.satellite == key.first && it.zoom == key.second
                        }
                    val average =
                        when {
                            group.stored >= 8 -> group.bytes / group.stored
                            shared != null && shared.files >= 8 -> shared.bytes / shared.files
                            else -> FlightOfflineSizeEstimate.bytesPerTile(key.first)
                        }
                    FlightOfflineLevelCoverage(
                        key.first,
                        key.second,
                        group.total,
                        group.inspected,
                        group.stored,
                        group.bytes,
                        (group.total - group.stored) * average,
                    )
                }
                .sortedWith(
                    compareByDescending<FlightOfflineLevelCoverage> { it.satellite }
                        .thenBy { it.zoom }
                )
        val remainingSatellite = levels.filter { it.satellite }.sumOf { it.estimatedRemainingBytes }
        val remainingTerrain = levels.filter { !it.satellite }.sumOf { it.estimatedRemainingBytes }
        return FlightOfflineCoverage(
            terrainTotal,
            satelliteTotal,
            inspected,
            terrainStored,
            satelliteStored,
            satelliteBytes + terrainBytes,
            remainingSatellite + remainingTerrain,
            satelliteBytes,
            terrainBytes,
            remainingSatellite,
            remainingTerrain,
            levels,
            sharedLevels,
            sharedInventoryComplete,
        )
    }
}

/** Decimal bytes for on-disk source images, excluding render caches and duplicate requests. */
internal object FlightOfflineSizeEstimate {
    fun bytesPerTile(satellite: Boolean): Long = if (satellite) 45_000L else 110_000L

    fun bytes(satelliteCount: Int, terrainCount: Int): Long =
        satelliteCount.toLong() * bytesPerTile(true) + terrainCount.toLong() * bytesPerTile(false)
}
