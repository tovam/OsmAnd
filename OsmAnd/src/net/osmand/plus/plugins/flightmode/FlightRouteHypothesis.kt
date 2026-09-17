package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Planned geography is a hypothesis, separate from every measured sample and its altitude. */
object FlightRouteHypothesis {
	internal data class RemainingStop(val index: Int, val stop: FlightStop)

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>) =
        FlightTerrainTilePlanner.distanceKm(a.first, a.second, b.first, b.second)

    private fun legDistance(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        p: Pair<Double, Double>,
    ): Double {
        // Bounded great-circle search avoids planar longitude errors at the date line/high
        // latitudes.
        var lo = 0.0
        var hi = 1.0
        repeat(20) {
            val m1 = (2 * lo + hi) / 3
            val m2 = (lo + 2 * hi) / 3
            if (
                distance(FlightTerrainTilePlanner.greatCircleInterpolate(a, b, m1), p) <
                    distance(FlightTerrainTilePlanner.greatCircleInterpolate(a, b, m2), p)
            )
                hi = m2
            else lo = m1
        }
        return min(
            min(distance(a, p), distance(b, p)),
            distance(FlightTerrainTilePlanner.greatCircleInterpolate(a, b, (lo + hi) / 2), p),
        )
    }

    /** A partially resolved route is not a different route with that stop removed. */
    private fun coordinates(plan: FlightPlan): List<Pair<Double, Double>>? {
        if (plan.stops.size < 2) return null
        return plan.stops.map { stop ->
            val latitude = stop.latitude ?: return null
            val longitude = stop.longitude ?: return null
            latitude to longitude
        }
    }

    fun distanceToPlanKm(plan: FlightPlan, sample: FlightSample): Double? =
        coordinates(plan)?.zipWithNext()?.minOfOrNull { (a, b) ->
            legDistance(a, b, sample.latitude to sample.longitude)
        }

    /** Stops after the deterministically nearest current segment, including its destination. */
    internal fun remainingStops(plan: FlightPlan, sample: FlightSample): List<RemainingStop> {
		if (plan.stops.size == 1) {
			val destination = plan.stops.single()
			return if (destination.latitude != null && destination.longitude != null)
				listOf(RemainingStop(0, destination)) else emptyList()
		}
        val stops = coordinates(plan) ?: return emptyList()
        val at = sample.latitude to sample.longitude
        var closestLeg = 0
        var closestDistance = legDistance(stops[0], stops[1], at)
        for (index in 1 until stops.lastIndex) {
            val candidate = legDistance(stops[index], stops[index + 1], at)
            if (candidate < closestDistance) {
                closestLeg = index
                closestDistance = candidate
            }
        }
        return (closestLeg + 1..plan.stops.lastIndex).map { index ->
            RemainingStop(index, plan.stops[index])
        }
    }

    fun remaining(plan: FlightPlan, sample: FlightSample): List<FlightSample> {
        val stops = remainingStops(plan, sample)
        if (stops.isEmpty()) return emptyList()
        val at = sample.latitude to sample.longitude
        val targets = listOf(at) + stops.map { requireNotNull(it.stop.latitude) to requireNotNull(it.stop.longitude) }
        return targets.zipWithNext().flatMap { (a, b) ->
            val count = ceil(distance(a, b) / 20).toInt().coerceIn(1, 200)
            (0..count).map { i ->
                val p = FlightTerrainTilePlanner.greatCircleInterpolate(a, b, i.toDouble() / count)
                sample.copy(latitude = p.first, longitude = p.second)
            }
        }
    }
}
