package net.osmand.plus.plugins.flightmode

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object FlightTrackMath {

	fun bearingBetween(from: FlightSample, to: FlightSample): Float? = bearingBetween(
		from.latitude,
		from.longitude,
		to.latitude,
		to.longitude
	)

	fun bearingBetween(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): Float? {
		if (kotlin.math.abs(fromLatitude - toLatitude) < 1e-9 &&
			kotlin.math.abs(fromLongitude - toLongitude) < 1e-9
		) return null
		val latitude1 = Math.toRadians(fromLatitude)
		val latitude2 = Math.toRadians(toLatitude)
		var longitudeDeltaDegrees = toLongitude - fromLongitude
		if (longitudeDeltaDegrees > 180.0) longitudeDeltaDegrees -= 360.0
		if (longitudeDeltaDegrees < -180.0) longitudeDeltaDegrees += 360.0
		val longitudeDelta = Math.toRadians(longitudeDeltaDegrees)
		val y = sin(longitudeDelta) * cos(latitude2)
		val x = cos(latitude1) * sin(latitude2) -
			sin(latitude1) * cos(latitude2) * cos(longitudeDelta)
		return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
	}

	fun fillMissingBearings(samples: List<FlightSample>): List<FlightSample> {
		if (samples.size < 2) return samples
		return samples.mapIndexed { index, sample ->
			if (sample.bearingDegrees != null) return@mapIndexed sample
			val next = (index + 1..samples.lastIndex)
				.firstOrNull { samples[it].legIndex == sample.legIndex && !samePosition(sample, samples[it]) }
				?.let(samples::get)
			val previous = (index - 1 downTo 0)
				.firstOrNull { samples[it].legIndex == sample.legIndex && !samePosition(sample, samples[it]) }
				?.let(samples::get)
			val bearing = when {
				next != null -> bearingBetween(sample, next)
				previous != null -> bearingBetween(previous, sample)
				else -> null
			}
			sample.copy(bearingDegrees = bearing)
		}
	}

	private fun samePosition(first: FlightSample, second: FlightSample): Boolean =
		kotlin.math.abs(first.latitude - second.latitude) < 1e-9 &&
			kotlin.math.abs(first.longitude - second.longitude) < 1e-9
}
