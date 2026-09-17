package net.osmand.plus.plugins.flightmode

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds a ground-to-ground profile between stopovers. A waypoint stays in the same
 * climb/cruise/descent block, so crossing it neither lands nor starts another climb.
 */
object FlightProfilePlanner {

	private const val EARTH_RADIUS_KM = 6_371.0088
	private const val FALLBACK_LEG_DISTANCE_KM = 650f
	private const val TYPICAL_CRUISE_SPEED_KMH = 820f
	private const val STOPOVER_MINUTES = 45

	fun build(plan: FlightPlan): FlightProfile {
		if (plan.stops.size < 2) {
			return FlightProfile(emptyList(), emptyList(), 0f, 0)
		}

		val distances = plan.stops.zipWithNext { from, to ->
			greatCircleDistanceKm(from, to) ?: FALLBACK_LEG_DISTANCE_KM
		}
		val totalDistance = distances.sum().coerceAtLeast(1f)
		val legs = mutableListOf<FlightProfileLeg>()
		val allPoints = mutableListOf<FlightProfilePoint>()
		var distanceBefore = 0f
		var totalDuration = 0

		distances.forEachIndexed { index, distanceKm ->
			val from = plan.stops[index]
			val to = plan.stops[index + 1]
			val start = distanceBefore / totalDistance
			val end = (distanceBefore + distanceKm) / totalDistance
			val blockStart = blockStartLeg(plan, index)
			val blockEnd = blockEndLeg(plan, index)
			val blockDistance = distances.subList(blockStart, blockEnd + 1).sum().coerceAtLeast(1f)
			val distanceIntoBlock = distances.subList(blockStart, index).sum()
			val cruiseAltitude = cruiseAltitudeMeters(blockDistance)
			val points = profilePointsForLeg(
				index = index,
				start = start,
				end = end,
				blockStart = distanceIntoBlock / blockDistance,
				blockEnd = (distanceIntoBlock + distanceKm) / blockDistance,
				cruiseAltitude = cruiseAltitude,
			)
			// The climb/descent allowance belongs to a ground-to-ground block, not to each
			// overflight waypoint inside it. This retains legacy timing for stopovers.
			val duration = (distanceKm / TYPICAL_CRUISE_SPEED_KMH * 60f).roundToInt() +
				if (index == blockStart) 24 else 0
			legs += FlightProfileLeg(
				index = index,
				from = from,
				to = to,
				startProgress = start,
				endProgress = end,
				distanceKm = distanceKm,
				cruiseAltitudeMeters = cruiseAltitude,
				estimatedDurationMinutes = duration,
				points = points
			)
			allPoints += points
			distanceBefore += distanceKm
			totalDuration += duration
		}

		totalDuration += (1 until plan.stops.lastIndex).count(plan::isIntermediateStopover) * STOPOVER_MINUTES
		return FlightProfile(legs, allPoints, totalDistance, totalDuration)
	}

	private fun blockStartLeg(plan: FlightPlan, legIndex: Int): Int {
		for (stopIndex in legIndex downTo 1) {
			if (plan.isIntermediateStopover(stopIndex)) return stopIndex
		}
		return 0
	}

	private fun blockEndLeg(plan: FlightPlan, legIndex: Int): Int {
		for (stopIndex in legIndex + 1 until plan.stops.lastIndex) {
			if (plan.isIntermediateStopover(stopIndex)) return stopIndex - 1
		}
		return plan.stops.lastIndex - 1
	}

	private fun profilePointsForLeg(
		index: Int,
		start: Float,
		end: Float,
		blockStart: Float,
		blockEnd: Float,
		cruiseAltitude: Float,
	): List<FlightProfilePoint> {
		val anchors = listOf(blockStart, 0.18f, 0.80f, blockEnd)
			.filter { it in blockStart..blockEnd }
			.distinct()
		return anchors.map { blockProgress ->
			val legProgress = if (blockEnd == blockStart) 0f else
				(blockProgress - blockStart) / (blockEnd - blockStart)
			FlightProfilePoint(
				progress = start + (end - start) * legProgress,
				altitudeMeters = altitudeAt(blockProgress, cruiseAltitude),
				legIndex = index,
			)
		}
	}

	private fun altitudeAt(progress: Float, cruiseAltitude: Float): Float = when {
		progress < 0.18f -> cruiseAltitude * (progress / 0.18f)
		progress > 0.80f -> cruiseAltitude * ((1f - progress) / 0.20f)
		else -> cruiseAltitude
	}

	fun fromTrip(trip: FlightTrip): FlightProfile {
		if (trip.samples.isEmpty()) return FlightProfile(emptyList(), emptyList(), 0f, 0)
		val sourceLegs = trip.legs.ifEmpty {
			listOf(
				FlightLeg(
					index = 0,
					name = "",
					startSampleIndex = 0,
					endSampleIndex = trip.samples.lastIndex,
					distanceMeters = trip.totalDistanceMeters,
					startTimeMillis = if (trip.hasUsableTimestamps) trip.samples.first().timestampMillis else null,
					endTimeMillis = if (trip.hasUsableTimestamps) trip.samples.last().timestampMillis else null
				)
			)
		}
		val profileLegs = sourceLegs.map { leg ->
			val points = trip.samples.subList(leg.startSampleIndex, leg.endSampleIndex + 1).map { sample ->
				FlightProfilePoint(
					progress = trip.progressFor(sample),
					altitudeMeters = sample.altitudeMeters?.toFloat() ?: 0f,
					legIndex = leg.index
				)
			}
			val durationMinutes = if (leg.startTimeMillis != null && leg.endTimeMillis != null) {
				((leg.endTimeMillis - leg.startTimeMillis) / 60_000.0).roundToInt().coerceAtLeast(0)
			} else 0
			FlightProfileLeg(
				index = leg.index,
				from = FlightStop(""),
				to = FlightStop(""),
				startProgress = points.firstOrNull()?.progress ?: 0f,
				endProgress = points.lastOrNull()?.progress ?: 0f,
				distanceKm = (leg.distanceMeters / 1_000.0).toFloat(),
				cruiseAltitudeMeters = points.maxOfOrNull { it.altitudeMeters } ?: 0f,
				estimatedDurationMinutes = durationMinutes,
				points = points
			)
		}
		return FlightProfile(
			legs = profileLegs,
			points = profileLegs.flatMap { it.points },
			totalDistanceKm = (trip.totalDistanceMeters / 1_000.0).toFloat(),
			totalDurationMinutes = ((trip.durationMillis ?: 0L) / 60_000.0).roundToInt(),
			recorded = true
		)
	}

	private fun cruiseAltitudeMeters(distanceKm: Float): Float = when {
		distanceKm < 250f -> 7_000f
		distanceKm < 600f -> 9_200f
		distanceKm < 1_400f -> 10_700f
		else -> 11_600f
	}

	private fun greatCircleDistanceKm(from: FlightStop, to: FlightStop): Float? {
		val lat1 = from.latitude ?: return null
		val lon1 = from.longitude ?: return null
		val lat2 = to.latitude ?: return null
		val lon2 = to.longitude ?: return null
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val rLat1 = Math.toRadians(lat1)
		val rLat2 = Math.toRadians(lat2)
		val a = sin(dLat / 2) * sin(dLat / 2) +
			cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
		return (2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))).toFloat()
	}
}
