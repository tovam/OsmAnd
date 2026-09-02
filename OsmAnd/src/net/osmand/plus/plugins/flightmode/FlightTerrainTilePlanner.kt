package net.osmand.plus.plugins.flightmode

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

object FlightTerrainTilePlanner {

	private const val EARTH_RADIUS_KM = 6_371.0088
	private const val MIN_ZOOM = 4
	private const val MAX_ZOOM = 12
	private const val DEFAULT_MAX_SCENE_TILES = 144
	private const val DEFAULT_MAX_CORRIDOR_TILES = 1_500

	fun scenePlan(
		latitude: Double,
		longitude: Double,
		radiusKm: Int,
		maxTiles: Int = DEFAULT_MAX_SCENE_TILES
	): TerrainTilePlan {
		val safeRadius = radiusKm.coerceIn(5, 1_000)
		for (zoom in MAX_ZOOM downTo MIN_ZOOM) {
			val tiles = tilesAround(latitude, longitude, safeRadius.toDouble(), zoom)
			if (tiles.size <= maxTiles || zoom == MIN_ZOOM) {
				return TerrainTilePlan(zoom, tiles.sortedWith(tileComparator))
			}
		}
		return TerrainTilePlan(MIN_ZOOM, emptyList())
	}

	fun corridorPlan(
		stops: List<FlightStop>,
		radiusKm: Int,
		maxTiles: Int = DEFAULT_MAX_CORRIDOR_TILES
	): TerrainTilePlan? {
		val coordinates = stops.mapNotNull { stop ->
			val latitude = stop.latitude ?: return@mapNotNull null
			val longitude = stop.longitude ?: return@mapNotNull null
			latitude to longitude
		}
		return corridorPlanForCoordinates(coordinates, radiusKm, maxTiles)
	}

	fun trackCorridorPlan(
		samples: List<FlightSample>,
		radiusKm: Int,
		maxTiles: Int = DEFAULT_MAX_CORRIDOR_TILES
	): TerrainTilePlan? {
		if (samples.size < 2) return null
		val stride = ceil(samples.size / MAXIMUM_TRACK_PLANNER_POINTS.toDouble()).toInt().coerceAtLeast(1)
		val coordinates = buildList {
			samples.forEachIndexed { index, sample ->
				if (index % stride == 0 || index == samples.lastIndex) add(sample.latitude to sample.longitude)
			}
		}
		return corridorPlanForCoordinates(coordinates, radiusKm, maxTiles)
	}

	private fun corridorPlanForCoordinates(
		coordinates: List<Pair<Double, Double>>,
		radiusKm: Int,
		maxTiles: Int
	): TerrainTilePlan? {
		if (coordinates.size < 2) return null
		val safeRadius = radiusKm.coerceIn(5, 1_000)
		val representativeLatitude = coordinates.map { it.first }.average()
		val representativeLongitude = coordinates.map { it.second }.average()
		val sceneZoom = scenePlan(representativeLatitude, representativeLongitude, safeRadius).zoom
		for (zoom in sceneZoom downTo MIN_ZOOM) {
			val tiles = linkedSetOf<TerrainTileId>()
			coordinates.zipWithNext().forEach { (from, to) ->
				val distanceKm = distanceKm(from.first, from.second, to.first, to.second)
				val sampleSpacingKm = max(20.0, safeRadius * 0.55)
				val samples = ceil(distanceKm / sampleSpacingKm).toInt().coerceAtLeast(1)
				for (index in 0..samples) {
					val fraction = index.toDouble() / samples
					val point = greatCircleInterpolate(from, to, fraction)
					tiles += tilesAround(point.first, point.second, safeRadius.toDouble(), zoom)
				}
			}
			if (tiles.size <= maxTiles || zoom == MIN_ZOOM) {
				return TerrainTilePlan(zoom, tiles.sortedWith(tileComparator))
			}
		}
		return null
	}

	fun tilesAround(latitude: Double, longitude: Double, radiusKm: Double, zoom: Int): Set<TerrainTileId> {
		val safeLatitude = latitude.coerceIn(-WEB_MERCATOR_MAX_LATITUDE, WEB_MERCATOR_MAX_LATITUDE)
		val tileCount = 1 shl zoom
		val centerX = floor(longitudeToTileX(longitude, zoom)).toInt()
		val centerY = floor(latitudeToTileY(safeLatitude, zoom)).toInt()
		val tileGroundKm = tileGroundWidthKm(safeLatitude, zoom).coerceAtLeast(0.1)
		val tileRange = ceil(radiusKm / tileGroundKm + 1.5).toInt()
		val tileDiagonalKm = tileGroundKm * sqrt(2.0)
		val result = linkedSetOf<TerrainTileId>()
		for (dy in -tileRange..tileRange) {
			val y = centerY + dy
			if (y !in 0 until tileCount) continue
			for (dx in -tileRange..tileRange) {
				val rawX = centerX + dx
				val x = floorMod(rawX, tileCount)
				val tileLatitude = tileYToLatitude(y + 0.5, zoom)
				val tileLongitude = tileXToLongitude(rawX + 0.5, zoom)
				if (distanceKm(safeLatitude, longitude, tileLatitude, tileLongitude) <= radiusKm + tileDiagonalKm * 0.6) {
					result += TerrainTileId(zoom, x, y)
				}
			}
		}
		return result
	}

	fun longitudeToTileX(longitude: Double, zoom: Int): Double =
		(longitude + 180.0) / 360.0 * 2.0.pow(zoom)

	fun latitudeToTileY(latitude: Double, zoom: Int): Double {
		val latitudeRadians = Math.toRadians(latitude.coerceIn(-WEB_MERCATOR_MAX_LATITUDE, WEB_MERCATOR_MAX_LATITUDE))
		return (1.0 - ln(tan(latitudeRadians) + 1.0 / cos(latitudeRadians)) / PI) / 2.0 * 2.0.pow(zoom)
	}

	fun tileXToLongitude(tileX: Double, zoom: Int): Double = tileX / 2.0.pow(zoom) * 360.0 - 180.0

	fun tileYToLatitude(tileY: Double, zoom: Int): Double {
		val n = PI - 2.0 * PI * tileY / 2.0.pow(zoom)
		return Math.toDegrees(atan(sinh(n)))
	}

	fun distanceKm(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
		val deltaLatitude = Math.toRadians(latitude2 - latitude1)
		val deltaLongitude = Math.toRadians(normalizeLongitude(longitude2 - longitude1))
		val latitude1Radians = Math.toRadians(latitude1)
		val latitude2Radians = Math.toRadians(latitude2)
		val a = sin(deltaLatitude / 2).pow(2) +
			cos(latitude1Radians) * cos(latitude2Radians) * sin(deltaLongitude / 2).pow(2)
		return 2.0 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
	}

	private fun tileGroundWidthKm(latitude: Double, zoom: Int): Double =
		cos(Math.toRadians(latitude)) * 2.0 * PI * EARTH_RADIUS_KM / 2.0.pow(zoom)

	private fun greatCircleInterpolate(
		from: Pair<Double, Double>,
		to: Pair<Double, Double>,
		fraction: Double
	): Pair<Double, Double> {
		val first = unitVector(from.first, from.second)
		val second = unitVector(to.first, to.second)
		val dot = (first[0] * second[0] + first[1] * second[1] + first[2] * second[2]).coerceIn(-1.0, 1.0)
		val angle = kotlin.math.acos(dot)
		if (angle < 1e-9) return from
		val denominator = sin(angle)
		val firstWeight = sin((1.0 - fraction) * angle) / denominator
		val secondWeight = sin(fraction * angle) / denominator
		val x = first[0] * firstWeight + second[0] * secondWeight
		val y = first[1] * firstWeight + second[1] * secondWeight
		val z = first[2] * firstWeight + second[2] * secondWeight
		val latitude = Math.toDegrees(kotlin.math.atan2(z, sqrt(x * x + y * y)))
		val longitude = Math.toDegrees(kotlin.math.atan2(y, x))
		return latitude to longitude
	}

	private fun unitVector(latitude: Double, longitude: Double): DoubleArray {
		val latitudeRadians = Math.toRadians(latitude)
		val longitudeRadians = Math.toRadians(longitude)
		val latitudeCosine = cos(latitudeRadians)
		return doubleArrayOf(
			latitudeCosine * cos(longitudeRadians),
			latitudeCosine * sin(longitudeRadians),
			sin(latitudeRadians)
		)
	}

	private fun normalizeLongitude(longitude: Double): Double {
		var normalized = longitude
		while (normalized > 180.0) normalized -= 360.0
		while (normalized < -180.0) normalized += 360.0
		return normalized
	}

	private fun floorMod(value: Int, modulus: Int): Int {
		val result = value % modulus
		return if (result < 0) result + modulus else result
	}

	private val tileComparator = compareBy<TerrainTileId>({ it.zoom }, { it.y }, { it.x })

	private const val WEB_MERCATOR_MAX_LATITUDE = 85.05112878
	private const val MAXIMUM_TRACK_PLANNER_POINTS = 512
}
