package net.osmand.plus.plugins.flightmode

import kotlin.math.floor

/** A cache-only query: finer local relief wins, missing/corrupt levels fall back to their parent. */
internal fun cachedCalibrationElevation(
    latitude: Double,
    longitude: Double,
    localTile: (TerrainTileId) -> TerrariumTile?,
): Double? {
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    for (zoom in 14 downTo 0) {
        val x = FlightTerrainTilePlanner.longitudeToTileX(longitude, zoom)
        val y = FlightTerrainTilePlanner.latitudeToTileY(latitude, zoom)
        val id = TerrainTileId(zoom, floor(x).toInt().coerceIn(0, (1 shl zoom) - 1), floor(y).toInt().coerceIn(0, (1 shl zoom) - 1))
        val tile = localTile(id) ?: continue
        if (tile.width < 2 || tile.height < 2) continue
        val px = ((x - id.x) * (tile.width - 1)).coerceIn(0.0, (tile.width - 1).toDouble())
        val py = ((y - id.y) * (tile.height - 1)).coerceIn(0.0, (tile.height - 1).toDouble())
        val ix = floor(px).toInt(); val iy = floor(py).toInt()
        val fx = px - ix; val fy = py - iy
        val elevation = (tile.elevation(ix, iy) * (1 - fx) + tile.elevation(ix + 1, iy) * fx) * (1 - fy) +
            (tile.elevation(ix, iy + 1) * (1 - fx) + tile.elevation(ix + 1, iy + 1) * fx) * fy
        if (elevation.isFinite()) return elevation
    }
    return null
}
