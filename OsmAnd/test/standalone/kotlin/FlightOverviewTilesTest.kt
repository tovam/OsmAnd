package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FlightOverviewTilesTest {
    @Test fun importedTrackPreparationIncludesCoarseBackdropInDownloadAndByteCounts() = runBlocking {
        val samples = listOf(
            FlightSample(0, 0, 1_000, 48.0, 2.0, 1000.0, 200f, 90f, 5f),
            FlightSample(1, 0, 2_000, 48.0, 3.0, 1000.0, 200f, 90f, 5f),
        )
        val quote = FlightOfflinePreparation.corridorQuote(
            FlightPlan(emptyList(), terrainCorridorKm = 20), recordedFlightTrip("synthetic", samples))
        val coarse = quote.requests.filter { it.satellite && it.tile.zoom == 4 }
        assertTrue(coarse.isNotEmpty())
        assertTrue(coarse.all { it.band == -1 })
        assertEquals(quote.requests.size, quote.requests.map { it.satellite to it.tile }.distinct().size)
        assertEquals(quote.requests.count { it.satellite }, quote.satelliteCount)
        assertTrue(quote.bandEstimatedBytes.getValue(-1) > 0L)
        assertEquals(4, quote.requests.first().tile.zoom)
        quote.requests.filter { it.tile.zoom > 4 && it.satellite }.forEach {
            val shift = it.tile.zoom - 4
            assertTrue(coarse.any { c -> c.tile == TerrainTileId(4, it.tile.x shr shift, it.tile.y shr shift) })
        }
    }

    @Test fun backdropCoversTheAircraftAtCoarseZoom() {
        val ids = flightOverviewTiles(48.0, 2.0, 1_000_000.0)
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { it.zoom == 4 && it.x in 0..15 && it.y in 0..15 })
        assertTrue(ids.contains(TerrainTileId(4,
            kotlin.math.floor(FlightTerrainTilePlanner.longitudeToTileX(2.0, 4)).toInt(),
            kotlin.math.floor(FlightTerrainTilePlanner.latitudeToTileY(48.0, 4)).toInt())))
    }

    @Test fun dateLineWrapsWithoutLoadingTheWholeEarth() {
        val ids = flightOverviewTiles(0.0, 179.99, 1_000_000.0)
        assertEquals(setOf(15, 0), ids.map { it.x }.toSet())
        assertEquals(ids.distinct(), ids)
        assertTrue(ids.size <= 8)
    }

    @Test fun largestViewAndPolarViewsHaveBoundedRequests() {
        for (latitude in listOf(-85.0, 0.0, 85.0)) {
            val ids = flightOverviewTiles(latitude, 179.0, 20_000_000.0)
            assertTrue(ids.size in 1..256)
            assertTrue(ids.all { it.x in 0..15 && it.y in 0..15 })
        }
        assertTrue(flightOverviewTiles(Double.NaN, 0.0, 1000.0).isEmpty())
    }
}
