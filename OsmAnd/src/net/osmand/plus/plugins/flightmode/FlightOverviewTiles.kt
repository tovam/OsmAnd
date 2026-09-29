package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Fixed, small coarse backdrop. Handles the date line and never enumerates a disk cache. */
internal fun flightOverviewTiles(latitude: Double, longitude: Double, sideMeters: Double): List<TerrainTileId> {
    if (!latitude.isFinite() || !longitude.isFinite() || !sideMeters.isFinite() || sideMeters <= 0) return emptyList()
    val zoom = 4
    val n = 1 shl zoom
    val lat = latitude.coerceIn(-85.0511, 85.0511)
    val halfLatitude = Math.toDegrees(sideMeters / (2 * 6_371_008.8))
    val halfLongitude = (halfLatitude / cos(Math.toRadians(lat)).coerceAtLeast(0.001)).coerceAtMost(180.0)
    val centerX = FlightTerrainTilePlanner.longitudeToTileX(longitude, zoom)
    val fromX = floor(centerX - halfLongitude / 360 * n).toInt()
    val toX = floor(centerX + halfLongitude / 360 * n).toInt()
    val fromY = floor(FlightTerrainTilePlanner.latitudeToTileY((lat + halfLatitude).coerceAtMost(85.0511), zoom)).toInt().coerceIn(0, n - 1)
    val toY = floor(FlightTerrainTilePlanner.latitudeToTileY((lat - halfLatitude).coerceAtLeast(-85.0511), zoom)).toInt().coerceIn(0, n - 1)
    val ids = linkedSetOf<TerrainTileId>()
    for (y in fromY..toY) for (x in fromX..minOf(toX, fromX + n - 1))
        ids += TerrainTileId(zoom, Math.floorMod(x, n), y)
    val centerY = FlightTerrainTilePlanner.latitudeToTileY(lat, zoom)
    return ids.sortedBy {
        val dx = abs(it.x + 0.5 - centerX) % n
        minOf(dx, n - dx).pow(2) + (it.y + 0.5 - centerY).pow(2)
    }
}
