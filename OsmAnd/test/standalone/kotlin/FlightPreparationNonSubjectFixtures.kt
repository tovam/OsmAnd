package net.osmand.plus.plugins.flightmode

// Standalone checks use the real photo calibration, but must never access terrain files/network.
class FlightTerrainRepository {
    var enabled = true
    var loads = 0
    var cancelled = 0
    var loading: suspend () -> Unit = {}

    fun setSceneWorkEnabled(value: Boolean) {
        enabled = value
        if (!value) cancelled++
    }

    fun cancelPendingAssets() {
        cancelled++
    }

    fun close() {
        enabled = false
    }

    suspend fun loadScene(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        satelliteQuality: FlightSatelliteQuality,
        terrainFineZoom: Int,
        terrainMiddleZoom: Int,
        detailFocus: FlightTerrainDetailFocus?,
        includeNativeMap: Boolean,
        previousScene: FlightTerrainScene?,
        onScene: (FlightTerrainScene) -> Unit,
        onStatus: (FlightTerrainStatus) -> Unit,
    ): FlightTerrainScene {
        check(enabled)
        loads++
        loading()
        return FlightTerrainScene(
                latitude,
                longitude,
                detailFocus,
                latitude,
                longitude,
                radiusKm,
                10,
                terrainFineZoom,
                terrainMiddleZoom,
                satelliteQuality,
                emptyList(),
                0,
                0,
                0,
                0,
                0,
                includeNativeMap,
                0f,
                loads.toLong(),
            )
            .also(onScene)
    }

    suspend fun calibrationElevation(latitude: Double, longitude: Double): Double =
        error("Terrain I/O is outside the standalone fixture")
}
