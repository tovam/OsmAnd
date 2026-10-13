package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightRelativeOrientationTest {
    @Test fun calibrationWrapAndResetDoNotJumpTheView() {
        val orientation = FlightRelativeOrientation()
        assertNull(orientation.update(179f, 0f))
        val delta = orientation.update(-179f, 8f)!!
        assertEquals(0.5f, delta.first, 0.001f)
        assertEquals(2f, delta.second, 0.001f)
        orientation.reset()
        assertNull(orientation.update(-90f, -30f))
        assertNull(orientation.update(Float.NaN, 0f))
    }
    @Test fun stationaryPhoneSettlesAndAltitudeGroupingIsStable() {
        val orientation = FlightRelativeOrientation()
        orientation.update(0f, 0f)
        var total = 0f
        repeat(80) { total += orientation.update(40f, 0f)!!.first }
        assertEquals(40f, total, 0.001f)
        assertEquals("10\u202f500 m", formatFlightAltitude(10500.0))
        assertEquals("—", formatFlightAltitude(Double.NaN))
    }
}
