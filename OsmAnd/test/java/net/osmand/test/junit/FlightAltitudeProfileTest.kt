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
}
