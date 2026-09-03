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
	val bytesPerSecond: Long = 0L,
	val overviewTextureTiles: Int = 0,
	val standardTextureTiles: Int = 0,
	val highTextureTiles: Int = 0,
	val ultraTextureTiles: Int = 0,
	val ultraPlusTextureTiles: Int = 0,
	val memoryCacheHits: Int = 0,
	val diskCacheHits: Int = 0,
	val networkRequests: Int = 0,
	val decodedTerrainCacheTiles: Int = 0,
	val decodedTerrainCacheBytes: Long = 0L,
	val geometryCacheTiles: Int = 0,
	val geometryCacheBytes: Long = 0L,
	val textureQueue: Int = 0,
	val estimatedVisibleGpuBytes: Long = 0L,
	val zoom: Int? = null,
	val message: String? = null
) {
	val progress: Float
		get() = if (requestedTiles == 0) 0f else
			((availableTiles + failedTiles).toFloat() / requestedTiles).coerceIn(0f, 1f)
}

enum class FlightTerrainTextureTier {
	OVERVIEW,
	STANDARD,
	HIGH,
	ULTRA,
	ULTRA_PLUS
}

data class FlightTerrainRenderStats(
	val visibleMeshes: Int = 0,
	val cachedGeometryTiles: Int = 0,
	val cachedTextures: Int = 0,
	val queuedTextureUploads: Int = 0,
	val geometryBytes: Long = 0L,
	val textureBytes: Long = 0L
)

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
	val tileId: TerrainTileId,
	val vertices: FloatArray,
	val indices: ShortArray,
	val refinementLevel: Int = 0,
	val terrainAvailable: Boolean = true,
	val gridQuads: Int = FlightTerrainMeshBuilder.DEFAULT_GRID_QUADS,
	val sourceWidthPixels: Int = 0,
	val sourceHeightPixels: Int = 0,
	val minimumElevationMeters: Float = 0f,
	val maximumElevationMeters: Float = 0f,
	val edgeMorphMask: Int = 0,
	val boundarySourceZoom: Int = tileId.zoom,
	val satelliteTexturePath: String? = null,
	val standardSatelliteTexturePath: String? = null,
	val satelliteTextureTier: FlightTerrainTextureTier = FlightTerrainTextureTier.OVERVIEW,
	val nativeMapTexturePath: String? = null
)

data class FlightTerrainGeometry(
	val vertices: FloatArray,
	val indices: ShortArray,
	val minimumElevationMeters: Float,
	val maximumElevationMeters: Float
)

data class FlightTerrainGeometryCacheKey(
	val tileId: TerrainTileId,
	val coordinateOriginLatitude: Double,
	val coordinateOriginLongitude: Double,
	val gridQuads: Int = FlightTerrainMeshBuilder.DEFAULT_GRID_QUADS,
	val textureZoom: Int = tileId.zoom,
	val boundaryMask: Int = 0,
	val boundarySourceZoom: Int = tileId.zoom
)

data class FlightTerrainScene(
	val centerLatitude: Double,
	val centerLongitude: Double,
	val detailFocus: FlightTerrainDetailFocus?,
	val coordinateOriginLatitude: Double,
	val coordinateOriginLongitude: Double,
	val radiusKm: Int,
	val zoom: Int,
	val terrainFineZoom: Int,
	val terrainMiddleZoom: Int,
	val satelliteQuality: FlightSatelliteQuality,
	val meshes: List<FlightTerrainMesh>,
	val loadedTiles: Int,
	val missingTiles: Int,
	val satelliteTiles: Int,
	val nativeMapTiles: Int,
	val nativeMapFailedTiles: Int,
	val nativeMapRequested: Boolean,
	val centerGroundElevationMeters: Float?,
	val geometryGeneration: Long,
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
