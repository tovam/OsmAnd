package net.osmand.plus.plugins.flightmode

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FlightTerrainCoordinates(val centerLatitude: Double, val centerLongitude: Double) {

	private val latitudeRadians = Math.toRadians(centerLatitude)
	private val longitudeRadians = Math.toRadians(centerLongitude)
	private val sinLatitude = sin(latitudeRadians)
	private val cosLatitude = cos(latitudeRadians)
	private val sinLongitude = sin(longitudeRadians)
	private val cosLongitude = cos(longitudeRadians)
	private val origin = ecef(latitudeRadians, longitudeRadians, 0.0)

	fun toLocal(latitude: Double, longitude: Double, elevationMeters: Double): FloatArray {
		val point = ecef(Math.toRadians(latitude), Math.toRadians(longitude), elevationMeters)
		val deltaX = point[0] - origin[0]
		val deltaY = point[1] - origin[1]
		val deltaZ = point[2] - origin[2]
		val east = -sinLongitude * deltaX + cosLongitude * deltaY
		val north = -sinLatitude * cosLongitude * deltaX -
			sinLatitude * sinLongitude * deltaY +
			cosLatitude * deltaZ
		val up = cosLatitude * cosLongitude * deltaX +
			cosLatitude * sinLongitude * deltaY +
			sinLatitude * deltaZ
		return floatArrayOf(east.toFloat(), up.toFloat(), (-north).toFloat())
	}

	/** Converts a direction expressed in the local east/up/-north frame at a point into this origin's frame. */
	fun vectorToLocal(
		latitude: Double,
		longitude: Double,
		east: Float,
		up: Float,
		negativeNorth: Float
	): FloatArray {
		val pointLatitude = Math.toRadians(latitude)
		val pointLongitude = Math.toRadians(longitude)
		val pointSinLatitude = sin(pointLatitude)
		val pointCosLatitude = cos(pointLatitude)
		val pointSinLongitude = sin(pointLongitude)
		val pointCosLongitude = cos(pointLongitude)
		val localEastInput = east.toDouble()
		val localUpInput = up.toDouble()
		val north = -negativeNorth.toDouble()
		val ecefX = localEastInput * -pointSinLongitude +
			north * -pointSinLatitude * pointCosLongitude +
			localUpInput * pointCosLatitude * pointCosLongitude
		val ecefY = localEastInput * pointCosLongitude +
			north * -pointSinLatitude * pointSinLongitude +
			localUpInput * pointCosLatitude * pointSinLongitude
		val ecefZ = north * pointCosLatitude + localUpInput * pointSinLatitude
		val localEast = -sinLongitude * ecefX + cosLongitude * ecefY
		val localNorth = -sinLatitude * cosLongitude * ecefX -
			sinLatitude * sinLongitude * ecefY +
			cosLatitude * ecefZ
		val localUp = cosLatitude * cosLongitude * ecefX +
			cosLatitude * sinLongitude * ecefY +
			sinLatitude * ecefZ
		return floatArrayOf(localEast.toFloat(), localUp.toFloat(), (-localNorth).toFloat())
	}

	private fun ecef(latitude: Double, longitude: Double, elevationMeters: Double): DoubleArray {
		val sinLatitude = sin(latitude)
		val cosLatitude = cos(latitude)
		val primeVerticalRadius = WGS84_SEMI_MAJOR_AXIS /
			sqrt(1.0 - WGS84_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude)
		return doubleArrayOf(
			(primeVerticalRadius + elevationMeters) * cosLatitude * cos(longitude),
			(primeVerticalRadius + elevationMeters) * cosLatitude * sin(longitude),
			(primeVerticalRadius * (1.0 - WGS84_ECCENTRICITY_SQUARED) + elevationMeters) * sinLatitude
		)
	}

	/** Inverse of toLocal, including ellipsoid curvature (not a flat lat/lon offset). */
	fun toGeographic(local: DoubleArray): DoubleArray {
		val east = local[0]
		val up = local[1]
		val north = -local[2]
		val x = origin[0] - sinLongitude * east - sinLatitude * cosLongitude * north + cosLatitude * cosLongitude * up
		val y = origin[1] + cosLongitude * east - sinLatitude * sinLongitude * north + cosLatitude * sinLongitude * up
		val z = origin[2] + cosLatitude * north + sinLatitude * up
		val p = kotlin.math.hypot(x, y)
		var latitude = kotlin.math.atan2(z, p * (1 - WGS84_ECCENTRICITY_SQUARED))
		repeat(10) {
			val n = WGS84_SEMI_MAJOR_AXIS / sqrt(1 - WGS84_ECCENTRICITY_SQUARED * sin(latitude) * sin(latitude))
			latitude = kotlin.math.atan2(z + WGS84_ECCENTRICITY_SQUARED * n * sin(latitude), p)
		}
		val n = WGS84_SEMI_MAJOR_AXIS / sqrt(1 - WGS84_ECCENTRICITY_SQUARED * sin(latitude) * sin(latitude))
		val altitude = if (kotlin.math.abs(cos(latitude)) > 1e-8) p / cos(latitude) - n
			else kotlin.math.abs(z) - n * (1 - WGS84_ECCENTRICITY_SQUARED)
		return doubleArrayOf(Math.toDegrees(latitude), Math.toDegrees(kotlin.math.atan2(y, x)), altitude)
	}

	fun vectorFromLocal(latitude: Double, longitude: Double, vector: FloatArray): FloatArray {
		fun dot(basis: FloatArray) = basis.indices.sumOf { (basis[it] * vector[it]).toDouble() }.toFloat()
		return floatArrayOf(dot(vectorToLocal(latitude, longitude, 1f, 0f, 0f)),
			dot(vectorToLocal(latitude, longitude, 0f, 1f, 0f)), dot(vectorToLocal(latitude, longitude, 0f, 0f, 1f)))
	}

	companion object {
		private const val WGS84_SEMI_MAJOR_AXIS = 6_378_137.0
		private const val WGS84_ECCENTRICITY_SQUARED = 6.69437999014e-3
	}
}
