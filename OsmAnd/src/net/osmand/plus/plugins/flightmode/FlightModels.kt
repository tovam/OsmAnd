package net.osmand.plus.plugins.flightmode

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

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
	ULTRA(2),
	ULTRA_PLUS(3)
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
		const val MIN_VERTICAL_FIELD_OF_VIEW_DEGREES = 14f
		const val MAX_VERTICAL_FIELD_OF_VIEW_DEGREES = 145f
		const val MIN_FORWARD_OFFSET_METERS = -0.90f
		const val MAX_FORWARD_OFFSET_METERS = 1.10f
		const val MIN_VERTICAL_OFFSET_METERS = -0.55f
		const val MAX_VERTICAL_OFFSET_METERS = 0.55f
		const val MIN_ZOOM = 0.36f
		const val MAX_ZOOM = 4f
	}
}

fun FlightWindowPlacement.verticalFieldOfViewDegrees(): Float =
	(FlightWindowPlacement.DEFAULT_VERTICAL_FIELD_OF_VIEW_DEGREES / clamped().zoom).coerceIn(
		FlightWindowPlacement.MIN_VERTICAL_FIELD_OF_VIEW_DEGREES,
		FlightWindowPlacement.MAX_VERTICAL_FIELD_OF_VIEW_DEGREES
	)

fun FlightWindowPlacement.horizontalFieldOfViewDegrees(viewAspectRatio: Float): Float {
	val verticalRadians = Math.toRadians(verticalFieldOfViewDegrees().toDouble())
	val horizontalRadians = 2.0 * atan(tan(verticalRadians / 2.0) * viewAspectRatio.coerceIn(0.25f, 4f))
	return Math.toDegrees(horizontalRadians).toFloat().coerceIn(8f, 170f)
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
	val terrainFineZoom: Int = DEFAULT_TERRAIN_FINE_ZOOM,
	val terrainMiddleZoom: Int = DEFAULT_TERRAIN_MIDDLE_ZOOM,
	val satelliteQuality: FlightSatelliteQuality = FlightSatelliteQuality.HIGH,
	val shadowsEnabled: Boolean = true,
	val shadowIntensity: Float = 0.85f,
	val resumeAfterRestart: Boolean = true
) {
	companion object {
		const val MIN_TERRAIN_DETAIL_ZOOM = 9
		const val MAX_TERRAIN_DETAIL_ZOOM = 12
		const val DEFAULT_TERRAIN_FINE_ZOOM = 12
		const val DEFAULT_TERRAIN_MIDDLE_ZOOM = 11

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

enum class FlightPhotoTimestampSource {
	EXIF,
	MEDIA_CAPTURE,
	FILE_NAME,
	FILE_MODIFIED,
	FILE_ADDED,
	LIVE_CAPTURE
}

data class FlightPhotoAttachment(
	val id: String,
	val fileName: String,
	val localPath: String,
	val timestampMillis: Long?,
	/** Zero-based, continuous point position. The UI displays this value plus one. */
	val matchedSamplePosition: Double?,
	val timestampSource: FlightPhotoTimestampSource? = null,
	/** Vertical camera field of view inferred from EXIF, after applying EXIF orientation. */
	val cameraVerticalFieldOfViewDegrees: Float? = null,
	val rotationDegrees: Float = 0f,
	val imageAdjustments: FlightPhotoImageAdjustments = FlightPhotoImageAdjustments(),
	val includeMainCamera: Boolean = true,
	val includeSelfie: Boolean = false,
	val includeMap: Boolean = true,
	val includeScene3d: Boolean = true,
	val windowAlignment: FlightPhotoWindowAlignment? = null
)

/** Non-destructive colour adjustments stored beside the original photo. */
data class FlightPhotoImageAdjustments(
	val brightness: Float = 0f,
	val contrast: Float = 0f,
	/** Blue (-1) to warm/orange (+1). */
	val temperature: Float = 0f,
	/** Green (-1) to magenta (+1). */
	val tint: Float = 0f,
	val saturation: Float = 0f
) {
	fun clamped(): FlightPhotoImageAdjustments = copy(
		brightness = brightness.finiteUnitValue(),
		contrast = contrast.finiteUnitValue(),
		temperature = temperature.finiteUnitValue(),
		tint = tint.finiteUnitValue(),
		saturation = saturation.finiteUnitValue()
	)

	fun isNeutral(): Boolean = this == FlightPhotoImageAdjustments()

	private fun Float.finiteUnitValue(): Float = takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
}

/** Builds the 4×5 colour matrix consumed directly by the GPU-backed Compose image layer. */
object FlightPhotoColorMatrix {

	fun values(adjustments: FlightPhotoImageAdjustments): FloatArray {
		val safe = adjustments.clamped()
		val saturation = 1f + safe.saturation
		val inverseSaturation = 1f - saturation
		var result = floatArrayOf(
			LUMA_RED * inverseSaturation + saturation,
			LUMA_GREEN * inverseSaturation,
			LUMA_BLUE * inverseSaturation,
			0f,
			0f,
			LUMA_RED * inverseSaturation,
			LUMA_GREEN * inverseSaturation + saturation,
			LUMA_BLUE * inverseSaturation,
			0f,
			0f,
			LUMA_RED * inverseSaturation,
			LUMA_GREEN * inverseSaturation,
			LUMA_BLUE * inverseSaturation + saturation,
			0f,
			0f,
			0f, 0f, 0f, 1f, 0f
		)

		val redScale = (1f + 0.20f * safe.temperature + 0.08f * safe.tint).coerceIn(0.65f, 1.35f)
		val greenScale = (1f - 0.04f * kotlin.math.abs(safe.temperature) - 0.16f * safe.tint)
			.coerceIn(0.65f, 1.35f)
		val blueScale = (1f - 0.20f * safe.temperature + 0.08f * safe.tint).coerceIn(0.65f, 1.35f)
		result = multiply(
			floatArrayOf(
				redScale, 0f, 0f, 0f, 0f,
				0f, greenScale, 0f, 0f, 0f,
				0f, 0f, blueScale, 0f, 0f,
				0f, 0f, 0f, 1f, 0f
			),
			result
		)

		val contrastScale = 1f + safe.contrast * 0.75f
		val offset = 128f * (1f - contrastScale) + safe.brightness * 96f
		return multiply(
			floatArrayOf(
				contrastScale, 0f, 0f, 0f, offset,
				0f, contrastScale, 0f, 0f, offset,
				0f, 0f, contrastScale, 0f, offset,
				0f, 0f, 0f, 1f, 0f
			),
			result
		)
	}

	/** Returns [after] × [before], including the constant fifth column. */
	private fun multiply(after: FloatArray, before: FloatArray): FloatArray {
		val result = FloatArray(MATRIX_VALUES)
		for (row in 0 until 4) {
			for (column in 0 until 4) {
				var value = 0f
				for (index in 0 until 4) {
					value += after[row * 5 + index] * before[index * 5 + column]
				}
				result[row * 5 + column] = value
			}
			var offset = after[row * 5 + 4]
			for (index in 0 until 4) {
				offset += after[row * 5 + index] * before[index * 5 + 4]
			}
			result[row * 5 + 4] = offset
		}
		return result
	}

	private const val MATRIX_VALUES = 20
	private const val LUMA_RED = 0.213f
	private const val LUMA_GREEN = 0.715f
	private const val LUMA_BLUE = 0.072f
}

enum class FlightWindowGestureTarget {
	VIEW,
	PHOTO,
	/** Move the virtual camera while keeping the calibrated photo registered to it. */
	LINKED
}

data class FlightWindowPhotoOverlay(
	val photoId: String? = null,
	val opacity: Float = 0.55f,
	val scale: Float = 1f,
	val offsetXFraction: Float = 0f,
	val offsetYFraction: Float = 0f,
	val gestureTarget: FlightWindowGestureTarget = FlightWindowGestureTarget.VIEW
) {
	fun clamped(): FlightWindowPhotoOverlay = copy(
		opacity = opacity.coerceIn(0f, 1f),
		scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
		offsetXFraction = offsetXFraction.coerceIn(-MAX_OFFSET_FRACTION, MAX_OFFSET_FRACTION),
		offsetYFraction = offsetYFraction.coerceIn(-MAX_OFFSET_FRACTION, MAX_OFFSET_FRACTION)
	)

	companion object {
		const val MIN_SCALE = 0.25f
		const val MAX_SCALE = 8f
		const val MAX_OFFSET_FRACTION = 2f
	}
}

/**
 * Everything required to reopen a photo over the exact same Hublot camera view.
 * The photo rotation itself remains on [FlightPhotoAttachment] because it is also
 * used by the gallery.
 */
data class FlightPhotoWindowAlignment(
	val opacity: Float = 0.55f,
	val scale: Float = 1f,
	val offsetXFraction: Float = 0f,
	val offsetYFraction: Float = 0f,
	val windowPlacement: FlightWindowPlacement = FlightWindowPlacement(),
	val windowLook: FlightWindowLook = FlightWindowLook(),
	val altitudeOverrideMeters: Float? = null,
	/**
	 * Absolute snapshot derived from the authoritative trip-relative calibration.
	 * Keeping both forms lets a corrected track rebuild the pose while an exported
	 * journal still knows exactly where the eye and optical axis were in WGS84.
	 */
	val spatialPose: FlightPhotoSpatialPose? = null
) {
	fun clamped(): FlightPhotoWindowAlignment {
		val safeOverlay = FlightWindowPhotoOverlay(
			opacity = opacity.takeIf(Float::isFinite) ?: 0.55f,
			scale = scale.takeIf(Float::isFinite) ?: 1f,
			offsetXFraction = offsetXFraction.takeIf(Float::isFinite) ?: 0f,
			offsetYFraction = offsetYFraction.takeIf(Float::isFinite) ?: 0f
		).clamped()
		val safePlacement = windowPlacement.copy(
			forwardOffsetMeters = windowPlacement.forwardOffsetMeters.takeIf(Float::isFinite) ?: 0f,
			verticalOffsetMeters = windowPlacement.verticalOffsetMeters.takeIf(Float::isFinite) ?: 0f,
			zoom = windowPlacement.zoom.takeIf(Float::isFinite) ?: 1f
		).clamped()
		val safeLook = windowLook.copy(
			yawDegrees = windowLook.yawDegrees.takeIf(Float::isFinite) ?: 0f,
			pitchDegrees = windowLook.pitchDegrees.takeIf(Float::isFinite) ?: 0f
		).clamped()
		return copy(
			opacity = safeOverlay.opacity,
			scale = safeOverlay.scale,
			offsetXFraction = safeOverlay.offsetXFraction,
			offsetYFraction = safeOverlay.offsetYFraction,
			windowPlacement = safePlacement,
			windowLook = safeLook,
			altitudeOverrideMeters = altitudeOverrideMeters?.takeIf(Float::isFinite)
				?.coerceIn(MIN_ALTITUDE_OVERRIDE_METERS, MAX_ALTITUDE_OVERRIDE_METERS),
			spatialPose = spatialPose?.clampedOrNull()
		)
	}

	companion object {
		const val MIN_ALTITUDE_OVERRIDE_METERS = -500f
		const val MAX_ALTITUDE_OVERRIDE_METERS = 15_000f
	}
}

/** A reproducible world-space camera pose associated with one calibrated photo. */
data class FlightPhotoSpatialPose(
	/** Zero-based continuous position in the journal track. */
	val samplePosition: Double,
	val timestampMillis: Long?,
	val eyeLatitude: Double,
	val eyeLongitude: Double,
	val eyeAltitudeMeters: Float?,
	val aircraftBearingDegrees: Float,
	val viewAzimuthDegrees: Float,
	val viewElevationDegrees: Float,
	val verticalFieldOfViewDegrees: Float
) {
	fun clampedOrNull(): FlightPhotoSpatialPose? {
		if (!samplePosition.isFinite() || samplePosition < 0.0 ||
			!eyeLatitude.isFinite() || eyeLatitude !in -90.0..90.0 ||
			!eyeLongitude.isFinite() ||
			!aircraftBearingDegrees.isFinite() || !viewAzimuthDegrees.isFinite() ||
			!viewElevationDegrees.isFinite() || !verticalFieldOfViewDegrees.isFinite()
		) return null
		return copy(
			eyeLongitude = normalizeLongitude(eyeLongitude),
			eyeAltitudeMeters = eyeAltitudeMeters?.takeIf(Float::isFinite)
				?.coerceIn(FlightPhotoWindowAlignment.MIN_ALTITUDE_OVERRIDE_METERS, MAXIMUM_PHOTO_EYE_ALTITUDE_METERS),
			aircraftBearingDegrees = normalizeDegrees(aircraftBearingDegrees),
			viewAzimuthDegrees = normalizeDegrees(viewAzimuthDegrees),
			viewElevationDegrees = viewElevationDegrees.coerceIn(-90f, 90f),
			verticalFieldOfViewDegrees = verticalFieldOfViewDegrees.coerceIn(
				FlightWindowPlacement.MIN_VERTICAL_FIELD_OF_VIEW_DEGREES,
				FlightWindowPlacement.MAX_VERTICAL_FIELD_OF_VIEW_DEGREES
			)
		)
	}

	private fun normalizeDegrees(value: Float): Float {
		val normalized = value % 360f
		return if (normalized < 0f) normalized + 360f else normalized
	}

	private fun normalizeLongitude(value: Double): Double {
		var normalized = value % 360.0
		if (normalized > 180.0) normalized -= 360.0
		if (normalized < -180.0) normalized += 360.0
		return normalized
	}

	companion object {
		private const val MAXIMUM_PHOTO_EYE_ALTITUDE_METERS = 100_000f
	}
}

/** Halves a pinch's logarithmic zoom delta, symmetrically for zoom-in and zoom-out. */
fun dampedFlightPinchFactor(rawFactor: Float): Float =
	if (rawFactor.isFinite() && rawFactor > 0f) sqrt(rawFactor) else 1f

data class FlightWindowLinkedTransform(
	val placement: FlightWindowPlacement,
	val look: FlightWindowLook,
	val photoOverlay: FlightWindowPhotoOverlay
)

/**
 * Applies one gesture to the 3D camera and its calibrated photo as a single unit.
 *
 * The photo scale follows the actual perspective projection rather than the raw
 * pinch factor. This matters at wide angles: halving a FOV in degrees does not
 * exactly double its projected size. The photo translation follows only the
 * camera movement that survived the vertical look limits.
 */
fun linkedFlightWindowTransform(
	placement: FlightWindowPlacement,
	look: FlightWindowLook,
	photoOverlay: FlightWindowPhotoOverlay,
	panXFraction: Float,
	panYFraction: Float,
	rawZoomFactor: Float,
	viewAspectRatio: Float
): FlightWindowLinkedTransform {
	val safePlacement = placement.clamped()
	val safeLook = look.clamped()
	val safeOverlay = photoOverlay.clamped()
	val safePanX = panXFraction.takeIf(Float::isFinite) ?: 0f
	val safePanY = panYFraction.takeIf(Float::isFinite) ?: 0f
	val verticalFovBefore = safePlacement.verticalFieldOfViewDegrees()
	val horizontalFovBefore = safePlacement.horizontalFieldOfViewDegrees(viewAspectRatio)
	val nextLook = safeLook.copy(
		yawDegrees = safeLook.yawDegrees - safePanX * horizontalFovBefore,
		pitchDegrees = safeLook.pitchDegrees + safePanY * verticalFovBefore
	).clamped()
	val realizedYawDelta = FlightWindowLook.normalizeYaw(nextLook.yawDegrees - safeLook.yawDegrees)
	val realizedPitchDelta = nextLook.pitchDegrees - safeLook.pitchDegrees

	val dampedZoom = dampedFlightPinchFactor(rawZoomFactor).coerceIn(0.75f, 1.35f)
	val nextPlacement = safePlacement.copy(zoom = safePlacement.zoom * dampedZoom).clamped()
	val verticalFovAfter = nextPlacement.verticalFieldOfViewDegrees()
	val horizontalFovAfter = nextPlacement.horizontalFieldOfViewDegrees(viewAspectRatio)
	val projectionMagnification = (
		tan(Math.toRadians((verticalFovBefore / 2f).toDouble())) /
			tan(Math.toRadians((verticalFovAfter / 2f).toDouble()))
	).toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
	val projectedPanX = -0.5f * projectedTangentRatio(realizedYawDelta, horizontalFovAfter)
	val projectedPanY = 0.5f * projectedTangentRatio(realizedPitchDelta, verticalFovAfter)
	val nextOverlay = safeOverlay.copy(
		scale = safeOverlay.scale * projectionMagnification,
		offsetXFraction = safeOverlay.offsetXFraction * projectionMagnification + projectedPanX,
		offsetYFraction = safeOverlay.offsetYFraction * projectionMagnification + projectedPanY,
		gestureTarget = FlightWindowGestureTarget.LINKED
	).clamped()
	return FlightWindowLinkedTransform(nextPlacement, nextLook, nextOverlay)
}

private fun projectedTangentRatio(angleDegrees: Float, fieldOfViewDegrees: Float): Float {
	val safeAngle = angleDegrees.coerceIn(-85f, 85f)
	val denominator = tan(Math.toRadians((fieldOfViewDegrees / 2f).toDouble()))
	if (!denominator.isFinite() || denominator == 0.0) return 0f
	return (tan(Math.toRadians(safeAngle.toDouble())) / denominator)
		.toFloat().takeIf(Float::isFinite) ?: 0f
}

data class FlightOfflineAssets(
	val terrainTiles: List<TerrainTileId> = emptyList(),
	val standardSatelliteTiles: List<TerrainTileId> = emptyList()
) {
	val terrainTileCount: Int
		get() = terrainTiles.size

	val standardSatelliteTileCount: Int
		get() = standardSatelliteTiles.size
}

data class FlightJourney(
	val id: String,
	val name: String,
	val createdAtMillis: Long,
	val updatedAtMillis: Long,
	val plan: FlightPlan,
	val trip: FlightTrip,
	val flightSpans: List<FlightSpan>,
	val photos: List<FlightPhotoAttachment>,
	val offlineAssets: FlightOfflineAssets = FlightOfflineAssets()
)

/** Ground point receiving an additional satellite-detail ring. */
data class FlightTerrainDetailFocus(
	val latitude: Double,
	val longitude: Double
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
	val terrainRenderStats: FlightTerrainRenderStats = FlightTerrainRenderStats(),
	val offlinePreloadStatus: FlightTerrainStatus = FlightTerrainStatus(),
	val terrainScene: FlightTerrainScene? = null,
	val windowPlacement: FlightWindowPlacement = FlightWindowPlacement(),
	val windowLook: FlightWindowLook = FlightWindowLook(),
	val windowPhotoOverlay: FlightWindowPhotoOverlay = FlightWindowPhotoOverlay(),
	val windowAltitudeOverrideMeters: Float? = null,
	val terrainDetailFocus: FlightTerrainDetailFocus? = null,
	val showSatelliteQualityOverlay: Boolean = false,
	val satelliteOpacity: Float = 0.92f,
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
	val offlineAssets: FlightOfflineAssets = FlightOfflineAssets(),
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
	val currentTerrainBytes: Long = 0L,
	val currentSatelliteStandardBytes: Long = 0L,
	val allJournalBytes: Long = 0L,
	val allPhotosBytes: Long = 0L,
	val terrainBytes: Long = 0L,
	val satelliteSourceBytes: Long = 0L,
	val satelliteRenderBytes: Long = 0L,
	val nativeMapRenderBytes: Long = 0L,
	val graphicsBytes: Long = 0L,
	val otherBytes: Long = 0L
) {
	val currentJourneyBytes: Long
		get() = currentJournalBytes + currentPhotosBytes + currentTerrainBytes + currentSatelliteStandardBytes

	val totalBytes: Long
		get() = allJournalBytes + allPhotosBytes + terrainBytes + satelliteSourceBytes +
			satelliteRenderBytes + nativeMapRenderBytes + graphicsBytes + otherBytes
}
