package net.osmand.test.junit

import kotlin.math.abs
import net.osmand.plus.plugins.flightmode.FlightLiveTimeline
import net.osmand.plus.plugins.flightmode.FlightOfflinePreparation
import net.osmand.plus.plugins.flightmode.FlightPlan
import net.osmand.plus.plugins.flightmode.FlightPreparation
import net.osmand.plus.plugins.flightmode.FlightProfilePlanner
import net.osmand.plus.plugins.flightmode.FlightReplayEngine
import net.osmand.plus.plugins.flightmode.FlightRouteHypothesis
import net.osmand.plus.plugins.flightmode.FlightSample
import net.osmand.plus.plugins.flightmode.FlightStop
import net.osmand.plus.plugins.flightmode.FlightStopType
import net.osmand.plus.plugins.flightmode.recordedFlightTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure synthetic route semantics: passage is overflight, escale is the only planned landing. */
class FlightWaypointPlanTest {

    @Test
    fun passageKeepsTheProfileAirborneAcrossItsTwoLegs() {
        val profile = FlightProfilePlanner.build(
            FlightPlan(
                listOf(
                    FlightStop("A", 0.0, 0.0),
                    FlightStop("Passage", 0.0, 5.0, FlightStopType.WAYPOINT),
                    FlightStop("B", 0.0, 10.0),
                ),
            ),
        )

        assertTrue(profile.legs[0].points.last().altitudeMeters > 0f)
        assertTrue(profile.legs[1].points.first().altitudeMeters > 0f)
        assertEquals(
            profile.legs.sumOf { it.estimatedDurationMinutes },
            profile.totalDurationMinutes,
        )
    }

    @Test
    fun legacyIntermediateDefaultsToEscaleWithGroundProfileAndDwell() {
        val profile = FlightProfilePlanner.build(
            FlightPlan(
                listOf(FlightStop("A", 0.0, 0.0), FlightStop("Legacy", 0.0, 5.0), FlightStop("B", 0.0, 10.0)),
            ),
        )

        assertEquals(0f, profile.legs[0].points.last().altitudeMeters, 0f)
        assertEquals(0f, profile.legs[1].points.first().altitudeMeters, 0f)
        assertEquals(profile.legs.sumOf { it.estimatedDurationMinutes } + 45, profile.totalDurationMinutes)
    }

    @Test
    fun incompleteWaypointDoesNotGetRemovedAndSpliceTheRoute() {
        val plan = FlightPlan(
            listOf(
                FlightStop("A", 0.0, 0.0),
                FlightStop("Unresolved passage", type = FlightStopType.WAYPOINT),
                FlightStop("B", 0.0, 10.0),
            ),
        )
        val fix = sample(longitude = 2.0)

        assertNull(FlightRouteHypothesis.distanceToPlanKm(plan, fix))
        assertTrue(FlightRouteHypothesis.remaining(plan, fix).isEmpty())
        assertTrue(FlightRouteHypothesis.remainingStops(plan, fix).isEmpty())
    }

    @Test
    fun remainingStopsStartAtTheDeterministicallyClosestForwardSegment() {
        val plan = FlightPlan(
            listOf(
                FlightStop("A", 0.0, 0.0),
                FlightStop("Passage", 0.0, 10.0, FlightStopType.WAYPOINT),
                FlightStop("B", 0.0, 20.0),
            ),
        )

        assertEquals(listOf(2), FlightRouteHypothesis.remainingStops(plan, sample(longitude = 12.0)).map { it.index })
    }

    @Test
    fun simulationInputIncludesPassageAndEscaleTypes() {
        val stopover = FlightPlan(
            listOf(FlightStop("A", 0.0, 0.0), FlightStop("Intermediate", 0.0, 5.0), FlightStop("B", 0.0, 10.0)),
        )
        val passage = stopover.copy(
            stops = stopover.stops.mapIndexed { index, stop ->
                if (index == 1) stop.copy(type = FlightStopType.WAYPOINT) else stop
            },
        )

        assertNotEquals(
            FlightOfflinePreparation.simulationInput(stopover),
            FlightOfflinePreparation.simulationInput(passage),
        )
    }

    @Test
    fun offlineSimulationStaysAirborneAtPassageAndDwellsOnlyAtEscale() {
        val start = 1_000_000L
        val trip = FlightOfflinePreparation.simulation(
            FlightPlan(
                stops = listOf(
                    FlightStop("A", 0.0, 0.0),
                    FlightStop("Passage", 0.0, 5.0, FlightStopType.WAYPOINT),
                    FlightStop("Escale", 0.0, 10.0),
                    FlightStop("B", 0.0, 15.0),
                ),
                preparation = FlightPreparation(
                    departureMillis = start,
                    arrivalMillis = start + 4 * 3_600_000L,
                ),
            ),
        )
        val passage = trip.samples.first { abs(it.longitude - 5.0) < 0.0001 }
        val escaleGround = trip.samples.filter {
            abs(it.longitude - 10.0) < 0.0001 && it.altitudeMeters == 0.0 && it.speedMetersPerSecond == 0f
        }

        assertTrue(passage.altitudeMeters!! > 0.0)
        assertTrue(escaleGround.size > 1)
        assertEquals(start + 4 * 3_600_000L, trip.samples.last().timestampMillis)
    }

    @Test
    fun displayFutureFollowsPassageAndEscaleWithoutChangingRecordedSamples() {
        val time = 1_000_000L
        val fix = sample(time = time, longitude = 1.0)
        val recorded = recordedFlightTrip("synthetic", listOf(fix))
        val plan = FlightPlan(
            stops = listOf(
                FlightStop("A", 0.0, 0.0),
                FlightStop("Passage", 0.0, 5.0, FlightStopType.WAYPOINT),
                FlightStop("Escale", 0.0, 10.0),
                FlightStop("B", 0.0, 15.0),
            ),
            preparation = FlightPreparation(arrivalMillis = time + 4 * 3_600_000L),
        )

        val timeline = FlightLiveTimeline.build(plan, recorded, fix)
        val future = timeline.samples.drop(1)
        val passage = future.first { abs(it.longitude - 5.0) < 0.0001 }
        val escale = future.filter { abs(it.longitude - 10.0) < 0.0001 && it.altitudeMeters == 0.0 }

        assertEquals(listOf(fix), recorded.samples)
        assertEquals(fix, timeline.samples.first())
        assertTrue(passage.altitudeMeters!! > 0.0)
        assertTrue(escale.size > 1)
        assertTrue(timeline.samples.all { it.legIndex == fix.legIndex })
        assertFalse(
            FlightReplayEngine(timeline)
                .snapshotAt(FlightLiveTimeline.progress(timeline, passage.timestampMillis))
                .dataGap,
        )
        assertEquals(time + 4 * 3_600_000L, future.last().timestampMillis)
        future.forEach {
            assertNull(it.horizontalAccuracyMeters)
            assertNull(it.satellitesUsed)
        }
    }

    private fun sample(time: Long = 1_000_000L, longitude: Double): FlightSample =
        FlightSample(
            index = 0,
            legIndex = 0,
            timestampMillis = time,
            latitude = 0.0,
            longitude = longitude,
            altitudeMeters = 9_000.0,
            speedMetersPerSecond = 220f,
            bearingDegrees = null,
            horizontalAccuracyMeters = 5f,
            satellitesUsed = 8,
        )
}
