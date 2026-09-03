package net.osmand.plus.plugins.flightmode

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Converts a real-valued point position into a synthetic sample.
 *
 * Positions are zero-based internally: 295.87 is displayed as point 296.87 and means 13% of
 * point 296 plus 87% of point 297. Keeping the fraction instead of rounding is important for
 * photos captured between two recorded GPS fixes.
 */
object FlightSampleInterpolator {
	fun quantizePosition(position: Double): Double = round(position * 100.0) / 100.0

	fun positionAtTimestamp(
		trip: FlightTrip?,
		timestampMillis: Long?,
		toleranceMillis: Long
	): Double? {
		if (trip == null || timestampMillis == null || !trip.hasUsableTimestamps) return null
		val timed = trip.samples.withIndex().filter { it.value.timestampMillis > 0L }
		if (timed.isEmpty()) return null
		val firstTime = timed.minOf { it.value.timestampMillis }
		val lastTime = timed.maxOf { it.value.timestampMillis }
		if (timestampMillis < firstTime - toleranceMillis || timestampMillis > lastTime + toleranceMillis) {
			return null
		}

		for (index in 0 until timed.lastIndex) {
			val lower = timed[index]
			val upper = timed[index + 1]
			val lowerTime = lower.value.timestampMillis
			val upperTime = upper.value.timestampMillis
			if (timestampMillis !in minOf(lowerTime, upperTime)..maxOf(lowerTime, upperTime)) continue
			if (lower.value.legIndex != upper.value.legIndex || lowerTime == upperTime ||
				abs(upperTime - lowerTime) > FlightReplayEngine.MAX_INTERPOLATION_GAP_MILLIS
			) {
				return nearestPosition(timestampMillis, lower, upper)
			}
			val fraction = (timestampMillis - lowerTime).toDouble() / (upperTime - lowerTime).toDouble()
			return lower.index + (upper.index - lower.index) * fraction.coerceIn(0.0, 1.0)
		}

		return timed.minByOrNull { abs(it.value.timestampMillis - timestampMillis) }?.index?.toDouble()
	}

	fun positionAtProgress(trip: FlightTrip?, requestedProgress: Float): Double? {
		val resolvedTrip = trip ?: return null
		val samples = resolvedTrip.samples
		if (samples.isEmpty()) return null
		if (samples.size == 1) return 0.0
		val progress = requestedProgress.coerceIn(0f, 1f)
		if (!resolvedTrip.hasUsableTimestamps) return (progress * samples.lastIndex).toDouble()

		val start = samples.first().timestampMillis
		val end = samples.last().timestampMillis
		val target = start + ((end - start) * progress).toLong()
		var low = 0
		var high = samples.lastIndex
		while (low <= high) {
			val middle = (low + high).ushr(1)
			if (samples[middle].timestampMillis < target) low = middle + 1 else high = middle - 1
		}
		val upperIndex = low.coerceIn(0, samples.lastIndex)
		val lowerIndex = (upperIndex - 1).coerceAtLeast(0)
		if (lowerIndex == upperIndex) return lowerIndex.toDouble()
		val lower = samples[lowerIndex]
		val upper = samples[upperIndex]
		val gapMillis = upper.timestampMillis - lower.timestampMillis
		if (gapMillis <= 0L || gapMillis > FlightReplayEngine.MAX_INTERPOLATION_GAP_MILLIS ||
			lower.legIndex != upper.legIndex
		) {
			return if (abs(target - lower.timestampMillis) <= abs(upper.timestampMillis - target)) {
				lowerIndex.toDouble()
			} else upperIndex.toDouble()
		}
		val fraction = (target - lower.timestampMillis).toDouble() / gapMillis.toDouble()
		return lowerIndex + fraction.coerceIn(0.0, 1.0)
	}

	fun sampleAt(trip: FlightTrip?, rawPosition: Double?): FlightSample? {
		val resolvedTrip = trip ?: return null
		val samples = resolvedTrip.samples
		if (samples.isEmpty() || rawPosition == null || !rawPosition.isFinite()) return null
		val position = rawPosition.coerceIn(0.0, samples.lastIndex.toDouble())
		val lowerIndex = floor(position).toInt()
		val upperIndex = ceil(position).toInt().coerceAtMost(samples.lastIndex)
		val fraction = (position - lowerIndex).toFloat().coerceIn(0f, 1f)
		val lower = samples[lowerIndex]
		val upper = samples[upperIndex]
		val crossesRecordingGap = lower.timestampMillis > 0L && upper.timestampMillis > 0L &&
			abs(upper.timestampMillis - lower.timestampMillis) > FlightReplayEngine.MAX_INTERPOLATION_GAP_MILLIS
		if (lowerIndex == upperIndex || lower.legIndex != upper.legIndex || crossesRecordingGap) {
			return if (fraction < 0.5f) lower else upper
		}
		return interpolateSamples(lower, upper, fraction)
	}

	fun progressAt(trip: FlightTrip?, position: Double?): Float? {
		val resolvedTrip = trip ?: return null
		val samples = resolvedTrip.samples
		if (samples.isEmpty() || position == null) return null
		if (samples.size == 1) return 0f
		if (!resolvedTrip.hasUsableTimestamps) {
			return (position / samples.lastIndex).toFloat().coerceIn(0f, 1f)
		}
		return sampleAt(resolvedTrip, position)?.let(resolvedTrip::progressFor)
	}

	private fun nearestPosition(
		timestampMillis: Long,
		lower: IndexedValue<FlightSample>,
		upper: IndexedValue<FlightSample>
	): Double = if (abs(timestampMillis - lower.value.timestampMillis) <=
		abs(upper.value.timestampMillis - timestampMillis)
	) lower.index.toDouble() else upper.index.toDouble()

	internal fun interpolateSamples(a: FlightSample, b: FlightSample, fraction: Float): FlightSample {
		val t = fraction.coerceIn(0f, 1f)
		val nearest = if (t < 0.5f) a else b
		return a.copy(
			index = nearest.index,
			timestampMillis = lerp(a.timestampMillis, b.timestampMillis, t),
			latitude = lerp(a.latitude, b.latitude, t),
			longitude = interpolateLongitude(a.longitude, b.longitude, t),
			altitudeMeters = lerpNullable(a.altitudeMeters, b.altitudeMeters, t),
			speedMetersPerSecond = lerpNullable(a.speedMetersPerSecond, b.speedMetersPerSecond, t),
			bearingDegrees = interpolateBearing(a.bearingDegrees, b.bearingDegrees, t),
			horizontalAccuracyMeters = lerpNullable(a.horizontalAccuracyMeters, b.horizontalAccuracyMeters, t),
			hdop = lerpNullable(a.hdop, b.hdop, t),
			satellitesUsed = nearest.satellitesUsed,
			satellitesFound = nearest.satellitesFound,
			soundDb = lerpNullable(a.soundDb, b.soundDb, t),
			soundSpectrum = interpolateSpectrum(a.soundSpectrum, b.soundSpectrum, t),
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

	private fun interpolateSpectrum(a: List<Float>?, b: List<Float>?, t: Float): List<Float>? {
		if (a == null || b == null || a.size != b.size) return if (t < 0.5f) a else b
		return a.indices.map { index -> a[index] + (b[index] - a[index]) * t }
	}

	private fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t
	private fun lerp(a: Long, b: Long, t: Float) = a + ((b - a) * t).toLong()
	private fun lerpNullable(a: Double?, b: Double?, t: Float): Double? =
		if (a != null && b != null) lerp(a, b, t) else a ?: b
	private fun lerpNullable(a: Float?, b: Float?, t: Float): Float? =
		if (a != null && b != null) a + (b - a) * t else a ?: b
}
