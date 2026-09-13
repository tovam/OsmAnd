package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Planned geography is a hypothesis, separate from every measured sample and its altitude. */
object FlightRouteHypothesis {
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

    private fun coordinates(plan: FlightPlan) =
        plan.stops.mapNotNull { s -> s.latitude?.let { a -> s.longitude?.let { a to it } } }

    fun distanceToPlanKm(plan: FlightPlan, sample: FlightSample): Double? =
        coordinates(plan).zipWithNext().minOfOrNull { (a, b) ->
            legDistance(a, b, sample.latitude to sample.longitude)
        }

    fun remaining(plan: FlightPlan, sample: FlightSample): List<FlightSample> {
        val stops = coordinates(plan)
        if (stops.size < 2) return emptyList()
        val at = sample.latitude to sample.longitude
        val leg =
            stops.zipWithNext().indices.minByOrNull { i -> legDistance(stops[i], stops[i + 1], at) }
                ?: 0
        val targets = listOf(at) + stops.drop(leg + 1)
        return targets.zipWithNext().flatMap { (a, b) ->
            val count = ceil(distance(a, b) / 20).toInt().coerceIn(1, 200)
            (0..count).map { i ->
                val p = FlightTerrainTilePlanner.greatCircleInterpolate(a, b, i.toDouble() / count)
                sample.copy(latitude = p.first, longitude = p.second)
            }
        }
    }
}
