package net.osmand.plus.plugins.flightmode

/** Presentation only: selecting a graph never replaces the recorder or playback timeline. */
internal data class FlightAltitudeProfile(val trip: FlightTrip?, val profile: FlightProfile) {
    fun progress(timeMillis: Long?, fallback: Float): Float =
        if (trip != null && timeMillis != null) FlightLiveTimeline.progress(trip, timeMillis) else fallback

    fun photoProgress(recorded: FlightTrip?, position: Double?): Float? =
        FlightSampleInterpolator.sampleAt(recorded, position)?.let { sample ->
            trip?.let { FlightLiveTimeline.progress(it, sample.timestampMillis) }
        }
}

internal fun selectFlightAltitudeProfile(
    recorded: FlightTrip?, timeline: FlightTrip?, fallback: FlightProfile,
    live: Boolean, includeFuture: Boolean,
): FlightAltitudeProfile {
    if (!live) return FlightAltitudeProfile(null, fallback)
    val selected = if (includeFuture) timeline ?: recorded else recorded
    return FlightAltitudeProfile(selected, selected?.let(FlightProfilePlanner::fromTrip)
        ?: FlightProfile(emptyList(), emptyList(), 0f, 0, recorded = true))
}
