package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/**
 * Display-only future. Never give this trip to the recorder, exporter or photo association code.
 */
internal object FlightLiveTimeline {
    fun build(plan: FlightPlan, recorded: FlightTrip?, fix: FlightSample): FlightTrip {
        val measured =
            recorded?.samples.orEmpty().filter { it.timestampMillis < fix.timestampMillis } + fix
        val end = plan.stops.lastOrNull()
        val lat = end?.latitude
        val lon = end?.longitude
        if (lat == null || lon == null) return recordedFlightTrip(recorded?.name ?: "", measured)
        val km = FlightTerrainTilePlanner.distanceKm(fix.latitude, fix.longitude, lat, lon)
        if (km < 0.2) return recordedFlightTrip(recorded?.name ?: "", measured)
        val speed = max(200.0, (fix.speedMetersPerSecond ?: 208f).toDouble() * 3.6)
        val duration =
            (plan.preparation?.arrivalMillis ?: 0L).minus(fix.timestampMillis).takeIf {
                it > 60_000L
            } ?: (km / speed * 3_600_000).toLong().coerceAtLeast(60_000L)
        val count = ceil(duration / 30_000.0).toInt().coerceIn(2, 2000)
        val altitude = fix.altitudeMeters ?: 0.0
        val cruise = max(altitude, min(12_000.0, max(2000.0, km * 25.0)))
        val future =
            (1..count).map { i ->
                val f = i.toDouble() / count
                val at =
                    FlightTerrainTilePlanner.greatCircleInterpolate(
                        fix.latitude to fix.longitude,
                        lat to lon,
                        f,
                    )
                val h =
                    when {
                        f < 0.18 -> altitude + (cruise - altitude) * f / 0.18
                        f < 0.80 -> cruise
                        else -> cruise * (1.0 - f) / 0.20
                    }
                // Construct anew: copying the live fix would invent accuracy, satellites and
                // sounds.
                FlightSample(
                    index = measured.size + i - 1,
                    legIndex = fix.legIndex,
                    timestampMillis = fix.timestampMillis + (duration * f).toLong(),
                    latitude = at.first,
                    longitude = at.second,
                    altitudeMeters = h,
                    speedMetersPerSecond = (km * 1000 / (duration / 1000.0)).toFloat(),
                    bearingDegrees = null,
                    horizontalAccuracyMeters = null,
                )
            }
        return recordedFlightTrip(
            recorded?.name ?: "",
            FlightTrackMath.fillMissingBearings(measured + future),
        )
    }

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
