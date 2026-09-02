package net.osmand.plus.plugins.flightmode

import kotlin.math.max
import kotlin.math.min

enum class FlightPage {
	PREPARE,
	MAP,
	WINDOW,
	SENSORS,
	PHOTO
}

enum class FlightSessionMode {
	PREPARE,
	LIVE,
	REPLAY
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
	val totalDurationMinutes: Int
)

data class FlightHeadPose(
	val horizontalMeters: Float = 0f,
	val verticalMeters: Float = 0f,
	val distanceMeters: Float = 0.18f
) {
	fun clamped(): FlightHeadPose = copy(
		horizontalMeters = horizontalMeters.coerceIn(-MAX_HORIZONTAL_METERS, MAX_HORIZONTAL_METERS),
		verticalMeters = verticalMeters.coerceIn(-MAX_VERTICAL_METERS, MAX_VERTICAL_METERS),
		distanceMeters = distanceMeters.coerceIn(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS)
	)

	companion object {
		const val MAX_HORIZONTAL_METERS = 0.35f
		const val MAX_VERTICAL_METERS = 0.25f
		const val MIN_DISTANCE_METERS = 0.08f
		const val MAX_DISTANCE_METERS = 0.80f
	}
}

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
	val terrainStatus: FlightTerrainStatus = FlightTerrainStatus(),
	val terrainScene: FlightTerrainScene? = null,
	val headPose: FlightHeadPose = FlightHeadPose(),
	val neutralHeadPose: FlightHeadPose = FlightHeadPose(),
	val calibratingHead: Boolean = false,
	val recordingPolicy: FlightRecordingPolicy = FlightRecordingPolicy(),
	val showTrackPoints: Boolean = false,
	val photoMainCamera: Boolean = true,
	val photoSelfie: Boolean = false,
	val photoMap: Boolean = true,
	val photoScene3d: Boolean = true
)
