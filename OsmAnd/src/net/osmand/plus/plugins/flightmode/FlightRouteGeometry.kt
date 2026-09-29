package net.osmand.plus.plugins.flightmode

import kotlin.math.ceil

/**
 * Source position also interpolates the map's fallback heights when recorded altitude is absent.
 */
internal data class FlightRouteVertex(val sourcePosition: Double, val sample: FlightSample)

internal data class FlightRoutePath(val isGap: Boolean, val vertices: List<FlightRouteVertex>)

/** Build once per trip change, never per replay frame. Red curves must not be decimated again. */
internal fun flightRoutePaths(
    samples: List<FlightSample>,
    recorded: Boolean = true,
    maximumMeasuredVertices: Int = 4_000,
): List<FlightRoutePath> {
    val gaps = if (recorded) flightGpsGaps(samples) else emptyList()
    val trajectories = FlightGapTrajectory.buildAll(samples, gaps)
    return flightRouteSegments(samples, gaps, recorded).map { segment ->
        val range = segment.range
        val vertices =
            if (segment.isGap) {
                buildList {
                    add(FlightRouteVertex(range.first.toDouble(), samples[range.first]))
                    for (edge in range.first until range.last) {
                        val trajectory = trajectories.getValue(edge)
                        for (vertex in trajectory.vertices.drop(1)) {
                            add(FlightRouteVertex(edge + vertex.fraction, vertex.sample))
                        }
                    }
                }
            } else {
                val step =
                    ceil(
                            (range.last - range.first) /
                                (maximumMeasuredVertices.coerceAtLeast(2) - 1).toDouble()
                        )
                        .toInt()
                        .coerceAtLeast(1)
                buildList {
                    for (index in range step step) add(
                        FlightRouteVertex(index.toDouble(), samples[index])
                    )
                    if (last().sourcePosition != range.last.toDouble()) {
                        add(FlightRouteVertex(range.last.toDouble(), samples[range.last]))
                    }
                }
            }
        FlightRoutePath(segment.isGap, vertices)
    }
}
