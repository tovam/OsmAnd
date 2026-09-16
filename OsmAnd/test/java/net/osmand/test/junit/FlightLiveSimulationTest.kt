package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightLiveSimulationTest {
    private val plan =
        FlightPlan(
            listOf(FlightStop("A", 48.0, 2.0), FlightStop("B", 43.0, 10.0)),
            preparation = FlightPreparation(startMinutesBefore = 15, stopMinutes = 30),
        )

    @Test
    fun realDetectorRunsThroughAirportTakeoffAndThirtyMinutesAfterLanding() {
        val source = FlightLiveSimulation(plan, null, 1_800_000_000_000)
        var tracking = FlightTrackingState()
        val phases = linkedSetOf<FlightTrackingPhase>()
        var time = source.beginning
        while (time <= source.end) {
            val fix = source.sampleAt(time)
            tracking = tracking.accept(fix, time, source.activePlan.preparation!!)
            phases += tracking.phase
            if (time < source.departure) assertEquals(FlightTrackingPhase.WAITING, tracking.phase)
            if (time >= source.arrival && time < source.arrival + 30 * 60_000L)
                assertNotEquals(FlightTrackingPhase.LANDED, tracking.phase)
            time += 1000
        }
        assertEquals(
            listOf(
                FlightTrackingPhase.WAITING,
                FlightTrackingPhase.AIRBORNE,
                FlightTrackingPhase.LANDED,
            ),
            phases.toList(),
        )
        assertEquals(FlightTrackingPhase.LANDED, tracking.phase)
        assertFalse(source.activePlan.preparation!!.automatic)
    }

    @Test
    fun replayedRecordingKeepsItsGeographyAndDoesNotMutateTheSource() {
        val original = FlightOfflinePreparation.simulation(plan)
        val originalTime = original.samples.first().timestampMillis
        val source = FlightLiveSimulation(plan, original, 2_000_000_000_000)
        val fix = source.sampleAt(source.departure + (source.arrival - source.departure) / 2)
        val expected = FlightReplayEngine(original).snapshotAt(0.5f).sample
        assertEquals(expected.latitude, fix.latitude, 0.00001)
        assertEquals(expected.longitude, fix.longitude, 0.00001)
        assertEquals(expected.altitudeMeters, fix.altitudeMeters)
        assertEquals(originalTime, original.samples.first().timestampMillis)
        assertNull(fix.satellitesUsed)
        assertNull(fix.soundDb)
    }

    @Test
    fun virtualClockPausesChangesRateAndBoundsWakeupCatchup() {
        val clock = FlightSimulationClock(100_000)
        clock.advance(1000)
        assertEquals(160_000L, clock.advance(2000))
        clock.paused = true
        assertEquals(160_000L, clock.advance(3000))
        clock.paused = false
        clock.rate = 10
        assertEquals(165_000L, clock.advance(3500))
        assertEquals(175_000L, clock.advance(500_000))
    }

    @Test
    fun invisibleSimulationCannotAdvanceAndResumeDoesNotCatchUp() {
        val clock = FlightSimulationClock(100_000)
        clock.rebase(1000)
        assertEquals(160_000L, clock.advance(2000))
        clock.backgroundPaused = true
        assertEquals(160_000L, clock.advance(3_600_000))
        clock.backgroundPaused = false
        clock.rebase(7_200_000)
        assertEquals(166_000L, clock.advance(7_200_100))
    }

    @Test
    fun returningToForegroundNeverClearsManualPause() {
        val clock = FlightSimulationClock(100_000)
        clock.paused = true
        clock.backgroundPaused = true
        clock.rebase(1000)
        clock.backgroundPaused = false
        clock.rebase(100_000)
        assertEquals(100_000L, clock.advance(101_000))
        assertTrue(clock.paused)
    }
}
