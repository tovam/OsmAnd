package net.osmand.plus.plugins.flightmode

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class FlightPage {
	PREPARE,
	MAP,
	WINDOW,
	WINDOW_SETUP,
	SATELLITE,
	SENSORS,
	PHOTO,
	JOURNEYS
}

enum class FlightSessionMode {
	PREPARE,
	LIVE,
	REPLAY
}

enum class FlightCabinSide {
	LEFT,
	RIGHT
}

enum class FlightSatelliteQuality(val zoomDelta: Int) {
	STANDARD(0),
	HIGH(1),
	ULTRA(2)
}

data class FlightWindowPlacement(
	val side: FlightCabinSide = FlightCabinSide.LEFT,
	val forwardOffsetMeters: Float = 0f,
	val verticalOffsetMeters: Float = 0f,
	val zoom: Float = 1f,
	val cabinTransparent: Boolean = false,
	val cabinHidden: Boolean = false
) {
	fun clamped(): FlightWindowPlacement = copy(
		forwardOffsetMeters = forwardOffsetMeters.coerceIn(MIN_FORWARD_OFFSET_METERS, MAX_FORWARD_OFFSET_METERS),
		verticalOffsetMeters = verticalOffsetMeters.coerceIn(MIN_VERTICAL_OFFSET_METERS, MAX_VERTICAL_OFFSET_METERS),
		zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
	)

	companion object {
		const val WINDOW_DIAMETER_METERS = 0.25f
		const val WALL_DISTANCE_METERS = 0.35f
		const val DEFAULT_VERTICAL_FIELD_OF_VIEW_DEGREES = 58f
		const val MIN_FORWARD_OFFSET_METERS = -0.90f
		const val MAX_FORWARD_OFFSET_METERS = 1.10f
		const val MIN_VERTICAL_OFFSET_METERS = -0.55f
		const val MAX_VERTICAL_OFFSET_METERS = 0.55f
		const val MIN_ZOOM = 0.65f
		const val MAX_ZOOM = 4f
	}
}

data class FlightWindowLook(
	val yawDegrees: Float = 0f,
	val pitchDegrees: Float = 0f
) {
	fun clamped(): FlightWindowLook = copy(
		yawDegrees = normalizeYaw(yawDegrees),
		pitchDegrees = pitchDegrees.coerceIn(MIN_PITCH_DEGREES, MAX_PITCH_DEGREES)
	)

	companion object {
		const val MIN_PITCH_DEGREES = -89f
		const val MAX_PITCH_DEGREES = 45f

		fun normalizeYaw(value: Float): Float {
			var normalized = value % 360f
			if (normalized >= 180f) normalized -= 360f
			if (normalized < -180f) normalized += 360f
			return normalized
		}
	}
}

data class FlightWindowGeometry(
	val lateralOffsetMeters: Float,
	val horizontalDistanceMeters: Float,
	val eyeToWindowDistanceMeters: Float,
	val relativeAzimuthRadians: Float,
	val elevationRadians: Float,
	val horizontalIncidence: Float,
	val verticalIncidence: Float
)

fun FlightWindowPlacement.geometry(): FlightWindowGeometry {
	val placement = clamped()
	val lateralOffset = if (placement.side == FlightCabinSide.RIGHT) {
		FlightWindowPlacement.WALL_DISTANCE_METERS
	} else {
		-FlightWindowPlacement.WALL_DISTANCE_METERS
	}
	val horizontalDistance = hypot(lateralOffset, placement.forwardOffsetMeters)
	val eyeToWindowDistance = hypot(horizontalDistance, placement.verticalOffsetMeters)
	return FlightWindowGeometry(
		lateralOffsetMeters = lateralOffset,
		horizontalDistanceMeters = horizontalDistance,
		eyeToWindowDistanceMeters = eyeToWindowDistance,
		relativeAzimuthRadians = atan2(lateralOffset, placement.forwardOffsetMeters),
		elevationRadians = atan2(placement.verticalOffsetMeters, horizontalDistance),
		horizontalIncidence = FlightWindowPlacement.WALL_DISTANCE_METERS / horizontalDistance,
		verticalIncidence = horizontalDistance / eyeToWindowDistance
	)
}

fun FlightWindowPlacement.viewAzimuthDegrees(
	aircraftBearingDegrees: Float,
	look: FlightWindowLook
): Float {
	val windowAzimuthDegrees = Math.toDegrees(geometry().relativeAzimuthRadians.toDouble()).toFloat()
	val value = aircraftBearingDegrees + windowAzimuthDegrees + look.yawDegrees
	val normalized = value % 360f
	return if (normalized < 0f) normalized + 360f else normalized
}

data class FlightStop(
	val name: String,
	val latitude: Double? = null,
	val longitude: Double? = null
)

data class FlightCitySuggestion(
	val name: String,
	val latitude: Double,
	val longitude: Double,
	val subType: String,
	val regionName: String? = null
)

data class FlightPlan(
	val stops: List<FlightStop>,
	val terrainCorridorKm: Int = 300,
	val detailedSatelliteRadiusKm: Int = 300,
	val satelliteQuality: FlightSatelliteQuality = FlightSatelliteQuality.HIGH,
	val shadowsEnabled: Boolean = true,
	val resumeAfterRestart: Boolean = true
) {
	companion object {
		fun preview(): FlightPlan = FlightPlan(
			stops = listOf(
				FlightStop("Paris", 49.0097, 2.5479),
				FlightStop("Vienne", 48.1103, 16.5697),
				FlightStop("Podgorica", 42.3594, 19.2519)
			)
		)
	}
}

data class FlightSample(
	val index: Int,
	val legIndex: Int,
	val timestampMillis: Long,
	val latitude: Double,
	val longitude: Double,
	val altitudeMeters: Double?,
	val speedMetersPerSecond: Float?,
	val bearingDegrees: Float?,
	val horizontalAccuracyMeters: Float?,
	val hdop: Float? = null,
	val satellitesUsed: Int? = null,
	val satellitesFound: Int? = null,
	val soundDb: Float? = null,
	val soundSpectrum: List<Float>? = null,
	val vibrationHz: Float? = null
)

data class FlightLeg(
	val index: Int,
	val name: String,
	val startSampleIndex: Int,
	val endSampleIndex: Int,
	val distanceMeters: Double,
	val startTimeMillis: Long?,
	val endTimeMillis: Long?
)

data class FlightTrip(
	val name: String,
	val samples: List<FlightSample>,
	val legs: List<FlightLeg>,
	val hasUsableTimestamps: Boolean,
	val totalDistanceMeters: Double,
	val sourceDescription: String
) {
	val durationMillis: Long?
		get() {
			if (!hasUsableTimestamps || samples.size < 2) return null
			return samples.last().timestampMillis - samples.first().timestampMillis
		}

	fun progressFor(sample: FlightSample): Float {
		if (samples.size < 2) return 0f
		if (hasUsableTimestamps) {
			val start = samples.first().timestampMillis
			val duration = samples.last().timestampMillis - start
			if (duration > 0L) {
				return ((sample.timestampMillis - start).toDouble() / duration)
					.toFloat()
					.coerceIn(0f, 1f)
			}
		}
		return (sample.index.toFloat() / samples.lastIndex.coerceAtLeast(1)).coerceIn(0f, 1f)
	}
}

data class FlightSnapshot(
	val sample: FlightSample,
	val progress: Float,
	val dataGap: Boolean = false,
	val interpolated: Boolean = false
)

data class FlightProfilePoint(
	val progress: Float,
	val altitudeMeters: Float,
	val legIndex: Int
)

data class FlightProfileLeg(
	val index: Int,
	val from: FlightStop,
	val to: FlightStop,
	val startProgress: Float,
	val endProgress: Float,
	val distanceKm: Float,
	val cruiseAltitudeMeters: Float,
	val estimatedDurationMinutes: Int,
	val points: List<FlightProfilePoint>
)

data class FlightProfile(
	val legs: List<FlightProfileLeg>,
	val points: List<FlightProfilePoint>,
	val totalDistanceKm: Float,
	val totalDurationMinutes: Int,
	val recorded: Boolean = false
)

data class FlightSpan(
	val startProgress: Float,
	val endProgress: Float
) {
	fun normalized(): FlightSpan = if (startProgress <= endProgress) {
		copy(
			startProgress = startProgress.coerceIn(0f, 1f),
			endProgress = endProgress.coerceIn(0f, 1f)
		)
	} else {
		FlightSpan(endProgress.coerceIn(0f, 1f), startProgress.coerceIn(0f, 1f))
	}
}

data class FlightPhotoAttachment(
	val id: String,
	val fileName: String,
	val localPath: String,
	val timestampMillis: Long?,
	val matchedSampleIndex: Int?,
	val includeMainCamera: Boolean = true,
	val includeSelfie: Boolean = false,
	val includeMap: Boolean = true,
	val includeScene3d: Boolean = true
)

data class FlightJourney(
	val id: String,
	val name: String,
	val createdAtMillis: Long,
	val updatedAtMillis: Long,
	val plan: FlightPlan,
	val trip: FlightTrip,
	val flightSpans: List<FlightSpan>,
	val photos: List<FlightPhotoAttachment>
)

data class FlightJourneySummary(
	val id: String,
	val name: String,
	val updatedAtMillis: Long,
	val sampleCount: Int,
	val photoCount: Int
)

data class FlightRecordingPolicy(
	val cruisePointDistanceMeters: Float = 1_000f,
	val maximumStraightIntervalSeconds: Float = 20f,
	val turnAcceleration: Float = 2f,
	val routeDeviationAcceleration: Float = 2f
) {
	fun intervalSeconds(
		speedMetersPerSecond: Float,
		turnRateDegreesPerSecond: Float = 0f,
		distanceFromExpectedRouteMeters: Float = 0f
	): Float {
		val safeSpeed = max(1f, speedMetersPerSecond)
		var interval = min(maximumStraightIntervalSeconds, cruisePointDistanceMeters / safeSpeed)
		if (turnRateDegreesPerSecond >= 1f) {
			interval /= max(1f, turnAcceleration)
		}
		if (distanceFromExpectedRouteMeters >= 5_000f) {
			interval /= max(1f, routeDeviationAcceleration)
		}
		return interval.coerceIn(1f, maximumStraightIntervalSeconds)
	}
}

data class FlightUiState(
	val page: FlightPage = FlightPage.PREPARE,
	val sessionMode: FlightSessionMode = FlightSessionMode.PREPARE,
	val plan: FlightPlan = FlightPlan.preview(),
	val profile: FlightProfile = FlightProfilePlanner.build(FlightPlan.preview()),
	val citySearchStopIndex: Int? = null,
	val citySuggestions: List<FlightCitySuggestion> = emptyList(),
	val citySearchLoading: Boolean = false,
	val trip: FlightTrip? = null,
	val snapshot: FlightSnapshot? = null,
	val replayProgress: Float = 0f,
	val replayPlaying: Boolean = false,
	val replaySpeed: Float = 1f,
	val loadingTrip: Boolean = false,
	val tripLoadError: String? = null,
	val duplicateJourneyWarning: FlightJourneySummary? = null,
	val terrainStatus: FlightTerrainStatus = FlightTerrainStatus(),
	val terrainScene: FlightTerrainScene? = null,
	val windowPlacement: FlightWindowPlacement = FlightWindowPlacement(),
	val windowLook: FlightWindowLook = FlightWindowLook(),
	val windowAltitudeOverrideMeters: Float? = null,
	val satelliteOpacity: Float = 0.92f,
	val terrainOpacity: Float = 0.70f,
	val mapFollowing: Boolean = true,
	val recordingPolicy: FlightRecordingPolicy = FlightRecordingPolicy(),
	val showTrackPoints: Boolean = false,
	val flightSpans: List<FlightSpan> = emptyList(),
	val pendingFlightStartProgress: Float? = null,
	val journeyId: String? = null,
	val journeyName: String = "",
	val journeyCreatedAtMillis: Long? = null,
	val journeyDirty: Boolean = false,
	val savedJourneys: List<FlightJourneySummary> = emptyList(),
	val photos: List<FlightPhotoAttachment> = emptyList(),
	val pendingPhotos: List<FlightPhotoAttachment> = emptyList(),
	val selectedPhotoId: String? = null,
	val journeyMessage: String? = null,
	val storageUsage: FlightStorageUsage? = null,
	val storageUsageLoading: Boolean = false,
	val photoMainCamera: Boolean = true,
	val photoSelfie: Boolean = false,
	val photoMap: Boolean = true,
	val photoScene3d: Boolean = true
)

data class FlightStorageUsage(
	val currentJournalBytes: Long = 0L,
	val currentPhotosBytes: Long = 0L,
	val allJournalBytes: Long = 0L,
	val allPhotosBytes: Long = 0L,
	val terrainBytes: Long = 0L,
	val satelliteSourceBytes: Long = 0L,
	val satelliteRenderBytes: Long = 0L,
	val graphicsBytes: Long = 0L,
	val otherBytes: Long = 0L
) {
	val currentJourneyBytes: Long
		get() = currentJournalBytes + currentPhotosBytes

	val totalBytes: Long
		get() = allJournalBytes + allPhotosBytes + terrainBytes + satelliteSourceBytes +
			satelliteRenderBytes + graphicsBytes + otherBytes
}
