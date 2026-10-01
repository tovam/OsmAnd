package net.osmand.plus.plugins.flightmode

import kotlin.math.pow
import java.io.File

data class FlightStorageCleanupSummary(val files: Int, val bytes: Long, val protectedBytes: Long)
internal data class FlightStorageRegion(val latitude: Double, val longitude: Double, val radiusKm: Double)

/** Conservative masks preserve descendants of retained route sources and calibrated photo views. */
internal class FlightStorageProtection(private val references: Set<FlightOfflineTileKey>,
    private val regions: List<FlightStorageRegion>) {
    fun protects(key: FlightOfflineTileKey): Boolean {
        if (key.tile.zoom <= 5 || key in references) return true
        var tile = key.tile
        while (tile.zoom > 6) {
            tile = TerrainTileId(tile.zoom - 1, tile.x / 2, tile.y / 2)
            if (FlightOfflineTileKey(tile, key.satellite) in references) return true
        }
        if (regions.isEmpty()) return false
        val latitude = FlightTerrainTilePlanner.tileYToLatitude(key.tile.y + .5, key.tile.zoom)
        val longitude = FlightTerrainTilePlanner.tileXToLongitude(key.tile.x + .5, key.tile.zoom)
        val margin = 56678.0 / 2.0.pow(key.tile.zoom)
        return regions.any { FlightTerrainTilePlanner.distanceKm(it.latitude, it.longitude, latitude, longitude) <= it.radiusKm + margin }
    }
}

internal data class FlightStorageCandidate(val key: FlightOfflineTileKey, val bytes: Long, val modified: Long) {
    fun matches(root: File, file: File): Boolean {
        val expected = File(root.canonicalFile, file.relativeTo(root).path).absoluteFile
        return file.canonicalFile == expected && file.isFile && file.length() == bytes && file.lastModified() == modified
    }
}
