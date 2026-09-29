package net.osmand.test.junit

import kotlin.math.*
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightGapTrajectoryTest {
    @Test
    fun aircraftIsOnTheActualRedMapPolylineAtEveryTestedTimeAndAltitude() {
        val samples = turningSamples()
        val trip = trip(samples)
        val engine = FlightReplayEngine(trip)
        val path = flightRoutePaths(samples).single { it.isGap }
        assertTrue(path.vertices.size > 2)
        for (step in 1..199) {
            val requested = 2_000L + 40_000L * step / 200
            val snapshot = engine.snapshotAt(((requested - 1000L) / 42_000.0).toFloat())
            val fraction = (snapshot.sample.timestampMillis - 2000L) / 40_000.0
            // Independently locate the two vertices sent to the map renderer, not a second call
            // to the trajectory's sampleAt. Mercator line intersection is the former failure.
            val position = 1.0 + fraction
            val upper =
                path.vertices.indexOfFirst { it.sourcePosition >= position }.coerceAtLeast(1)
            val a = path.vertices[upper - 1]
            val b = path.vertices[upper]
            val t = (position - a.sourcePosition) / (b.sourcePosition - a.sourcePosition)
            val expectedX = a.sample.longitude + wrap(b.sample.longitude - a.sample.longitude) * t
            val expectedY = mercator(a.sample.latitude) * (1 - t) + mercator(b.sample.latitude) * t
            assertEquals(expectedX, snapshot.sample.longitude, 2e-7)
            assertEquals(expectedY, mercator(snapshot.sample.latitude), 4e-9)
            val expectedAltitude =
                a.sample.altitudeMeters!! * (1 - t) + b.sample.altitudeMeters!! * t
            assertEquals(expectedAltitude, snapshot.sample.altitudeMeters!!, 0.003)
            assertTrue(snapshot.dataGap)
        }
    }

    @Test
    fun joinsFollowAdjacentMeasuredMotionAndIgnoreStaleEndpointBearings() {
        val samples = turningSamples()
        val curve = FlightGapTrajectory.buildAll(samples).getValue(1)
        val initial = (FlightTrackMath.bearingBetween(samples[1], samples[0])!! + 180f) % 360f
        val terminal = FlightTrackMath.bearingBetween(samples[2], samples[3])!!
        val departure = FlightTrackMath.bearingBetween(samples[1], curve.sampleAt(0.0001))!!
        val arrival = FlightTrackMath.bearingBetween(curve.sampleAt(0.9999), samples[2])!!
        assertTrue(
            "entry $initial -> $departure",
            abs(wrap((departure - initial).toDouble())) < 4.0,
        )
        assertTrue("exit $terminal -> $arrival", abs(wrap((arrival - terminal).toDouble())) < 4.0)
        assertEquals(initial, curve.sampleAt(0.0001).bearingDegrees!!, 0.2f)
        assertEquals(terminal, curve.sampleAt(0.9999).bearingDegrees!!, 0.2f)
        var previous = curve.sampleAt(0.0001)
        for (step in 1..999) {
            val current = curve.sampleAt(step / 1000.0)
            assertTrue(
                abs(wrap((current.bearingDegrees!! - previous.bearingDegrees!!).toDouble())) < 2.0
            )
            previous = current
        }
    }

    @Test
    fun perpendicularEntryAndExitBendSmoothlyInsteadOfTurningNinetyDegreesAtTheFix() {
        val samples =
            listOf(
                sample(0, 1000, -0.001, 2.0),
                sample(1, 2000, 0.0, 2.0),
                sample(2, 42_000, 0.0, 2.02),
                sample(3, 43_000, 0.001, 2.02),
            )
        val curve = FlightGapTrajectory.buildAll(samples).getValue(1)
        val entry = FlightTrackMath.bearingBetween(samples[1], curve.sampleAt(0.0001))!!
        val exit = FlightTrackMath.bearingBetween(curve.sampleAt(0.9999), samples[2])!!
        assertTrue(abs(wrap(entry.toDouble())) < 7.0)
        assertTrue(abs(wrap(exit.toDouble())) < 7.0)
        assertTrue(curve.sampleAt(0.1).latitude > 0.0)
        assertTrue(curve.sampleAt(0.9).latitude < 0.0)
    }

    @Test
    fun missingHeadingsUseTheShortGreatCircleRatherThanLinearLatitude() {
        val a = sample(0, 1000, 60.0, -50.0, leg = 0)
        val b = sample(1, 3_601_000, 60.0, 40.0, leg = 1)
        val curve = FlightGapTrajectory.buildAll(listOf(a, b)).getValue(0)
        val midpoint = curve.sampleAt(0.5)
        assertTrue(midpoint.latitude > 67.0)
        assertEquals(-5.0, midpoint.longitude, 0.0001)
        assertTrue(curve.vertices.size <= 513)
    }

    @Test
    fun antimeridianCrossingIsContinuousAndUsesShortChords() {
        val a = sample(0, 1000, 20.0, 179.0, leg = 0)
        val b = sample(1, 601_000, 20.0, -179.0, leg = 1)
        val curve = FlightGapTrajectory.buildAll(listOf(a, b)).getValue(0)
        assertEquals(180.0, abs(curve.sampleAt(0.5).longitude), 0.0001)
        assertTrue(curve.vertices.all { abs(it.sample.longitude) > 178.9 })
        for (step in 1..100) {
            assertTrue(
                abs(
                    wrap(
                        curve.sampleAt(step / 100.0).longitude -
                            curve.sampleAt((step - 1) / 100.0).longitude
                    )
                ) < 0.1
            )
        }
    }

    @Test
    fun coincidentAndNearlyAntipodalEndpointsStayFinite() {
        for (end in
            listOf(48.0 to 2.0, 48.00000000001 to 2.0, -48.0 to -178.0, -47.99999 to -178.0)) {
            val a = sample(0, 1000, 48.0, 2.0)
            val b = sample(1, 601_000, end.first, end.second, leg = 1)
            val curve = FlightGapTrajectory.buildAll(listOf(a, b)).getValue(0)
            assertSame(a, curve.sampleAt(0.0))
            assertSame(b, curve.sampleAt(1.0))
            for (step in 1..99) {
                val at = curve.sampleAt(step / 100.0)
                assertTrue(at.latitude.isFinite() && at.longitude.isFinite())
                assertTrue(at.latitude in -90.0..90.0 && at.longitude in -180.0..180.0)
            }
        }
    }

    @Test
    fun implausibleBackwardsBearingsDoNotInventLoops() {
        val a = sample(0, 1000, 0.0, 1.0).copy(bearingDegrees = 270f)
        val b = sample(1, 601_000, 0.0, 2.0, leg = 1).copy(bearingDegrees = 270f)
        val curve = FlightGapTrajectory.buildAll(listOf(a, b)).getValue(0)
        assertTrue(
            curve.vertices.all { it.sample.longitude in 1.0..2.0 && abs(it.sample.latitude) < 1e-8 }
        )
    }

    @Test
    fun recordedVerticesAndOrdinaryBlackSegmentsRemainUnchanged() {
        val samples = turningSamples()
        val original = samples.toList()
        val paths = flightRoutePaths(samples)
        assertEquals(listOf(false, true, false), paths.map { it.isGap })
        assertSame(samples[0], paths.first().vertices[0].sample)
        assertSame(samples[1], paths.first().vertices[1].sample)
        assertSame(samples[2], paths.last().vertices[0].sample)
        assertSame(samples[3], paths.last().vertices[1].sample)
        assertEquals(original, samples)
        assertEquals(4, samples.size)
    }

    @Test
    fun consecutiveRedEdgesKeepEveryCurveVertexEvenWithLowMeasuredBudget() {
        val samples = turningSamples().toMutableList()
        samples.add(2, sample(10, 22_000, 50.01, 2.05, leg = 4))
        val curves = FlightGapTrajectory.buildAll(samples)
        val paths = flightRoutePaths(samples, maximumMeasuredVertices = 2)
        val red = paths.single { it.isGap }
        assertEquals(curves.values.sumOf { it.vertices.size - 1 } + 1, red.vertices.size)
        assertTrue(red.vertices.zipWithNext().all { (a, b) -> b.sourcePosition > a.sourcePosition })
    }

    @Test
    fun liveFutureMustNotSteerTheLastRecordedGap() {
        val measured =
            listOf(
                sample(0, 1000, 49.998, 2.0),
                sample(1, 2000, 49.999, 2.0),
                sample(2, 3000, 50.0, 2.0),
                sample(3, 43_000, 50.025, 2.1),
            )
        val future = sample(4, 44_000, 49.0, 1.0)
        val gaps = flightGpsGaps(measured)
        val measuredCurve = FlightGapTrajectory.buildAll(measured, gaps).getValue(2)
        val liveCurve =
            FlightGapTrajectory.buildAll(measured + future, gaps, recordedSampleCount = 4)
                .getValue(2)
        assertEquals(measuredCurve.vertices, liveCurve.vertices)
    }

    @Test
    fun plannedFlightKeepsItsExistingGeometryAndDoesNotInventRedGaps() {
        val paths = flightRoutePaths(turningSamples(), recorded = false)
        assertEquals(1, paths.size)
        assertFalse(paths.single().isGap)
        assertEquals(4, paths.single().vertices.size)
    }

    private fun turningSamples() =
        listOf(
            sample(0, 1000, 49.999, 2.0),
            sample(1, 2000, 50.0, 2.0).copy(bearingDegrees = 270f),
            sample(2, 42_000, 50.025, 2.1).copy(altitudeMeters = 12_000.0, bearingDegrees = 270f),
            sample(3, 43_000, 50.025, 2.101).copy(altitudeMeters = 12_000.0),
        )

    private fun sample(index: Int, time: Long, latitude: Double, longitude: Double, leg: Int = 0) =
        FlightSample(index, leg, time, latitude, longitude, 10_000.0, 200f, null, 5f)

    private fun trip(samples: List<FlightSample>) =
        FlightTrip("synthetic", samples, emptyList(), true, 0.0, "fixture")

    private fun mercator(latitude: Double) = ln(tan(PI / 4 + Math.toRadians(latitude) / 2))

    private fun wrap(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
