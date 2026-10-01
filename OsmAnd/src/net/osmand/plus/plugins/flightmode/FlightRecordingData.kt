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
    val receivedFixesThisSession: Long = 0L,
    val recordingDecision: FlightRecordingDecision? = null,
    val lastSavedReason: FlightRecordingSaveReason? = null,
    val error: String? = null,
    val simulation: Boolean = false,
    val simulationRate: Int = 60,
    val simulationPaused: Boolean = false,
    val simulationBackgroundPaused: Boolean = false,
    val simulationProgress: Float = 0f,
    val simulationPlan: FlightPlan? = null,
)

internal fun recordedFlightTrip(name: String, samples: List<FlightSample>, knownDistanceMeters: Double? = null): FlightTrip {
    val distance = knownDistanceMeters ?:
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

/** Immutable snapshots copy only a bounded tail; completed chunks are shared safely with readers. */
internal class FlightRecordedHistory(initial: List<FlightSample> = emptyList()) : AbstractList<FlightSample>() {
    private var chunks = emptyList<List<FlightSample>>()
    private val tail = ArrayList<FlightSample>(256)
    override var size: Int = 0
        private set
    var distanceMeters: Double = 0.0
        private set

    init { initial.forEach(::append) }

    fun append(sample: FlightSample) {
        lastOrNull()?.let { previous -> distanceMeters += FlightTerrainTilePlanner.distanceKm(
            previous.latitude, previous.longitude, sample.latitude, sample.longitude) * 1000.0 }
        tail.add(sample)
        size++
        if (tail.size == 256) {
            chunks = chunks + listOf(tail.toList())
            tail.clear()
        }
    }

    override fun get(index: Int): FlightSample {
        if (index !in 0 until size) throw IndexOutOfBoundsException(index.toString())
        val chunk = index / 256
        return if (chunk < chunks.size) chunks[chunk][index % 256] else tail[index % 256]
    }

    fun snapshot(): List<FlightSample> {
        val full = chunks
        val end = tail.toList()
        val count = size
        return object : AbstractList<FlightSample>() {
            override val size = count
            override fun get(index: Int): FlightSample {
                if (index !in 0 until count) throw IndexOutOfBoundsException(index.toString())
                val chunk = index / 256
                return if (chunk < full.size) full[chunk][index % 256] else end[index % 256]
            }
        }
    }
}
