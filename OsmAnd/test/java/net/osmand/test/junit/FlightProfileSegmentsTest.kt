package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightProfileSegmentsTest {
    private val points = listOf(FlightProfilePoint(0f, 0f, 0), FlightProfilePoint(1f, 10000f, 0))
    @Test fun boundaryInsideClimbIsSharedWithoutOffset() {
        val (measured, predicted) = splitFlightProfilePoints(points, 0.4f)
        assertEquals(measured.last(), predicted.first())
        assertEquals(4000f, measured.last().altitudeMeters, 0.001f)
        assertEquals(points.first(), measured.first())
        assertEquals(points.last(), predicted.last())
    }
    @Test fun exactEndpointAndAbsentFutureStayContinuous() {
        val (past, future) = splitFlightProfilePoints(points, 1f)
        assertEquals(points, past)
        assertEquals(listOf(points.last()), future)
        assertEquals(points to emptyList<FlightProfilePoint>(), splitFlightProfilePoints(points, null))
    }
}
