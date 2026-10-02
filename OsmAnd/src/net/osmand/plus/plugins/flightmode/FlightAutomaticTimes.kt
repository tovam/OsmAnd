package net.osmand.plus.plugins.flightmode

import kotlin.math.max

/** Shared retrospective/live evidence. A fast core is extended to the surrounding stable stops. */
internal object FlightAutomaticTimes {
    const val STILL_KMH = 3.0
    const val STILL_MILLIS = 120_000L
    const val MAX_FIX_GAP = 120_000L
    private const val CORE_MILLIS = 30_000L
    private const val TAXI_LOOKBACK = 45 * 60_000L

    data class Evidence(val speedKmh: Double?, val reliable: Boolean)

    fun evidence(sample: FlightSample, previous: FlightSample?): Evidence {
        val reliable =
            sample.timestampMillis > 0 &&
                sample.latitude in -90.0..90.0 &&
                sample.longitude in -180.0..180.0 &&
                sample.horizontalAccuracyMeters?.let { it.isFinite() && it in 0f..100f } != false
        if (!reliable) return Evidence(null, false)
        val measured = sample.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 }?.times(3.6)
        if (measured != null) return Evidence(measured, true)
        val elapsed = previous?.let { sample.timestampMillis - it.timestampMillis } ?: 0L
        if (previous == null || elapsed !in 1000..MAX_FIX_GAP) return Evidence(null, true)
        val distance =
            FlightTerrainTilePlanner.distanceKm(
                previous.latitude,
                previous.longitude,
                sample.latitude,
                sample.longitude,
            ) * 1000
        val uncertainty =
            max(
                sample.horizontalAccuracyMeters?.toDouble() ?: 5.0,
                previous.horizontalAccuracyMeters?.toDouble() ?: 5.0,
            )
        return Evidence(((distance - uncertainty).coerceAtLeast(0.0) / elapsed) * 3600, true)
    }

    fun flights(
        trip: FlightTrip,
        plan: FlightPreparation = FlightPreparation(),
    ): List<FlightTimeRange> {
        val samples = trip.recording().samples
        if (!trip.hasUsableTimestamps || samples.size < 3) return emptyList()
        val stillKmh = plan.stopSpeedKmh.coerceIn(1, 10).toDouble()
        val result = mutableListOf<FlightTimeRange>()
        val speeds = ArrayDeque<Pair<Long, Double>>()
        var highSince: Long? = null
        var highCount = 0
        var start: Long? = null
        var stillSince: Long? = null
        var stillAnchor: FlightSample? = null
        var lastRawStill: Long? = null
        val stops = mutableListOf<FlightTimeRange>()
        var previous: FlightSample? = null
        samples.forEach { sample ->
            val now = sample.timestampMillis
            val continuity = previous?.let { now - it.timestampMillis in 1..MAX_FIX_GAP } ?: true
            val evidence = evidence(sample, previous)
            if (!continuity || !evidence.reliable || evidence.speedKmh == null) {
                speeds.clear()
                highSince = null
                highCount = 0
                stillSince = null
                stillAnchor = null
                previous = sample
                return@forEach
            }
            while (speeds.isNotEmpty() && now - speeds.first().first > 20_000) speeds.removeFirst()
            speeds.addLast(now to evidence.speedKmh)
            while (speeds.size > 21) speeds.removeFirst()
            // Median rejects isolated speed spikes without extrapolating acceleration backwards.
            val sorted = speeds.map { it.second }.sorted()
            val speed = sorted[sorted.size / 2]
            if (evidence.speedKmh <= stillKmh) lastRawStill = now
            val anchor = stillAnchor
            val stationary =
                speed <= stillKmh &&
                    (anchor == null ||
                        FlightTerrainTilePlanner.distanceKm(
                            anchor.latitude,
                            anchor.longitude,
                            sample.latitude,
                            sample.longitude,
                        ) <= 0.1)
            if (stationary) {
                if (stillSince == null) {
                    stillSince =
                        previous
                            ?.takeIf {
                                it.speedMetersPerSecond?.times(3.6)?.let { speed ->
                                    speed <= stillKmh
                                } == true
                            }
                            ?.timestampMillis ?: now
                    stillAnchor = sample
                }
            } else {
                if (
                    stillSince != null &&
                        now - stillSince!! >= plan.stopMinutes.coerceIn(1, 10) * 60_000L
                )
                    stops +=
                        FlightTimeRange(
                            stillSince!!,
                            lastRawStill ?: previous?.timestampMillis ?: now,
                        )
                stillSince = null
                stillAnchor = null
            }
            if (speed >= plan.airborneSpeedKmh) {
                if (highSince == null) {
                    highSince = now
                    highCount = 0
                }
                highCount++
                if (start == null && highCount >= 3 && now - highSince!! >= CORE_MILLIS) {
                    // Prefer the longest boarding stop: shorter taxi pauses must not trim pushback.
                    val stop =
                        stops
                            .filter { it.endMillis >= highSince!! - TAXI_LOOKBACK }
                            .maxByOrNull { it.endMillis - it.startMillis }
                    start =
                        stop?.endMillis
                            ?: samples
                                .firstOrNull { it.timestampMillis >= highSince!! - TAXI_LOOKBACK }
                                ?.timestampMillis
                            ?: highSince
                }
            } else {
                highSince = null
                highCount = 0
            }
            if (
                start != null &&
                    stillSince != null &&
                    now - stillSince!! >= plan.stopMinutes.coerceIn(1, 10) * 60_000L
            ) {
                result += FlightTimeRange(start!!, stillSince!!)
                start = null
                stops.clear()
                highSince = null
                highCount = 0
            }
            previous = sample
        }
        // An unfinished flight or missing landing fixes remains visible to the last recorded point.
        start?.let { result += FlightTimeRange(it, samples.last().timestampMillis) }
        return normalizedFlightTimeRanges(result)
    }

    fun excluded(
        trip: FlightTrip,
        plan: FlightPreparation = FlightPreparation(),
    ): List<FlightTimeRange> {
        val flights = flights(trip, plan)
        if (flights.isEmpty()) return emptyList() // Ambiguous traces stay visible.
        var cursor = trip.recording().samples.first().timestampMillis
        val end = trip.recording().samples.last().timestampMillis
        val excluded = mutableListOf<FlightTimeRange>()
        flights.forEach { flight ->
            if (cursor < flight.startMillis)
                excluded += FlightTimeRange(cursor, flight.startMillis - 1)
            cursor = flight.endMillis + 1
        }
        if (cursor <= end) excluded += FlightTimeRange(cursor, end)
        return excluded
    }
}

/** Manual exclusions win over restored automatic periods; original recordings remain untouched. */
internal fun replayFlightTrip(
    trip: FlightTrip,
    ignored: List<FlightTimeRange>,
    automatic: Boolean = true,
    restored: List<FlightTimeRange> = emptyList(),
    plan: FlightPreparation = FlightPreparation(),
): FlightTrip {
    val automaticRanges = if (automatic) FlightAutomaticTimes.excluded(trip, plan) else emptyList()
    return visibleFlightTrip(trip, ignored + subtractFlightTimeRanges(automaticRanges, restored))
}

internal fun subtractFlightTimeRanges(
    ranges: List<FlightTimeRange>,
    restored: List<FlightTimeRange>,
): List<FlightTimeRange> {
    var result = normalizedFlightTimeRanges(ranges)
    normalizedFlightTimeRanges(restored).forEach { keep ->
        result =
            result.flatMap { range ->
                if (keep.endMillis < range.startMillis || keep.startMillis > range.endMillis)
                    listOf(range)
                else
                    buildList {
                        if (range.startMillis < keep.startMillis)
                            add(FlightTimeRange(range.startMillis, keep.startMillis - 1))
                        if (range.endMillis > keep.endMillis)
                            add(FlightTimeRange(keep.endMillis + 1, range.endMillis))
                    }
            }
    }
    return result
}
