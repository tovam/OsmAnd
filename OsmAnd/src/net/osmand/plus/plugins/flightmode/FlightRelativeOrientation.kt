package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Calibrate a sensor frame to the displayed camera, then filter its optical direction in 3D. */
internal class FlightPhoneAimController {
    private var alignment: DoubleArray? = null
    private var filtered = doubleArrayOf(0.0, 1.0, 0.0)
    private var baseline = FlightWindowLook()
    private var baselineBearing: Float? = null
    private var baselineAzimuth = 0f
    private var baselineElevation = 0f
    private var lastAzimuth = 0f
    private var timestamp = 0L

    fun reset(look: FlightWindowLook, bearing: Float?, azimuth: Float, elevation: Float) {
        baseline = look.clamped()
        baselineBearing = bearing?.takeIf(Float::isFinite)
        baselineAzimuth = azimuth
        baselineElevation = elevation
        lastAzimuth = azimuth
        alignment = null
        timestamp = 0L
    }

    /** Absolute targets cannot accumulate duplicate deltas when Compose is behind the sensors. */
    fun update(rotation: FloatArray, timeNanos: Long, bearing: Float?): FlightWindowLook? {
        if (rotation.size != 9 || rotation.any { !it.isFinite() } || timeNanos <= timestamp)
            return null
        if (alignment == null) {
            val azimuth = Math.toRadians(baselineAzimuth.toDouble())
            val elevation = Math.toRadians(baselineElevation.toDouble())
            filtered =
                doubleArrayOf(
                    sin(azimuth) * cos(elevation),
                    cos(azimuth) * cos(elevation),
                    sin(elevation),
                )
            val right = doubleArrayOf(cos(azimuth), -sin(azimuth), 0.0)
            val up =
                doubleArrayOf(
                    -sin(azimuth) * sin(elevation),
                    -cos(azimuth) * sin(elevation),
                    cos(elevation),
                )
            val camera =
                DoubleArray(9) { i ->
                    when (i % 3) {
                        0 -> right[i / 3]
                        1 -> up[i / 3]
                        else -> -filtered[i / 3]
                    }
                }
            // Sensor world -> desired world. Initial sensor north and phone roll are irrelevant.
            alignment =
                DoubleArray(9) { i ->
                    (0..2).sumOf { k -> camera[i / 3 * 3 + k] * rotation[i % 3 * 3 + k] }
                }
            timestamp = timeNanos
            return baseline
        }
        val transform = requireNotNull(alignment)
        val direction =
            DoubleArray(3) { axis ->
                -(0..2).sumOf { k -> transform[axis * 3 + k] * rotation[k * 3 + 2] }
            }
        val length = sqrt(direction.sumOf { it * it })
        if (length < 1e-6 || !length.isFinite()) return null
        val dt = ((timeNanos - timestamp) / 1e9).coerceIn(0.001, 0.2)
        val alpha = 1.0 - exp(-dt / 0.10)
        val next = DoubleArray(3) { filtered[it] + alpha * (direction[it] / length - filtered[it]) }
        val nextLength = sqrt(next.sumOf { it * it })
        if (nextLength < 1e-6) return null
        filtered = DoubleArray(3) { next[it] / nextLength }
        timestamp = timeNanos
        // Heading is undefined at the zenith/nadir. Retain it rather than amplifying GPS noise.
        if (hypot(filtered[0], filtered[1]) > 0.03)
            lastAzimuth = Math.toDegrees(atan2(filtered[0], filtered[1])).toFloat()
        val elevation = Math.toDegrees(asin(filtered[2].coerceIn(-1.0, 1.0))).toFloat()
        val course = bearing?.takeIf(Float::isFinite)
        if (baselineBearing == null && course != null) baselineBearing = course
        val turn =
            if (course != null && baselineBearing != null)
                FlightWindowLook.normalizeYaw(course - requireNotNull(baselineBearing))
            else 0f
        return FlightWindowLook(
                baseline.yawDegrees + FlightWindowLook.normalizeYaw(lastAzimuth - baselineAzimuth) -
                    turn,
                baseline.pitchDegrees + elevation - baselineElevation,
            )
            .clamped()
    }
}
