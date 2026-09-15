package net.osmand.plus.plugins.flightmode

/**
 * Virtual fixes, not virtual screens: the service sends these through its normal recording path.
 */
internal class FlightLiveSimulation(plan: FlightPlan, source: FlightTrip?, startMillis: Long) {
    private val route =
        source?.takeIf { it.samples.size >= 2 } ?: FlightOfflinePreparation.simulation(plan)
    private val replay = FlightReplayEngine(route)
    private val lead = (plan.preparation ?: FlightPreparation()).startMinutesBefore * 60_000L
    private val flightDuration =
        if (route.hasUsableTimestamps)
            (route.samples.last().timestampMillis - route.samples.first().timestampMillis)
                .coerceAtLeast(1_000L)
        else (route.samples.size - 1) * 10_000L
    val beginning = startMillis
    val departure = beginning + lead
    val arrival = departure + flightDuration
    val end = arrival + ((plan.preparation ?: FlightPreparation()).stopMinutes + 1) * 60_000L
    val activePlan =
        plan.copy(
            stops =
                if (FlightOfflinePreparation.canSimulate(plan)) plan.stops
                else
                    listOf(
                        FlightStop(
                            "Departure",
                            route.samples.first().latitude,
                            route.samples.first().longitude,
                        ),
                        FlightStop(
                            "Arrival",
                            route.samples.last().latitude,
                            route.samples.last().longitude,
                        ),
                    ),
            preparation =
                (plan.preparation ?: FlightPreparation()).copy(
                    departureMillis = departure,
                    arrivalMillis = arrival,
                    automatic = false,
                ),
        )

    fun sampleAt(time: Long): FlightSample {
        val t = time.coerceIn(beginning, end)
        val snapshot =
            replay.snapshotAt(
                ((t - departure).toDouble() / flightDuration).toFloat().coerceIn(0f, 1f)
            )
        val sample = snapshot.sample
        val ground = t < departure || t >= arrival
        val next =
            replay.snapshotAt(
                ((t + 1000 - departure).toDouble() / flightDuration).toFloat().coerceIn(0f, 1f)
            )
        val speed =
            sample.speedMetersPerSecond
                ?: if (!snapshot.dataGap && !next.dataGap)
                    (FlightTerrainTilePlanner.distanceKm(
                            sample.latitude,
                            sample.longitude,
                            next.sample.latitude,
                            next.sample.longitude,
                        ) * 1000)
                        .toFloat()
                else null
        return sample.copy(
            timestampMillis = t,
            speedMetersPerSecond = if (ground) 0f else speed,
            // Synthetic source accuracy is explicit; recorded gaps remain unreliable.
            horizontalAccuracyMeters =
                if (!ground && snapshot.dataGap) null else sample.horizontalAccuracyMeters ?: 3f,
            satellitesUsed = null,
            satellitesFound = null,
            soundDb = null,
            soundSpectrum = null,
            vibrationHz = null,
        )
    }

    fun progress(time: Long) =
        ((time - beginning).toDouble() / (end - beginning)).toFloat().coerceIn(0f, 1f)
}

/** Monotonic virtual clock; rate changes and pause never skip detector input samples. */
internal class FlightSimulationClock(val start: Long) {
    var time: Long = start
        private set

    var rate: Int = 60
    var paused: Boolean = false
    private var previousElapsed: Long? = null

    fun advance(elapsed: Long): Long {
        val previous = previousElapsed
        previousElapsed = elapsed
        // Do not replay hours in one worker turn after Android suspends the process.
        if (previous != null && !paused)
            time += (elapsed - previous).coerceIn(0, 1000) * rate.coerceIn(1, 300)
        return time
    }
}
