package net.osmand.plus.plugins.flightmode

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FlightTerrainCoordinates(centerLatitude: Double, centerLongitude: Double) {

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

	companion object {
		private const val WGS84_SEMI_MAJOR_AXIS = 6_378_137.0
		private const val WGS84_ECCENTRICITY_SQUARED = 6.69437999014e-3
	}
}
