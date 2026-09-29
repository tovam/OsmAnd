package net.osmand.plus.plugins.flightmode

/** Process-wide metadata for the one shared source-file store. No images are held here. */
internal class FlightTileCatalog {
    data class FileRecord(val bytes: Long, val revision: Long)

    data class Snapshot(
        val revision: Long,
        val complete: Boolean,
        val files: Map<FlightOfflineTileKey, FileRecord>,
    )

    data class Summary(
        val revision: Long,
        val complete: Boolean,
        val levels: List<FlightStoredTileLevel>,
    )

    private val files = HashMap<FlightOfflineTileKey, FileRecord>()
    private val revisions = HashMap<FlightOfflineTileKey, Long>()
    private val groups = HashMap<Pair<Boolean, Int>, FlightStoredTileLevel>()
    private val listeners = linkedSetOf<(FlightOfflineTileKey?, Long) -> Unit>()
    private var revision = 0L
    private var complete = false

    @Synchronized fun revision(key: FlightOfflineTileKey): Long = revisions[key] ?: 0L

    @Synchronized fun get(key: FlightOfflineTileKey): FileRecord? = files[key]

    /** A download/removal that races the first disk scan always wins. */
    @Synchronized
    fun inspected(key: FlightOfflineTileKey, expected: Long, bytes: Long) {
        if (revision(key) == expected) changed(key, bytes)
    }

    @Synchronized
    fun changed(key: FlightOfflineTileKey, bytes: Long) {
        val old = files[key]
        val next = bytes.coerceAtLeast(0)
        // Verification of an unchanged file is not a new texture. Managed replacements
        // publish removal before the new source, including equal-sized replacements.
        if (next > 0 && old?.bytes == next) return
        revision++
        revisions[key] = revision
        if (next > 0) files[key] = FileRecord(next, revision) else files.remove(key)
        val groupKey = key.satellite to key.tile.zoom
        val group = groups[groupKey] ?: FlightStoredTileLevel(key.satellite, key.tile.zoom, 0, 0)
        groups[groupKey] =
            group.copy(
                files = group.files + (if (next > 0) 1 else 0) - (if (old != null) 1 else 0),
                bytes = group.bytes + next - (old?.bytes ?: 0L),
            )
        // Callbacks only update counters or enqueue work, never perform IO. Serial delivery
        // prevents a late scan notification from overtaking a newer download notification.
        listeners.forEach { it(key, next) }
    }

    @Synchronized
    fun finishInventory() {
        if (!complete) {
            complete = true
            revision++
            listeners.forEach { it(null, 0L) }
        }
    }

    @Synchronized
    fun observe(listener: (FlightOfflineTileKey?, Long) -> Unit): () -> Unit {
        listeners.add(listener)
        return {
            synchronized(this) { listeners.remove(listener) }
            Unit
        }
    }

    @Synchronized
    fun summary() =
        Summary(
            revision,
            complete,
            groups.values
                .filter { it.files > 0 }
                .sortedWith(
                    compareByDescending<FlightStoredTileLevel> { it.satellite }.thenBy { it.zoom }
                ),
        )

    /** Copy only on a background worker, and only when revision changes. */
    @Synchronized fun snapshot() = Snapshot(revision, complete, files.toMap())
}
