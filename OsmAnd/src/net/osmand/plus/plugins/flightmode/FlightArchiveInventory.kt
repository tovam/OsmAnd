package net.osmand.plus.plugins.flightmode

import java.io.IOException

/** Portable ZIP entries never resolve to media belonging to another local journal. */
internal class FlightArchiveInventory {
    private val entries = hashSetOf<String>()
    private val photos = hashSetOf<String>()

    fun accept(name: String, directory: Boolean, knownTile: Boolean) {
        if (entries.size >= 100_000 || !entries.add(name) || name.length > 300 ||
            '\\' in name || name.any { it.code < 32 } ||
            name.trimEnd('/').split('/').any { it.isEmpty() || it == "." || it == ".." }) invalid()
        if (directory) return
        when {
            name == "journey.json" || name == "track.gpx" || knownTile -> Unit
            name.startsWith("photos/") && name.count { it == '/' } == 1 -> {
                val fileName = name.substringAfter('/')
                flightPhotoStorageName(fileName)
                photos += fileName
            }
            else -> invalid()
        }
    }

    fun requirePhotoManifest(names: List<String>) {
        if (names.size != names.distinct().size || names.toSet() != photos) invalid()
    }

    private fun invalid(): Nothing = throw IOException("invalid_archive")
}

/** Validate the stored identifier without truncating it or collapsing distinct archive names. */
internal fun flightPhotoStorageName(value: String): String {
    if (!value.matches(Regex("[A-Za-z0-9._-]{1,281}")) || value.startsWith('.')) {
        throw IOException("invalid_archive")
    }
    return value
}
