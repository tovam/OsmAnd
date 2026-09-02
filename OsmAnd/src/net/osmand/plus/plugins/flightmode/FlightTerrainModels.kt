package net.osmand.plus.plugins.flightmode

enum class FlightTerrainPhase {
	IDLE,
	PLANNING,
	DOWNLOADING,
	BUILDING,
	READY,
	ERROR
}

data class FlightTerrainStatus(
	val phase: FlightTerrainPhase = FlightTerrainPhase.IDLE,
	val requestedTiles: Int = 0,
	val availableTiles: Int = 0,
	val downloadedTiles: Int = 0,
	val failedTiles: Int = 0,
	val satelliteTiles: Int = 0,
	val satelliteFailedTiles: Int = 0,
	val nativeMapTiles: Int = 0,
	val nativeMapFailedTiles: Int = 0,
	val bytesDownloaded: Long = 0L,
	val zoom: Int? = null,
	val message: String? = null
) {
	val progress: Float
		get() = if (requestedTiles == 0) 0f else
			((availableTiles + failedTiles).toFloat() / requestedTiles).coerceIn(0f, 1f)
}

data class TerrainTileId(
	val zoom: Int,
	val x: Int,
	val y: Int
)

data class TerrariumTile(
	val id: TerrainTileId,
	val width: Int,
	val height: Int,
	val elevationsMeters: FloatArray
) {
	init {
		require(width > 0 && height > 0)
		require(elevationsMeters.size == width * height)
	}

	fun elevation(x: Int, y: Int): Float {
		val safeX = x.coerceIn(0, width - 1)
		val safeY = y.coerceIn(0, height - 1)
		return elevationsMeters[safeY * width + safeX]
	}
}

data class FlightTerrainMesh(
	val vertices: FloatArray,
	val indices: ShortArray,
	val satelliteTexturePath: String? = null,
	val nativeMapTexturePath: String? = null
)

data class FlightTerrainScene(
	val centerLatitude: Double,
	val centerLongitude: Double,
	val radiusKm: Int,
	val zoom: Int,
	val satelliteQuality: FlightSatelliteQuality,
	val meshes: List<FlightTerrainMesh>,
	val loadedTiles: Int,
	val missingTiles: Int,
	val satelliteTiles: Int,
	val nativeMapTiles: Int,
	val nativeMapFailedTiles: Int,
	val nativeMapRequested: Boolean,
	val centerGroundElevationMeters: Float?,
	val generation: Long = System.nanoTime()
)

data class TerrainTilePlan(
	val zoom: Int,
	val tiles: List<TerrainTileId>
)

object TerrariumCodec {
	fun decodeElevation(red: Int, green: Int, blue: Int): Float =
		red.coerceIn(0, 255) * 256f +
			green.coerceIn(0, 255) +
			blue.coerceIn(0, 255) / 256f -
			32_768f

	fun decodeArgb(argb: Int): Float = decodeElevation(
		red = argb shr 16 and 0xff,
		green = argb shr 8 and 0xff,
		blue = argb and 0xff
	)
}
