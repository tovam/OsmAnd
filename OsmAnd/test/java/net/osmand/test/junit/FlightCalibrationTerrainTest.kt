package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightCalibrationTerrainTest {
    @Test fun missingFineReliefUsesExistingParentWithoutFetching() {
        val visited = mutableListOf<Int>()
        val height = cachedCalibrationElevation(44.0, 6.0) { id ->
            visited += id.zoom
            if (id.zoom == 11) TerrariumTile(id, 2, 2, FloatArray(4) { 2500f }) else null
        }
        assertEquals(2500.0, height!!, 0.001)
        assertEquals(listOf(14, 13, 12, 11), visited)
    }
    @Test fun missingReliefIsNotInventedAsSeaLevel() {
        assertNull(cachedCalibrationElevation(44.0, 6.0) { null })
    }
    @Test fun corruptFineSamplesFallBackButRealSeaLevelIsValid() {
        val height = cachedCalibrationElevation(0.0, 0.0) { id ->
            TerrariumTile(id, 2, 2, FloatArray(4) { if (id.zoom == 14) Float.NaN else 0f })
        }
        assertEquals(0.0, height!!, 0.0)
    }
}
