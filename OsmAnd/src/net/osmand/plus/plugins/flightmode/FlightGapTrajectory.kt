package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Display geometry only: never append these estimated vertices to a recorded trip. */
internal data class FlightGapVertex(val fraction: Double, val sample: FlightSample)

/**
 * One path for both the aircraft and its red native/Canvas stroke. The curve lives on a sphere, not
 * in latitude/longitude coordinates. Playback follows the rendered Mercator chords between its
 * vertices, including their altitude, rather than evaluating a second, different curve.
 */
internal class FlightGapTrajectory
private constructor(
    private val from: FlightSample,
    private val to: FlightSample,
    val vertices: List<FlightGapVertex>,
) {
    fun sampleAt(requestedFraction: Double): FlightSample {
        val fraction = requestedFraction.coerceIn(0.0, 1.0)
        if (fraction <= 0.0) return from
        if (fraction >= 1.0) return to
        var low = 1
        var high = vertices.lastIndex
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (vertices[middle].fraction < fraction) low = middle + 1 else high = middle
        }
        val a = vertices[low - 1]
        val b = vertices[low]
        val t = (fraction - a.fraction) / (b.fraction - a.fraction)
        val interpolated = FlightSampleInterpolator.interpolateSamples(from, to, fraction.toFloat())
        return interpolated.copy(
            latitude =
                latitudeFromMercator(
                    mercatorY(a.sample.latitude) * (1.0 - t) + mercatorY(b.sample.latitude) * t
                ),
            longitude =
                wrapLongitude(
                    a.sample.longitude + wrapLongitude(b.sample.longitude - a.sample.longitude) * t
                ),
            bearingDegrees = interpolateHeading(a.sample.bearingDegrees, b.sample.bearingDegrees, t),
        )
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MAX_CONTROL_METERS = 15_000.0
        private const val MAX_CONTROL_SECONDS = 60.0
        private const val MAX_MERCATOR_LATITUDE = 85.05112878

        fun buildAll(
            samples: List<FlightSample>,
            gaps: List<FlightGpsGap>? = null,
            recordedSampleCount: Int = samples.size,
        ): Map<Int, FlightGapTrajectory> {
            val count = recordedSampleCount.coerceIn(0, samples.size)
            val measuredGaps = gaps ?: flightGpsGaps(samples, count)
            val gapEdges = measuredGaps.map { it.fromIndex }.toSet()
            return measuredGaps.associate { gap ->
                val index = gap.fromIndex
                val previous =
                    samples.getOrNull(index - 1)?.takeIf {
                        index - 1 !in gapEdges && it.legIndex == gap.from.legIndex
                    }
                val next =
                    samples.getOrNull(index + 2)?.takeIf {
                        index + 2 < count &&
                            index + 1 !in gapEdges &&
                            it.legIndex == gap.to.legIndex
                    }
                index to create(gap.from, gap.to, previous, next)
            }
        }

        private fun create(
            from: FlightSample,
            to: FlightSample,
            previous: FlightSample?,
            next: FlightSample?,
        ): FlightGapTrajectory {
            val start = SpherePoint.from(from)
            val end = SpherePoint.from(to)
            val angle = atan2(start.cross(end).length(), start.dot(end).coerceIn(-1.0, 1.0))
            val distance = angle * EARTH_RADIUS_METERS
            val initial = FlightTrackMath.bearingBetween(from, to)?.toDouble() ?: 0.0
            val terminal =
                FlightTrackMath.bearingBetween(to, from)?.let { (it + 180.0) % 360.0 } ?: initial
            // A leg boundary or missing neighbour is not evidence of an incoming/outgoing turn.
            // Prefer measured adjacent motion to a recorded bearing (which may be stale/derived).
            val incoming =
                previous?.let {
                    FlightTrackMath.bearingBetween(from, it)?.let { value ->
                        (value + 180.0) % 360.0
                    }
                } ?: from.bearingDegrees?.takeIf { it.isFinite() }?.toDouble()
            val outgoing =
                next?.let { FlightTrackMath.bearingBetween(to, it)?.toDouble() }
                    ?: to.bearingDegrees?.takeIf { it.isFinite() }?.toDouble()
            fun usable(heading: Double?, direct: Double): Double? =
                heading?.takeIf {
                    distance > 5.0 && angle < PI - 1e-5 && abs(wrapLongitude(it - direct)) < 135.0
                }
            val entryHeading = usable(incoming, initial)
            val exitHeading = usable(outgoing, terminal)
            val duration = (to.timestampMillis - from.timestampMillis).coerceAtLeast(0L) / 1000.0
            fun controlDistance(sample: FlightSample): Double {
                val speed =
                    sample.speedMetersPerSecond
                        ?.takeIf { it.isFinite() && it in 1f..400f }
                        ?.toDouble()
                        ?: (distance / duration.coerceAtLeast(1.0)).coerceIn(1.0, 400.0)
                return minOf(distance / 3.0, MAX_CONTROL_METERS, speed * MAX_CONTROL_SECONDS)
            }
            val controlA =
                if (entryHeading == null) start.interpolate(end, 1.0 / 3.0)
                else start.destination(entryHeading, controlDistance(from) / EARTH_RADIUS_METERS)
            val controlB =
                if (exitHeading == null) start.interpolate(end, 2.0 / 3.0)
                else end.destination(exitHeading + 180.0, controlDistance(to) / EARTH_RADIUS_METERS)
            val curved = entryHeading != null || exitHeading != null
            // Bounded work per gap. Arc-length timing avoids artificial slowing near short
            // handles, while the stored fractions let the aircraft use these exact same chords.
            val steps = ceil(distance / 1_000.0).toInt().coerceIn(32, 512)
            val points =
                (0..steps).map { step ->
                    val t = step.toDouble() / steps
                    if (!curved) start.interpolate(end, t)
                    else {
                        val ab = start.interpolate(controlA, t)
                        val bc = controlA.interpolate(controlB, t)
                        val cd = controlB.interpolate(end, t)
                        ab.interpolate(bc, t).interpolate(bc.interpolate(cd, t), t)
                    }
                }
            val cumulative = DoubleArray(points.size)
            for (i in 1..points.lastIndex) {
                cumulative[i] =
                    cumulative[i - 1] +
                        atan2(points[i - 1].cross(points[i]).length(), points[i - 1].dot(points[i]))
            }
            val total = cumulative.last()
            val vertices =
                points
                    .mapIndexed { index, point ->
                        val fraction =
                            if (total > 1e-12) cumulative[index] / total
                            else index.toDouble() / steps
                        val sample =
                            when (index) {
                                0 -> from
                                steps -> to
                                else ->
                                    FlightSampleInterpolator.interpolateSamples(
                                            from,
                                            to,
                                            fraction.toFloat(),
                                        )
                                        .copy(
                                            latitude = point.latitude(),
                                            longitude = point.longitude(),
                                        )
                            }
                        FlightGapVertex(fraction, sample)
                    }
                    .filterIndexed { index, vertex ->
                        index == 0 ||
                            index == steps ||
                            total <= 1e-12 ||
                            vertex.fraction > cumulative[index - 1] / total
                    }
            // Orient along the path, not by averaging unrelated compass readings through a gap.
            val directed =
                vertices.mapIndexed { index, vertex ->
                    val before = vertices[(index - 1).coerceAtLeast(0)].sample
                    val after = vertices[(index + 1).coerceAtMost(vertices.lastIndex)].sample
                    val heading =
                        when (index) {
                            0 ->
                                entryHeading?.toFloat()
                                    ?: FlightTrackMath.bearingBetween(vertex.sample, after)
                            vertices.lastIndex ->
                                exitHeading?.toFloat()
                                    ?: FlightTrackMath.bearingBetween(before, vertex.sample)
                            else -> FlightTrackMath.bearingBetween(before, after)
                        }
                    vertex.copy(
                        sample =
                            vertex.sample.copy(
                                bearingDegrees = heading ?: vertex.sample.bearingDegrees
                            )
                    )
                }
            return FlightGapTrajectory(from, to, directed)
        }

        private fun mercatorY(latitude: Double): Double =
            ln(
                tan(
                    PI / 4 +
                        Math.toRadians(
                            latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
                        ) / 2
                )
            )

        private fun latitudeFromMercator(y: Double): Double = Math.toDegrees(atan(sinh(y)))

        private fun wrapLongitude(value: Double): Double =
            ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

        private fun interpolateHeading(a: Float?, b: Float?, t: Double): Float? {
            if (a == null || b == null) return a ?: b
            return ((a + wrapLongitude((b - a).toDouble()) * t + 360.0) % 360.0).toFloat()
        }

        private data class SpherePoint(val x: Double, val y: Double, val z: Double) {
            operator fun plus(other: SpherePoint) =
                SpherePoint(x + other.x, y + other.y, z + other.z)

            operator fun times(scale: Double) = SpherePoint(x * scale, y * scale, z * scale)

            fun dot(other: SpherePoint) = x * other.x + y * other.y + z * other.z

            fun cross(other: SpherePoint) =
                SpherePoint(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x,
                )

            fun length() = sqrt(dot(this))

            fun normalized() = this * (1.0 / length().coerceAtLeast(1e-15))

            fun latitude() = Math.toDegrees(atan2(z, hypot(x, y)))

            fun longitude() = Math.toDegrees(atan2(y, x))

            fun interpolate(other: SpherePoint, t: Double): SpherePoint {
                val dot = dot(other).coerceIn(-1.0, 1.0)
                val crossProduct = cross(other)
                val angle = atan2(crossProduct.length(), dot)
                if (angle < 1e-8) return (this * (1.0 - t) + other * t).normalized()
                // An exactly antipodal pair has no unique shortest arc; choose a stable plane.
                val axis =
                    if (crossProduct.length() > 1e-12) crossProduct.normalized()
                    else
                        cross(
                                if (abs(z) < 0.9) SpherePoint(0.0, 0.0, 1.0)
                                else SpherePoint(1.0, 0.0, 0.0)
                            )
                            .normalized()
                return (this * cos(angle * t) + axis.cross(this) * sin(angle * t)).normalized()
            }

            fun destination(headingDegrees: Double, angle: Double): SpherePoint {
                val longitude = atan2(y, x)
                val east = SpherePoint(-sin(longitude), cos(longitude), 0.0)
                val north = cross(east).normalized()
                val heading = Math.toRadians(headingDegrees)
                return (this * cos(angle) +
                        (north * cos(heading) + east * sin(heading)) * sin(angle))
                    .normalized()
            }

            companion object {
                fun from(sample: FlightSample): SpherePoint {
                    val latitude = Math.toRadians(sample.latitude)
                    val longitude = Math.toRadians(sample.longitude)
                    return SpherePoint(
                        cos(latitude) * cos(longitude),
                        cos(latitude) * sin(longitude),
                        sin(latitude),
                    )
                }
            }
        }
    }
}
