package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightWorkPolicy
import org.junit.Assert.*
import org.junit.Test

class FlightCameraWorkPolicyTest {
    @Test fun cameraSuspendsOnlyVisualWork() {
        assertTrue(FlightWorkPolicy.visualWorkActive(true, false))
        assertFalse(FlightWorkPolicy.visualWorkActive(true, true))
        assertFalse(FlightWorkPolicy.visualWorkActive(false, false))
        assertFalse(FlightWorkPolicy.visualWorkActive(false, true))
        assertTrue(FlightWorkPolicy.recordingActive(false, false, false))
        assertTrue(FlightWorkPolicy.recordingActive(true, true, false))
    }
}
