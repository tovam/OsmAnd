package net.osmand.plus.plugins.flightmode

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

object FlightTerrainMeshBuilder {

	private const val GRID_QUADS = 32
	private const val GRID_SIZE = GRID_QUADS + 1
	const val VERTEX_COMPONENTS = 9

	fun build(
		centerLatitude: Double,
		centerLongitude: Double,
		radiusKm: Int,
		plan: TerrainTilePlan,
		tiles: Map<TerrainTileId, TerrariumTile>,
		satelliteQuality: FlightSatelliteQuality = FlightSatelliteQuality.HIGH,
		satelliteTexturePaths: Map<TerrainTileId, String> = emptyMap()
	): FlightTerrainScene {
		val projection = FlightTerrainCoordinates(centerLatitude, centerLongitude)
		val sampler = TileElevationSampler(plan.zoom, tiles)
		val meshes = plan.tiles.mapNotNull { tileId ->
			val tile = tiles[tileId] ?: return@mapNotNull null
			buildTileMesh(tile, sampler, projection, satelliteTexturePaths[tileId])
		}
		val centerGround = sampler.elevationAt(
			FlightTerrainTilePlanner.longitudeToTileX(centerLongitude, plan.zoom),
			FlightTerrainTilePlanner.latitudeToTileY(centerLatitude, plan.zoom),
			null
		)
		return FlightTerrainScene(
			centerLatitude = centerLatitude,
			centerLongitude = centerLongitude,
			radiusKm = radiusKm,
			zoom = plan.zoom,
			satelliteQuality = satelliteQuality,
			meshes = meshes,
			loadedTiles = tiles.size,
			missingTiles = (plan.tiles.size - tiles.size).coerceAtLeast(0),
			satelliteTiles = satelliteTexturePaths.size,
			centerGroundElevationMeters = centerGround
		)
	}

	private fun buildTileMesh(
		tile: TerrariumTile,
		sampler: TileElevationSampler,
		projection: FlightTerrainCoordinates,
		satelliteTexturePath: String?
	): FlightTerrainMesh {
		val vertices = FloatArray(GRID_SIZE * GRID_SIZE * VERTEX_COMPONENTS)
		for (row in 0 until GRID_SIZE) {
			val tileY = tile.id.y + row.toDouble() / GRID_QUADS
			val latitude = FlightTerrainTilePlanner.tileYToLatitude(tileY, tile.id.zoom)
			for (column in 0 until GRID_SIZE) {
				val tileX = tile.id.x + column.toDouble() / GRID_QUADS
				val longitude = FlightTerrainTilePlanner.tileXToLongitude(tileX, tile.id.zoom)
				val elevation = sampler.elevationAt(tileX, tileY, tile) ?: 0f
				val position = projection.toLocal(latitude, longitude, max(0f, elevation).toDouble())
				val offset = (row * GRID_SIZE + column) * VERTEX_COMPONENTS
				vertices[offset] = position[0]
				vertices[offset + 1] = position[1]
				vertices[offset + 2] = position[2]
				vertices[offset + 6] = elevation
				vertices[offset + 7] = column.toFloat() / GRID_QUADS
				vertices[offset + 8] = row.toFloat() / GRID_QUADS
			}
		}
		calculateNormals(vertices)

		val indices = ShortArray(GRID_QUADS * GRID_QUADS * 6)
		var indexOffset = 0
		for (row in 0 until GRID_QUADS) {
			for (column in 0 until GRID_QUADS) {
				val topLeft = row * GRID_SIZE + column
				val topRight = topLeft + 1
				val bottomLeft = topLeft + GRID_SIZE
				val bottomRight = bottomLeft + 1
				indices[indexOffset++] = topLeft.toShort()
				indices[indexOffset++] = bottomLeft.toShort()
				indices[indexOffset++] = topRight.toShort()
				indices[indexOffset++] = topRight.toShort()
				indices[indexOffset++] = bottomLeft.toShort()
				indices[indexOffset++] = bottomRight.toShort()
			}
		}
		return FlightTerrainMesh(vertices, indices, satelliteTexturePath)
	}

	private fun calculateNormals(vertices: FloatArray) {
		for (row in 0 until GRID_SIZE) {
			for (column in 0 until GRID_SIZE) {
				val left = vertexPosition(vertices, row, (column - 1).coerceAtLeast(0))
				val right = vertexPosition(vertices, row, (column + 1).coerceAtMost(GRID_SIZE - 1))
				val top = vertexPosition(vertices, (row - 1).coerceAtLeast(0), column)
				val bottom = vertexPosition(vertices, (row + 1).coerceAtMost(GRID_SIZE - 1), column)
				val east = floatArrayOf(right[0] - left[0], right[1] - left[1], right[2] - left[2])
				val south = floatArrayOf(bottom[0] - top[0], bottom[1] - top[1], bottom[2] - top[2])
				var normalX = south[1] * east[2] - south[2] * east[1]
				var normalY = south[2] * east[0] - south[0] * east[2]
				var normalZ = south[0] * east[1] - south[1] * east[0]
				val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ).coerceAtLeast(1e-6f)
				normalX /= length
				normalY /= length
				normalZ /= length
				if (normalY < 0f) {
					normalX = -normalX
					normalY = -normalY
					normalZ = -normalZ
				}
				val offset = (row * GRID_SIZE + column) * VERTEX_COMPONENTS
				vertices[offset + 3] = normalX
				vertices[offset + 4] = normalY
				vertices[offset + 5] = normalZ
			}
		}
	}

	private fun vertexPosition(vertices: FloatArray, row: Int, column: Int): FloatArray {
		val offset = (row * GRID_SIZE + column) * VERTEX_COMPONENTS
		return floatArrayOf(vertices[offset], vertices[offset + 1], vertices[offset + 2])
	}

	private class TileElevationSampler(
		private val zoom: Int,
		private val tiles: Map<TerrainTileId, TerrariumTile>
	) {
		private val tileCount = 1 shl zoom

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

	private const val TERRARIUM_TILE_SIZE = 256
}
