package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightCameraOptics
import net.osmand.plus.plugins.flightmode.FlightCameraOptics.Preference
import org.junit.Assert.*
import org.junit.Test

class FlightCameraOpticsTest {
    @Test
    fun sameFocalLengthDoesNotMeanSameFieldOfView() {
        assertEquals(
            2f,
            FlightCameraOptics.fieldScale(6f, 5f) / FlightCameraOptics.fieldScale(6f, 10f),
            0.001f,
        )
    }

    @Test
    fun invalidCharacteristicsDoNotProduceInfinity() {
        assertEquals(0f, FlightCameraOptics.fieldScale(6f, 0f), 0f)
        assertEquals(0f, FlightCameraOptics.fieldScale(null, 10f), 0f)
        assertEquals(0f, FlightCameraOptics.fieldScale(Float.NaN, 10f), 0f)
    }

    @Test
    fun mainCameraDoesNotDependOnEnumerationOrder() {
        val fields = listOf(120f / 36, 12f / 36, 24f / 36)
        assertEquals(24f / 36, FlightCameraOptics.mainScale(fields, 0f), 0.001f)
        assertEquals(24f / 36, FlightCameraOptics.mainScale(fields.reversed(), 0f), 0.001f)
    }

    @Test
    fun rearLockedMainIsPreferredOverFrontAndAutomaticLogicalCamera() {
        val choices =
            listOf(
                Preference(true, true, 1f),
                Preference(false, false, 1f),
                Preference(false, true, 5f),
                Preference(false, true, 0.5f),
                Preference(false, true, 1f),
            )
        assertEquals(4, FlightCameraOptics.defaultIndex(choices))
    }

    @Test
    fun emptyAndSingleCameraAreHandled() {
        assertEquals(0, FlightCameraOptics.defaultIndex(emptyList()))
        assertEquals(0, FlightCameraOptics.defaultIndex(listOf(Preference(true, true, 1f))))
        assertEquals(0.7f, FlightCameraOptics.mainScale(emptyList(), 0.7f), 0f)
    }
}
