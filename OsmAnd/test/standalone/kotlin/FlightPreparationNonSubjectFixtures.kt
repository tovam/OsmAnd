package net.osmand.plus.plugins.flightmode

// Standalone checks use the real photo calibration, but must never access terrain files/network.
class FlightTerrainRepository {
    suspend fun calibrationElevation(latitude: Double, longitude: Double): Double =
        error("Terrain I/O is outside the standalone fixture")
}

object FlightTerrainMeshBuilder {
    const val DEFAULT_GRID_QUADS = 32
}
