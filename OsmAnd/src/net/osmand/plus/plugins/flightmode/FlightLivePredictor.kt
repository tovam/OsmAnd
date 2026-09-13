package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Display-only prediction. Never feed this position back to the recorder or landing detector. */
internal class FlightLivePredictor {
    private var fix: FlightSample? = null
    private var receivedAt = 0L
    private var correctionFrom: FlightSample? = null

    fun reset() {
        fix = null
        receivedAt = 0
        correctionFrom = null
    }

    fun accept(sample: FlightSample, elapsed: Long) {
        if (sample.timestampMillis == fix?.timestampMillis) return
        correctionFrom = position(elapsed)
        fix = sample
        receivedAt = elapsed
    }

    fun position(elapsed: Long): FlightSample? {
        val p = fix ?: return null
        val seconds = ((elapsed - receivedAt) / 1000.0).coerceIn(0.0, 5.0)
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
        val blend = (seconds / 1.2).coerceIn(0.0, 1.0)
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
