package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightRelativeOrientationTest {
    @Test fun aircraftHeadingChangesAreNotCountedTwice() {
        val controller = FlightPhoneAimController()
        var look = FlightWindowLook()
        controller.reset(look, 20f)
        controller.update(100f, 0f, look, 20f)
        repeat(100) {
            controller.update(120f, 0f, look, 40f)?.let { look = look.copy(
                yawDegrees = look.yawDegrees + it.first, pitchDegrees = look.pitchDegrees + it.second).clamped() }
        }
        assertEquals(0f, look.yawDegrees, 0.04f)
    }
    @Test fun exceedingCameraPitchLimitDoesNotShiftTheNeutralReference() {
        val controller = FlightPhoneAimController()
        var look = FlightWindowLook()
        controller.reset(look)
        controller.update(0f, 0f, look)
        repeat(80) {
            controller.update(0f, 70f, look)?.let { look = look.copy(
                yawDegrees = look.yawDegrees + it.first, pitchDegrees = look.pitchDegrees + it.second).clamped() }
        }
        assertEquals(FlightWindowLook.MAX_PITCH_DEGREES, look.pitchDegrees, 0.001f)
        repeat(80) {
            controller.update(0f, 0f, look)?.let { look = look.copy(
                yawDegrees = look.yawDegrees + it.first, pitchDegrees = look.pitchDegrees + it.second).clamped() }
        }
        assertEquals(0f, look.pitchDegrees, 0.04f)
    }
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
