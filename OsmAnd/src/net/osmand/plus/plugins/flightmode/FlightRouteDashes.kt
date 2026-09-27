package net.osmand.plus.plugins.flightmode

import kotlin.math.max

/** Constant arclength dashes; at most 96 native meshes irrespective of track size. */
internal fun flightRouteDashes(route: List<FlightSample>): List<List<FlightSample>> {
    if (route.size < 2) return emptyList()
    val lengths =
        route.zipWithNext().map { (a, b) ->
            FlightTerrainTilePlanner.distanceKm(a.latitude, a.longitude, b.latitude, b.longitude) *
                1000.0
        }
    val total = lengths.sum()
    if (!total.isFinite() || total <= 0.0) return emptyList()
    val period = max(1000.0, total / 96.0)
    val result = mutableListOf<List<FlightSample>>()
    var start = 0.0
    var segment = 0
    var segmentStart = 0.0
    while (start < total && result.size < 96) {
        val end = minOf(total, start + period * 0.62)
        while (segment < lengths.lastIndex && segmentStart + lengths[segment] < start) {
            segmentStart += lengths[segment++]
        }
        val dash = mutableListOf<FlightSample>()
        var cursor = segment
        var cursorStart = segmentStart
        while (cursor < lengths.size && cursorStart <= end) {
            val length = lengths[cursor]
            if (length > 0) {
                val from = ((start - cursorStart) / length).coerceIn(0.0, 1.0).toFloat()
                val to = ((end - cursorStart) / length).coerceIn(0.0, 1.0).toFloat()
                if (to > from) {
                    if (dash.isEmpty())
                        dash +=
                            FlightSampleInterpolator.interpolateSamples(
                                route[cursor],
                                route[cursor + 1],
                                from,
                            )
                    dash +=
                        FlightSampleInterpolator.interpolateSamples(
                            route[cursor],
                            route[cursor + 1],
                            to,
                        )
                }
            }
            cursorStart += length
            cursor++
        }
        if (dash.size >= 2) result += dash
        start += period
    }
    return result
}
