package net.osmand.plus.plugins.flightmode

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class FlightSunVector(
	val east: Float,
	val north: Float,
	val up: Float
)

object FlightSunPosition {

	fun direction(timestampMillis: Long, latitude: Double, longitude: Double): FlightSunVector {
		val julianDay = timestampMillis / MILLIS_PER_DAY + UNIX_EPOCH_JULIAN_DAY
		val daysSinceJ2000 = julianDay - J2000_JULIAN_DAY
		val meanLongitude = normalizeDegrees(280.460 + 0.9856474 * daysSinceJ2000)
		val meanAnomaly = Math.toRadians(normalizeDegrees(357.528 + 0.9856003 * daysSinceJ2000))
		val eclipticLongitude = Math.toRadians(
			normalizeDegrees(meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2.0 * meanAnomaly))
		)
		val obliquity = Math.toRadians(23.439 - 0.0000004 * daysSinceJ2000)
		val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
		val declination = asin(sin(obliquity) * sin(eclipticLongitude))
		val greenwichSiderealDegrees = normalizeDegrees(
			280.46061837 + 360.98564736629 * daysSinceJ2000
		)
		val hourAngle = Math.toRadians(normalizeSignedDegrees(
			greenwichSiderealDegrees + longitude - Math.toDegrees(rightAscension)
		))
		val latitudeRadians = Math.toRadians(latitude)
		val east = -cos(declination) * sin(hourAngle)
		val north = sin(declination) * cos(latitudeRadians) -
			cos(declination) * cos(hourAngle) * sin(latitudeRadians)
		val up = sin(declination) * sin(latitudeRadians) +
			cos(declination) * cos(hourAngle) * cos(latitudeRadians)
		return FlightSunVector(east.toFloat(), north.toFloat(), up.toFloat())
	}

	private fun normalizeDegrees(value: Double): Double {
		val result = value % 360.0
		return if (result < 0.0) result + 360.0 else result
	}

	private fun normalizeSignedDegrees(value: Double): Double {
		val normalized = normalizeDegrees(value)
		return if (normalized > 180.0) normalized - 360.0 else normalized
	}

	private const val MILLIS_PER_DAY = 86_400_000.0
	private const val UNIX_EPOCH_JULIAN_DAY = 2_440_587.5
	private const val J2000_JULIAN_DAY = 2_451_545.0
}
