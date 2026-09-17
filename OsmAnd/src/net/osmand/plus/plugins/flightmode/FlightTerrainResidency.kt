package net.osmand.plus.plugins.flightmode

/** A progressive scene may reuse coverage, but it must not accumulate the whole flight. */
internal object FlightTerrainResidency {
    fun retainPlanned(
        previous: List<FlightTerrainMesh>,
        desired: Set<Pair<TerrainTileId, Int>>,
    ): List<FlightTerrainMesh> = previous.filter { (it.tileId to it.refinementLevel) in desired }

    fun parent(tile: TerrainTileId, zoom: Int): TerrainTileId? {
        if (zoom !in 0..tile.zoom || tile.zoom - zoom > 30) return null
        val shift = tile.zoom - zoom
        return TerrainTileId(zoom, tile.x shr shift, tile.y shr shift)
    }
}
