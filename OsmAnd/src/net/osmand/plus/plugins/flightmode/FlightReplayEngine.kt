package net.osmand.plus.plugins.flightmode

import kotlin.math.abs

/**
 * Deterministic replay source shared by the map, window and sensor pages.
 * It never invents data across a long recording gap.
 */
class FlightReplayEngine(private val trip: FlightTrip) {

	fun snapshotAt(requestedProgress: Float): FlightSnapshot {
		val progress = requestedProgress.coerceIn(0f, 1f)
		val samples = trip.samples
		require(samples.isNotEmpty()) { "A flight replay needs at least one sample" }
		if (samples.size == 1) return FlightSnapshot(samples.first(), progress)

		return if (trip.hasUsableTimestamps) {
			byTimestamp(progress)
		} else {
			byPointIndex(progress)
		}
	}

	private fun byPointIndex(progress: Float): FlightSnapshot {
		val exact = progress * (trip.samples.size - 1)
		val lowerIndex = exact.toInt().coerceIn(0, trip.samples.lastIndex)
		val upperIndex = (lowerIndex + 1).coerceAtMost(trip.samples.lastIndex)
		val fraction = exact - lowerIndex
		val lower = trip.samples[lowerIndex]
		val upper = trip.samples[upperIndex]
		if (lowerIndex == upperIndex || lower.legIndex != upper.legIndex) {
			return FlightSnapshot(if (fraction < 0.5f) lower else upper, progress)
		}
		return FlightSnapshot(interpolate(lower, upper, fraction), progress, interpolated = true)
	}

	private fun byTimestamp(progress: Float): FlightSnapshot {
		val samples = trip.samples
		val start = samples.first().timestampMillis
		val end = samples.last().timestampMillis
		val target = start + ((end - start) * progress).toLong()
		var low = 0
		var high = samples.lastIndex
		while (low <= high) {
			val mid = (low + high).ushr(1)
			if (samples[mid].timestampMillis < target) low = mid + 1 else high = mid - 1
		}
		val upperIndex = low.coerceIn(0, samples.lastIndex)
		val lowerIndex = (upperIndex - 1).coerceAtLeast(0)
		val lower = samples[lowerIndex]
		val upper = samples[upperIndex]
		if (lowerIndex == upperIndex) return FlightSnapshot(lower, progress)

		val gapMillis = upper.timestampMillis - lower.timestampMillis
		val crossesLeg = lower.legIndex != upper.legIndex
		if (gapMillis <= 0L || gapMillis > MAX_INTERPOLATION_GAP_MILLIS || crossesLeg) {
			val nearest = if (abs(target - lower.timestampMillis) <= abs(upper.timestampMillis - target)) lower else upper
			return FlightSnapshot(nearest, progress, dataGap = gapMillis > MAX_INTERPOLATION_GAP_MILLIS)
		}
		val fraction = (target - lower.timestampMillis).toFloat() / gapMillis
		return FlightSnapshot(interpolate(lower, upper, fraction), progress, interpolated = true)
	}

	private fun interpolate(a: FlightSample, b: FlightSample, fraction: Float): FlightSample {
		val t = fraction.coerceIn(0f, 1f)
		return a.copy(
			index = if (t < 0.5f) a.index else b.index,
			timestampMillis = lerp(a.timestampMillis, b.timestampMillis, t),
			latitude = lerp(a.latitude, b.latitude, t),
			longitude = interpolateLongitude(a.longitude, b.longitude, t),
			altitudeMeters = lerpNullable(a.altitudeMeters, b.altitudeMeters, t),
			speedMetersPerSecond = lerpNullable(a.speedMetersPerSecond, b.speedMetersPerSecond, t),
			bearingDegrees = interpolateBearing(a.bearingDegrees, b.bearingDegrees, t),
			horizontalAccuracyMeters = lerpNullable(a.horizontalAccuracyMeters, b.horizontalAccuracyMeters, t),
			hdop = lerpNullable(a.hdop, b.hdop, t),
			soundDb = lerpNullable(a.soundDb, b.soundDb, t),
			vibrationHz = lerpNullable(a.vibrationHz, b.vibrationHz, t)
		)
	}

	private fun interpolateLongitude(a: Double, b: Double, t: Float): Double {
		var delta = b - a
		if (delta > 180.0) delta -= 360.0
		if (delta < -180.0) delta += 360.0
		val value = a + delta * t
		return when {
			value > 180.0 -> value - 360.0
			value < -180.0 -> value + 360.0
			else -> value
		}
	}

	private fun interpolateBearing(a: Float?, b: Float?, t: Float): Float? {
		if (a == null || b == null) return a ?: b
		var delta = b - a
		if (delta > 180f) delta -= 360f
		if (delta < -180f) delta += 360f
		return (a + delta * t + 360f) % 360f
	}

	private fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t
	private fun lerp(a: Long, b: Long, t: Float) = a + ((b - a) * t).toLong()
	private fun lerpNullable(a: Double?, b: Double?, t: Float): Double? =
		if (a != null && b != null) lerp(a, b, t) else a ?: b
	private fun lerpNullable(a: Float?, b: Float?, t: Float): Float? =
		if (a != null && b != null) a + (b - a) * t else a ?: b

	companion object {
		const val MAX_INTERPOLATION_GAP_MILLIS = 120_000L
	}
}
