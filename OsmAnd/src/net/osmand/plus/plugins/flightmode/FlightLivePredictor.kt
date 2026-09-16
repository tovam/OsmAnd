package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Display-only prediction. Never feed this position back to the recorder or landing detector. */
internal class FlightLivePredictor {
    private var fix: FlightSample? = null
    private var receivedAt = 0L
    private var correctedAt = 0L
    private var correctionFrom: FlightSample? = null
    private var timeScale = 1.0

    fun reset() {
        fix = null
        receivedAt = 0
        correctedAt = 0
        correctionFrom = null
        timeScale = 1.0
    }

    fun accept(
        sample: FlightSample,
        elapsed: Long,
        rate: Double = 1.0,
        fixReceivedAt: Long? = elapsed,
    ) {
        val previousPosition = position(elapsed)
        timeScale = if (fixReceivedAt != null) rate.coerceIn(0.0, 300.0) else 0.0
        if (sample.timestampMillis == fix?.timestampMillis) return
        correctionFrom = previousPosition
        fix = sample
        correctedAt = elapsed
        // Reopening the UI must not make a restored/old fix fresh again.
        receivedAt = fixReceivedAt ?: elapsed
    }

    fun position(elapsed: Long): FlightSample? {
        val p = fix ?: return null
        val realSeconds = ((elapsed - receivedAt) / 1000.0).coerceIn(0.0, 5.0)
        val seconds = realSeconds * timeScale
        val speed = p.speedMetersPerSecond?.toDouble() ?: 0.0
        val heading = p.bearingDegrees?.toDouble() ?: 0.0
        val distance =
            if (p.bearingDegrees != null && (p.horizontalAccuracyMeters ?: Float.MAX_VALUE) <= 100)
                speed * seconds
            else 0.0
        val a = distance / 6_371_000.0
        val lat = Math.toRadians(p.latitude)
        val lon = Math.toRadians(p.longitude)
        val b = Math.toRadians(heading)
        val newLat = asin(sin(lat) * cos(a) + cos(lat) * sin(a) * cos(b))
        val newLon = lon + atan2(sin(b) * sin(a) * cos(lat), cos(a) - sin(lat) * sin(newLat))
        val predicted =
            p.copy(
                latitude = Math.toDegrees(newLat),
                longitude = ((Math.toDegrees(newLon) + 540) % 360) - 180,
            )
        val from = correctionFrom ?: return predicted
        val correctionSeconds = ((elapsed - correctedAt) / 1000.0).coerceAtLeast(0.0)
        val blend = (correctionSeconds / if (timeScale == 1.0) 1.2 else 0.12).coerceIn(0.0, 1.0)
        val point =
            FlightTerrainTilePlanner.greatCircleInterpolate(
                from.latitude to from.longitude,
                predicted.latitude to predicted.longitude,
                blend,
            )
        return predicted.copy(
            latitude = point.first,
            longitude = point.second,
            altitudeMeters =
                if (from.altitudeMeters != null && predicted.altitudeMeters != null)
                    from.altitudeMeters + (predicted.altitudeMeters - from.altitudeMeters) * blend
                else predicted.altitudeMeters,
        )
    }
}
