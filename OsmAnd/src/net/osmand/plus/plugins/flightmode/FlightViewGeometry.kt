package net.osmand.plus.plugins.flightmode

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Shared, testable camera math used by photo calibration and detail-tile targeting. */
object FlightViewGeometry {

	fun viewElevationDegrees(
		placement: FlightWindowPlacement,
		look: FlightWindowLook
	): Float = viewDirection(0f, placement, look).elevationDegrees

	fun photoSpatialPose(
		trip: FlightTrip?,
		samplePosition: Double?,
		placement: FlightWindowPlacement,
		look: FlightWindowLook,
		altitudeOverrideMeters: Float?
	): FlightPhotoSpatialPose? {
		val position = samplePosition?.takeIf { it.isFinite() } ?: return null
		val sample = FlightSampleInterpolator.sampleAt(trip, position) ?: return null
		val bearing = sample.bearingDegrees ?: return null
		val direction = viewDirection(bearing, placement, look)
		return FlightPhotoSpatialPose(
			samplePosition = FlightSampleInterpolator.quantizePosition(position),
			timestampMillis = sample.timestampMillis.takeIf { it > 0L },
			eyeLatitude = sample.latitude,
			eyeLongitude = sample.longitude,
			eyeAltitudeMeters = altitudeOverrideMeters ?: sample.altitudeMeters?.toFloat(),
			aircraftBearingDegrees = bearing,
			viewAzimuthDegrees = direction.azimuthDegrees,
			viewElevationDegrees = direction.elevationDegrees,
			verticalFieldOfViewDegrees = placement.verticalFieldOfViewDegrees()
		).clampedOrNull()
	}

	/**
	 * Intersects the camera's centre ray with a flat zero-altitude ground plane.
	 * Looking at or above the horizon, missing altitude, and intersections farther
	 * than [maximumDistanceKm] intentionally produce no secondary detail focus.
	 */
	fun groundDetailFocus(
		sample: FlightSample,
		placement: FlightWindowPlacement,
		look: FlightWindowLook,
		altitudeOverrideMeters: Float?,
		maximumDistanceKm: Double = DEFAULT_MAXIMUM_GROUND_FOCUS_KM
	): FlightTerrainDetailFocus? {
		val altitudeMeters = (altitudeOverrideMeters ?: sample.altitudeMeters?.toFloat())
			?.takeIf(Float::isFinite)
			?.coerceAtLeast(0f)
			?: return null
		val aircraftBearing = sample.bearingDegrees ?: return null
		val direction = viewDirection(aircraftBearing, placement, look)
		if (direction.vertical >= -MINIMUM_DOWNWARD_COMPONENT) return null
		val horizontalDistanceKm = altitudeMeters.toDouble() * direction.horizontal /
			-direction.vertical / 1_000.0
		if (!horizontalDistanceKm.isFinite() || horizontalDistanceKm !in 0.0..maximumDistanceKm) return null
		return destination(
			latitude = sample.latitude,
			longitude = sample.longitude,
			bearingDegrees = direction.azimuthDegrees.toDouble(),
			distanceKm = horizontalDistanceKm
		)
	}

	/** Canonical spherical angles for the exact forward vector used by the GL camera. */
	private fun viewDirection(
		aircraftBearingDegrees: Float,
		placement: FlightWindowPlacement,
		look: FlightWindowLook
	): ViewDirection {
		val rawElevation = placement.geometry().elevationRadians.toDouble() +
			Math.toRadians(look.clamped().pitchDegrees.toDouble())
		val vertical = sin(rawElevation)
		val rawHorizontal = cos(rawElevation)
		val horizontal = kotlin.math.abs(rawHorizontal)
		val rawAzimuth = placement.viewAzimuthDegrees(aircraftBearingDegrees, look)
		val azimuth = normalizeDegrees(rawAzimuth + if (rawHorizontal < 0.0) 180f else 0f)
		return ViewDirection(
			azimuthDegrees = azimuth,
			elevationDegrees = Math.toDegrees(atan2(vertical, horizontal)).toFloat(),
			horizontal = horizontal,
			vertical = vertical
		)
	}

	private fun normalizeDegrees(value: Float): Float {
		val normalized = value % 360f
		return if (normalized < 0f) normalized + 360f else normalized
	}

	private data class ViewDirection(
		val azimuthDegrees: Float,
		val elevationDegrees: Float,
		val horizontal: Double,
		val vertical: Double
	)

	private fun destination(
		latitude: Double,
		longitude: Double,
		bearingDegrees: Double,
		distanceKm: Double
	): FlightTerrainDetailFocus {
		val angularDistance = distanceKm / EARTH_RADIUS_KM
		val bearing = Math.toRadians(bearingDegrees)
		val latitudeRadians = Math.toRadians(latitude)
		val longitudeRadians = Math.toRadians(longitude)
		val targetLatitude = asin(
			sin(latitudeRadians) * cos(angularDistance) +
				cos(latitudeRadians) * sin(angularDistance) * cos(bearing)
		)
		val targetLongitude = longitudeRadians + atan2(
			sin(bearing) * sin(angularDistance) * cos(latitudeRadians),
			cos(angularDistance) - sin(latitudeRadians) * sin(targetLatitude)
		)
		var longitudeDegrees = Math.toDegrees(targetLongitude) % 360.0
		if (longitudeDegrees > 180.0) longitudeDegrees -= 360.0
		if (longitudeDegrees < -180.0) longitudeDegrees += 360.0
		return FlightTerrainDetailFocus(Math.toDegrees(targetLatitude), longitudeDegrees)
	}

	const val DEFAULT_MAXIMUM_GROUND_FOCUS_KM = 100.0
	private const val EARTH_RADIUS_KM = 6_371.0088
	private const val MINIMUM_DOWNWARD_COMPONENT = 0.0017
}
