package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightAltitudeProfileTest {
    private fun sample(index: Int, time: Long, altitude: Double) = FlightSample(
        index, 0, time, 48.0, 2.0 + index * 0.01, altitude, 200f, 90f, 5f)

    @Test fun graphsUseTheirOwnTimeAxisWithoutChangingRecordedSamples() {
        val points = listOf(sample(0, 1000, 0.0), sample(1, 2000, 10000.0))
        val recorded = recordedFlightTrip("test", points)
        val full = recordedFlightTrip("test", points + sample(2, 5000, 0.0))
        val fallback = FlightProfilePlanner.fromTrip(full)
        val measured = selectFlightAltitudeProfile(recorded, full, fallback, true, false)
        val estimated = selectFlightAltitudeProfile(recorded, full, fallback, true, true)
        assertEquals(1f, measured.progress(2000, 0f), 0.0001f)
        assertEquals(0.25f, estimated.progress(2000, 0f), 0.0001f)
        assertEquals(1f, measured.photoProgress(recorded, 1.0)!!, 0.0001f)
        assertEquals(0.25f, estimated.photoProgress(recorded, 1.0)!!, 0.0001f)
        assertEquals(points, recorded.samples)
    }

    @Test fun missingPredictionDoesNotReplaceMeasuredDataWithPlannedProfile() {
        val recorded = recordedFlightTrip("test", listOf(sample(0, 1000, 700.0)))
        val fallback = FlightProfilePlanner.build(FlightPlan.preview())
        val result = selectFlightAltitudeProfile(recorded, null, fallback, true, true)
        assertEquals(700f, result.profile.points.single().altitudeMeters)
        assertSame(fallback, selectFlightAltitudeProfile(recorded, null, fallback, false, true).profile)
    }

    @Test fun fullTimelineHighlightsOnlyGapsFromRecordedSamples() {
        val recordedSamples = listOf(
            sample(0, 1000, 100.0), sample(1, 2000, 200.0),
            sample(2, 3000, 300.0), sample(3, 8000, 400.0),
        )
        val recorded = recordedFlightTrip("test", recordedSamples)
        val future = recordedFlightTrip("test", recordedSamples +
            sample(4, 38_000, 500.0) + sample(5, 68_000, 600.0))
        val selected = selectFlightAltitudeProfile(recorded, future, FlightProfilePlanner.fromTrip(future), true, true)
        assertEquals(listOf(3000L), selected.gaps.map { it.from.timestampMillis })
        assertEquals(recordedSamples, recorded.samples)
    }

    @Test fun replayProfileUsesRecordedTripForGapProgress() {
        val samples = listOf(
            sample(0, 1000, 100.0), sample(1, 2000, 200.0),
            sample(2, 3000, 300.0), sample(3, 8000, 400.0),
        )
        val recorded = recordedFlightTrip("replay", samples)
        val fallback = FlightProfilePlanner.fromTrip(recorded)
        val selected = selectFlightAltitudeProfile(recorded, null, fallback, live = false, includeFuture = false)
        assertNull(selected.trip)
        assertSame(recorded, selected.gapTrip)
        assertEquals(listOf(3000L), selected.gaps.map { it.from.timestampMillis })
    }
}
