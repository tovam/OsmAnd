package net.osmand.plus.plugins.flightmode

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds one complete climb/cruise/descent profile per leg. A stopover therefore
 * always reaches ground level before the next leg starts climbing.
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
			val span = end - start
			val cruiseAltitude = cruiseAltitudeMeters(distanceKm)
			val climbDistance = min(distanceKm * 0.34f, 170f)
			val descentDistance = min(distanceKm * 0.38f, 190f)
			val climbEnd = start + span * (climbDistance / distanceKm).coerceIn(0.18f, 0.46f)
			val descentStart = end - span * (descentDistance / distanceKm).coerceIn(0.20f, 0.48f)
			val safeDescentStart = descentStart.coerceAtLeast(climbEnd)

			val points = listOf(
				FlightProfilePoint(start, 0f, index),
				FlightProfilePoint(start + (climbEnd - start) * 0.18f, cruiseAltitude * 0.30f, index),
				FlightProfilePoint(climbEnd, cruiseAltitude, index),
				FlightProfilePoint(safeDescentStart, cruiseAltitude, index),
				FlightProfilePoint(safeDescentStart + (end - safeDescentStart) * 0.68f, cruiseAltitude * 0.28f, index),
				FlightProfilePoint(end, 0f, index)
			)
			val duration = ((distanceKm / TYPICAL_CRUISE_SPEED_KMH) * 60f + 24f).roundToInt()
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

		totalDuration += STOPOVER_MINUTES * (legs.size - 1).coerceAtLeast(0)
		return FlightProfile(legs, allPoints, totalDistance, totalDuration)
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
