package net.osmand.plus.plugins.flightmode

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Terrain geometry LOD is deliberately independent from satellite texture quality.
 * The tile containing the aircraft keeps almost every Terrarium sample, nearby tiles
 * keep every other sample, and distant coverage stays cheap. Changing imagery quality
 * therefore never churns vertex buffers, while nearby ridgelines retain their shape.
 */
object FlightTerrainGeometryLodPolicy {

	fun quadsForDistance(radiusKm: Int, nearestDistanceKm: Double): Int {
		val radius = radiusKm.coerceAtLeast(1).toDouble()
		return when {
			nearestDistanceKm <= minOf(35.0, radius * 0.18) -> FlightTerrainMeshBuilder.MAXIMUM_GRID_QUADS
			nearestDistanceKm <= minOf(120.0, radius * 0.50) -> 128
			else -> FlightTerrainMeshBuilder.DEFAULT_GRID_QUADS
		}
	}

	fun estimatedBytes(gridQuads: Int): Long {
		val quads = gridQuads.coerceIn(
			FlightTerrainMeshBuilder.DEFAULT_GRID_QUADS,
			FlightTerrainMeshBuilder.MAXIMUM_GRID_QUADS
		).toLong()
		val vertices = (quads + 1L) * (quads + 1L)
		val indices = quads * quads * 6L
		return vertices * FlightTerrainMeshBuilder.VERTEX_COMPONENTS * FLOAT_BYTES + indices * SHORT_BYTES
	}

	private const val FLOAT_BYTES = 4L
	private const val SHORT_BYTES = 2L
}

/**
 * Bounded high-resolution patches layered over the complete coarse scene.
 * The fine patch preserves almost every Terrarium source sample; the middle
 * patch keeps every other sample and bridges toward the cheap distant mesh.
 */
object FlightTerrainRefinementPolicy {

	const val FINE_RADIUS_KM = 10.0
	const val MIDDLE_RADIUS_KM = 35.0
	const val MAXIMUM_FINE_TILES = 12
	const val MAXIMUM_MIDDLE_TILES = 32
	const val FINE_GRID_QUADS = 255
	const val MIDDLE_GRID_QUADS = 128
	const val MINIMUM_DISTINCT_FOCUS_KM = 2.0
}

object FlightTerrainMeshBuilder {

	const val DEFAULT_GRID_QUADS = 32
	const val MAXIMUM_GRID_QUADS = 255
	const val VERTEX_COMPONENTS = 9

	fun build(
		centerLatitude: Double,
		centerLongitude: Double,
		radiusKm: Int,
		plan: TerrainTilePlan,
		tiles: Map<TerrainTileId, TerrariumTile>,
		satelliteQuality: FlightSatelliteQuality = FlightSatelliteQuality.HIGH,
		terrainFineZoom: Int = FlightPlan.DEFAULT_TERRAIN_FINE_ZOOM,
		terrainMiddleZoom: Int = FlightPlan.DEFAULT_TERRAIN_MIDDLE_ZOOM,
		detailFocus: FlightTerrainDetailFocus? = null,
		satelliteTexturePaths: Map<TerrainTileId, String> = emptyMap(),
		standardSatelliteTexturePaths: Map<TerrainTileId, String> = emptyMap(),
		satelliteTextureTiers: Map<TerrainTileId, FlightTerrainTextureTier> = emptyMap(),
		nativeMapTexturePaths: Map<TerrainTileId, String> = emptyMap(),
		nativeMapFailedTiles: Int = 0,
		nativeMapRequested: Boolean = nativeMapTexturePaths.isNotEmpty(),
		coordinateOriginLatitude: Double = centerLatitude,
		coordinateOriginLongitude: Double = centerLongitude,
		geometryQuadsByTile: Map<TerrainTileId, Int> = emptyMap(),
		geometryCache: MutableMap<FlightTerrainGeometryCacheKey, FlightTerrainGeometry>? = null,
		geometryGeneration: Long = System.nanoTime(),
		includePlaceholders: Boolean = false
	): FlightTerrainScene {
		val projection = FlightTerrainCoordinates(coordinateOriginLatitude, coordinateOriginLongitude)
		val sampler = TileElevationSampler(plan.zoom, tiles)
		val meshes = plan.tiles.mapNotNull { tileId ->
			val terrainAvailable = tiles.containsKey(tileId)
			val gridQuads = geometryQuadsByTile[tileId]
				?.coerceIn(DEFAULT_GRID_QUADS, MAXIMUM_GRID_QUADS)
				?: DEFAULT_GRID_QUADS
			val geometryCacheKey = FlightTerrainGeometryCacheKey(
				tileId,
				coordinateOriginLatitude,
				coordinateOriginLongitude,
				gridQuads
			)
			val geometry = cachedOrBuildGeometry(geometryCache, geometryCacheKey) {
				val tile = tiles[tileId]
				when {
					tile != null -> buildTileGeometry(tile, sampler, projection, gridQuads)
					includePlaceholders -> buildPlaceholderGeometry(tileId, projection)
					else -> null
				}
			} ?: return@mapNotNull null
			FlightTerrainMesh(
				tileId = tileId,
				vertices = geometry.vertices,
				indices = geometry.indices,
				refinementLevel = 0,
				terrainAvailable = terrainAvailable,
				satelliteTexturePath = satelliteTexturePaths[tileId],
				standardSatelliteTexturePath = standardSatelliteTexturePaths[tileId],
				satelliteTextureTier = satelliteTextureTiers[tileId]
					?: if (satelliteTexturePaths[tileId] != null) {
						FlightTerrainTextureTier.STANDARD
					} else FlightTerrainTextureTier.OVERVIEW,
				nativeMapTexturePath = nativeMapTexturePaths[tileId]
			)
		}
		val centerGround = sampler.elevationAt(
			FlightTerrainTilePlanner.longitudeToTileX(centerLongitude, plan.zoom),
			FlightTerrainTilePlanner.latitudeToTileY(centerLatitude, plan.zoom),
			null
		)
		return FlightTerrainScene(
			centerLatitude = centerLatitude,
			centerLongitude = centerLongitude,
			detailFocus = detailFocus,
			coordinateOriginLatitude = coordinateOriginLatitude,
			coordinateOriginLongitude = coordinateOriginLongitude,
			radiusKm = radiusKm,
			zoom = plan.zoom,
			terrainFineZoom = terrainFineZoom,
			terrainMiddleZoom = terrainMiddleZoom,
			satelliteQuality = satelliteQuality,
			meshes = meshes,
			loadedTiles = tiles.size,
			missingTiles = (plan.tiles.size - tiles.size).coerceAtLeast(0),
			satelliteTiles = (satelliteTexturePaths.keys + standardSatelliteTexturePaths.keys).size,
			nativeMapTiles = nativeMapTexturePaths.size,
			nativeMapFailedTiles = nativeMapFailedTiles,
			nativeMapRequested = nativeMapRequested,
			centerGroundElevationMeters = centerGround,
			geometryGeneration = geometryGeneration
		)
	}

	/**
	 * Builds a nested elevation layer while reusing the already loaded base satellite
	 * texture. Exposed patch edges morph back to the coarser elevation over a few
	 * quads, so a new level appears as added detail instead of a vertical tile wall.
	 */
	fun buildRefinementMeshes(
		baseZoom: Int,
		plan: TerrainTilePlan,
		tiles: Map<TerrainTileId, TerrariumTile>,
		boundaryZoom: Int,
		boundaryTiles: Map<TerrainTileId, TerrariumTile>,
		baseSatelliteTexturePaths: Map<TerrainTileId, String>,
		baseStandardSatelliteTexturePaths: Map<TerrainTileId, String>,
		baseSatelliteTextureTiers: Map<TerrainTileId, FlightTerrainTextureTier>,
		coordinateOriginLatitude: Double,
		coordinateOriginLongitude: Double,
		geometryQuadsByTile: Map<TerrainTileId, Int>,
		geometryCache: MutableMap<FlightTerrainGeometryCacheKey, FlightTerrainGeometry>?
	): List<FlightTerrainMesh> {
		if (plan.zoom <= baseZoom || tiles.isEmpty()) return emptyList()
		val projection = FlightTerrainCoordinates(coordinateOriginLatitude, coordinateOriginLongitude)
		val sampler = TileElevationSampler(plan.zoom, tiles)
		val boundarySampler = TileElevationSampler(boundaryZoom, boundaryTiles)
		val availableIds = tiles.keys
		return plan.tiles.mapNotNull { tileId ->
			val tile = tiles[tileId] ?: return@mapNotNull null
			val gridQuads = geometryQuadsByTile[tileId]
				?.coerceIn(DEFAULT_GRID_QUADS, MAXIMUM_GRID_QUADS)
				?: DEFAULT_GRID_QUADS
			val textureTransform = textureTransform(tileId, baseZoom)
			val boundaryMask = exposedBoundaryMask(tileId, availableIds)
			val geometryCacheKey = FlightTerrainGeometryCacheKey(
				tileId = tileId,
				coordinateOriginLatitude = coordinateOriginLatitude,
				coordinateOriginLongitude = coordinateOriginLongitude,
				gridQuads = gridQuads,
				textureZoom = baseZoom,
				boundaryMask = boundaryMask,
				boundarySourceZoom = boundaryZoom
			)
			val geometry = cachedOrBuildGeometry(geometryCache, geometryCacheKey) {
				buildTileGeometry(
					tile = tile,
					sampler = sampler,
					projection = projection,
					gridQuads = gridQuads,
					textureTransform = textureTransform,
					boundarySampler = boundarySampler,
					boundaryMask = boundaryMask
				)
			}
			val textureTile = textureTransform.textureTile
			FlightTerrainMesh(
				tileId = tileId,
				vertices = geometry.vertices,
				indices = geometry.indices,
				refinementLevel = (tileId.zoom - baseZoom).coerceAtLeast(1),
				terrainAvailable = true,
				satelliteTexturePath = baseSatelliteTexturePaths[textureTile],
				standardSatelliteTexturePath = baseStandardSatelliteTexturePaths[textureTile],
				satelliteTextureTier = baseSatelliteTextureTiers[textureTile]
					?: FlightTerrainTextureTier.OVERVIEW,
				nativeMapTexturePath = null
			)
		}
	}

	private fun cachedOrBuildGeometry(
		cache: MutableMap<FlightTerrainGeometryCacheKey, FlightTerrainGeometry>?,
		key: FlightTerrainGeometryCacheKey,
		builder: () -> FlightTerrainGeometry?
	): FlightTerrainGeometry? {
		if (cache == null) return builder()
		synchronized(cache) { cache[key] }?.let { return it }
		val built = builder() ?: return null
		return synchronized(cache) {
			cache[key] ?: built.also { cache[key] = it }
		}
	}

	/**
	 * A four-vertex Earth-positioned tile shown while its Terrarium payload is still loading.
	 * It provides complete, stable coverage immediately; the repository invalidates it when
	 * the real adaptive geometry becomes available.
	 */
	private fun buildPlaceholderGeometry(
		tileId: TerrainTileId,
		projection: FlightTerrainCoordinates
	): FlightTerrainGeometry {
		val vertices = FloatArray(4 * VERTEX_COMPONENTS)
		val corners = arrayOf(
			0.0 to 0.0,
			1.0 to 0.0,
			0.0 to 1.0,
			1.0 to 1.0
		)
		corners.forEachIndexed { index, (u, v) ->
			val latitude = FlightTerrainTilePlanner.tileYToLatitude(tileId.y + v, tileId.zoom)
			val longitude = FlightTerrainTilePlanner.tileXToLongitude(tileId.x + u, tileId.zoom)
			val position = projection.toLocal(latitude, longitude, 0.0)
			val normal = projection.vectorToLocal(latitude, longitude, 0f, 1f, 0f)
			val offset = index * VERTEX_COMPONENTS
			vertices[offset] = position[0]
			vertices[offset + 1] = position[1]
			vertices[offset + 2] = position[2]
			vertices[offset + 3] = normal[0]
			vertices[offset + 4] = normal[1]
			vertices[offset + 5] = normal[2]
			vertices[offset + 6] = 0f
			vertices[offset + 7] = u.toFloat()
			vertices[offset + 8] = v.toFloat()
		}
		return FlightTerrainGeometry(
			vertices = vertices,
			indices = shortArrayOf(0, 2, 1, 1, 2, 3)
		)
	}

	private fun buildTileGeometry(
		tile: TerrariumTile,
		sampler: TileElevationSampler,
		projection: FlightTerrainCoordinates,
		gridQuads: Int,
		textureTransform: TextureTransform? = null,
		boundarySampler: TileElevationSampler? = null,
		boundaryMask: Int = 0
	): FlightTerrainGeometry {
		val resolvedTextureTransform = textureTransform ?: TextureTransform(tile.id, 0f, 0f, 1f)
		val gridSize = gridQuads + 1
		val vertices = FloatArray(gridSize * gridSize * VERTEX_COMPONENTS)
		for (row in 0 until gridSize) {
			val tileY = tile.id.y + row.toDouble() / gridQuads
			val latitude = FlightTerrainTilePlanner.tileYToLatitude(tileY, tile.id.zoom)
			for (column in 0 until gridSize) {
				val tileX = tile.id.x + column.toDouble() / gridQuads
				val longitude = FlightTerrainTilePlanner.tileXToLongitude(tileX, tile.id.zoom)
				val detailedElevation = sampler.elevationAt(tileX, tileY, tile) ?: 0f
				val edgeBlend = boundaryBlend(column, row, gridQuads, boundaryMask)
				val elevation = if (edgeBlend < 1f && boundarySampler != null) {
					val coarseElevation = boundarySampler.elevationAtLocation(latitude, longitude)
					detailedElevation + ((coarseElevation ?: detailedElevation) - detailedElevation) * (1f - edgeBlend)
				} else detailedElevation
				val position = projection.toLocal(latitude, longitude, elevation.toDouble())
				val offset = (row * gridSize + column) * VERTEX_COMPONENTS
				vertices[offset] = position[0]
				vertices[offset + 1] = position[1]
				vertices[offset + 2] = position[2]
				vertices[offset + 6] = elevation
				vertices[offset + 7] = resolvedTextureTransform.offsetU +
					column.toFloat() / gridQuads * resolvedTextureTransform.scale
				vertices[offset + 8] = resolvedTextureTransform.offsetV +
					row.toFloat() / gridQuads * resolvedTextureTransform.scale
			}
		}
		calculateNormals(vertices, gridSize)

		val indices = ShortArray(gridQuads * gridQuads * 6)
		var indexOffset = 0
		for (row in 0 until gridQuads) {
			for (column in 0 until gridQuads) {
				val topLeft = row * gridSize + column
				val topRight = topLeft + 1
				val bottomLeft = topLeft + gridSize
				val bottomRight = bottomLeft + 1
				indices[indexOffset++] = topLeft.toShort()
				indices[indexOffset++] = bottomLeft.toShort()
				indices[indexOffset++] = topRight.toShort()
				indices[indexOffset++] = topRight.toShort()
				indices[indexOffset++] = bottomLeft.toShort()
				indices[indexOffset++] = bottomRight.toShort()
			}
		}
		return FlightTerrainGeometry(
			vertices = vertices,
			indices = indices
		)
	}

	private fun boundaryBlend(column: Int, row: Int, gridQuads: Int, boundaryMask: Int): Float {
		if (boundaryMask == 0) return 1f
		var distance = EDGE_MORPH_QUADS
		if (boundaryMask and BOUNDARY_LEFT != 0) distance = minOf(distance, column)
		if (boundaryMask and BOUNDARY_RIGHT != 0) distance = minOf(distance, gridQuads - column)
		if (boundaryMask and BOUNDARY_TOP != 0) distance = minOf(distance, row)
		if (boundaryMask and BOUNDARY_BOTTOM != 0) distance = minOf(distance, gridQuads - row)
		return (distance.toFloat() / EDGE_MORPH_QUADS).coerceIn(0f, 1f)
	}

	private fun textureTransform(tileId: TerrainTileId, textureZoom: Int): TextureTransform {
		val zoomDelta = (tileId.zoom - textureZoom).coerceAtLeast(0)
		val factor = 1 shl zoomDelta
		val textureTile = TerrainTileId(textureZoom, tileId.x / factor, tileId.y / factor)
		return TextureTransform(
			textureTile = textureTile,
			offsetU = (tileId.x % factor).toFloat() / factor,
			offsetV = (tileId.y % factor).toFloat() / factor,
			scale = 1f / factor
		)
	}

	private fun exposedBoundaryMask(tileId: TerrainTileId, availableIds: Set<TerrainTileId>): Int {
		val tileCount = 1 shl tileId.zoom
		val left = TerrainTileId(tileId.zoom, Math.floorMod(tileId.x - 1, tileCount), tileId.y)
		val right = TerrainTileId(tileId.zoom, Math.floorMod(tileId.x + 1, tileCount), tileId.y)
		val top = TerrainTileId(tileId.zoom, tileId.x, tileId.y - 1)
		val bottom = TerrainTileId(tileId.zoom, tileId.x, tileId.y + 1)
		var mask = 0
		if (left !in availableIds) mask = mask or BOUNDARY_LEFT
		if (right !in availableIds) mask = mask or BOUNDARY_RIGHT
		if (top !in availableIds) mask = mask or BOUNDARY_TOP
		if (bottom !in availableIds) mask = mask or BOUNDARY_BOTTOM
		return mask
	}

	private fun calculateNormals(vertices: FloatArray, gridSize: Int) {
		for (row in 0 until gridSize) {
			for (column in 0 until gridSize) {
				val leftOffset = (row * gridSize + (column - 1).coerceAtLeast(0)) * VERTEX_COMPONENTS
				val rightOffset = (row * gridSize + (column + 1).coerceAtMost(gridSize - 1)) * VERTEX_COMPONENTS
				val topOffset = ((row - 1).coerceAtLeast(0) * gridSize + column) * VERTEX_COMPONENTS
				val bottomOffset = ((row + 1).coerceAtMost(gridSize - 1) * gridSize + column) * VERTEX_COMPONENTS
				val eastX = vertices[rightOffset] - vertices[leftOffset]
				val eastY = vertices[rightOffset + 1] - vertices[leftOffset + 1]
				val eastZ = vertices[rightOffset + 2] - vertices[leftOffset + 2]
				val southX = vertices[bottomOffset] - vertices[topOffset]
				val southY = vertices[bottomOffset + 1] - vertices[topOffset + 1]
				val southZ = vertices[bottomOffset + 2] - vertices[topOffset + 2]
				var normalX = southY * eastZ - southZ * eastY
				var normalY = southZ * eastX - southX * eastZ
				var normalZ = southX * eastY - southY * eastX
				val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ).coerceAtLeast(1e-6f)
				normalX /= length
				normalY /= length
				normalZ /= length
				if (normalY < 0f) {
					normalX = -normalX
					normalY = -normalY
					normalZ = -normalZ
				}
				val offset = (row * gridSize + column) * VERTEX_COMPONENTS
				vertices[offset + 3] = normalX
				vertices[offset + 4] = normalY
				vertices[offset + 5] = normalZ
			}
		}
	}

	private class TileElevationSampler(
		private val zoom: Int,
		private val tiles: Map<TerrainTileId, TerrariumTile>
	) {
		private val tileCount = 1 shl zoom

		fun elevationAtLocation(latitude: Double, longitude: Double): Float? = elevationAt(
			FlightTerrainTilePlanner.longitudeToTileX(longitude, zoom),
			FlightTerrainTilePlanner.latitudeToTileY(latitude, zoom),
			null
		)

		fun elevationAt(tileX: Double, tileY: Double, fallback: TerrariumTile?): Float? {
			val globalPixelX = tileX * TERRARIUM_TILE_SIZE
			val globalPixelY = tileY * TERRARIUM_TILE_SIZE
			val pixelX = floor(globalPixelX).toLong()
			val pixelY = floor(globalPixelY).toLong()
			val fractionX = (globalPixelX - pixelX).toFloat()
			val fractionY = (globalPixelY - pixelY).toFloat()
			val topLeft = pixel(pixelX, pixelY, fallback) ?: return null
			val topRight = pixel(pixelX + 1, pixelY, fallback) ?: topLeft
			val bottomLeft = pixel(pixelX, pixelY + 1, fallback) ?: topLeft
			val bottomRight = pixel(pixelX + 1, pixelY + 1, fallback) ?: bottomLeft
			val top = topLeft + (topRight - topLeft) * fractionX
			val bottom = bottomLeft + (bottomRight - bottomLeft) * fractionX
			return top + (bottom - top) * fractionY
		}

		private fun pixel(globalX: Long, globalY: Long, fallback: TerrariumTile?): Float? {
			val rawTileX = floorDiv(globalX, TERRARIUM_TILE_SIZE.toLong())
			val tileY = floorDiv(globalY, TERRARIUM_TILE_SIZE.toLong()).toInt()
			if (tileY !in 0 until tileCount) return null
			val tileX = floorMod(rawTileX, tileCount.toLong()).toInt()
			val localX = floorMod(globalX, TERRARIUM_TILE_SIZE.toLong()).toInt()
			val localY = floorMod(globalY, TERRARIUM_TILE_SIZE.toLong()).toInt()
			val tile = tiles[TerrainTileId(zoom, tileX, tileY)]
			if (tile != null) {
				val scaledX = (localX.toDouble() / TERRARIUM_TILE_SIZE * tile.width).toInt()
				val scaledY = (localY.toDouble() / TERRARIUM_TILE_SIZE * tile.height).toInt()
				return tile.elevation(scaledX, scaledY)
			}
			if (fallback == null) return null
			val fallbackStartX = fallback.id.x.toLong() * TERRARIUM_TILE_SIZE
			val fallbackStartY = fallback.id.y.toLong() * TERRARIUM_TILE_SIZE
			val fallbackPixelX = (globalX - fallbackStartX).coerceIn(0L, (TERRARIUM_TILE_SIZE - 1).toLong())
			val fallbackPixelY = (globalY - fallbackStartY).coerceIn(0L, (TERRARIUM_TILE_SIZE - 1).toLong())
			return fallback.elevation(
				(fallbackPixelX.toDouble() / TERRARIUM_TILE_SIZE * fallback.width).toInt(),
				(fallbackPixelY.toDouble() / TERRARIUM_TILE_SIZE * fallback.height).toInt()
			)
		}

		private fun floorDiv(value: Long, divisor: Long): Long = Math.floorDiv(value, divisor)
		private fun floorMod(value: Long, divisor: Long): Long = Math.floorMod(value, divisor)
	}

	private data class TextureTransform(
		val textureTile: TerrainTileId,
		val offsetU: Float,
		val offsetV: Float,
		val scale: Float
	)

	private const val TERRARIUM_TILE_SIZE = 256
	private const val EDGE_MORPH_QUADS = 4
	private const val BOUNDARY_LEFT = 1
	private const val BOUNDARY_RIGHT = 2
	private const val BOUNDARY_TOP = 4
	private const val BOUNDARY_BOTTOM = 8
}
