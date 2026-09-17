package net.osmand.plus.plugins.flightmode

/** Split exactly at the measured/predicted boundary, including when it lies inside an edge. */
internal fun splitFlightProfilePoints(
    points: List<FlightProfilePoint>, futureStart: Float?,
): Pair<List<FlightProfilePoint>, List<FlightProfilePoint>> {
    if (futureStart == null || !futureStart.isFinite()) return points to emptyList()
    val before = mutableListOf<FlightProfilePoint>()
    val after = mutableListOf<FlightProfilePoint>()
    points.forEachIndexed { index, point ->
        val previous = points.getOrNull(index - 1)
        if (previous != null && previous.progress < futureStart && point.progress > futureStart) {
            val fraction = (futureStart - previous.progress) / (point.progress - previous.progress)
            val boundary = point.copy(progress = futureStart,
                altitudeMeters = previous.altitudeMeters + fraction * (point.altitudeMeters - previous.altitudeMeters))
            before += boundary
            after += boundary
        }
        if (point.progress <= futureStart) before += point
        if (point.progress >= futureStart) after += point
    }
    return before to after
}

internal fun flightProfileFutureStart(trip: FlightTrip?, latestTime: Long?): Float? {
    if (trip == null || latestTime == null || trip.samples.lastOrNull()?.timestampMillis?.let { it <= latestTime } != false) return null
    return FlightLiveTimeline.progress(trip, latestTime)
}
