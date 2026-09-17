package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/**
 * Display-only future. Never give this trip to the recorder, exporter or photo association code.
 */
internal object FlightLiveTimeline {
    fun build(plan: FlightPlan, recorded: FlightTrip?, fix: FlightSample): FlightTrip {
        val measured =
            recorded?.samples.orEmpty().filter { it.timestampMillis < fix.timestampMillis } + fix
        val remaining = FlightRouteHypothesis.remainingStops(plan, fix)
        if (remaining.isEmpty()) return recordedFlightTrip(recorded?.name ?: "", measured)
        val targets = remaining.map { target ->
            target to (requireNotNull(target.stop.latitude) to requireNotNull(target.stop.longitude))
        }
        val distances = (listOf(fix.latitude to fix.longitude) + targets.map { it.second })
            .zipWithNext { from, to -> FlightTerrainTilePlanner.distanceKm(from.first, from.second, to.first, to.second) }
        val km = distances.sum()
        if (km < 0.2) return recordedFlightTrip(recorded?.name ?: "", measured)
        val speed = max(200.0, (fix.speedMetersPerSecond ?: 208f).toDouble() * 3.6)
        val scheduledArrival = plan.preparation?.arrivalMillis ?: 0L
        val totalDuration =
            scheduledArrival.minus(fix.timestampMillis).takeIf {
                it > 60_000L
            } ?: (km / speed * 3_600_000).toLong().coerceAtLeast(60_000L) +
                stopoverCount(plan, remaining) * STOPOVER_MILLIS
        val plannedStopovers = stopoverCount(plan, remaining)
        val dwellMillis = (plannedStopovers * STOPOVER_MILLIS)
            .coerceAtMost((totalDuration - 60_000L).coerceAtLeast(0L))
        val dwellPerStop = if (plannedStopovers == 0L) 0L else dwellMillis / plannedStopovers
        val flightDuration = (totalDuration - dwellMillis).coerceAtLeast(60_000L)
        val altitude = fix.altitudeMeters ?: 0.0
        var timestamp = fix.timestampMillis
        var sampleIndex = measured.size
        val future = mutableListOf<FlightSample>()
        var blockStart = 0
        while (blockStart < targets.size) {
            val landingTarget = (blockStart until targets.size).firstOrNull { targetIndex ->
                targetIndex == targets.lastIndex || plan.isIntermediateStopover(targets[targetIndex].first.index)
            } ?: targets.lastIndex
            val blockDistances = distances.subList(blockStart, landingTarget + 1)
            val blockDistance = blockDistances.sum().coerceAtLeast(0.001)
            val blockInitialAltitude = if (blockStart == 0) altitude else 0.0
            val cruise = max(blockInitialAltitude, min(12_000.0, max(2000.0, blockDistance * 25.0)))
            for (targetIndex in blockStart..landingTarget) {
                val segmentDistance = distances[targetIndex]
                val segmentDuration = (flightDuration * segmentDistance / km).toLong().coerceAtLeast(1L)
                val count = ceil(segmentDuration / 30_000.0).toInt().coerceIn(1, 2000)
                val from = if (targetIndex == 0) fix.latitude to fix.longitude else targets[targetIndex - 1].second
                val to = targets[targetIndex].second
                val before = distances.subList(blockStart, targetIndex).sum()
                for (step in 1..count) {
                    val fraction = step.toDouble() / count
                    val at = FlightTerrainTilePlanner.greatCircleInterpolate(from, to, fraction)
                    val blockProgress = ((before + segmentDistance * fraction) / blockDistance).coerceIn(0.0, 1.0)
                    timestamp += (segmentDuration / count).coerceAtLeast(1L)
                    future += futureSample(
                        index = sampleIndex++,
                        legIndex = fix.legIndex,
                        timestamp = timestamp,
                        point = at,
                        altitude = altitudeForBlock(blockProgress, blockInitialAltitude, cruise),
                        speed = (segmentDistance * 1000 / (segmentDuration / 1000.0)).toFloat(),
                    )
                }
            }
            if (landingTarget != targets.lastIndex && dwellPerStop > 0L) {
                val target = targets[landingTarget]
                val count = ceil(dwellPerStop / 30_000.0).toInt().coerceIn(1, 2000)
                repeat(count) {
                    timestamp += (dwellPerStop / count).coerceAtLeast(1L)
                    future += futureSample(
                        index = sampleIndex++,
                        legIndex = fix.legIndex,
                        timestamp = timestamp,
                        point = target.second,
                        altitude = 0.0,
                        speed = 0f,
                    )
                }
            }
            blockStart = landingTarget + 1
        }
        if (future.isNotEmpty() && scheduledArrival > fix.timestampMillis + 60_000L) {
            future[future.lastIndex] = future.last().copy(timestampMillis = scheduledArrival)
        }
        return recordedFlightTrip(
            recorded?.name ?: "",
            // Keep the measured prefix byte-for-byte as supplied by the recorder. Only the
            // display-only samples may receive derived bearings.
            measured + FlightTrackMath.fillMissingBearings(future),
        )
    }

    private fun stopoverCount(plan: FlightPlan, remaining: List<FlightRouteHypothesis.RemainingStop>): Long =
        remaining.count { plan.isIntermediateStopover(it.index) }.toLong()

    private fun altitudeForBlock(progress: Double, initialAltitude: Double, cruise: Double): Double = when {
        progress < 0.18 -> initialAltitude + (cruise - initialAltitude) * progress / 0.18
        progress < 0.80 -> cruise
        else -> cruise * (1.0 - progress) / 0.20
    }

    /** Construct anew: future samples must not inherit recorded accuracy or sensor readings. */
    private fun futureSample(
        index: Int,
        legIndex: Int,
        timestamp: Long,
        point: Pair<Double, Double>,
        altitude: Double,
        speed: Float,
    ) = FlightSample(
        index = index,
        legIndex = legIndex,
        timestampMillis = timestamp,
        latitude = point.first,
        longitude = point.second,
        altitudeMeters = altitude,
        speedMetersPerSecond = speed,
        bearingDegrees = null,
        horizontalAccuracyMeters = null,
    )

    private const val STOPOVER_MILLIS = 45 * 60_000L

    fun progress(trip: FlightTrip, time: Long): Float {
        val start = trip.samples.firstOrNull()?.timestampMillis ?: return 0f
        val end = trip.samples.lastOrNull()?.timestampMillis ?: return 0f
        return ((time - start).toDouble() / (end - start).coerceAtLeast(1L))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun withoutFutureMeasurements(snapshot: FlightSnapshot, lastFixMillis: Long): FlightSnapshot =
        if (snapshot.sample.timestampMillis <= lastFixMillis) snapshot
        else
            snapshot.copy(
                sample =
                    snapshot.sample.copy(
                        horizontalAccuracyMeters = null,
                        hdop = null,
                        satellitesUsed = null,
                        satellitesFound = null,
                        soundDb = null,
                        soundSpectrum = null,
                        vibrationHz = null,
                    )
            )
}

/** Photo associations use measured points, not the progress fraction of the predicted future. */
internal fun FlightUiState.recordedPhotoPositionAtCursor(): Double? =
    if (sessionMode == FlightSessionMode.LIVE) {
        FlightSampleInterpolator.positionAtTimestamp(trip, snapshot?.sample?.timestampMillis, 0L)
    } else FlightSampleInterpolator.positionAtProgress(trip, replayProgress)

internal fun FlightUiState.progressForRecordedPhoto(position: Double?): Float? {
    val timeline = liveTimeline
    return if (sessionMode == FlightSessionMode.LIVE && timeline != null) {
        FlightSampleInterpolator.sampleAt(trip, position)?.let {
            FlightLiveTimeline.progress(timeline, it.timestampMillis)
        }
    } else FlightSampleInterpolator.progressAt(trip, position)
}
