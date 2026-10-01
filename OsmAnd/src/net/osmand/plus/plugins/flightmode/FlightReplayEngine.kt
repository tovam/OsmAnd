package net.osmand.plus.plugins.flightmode

import kotlin.math.abs

/**
 * Deterministic replay source shared by the map, window and sensor pages.
 * Bridges missing fixes for display only, without filling the recorded trip or its sensor data.
 * A live timeline can append a predicted future after [recordedSampleCount] measured samples.
 */
class FlightReplayEngine(private val trip: FlightTrip, recordedSampleCount: Int = trip.samples.size) {

	private val recordedCount = recordedSampleCount.coerceIn(0, trip.samples.size)
	// Compute the same red-edge policy as the route/profile once, not on every scrub frame.
	private val gapTrajectories = FlightGapTrajectory.buildAll(trip.samples, recordedSampleCount = recordedCount)

	fun snapshotAt(requestedProgress: Float): FlightSnapshot {
		val progress = flightProgressOutsideIgnoredTime(trip,
			(requestedProgress.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f), true)
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
		if (lowerIndex == upperIndex || fraction == 0f) return FlightSnapshot(lower, progress)
		return between(lowerIndex, lower, upper, fraction, progress)
	}

	private fun byTimestamp(progress: Float): FlightSnapshot {
		val samples = trip.samples
		val start = samples.first().timestampMillis
		val end = samples.last().timestampMillis
		val target = start + ((end - start).toDouble() * progress).toLong()
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
		if (target == upper.timestampMillis) return FlightSnapshot(upper, progress)
		if (target == lower.timestampMillis) return FlightSnapshot(lower, progress)
		if (upper.excludedBefore) {
			val nearest = if (abs(target - lower.timestampMillis) < abs(upper.timestampMillis - target)) lower else upper
			return FlightSnapshot(nearest, trip.progressFor(nearest))
		}

		val gapMillis = upper.timestampMillis - lower.timestampMillis
		if (gapMillis <= 0L) {
			val nearest = if (abs(target - lower.timestampMillis) <= abs(upper.timestampMillis - target)) lower else upper
			return FlightSnapshot(nearest, progress)
		}
		val fraction = ((target - lower.timestampMillis).toDouble() / gapMillis).toFloat()
		val snapshot = between(lowerIndex, lower, upper, fraction, progress)
		// The shared Float interpolator may round milliseconds; keep the playhead's actual time.
		return snapshot.copy(sample = snapshot.sample.copy(timestampMillis = target))
	}

	private fun between(
		lowerIndex: Int, lower: FlightSample, upper: FlightSample, fraction: Float, progress: Float
	): FlightSnapshot {
		val dataGap = lowerIndex + 1 < recordedCount && (
			lowerIndex in gapTrajectories || lower.legIndex != upper.legIndex ||
			upper.timestampMillis - lower.timestampMillis > MAX_INTERPOLATION_GAP_MILLIS
		)
		val sample = gapTrajectories[lowerIndex]?.sampleAt(fraction.toDouble())
			?: FlightSampleInterpolator.interpolateSamples(lower, upper, fraction)
		return FlightSnapshot(
			// Position, altitude, speed and heading are display estimates. Accuracy, GPS counts
			// and other sensor measurements are unknown between the real endpoints of a gap.
			if (dataGap) sample.copy(
				horizontalAccuracyMeters = null,
				hdop = null,
				satellitesUsed = null,
				satellitesFound = null,
				soundDb = null,
				soundSpectrum = null,
				vibrationHz = null
			) else sample,
			progress,
			dataGap = dataGap,
			interpolated = true
		)
	}

	companion object {
		// Conservative legacy threshold also used by photo association; it no longer blocks replay.
		const val MAX_INTERPOLATION_GAP_MILLIS = 120_000L
	}
}
