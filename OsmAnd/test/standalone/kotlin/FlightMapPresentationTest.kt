package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightMapPresentationTest {
    private fun sample(index: Int, longitude: Double) =
        FlightSample(index, 0, index * 1000L, 45.0, longitude, 12000.0, 250f, 90f, 5f)

    @Test
    fun scaleFitsViewportAtEveryZoom() {
        for (raw in listOf(0.01, 0.9, 1.0, 2.4, 38.0, 199.0, 2300.0, 999999.0)) {
            val scale = flightScaleStep(raw)
            assertTrue(scale > 0.0)
            assertTrue(scale <= raw)
            assertTrue(scale >= raw / 2.5)
        }
        assertEquals(0.0, flightScaleStep(Double.NaN), 0.0)
        assertEquals(0.0, flightScaleStep(0.0), 0.0)
    }

    @Test
    fun timeScaleRepresentsCurrentSpeed() {
        for (speed in listOf(1.0, 50.0, 250.0)) {
            val visibleMeters = 6400.0
            val seconds = flightScaleStep(visibleMeters / speed)
            val fraction = seconds * speed / visibleMeters
            assertTrue(fraction > 0 && fraction <= 1)
            assertEquals(seconds, fraction * visibleMeters / speed, 1e-9)
        }
    }

    @Test
    fun dashesKeepAltitudeAndAreBoundedAndSeparated() {
        val route = List(1001) { sample(it, 2.0 + it * 0.01) }
        val dashes = flightRouteDashes(route)
        assertTrue(dashes.size in 2..96)
        assertEquals(route.first().longitude, dashes.first().first().longitude, 0.0)
        for (dash in dashes) {
            assertTrue(dash.size >= 2)
            assertTrue(dash.last().longitude > dash.first().longitude)
            assertTrue(dash.all { it.altitudeMeters == 12000.0 && it.latitude == 45.0 })
        }
        for ((a, b) in dashes.zipWithNext()) assertTrue(a.last().longitude < b.first().longitude)
    }

    @Test
    fun stationaryAndEmptyPathsProduceNoDegenerateMesh() {
        assertTrue(flightRouteDashes(emptyList()).isEmpty())
        assertTrue(flightRouteDashes(listOf(sample(0, 2.0))).isEmpty())
        assertTrue(flightRouteDashes(List(5) { sample(it, 2.0) }).isEmpty())
    }
}
