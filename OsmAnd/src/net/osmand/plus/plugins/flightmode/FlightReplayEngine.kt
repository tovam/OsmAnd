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
		return FlightSnapshot(
			FlightSampleInterpolator.interpolateSamples(lower, upper, fraction),
			progress,
			interpolated = true
		)
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
		return FlightSnapshot(
			FlightSampleInterpolator.interpolateSamples(lower, upper, fraction),
			progress,
			interpolated = true
		)
	}

	companion object {
		const val MAX_INTERPOLATION_GAP_MILLIS = 120_000L
	}
}
