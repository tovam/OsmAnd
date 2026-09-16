package net.osmand.plus.plugins.flightmode

data class FlightLiveState(
    val journeyId: String? = null,
    val trip: FlightTrip? = null,
    val latest: FlightSample? = null,
    val tracking: FlightTrackingState = FlightTrackingState(),
    val battery: List<FlightBatteryPoint> = emptyList(),
    val lastFixElapsed: Long = 0,
    val running: Boolean = false,
    val microphone: Boolean = false,
    val policy: FlightRecordingPolicy = FlightRecordingPolicy(),
    val error: String? = null,
    val simulation: Boolean = false,
    val simulationRate: Int = 60,
    val simulationPaused: Boolean = false,
    val simulationBackgroundPaused: Boolean = false,
    val simulationProgress: Float = 0f,
    val simulationPlan: FlightPlan? = null,
)

internal fun recordedFlightTrip(name: String, samples: List<FlightSample>): FlightTrip {
    val distance =
        samples.zipWithNext().sumOf { (a, b) ->
            FlightTerrainTilePlanner.distanceKm(a.latitude, a.longitude, b.latitude, b.longitude) *
                1000
        }
    return FlightTrip(
        name,
        samples,
        if (samples.isEmpty()) emptyList()
        else
            listOf(
                FlightLeg(
                    0,
                    "",
                    0,
                    samples.lastIndex,
                    distance,
                    samples.first().timestampMillis,
                    samples.last().timestampMillis,
                )
            ),
        samples.size > 1,
        distance,
        "Flight recorder",
    )
}
