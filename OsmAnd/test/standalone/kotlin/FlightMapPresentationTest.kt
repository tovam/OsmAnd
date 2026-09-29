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
            val seconds = flightTimeScaleStep(visibleMeters / speed)
            val fraction = seconds * speed / visibleMeters
            assertTrue(fraction > 0 && fraction <= 1)
            assertEquals(seconds, fraction * visibleMeters / speed, 1e-9)
        }
    }

    @Test
    fun timeScaleUsesReadableIntervalsAndLabels() {
        assertEquals(15.0, flightTimeScaleStep(17.0), 0.0)
        assertEquals(30.0, flightTimeScaleStep(59.9), 0.0)
        assertEquals(60.0, flightTimeScaleStep(60.0), 0.0)
        assertEquals(120.0, flightTimeScaleStep(299.9), 0.0)
        assertEquals(300.0, flightTimeScaleStep(300.0), 0.0)
        assertEquals(300.0, flightTimeScaleStep(599.9), 0.0)
        assertEquals(600.0, flightTimeScaleStep(600.0), 0.0)
        assertEquals(900.0, flightTimeScaleStep(960.0), 0.0)
        assertEquals(1800.0, flightTimeScaleStep(31 * 60.0), 0.0)
        assertEquals(7200.0, flightTimeScaleStep(8000.0), 0.0)
        assertEquals("30 s", flightTimeScaleLabel(30.0))
        assertEquals("5 min", flightTimeScaleLabel(300.0))
        assertEquals("10 min", flightTimeScaleLabel(600.0))
        assertEquals("2 h", flightTimeScaleLabel(7200.0))
        assertEquals(0.0, flightTimeScaleStep(0.5), 0.0)
        assertEquals(
            setOf(60.0, 120.0, 300.0, 600.0, 900.0, 1800.0),
            (60 until 3600).map { flightTimeScaleStep(it.toDouble()) }.toSet(),
        )
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
