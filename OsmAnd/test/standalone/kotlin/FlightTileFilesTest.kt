package net.osmand.test.junit

import kotlinx.coroutines.runBlocking
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** All sources, clocks and tile bytes below are synthetic. No application data is read. */
class FlightTileFilesTest {
    @Test
    fun missingFilesDownloadBeforeCachedVerificationWithoutChangingCoarseFirstOrder() {
        val a = request(key(3, 0, 0))
        val b = request(key(4, 0, 0))
        val c = request(key(5, 0, 0))
        val d = request(key(6, 0, 0))
        val present =
            setOf(
                FlightOfflineTileKey(a.tile, a.satellite),
                FlightOfflineTileKey(c.tile, c.satellite),
            )
        assertEquals(
            listOf(b, d, a, c),
            flightMissingTilesFirst(listOf(a, b, c, d), present::contains),
        )
    }

    private fun key(z: Int = 8, x: Int = 20, y: Int = 40, satellite: Boolean = true) =
        FlightOfflineTileKey(TerrainTileId(z, x, y), satellite)

    private fun request(key: FlightOfflineTileKey) =
        FlightOfflineRequest(key.tile, key.satellite, 0)

    private fun bounds(z: Int = 8, x: Int = 20, y: Int = 40): FlightTileMapBounds {
        val n = (1 shl z).toDouble()
        return FlightTileMapBounds((x + .001) / n, (x + .999) / n, (y + .001) / n, (y + .999) / n)
    }

    @Test
    fun rowsSeparateAllSourcesAndZooms() {
        val keys = listOf(key(), key(satellite = false), key(z = 9), key(z = 9, x = 21))
        val inventory = FlightOfflineInventory(keys.map(::request))
        inventory.changed(keys[0], 40_000)
        inventory.changed(keys[1], 110_000)
        inventory.changed(keys[2], 0)
        val c = inventory.snapshot()
        assertEquals(3, c.levels.size)
        assertEquals(4, c.levels.sumOf { it.required })
        assertEquals(2, c.levels.sumOf { it.present })
        assertEquals(1, c.levels.sumOf { it.missing })
        assertEquals(1, c.levels.sumOf { it.unknown })
        assertEquals(c.storedBytes, c.levels.sumOf { it.storedBytes })
        assertEquals(c.estimatedRemainingBytes, c.levels.sumOf { it.estimatedRemainingBytes })
    }

    @Test
    fun downloadRequiresCompletedInventoryAndAtLeastOneMissingFile() {
        val inventory = FlightOfflineInventory(listOf(request(key())))
        assertFalse(inventory.snapshot().canDownloadMissing)
        assertEquals(0, inventory.snapshot().confirmedMissing)
        inventory.changed(key(), 0)
        assertTrue(inventory.snapshot().canDownloadMissing)
        inventory.changed(key(), 12_345)
        assertFalse(inventory.snapshot().canDownloadMissing)
        assertEquals(0, inventory.snapshot().missing)
    }

    @Test
    fun sharedLevelAverageHelpsNewFlightWithoutCountingUnrelatedFiles() {
        val inventory = FlightOfflineInventory(listOf(request(key())))
        inventory.changed(key(), 0)
        val snapshot =
            inventory.snapshot(listOf(FlightStoredTileLevel(true, 8, 100, 8_000_000)), true)
        assertEquals(80_000L, snapshot.estimatedRemainingBytes)
        assertEquals(0, snapshot.stored)
        assertEquals(100, snapshot.sharedLevels.single().files)
        assertTrue(snapshot.sharedInventoryComplete)
    }

    @Test
    fun catalogDeduplicatesUpdatesAndRecordsDeletion() {
        val c = FlightTileCatalog()
        c.changed(key(), 100)
        val revision = c.revision(key())
        c.changed(key(), 100)
        assertEquals(revision, c.revision(key()))
        c.changed(key(), 200)
        assertEquals(1, c.summary().levels.single().files)
        assertEquals(200L, c.summary().levels.single().bytes)
        c.changed(key(), 0)
        assertTrue(c.summary().levels.isEmpty())
        assertTrue(c.snapshot().files.isEmpty())
    }

    @Test
    fun staleDiskReadCannotReviveRemovedFileOrEraseDownload() {
        val c = FlightTileCatalog()
        val revision = c.revision(key())
        c.changed(key(), 500)
        c.inspected(key(), revision, 0)
        assertEquals(500L, c.get(key())!!.bytes)
        val next = c.revision(key())
        c.changed(key(), 0)
        c.inspected(key(), next, 500)
        assertNull(c.get(key()))
    }

    @Test
    fun oneCatalogFeedsTwoFlightsAndMarksCompletionOnce() {
        val c = FlightTileCatalog()
        val a = FlightOfflineInventory(listOf(request(key())))
        val b = FlightOfflineInventory(listOf(request(key()), request(key(satellite = false))))
        var completions = 0
        val stopA = c.observe { k, bytes -> if (k != null) a.changed(k, bytes) else completions++ }
        val stopB = c.observe { k, bytes -> if (k != null) b.changed(k, bytes) }
        c.changed(key(), 123)
        assertEquals(1, a.snapshot().stored)
        assertEquals(1, b.snapshot().stored)
        c.finishInventory()
        c.finishInventory()
        assertEquals(1, completions)
        assertTrue(c.snapshot().complete)
        stopA()
        stopB()
        c.changed(key(), 0)
        assertEquals(1, a.snapshot().stored)
    }

    @Test
    fun changingGridDoesNotChangeGeographicCamera() {
        val camera = FlightTileMapCamera.fit(listOf(45.0 to 4.0, 48.0 to 14.0), 1080, 1200)!!
        val bounds = camera.bounds(1080, 1200)
        for (zoom in 3..14) FlightTileMapModel.cells(
            bounds,
            zoom,
            FlightTileCatalog().snapshot(),
            emptyList(),
        )
        assertEquals(bounds, camera.bounds(1080, 1200))
    }

    @Test
    fun zoomKeepsFingerOnSameGeographicPoint() {
        val a = FlightTileMapCamera(.52, .35, 100_000.0)
        val b = a.zoom(1.7, 80.0, 600.0, 1080, 1200)
        assertEquals(a.x + (80 - 540) / a.worldPixels, b.x + (80 - 540) / b.worldPixels, 1e-12)
        assertEquals(a.y, b.y, 1e-12)
    }

    @Test
    fun fitUsesRouteNotTileBoundsAndUnwrapsDateLine() {
        val camera = FlightTileMapCamera.fit(listOf(10.0 to 179.0, 11.0 to -179.0), 1000, 1000)!!
        assertTrue(camera.x > .99)
        assertTrue(camera.worldPixels > 100_000)
        val route = flightTileMapRoute(listOf(50.0 to -120.0, 50.0 to 120.0))
        assertTrue(route.minOf { it.second } < FlightTerrainTilePlanner.latitudeToTileY(50.0, 0))
    }

    @Test
    fun importedTrackQuoteFitsActualGpsTrackWithoutNamedCities() = runBlocking {
        val samples =
            listOf(
                FlightSample(0, 0, 1, 45.0, 4.0, 10_000.0, null, null, null),
                FlightSample(1, 0, 2, 46.0, 5.0, 10_000.0, null, null, null),
            )
        val quote =
            FlightOfflinePreparation.corridorQuote(
                FlightPlan(stops = emptyList()),
                recordedFlightTrip("test", samples),
            )
        assertEquals(listOf(45.0 to 4.0, 46.0 to 5.0), quote.route)
        assertTrue(quote.mapRoute.isNotEmpty())
        assertTrue(quote.requests.any { it.tile.zoom == 3 && it.satellite })
        assertTrue(quote.requests.any { it.tile.zoom == 3 && !it.satellite })
    }

    @Test
    fun squareIncludesCachedLevelsNotRequestedBySelectedFlight() {
        val c = FlightTileCatalog()
        c.changed(key(), 100)
        c.changed(key(7, 10, 20, false), 200)
        c.finishInventory()
        val cell = FlightTileMapModel.cells(bounds(), 8, c.snapshot(), emptyList()).single()
        assertEquals(listOf(8), cell.satellite.map { it.zoom })
        assertEquals(listOf(7), cell.terrain.map { it.zoom })
        assertTrue(cell.terrain.single().full)
        assertEquals(3, cell.state)
        assertEquals(0, cell.required)
    }

    @Test
    fun finerPartialLevelDoesNotPretendWholeSquareIsReady() {
        val c = FlightTileCatalog()
        val children = listOf(key(9, 40, 80), key(9, 41, 80), key(9, 40, 81), key(9, 41, 81))
        children.take(3).forEach { c.changed(it, 100) }
        c.finishInventory()
        val cell =
            FlightTileMapModel.cells(bounds(), 8, c.snapshot(), children.map(::request)).single()
        assertEquals("z9(3/4)", cell.satellite.single().compact())
        assertEquals("z9", cell.requestedSatellite.single().compact())
        assertEquals(4, cell.required)
        assertEquals(3, cell.presentRequired)
        assertEquals(2, cell.state)
    }

    @Test
    fun multipleLevelsCoexistAndCoarseFileDoesNotSatisfyFineRequest() {
        val c = FlightTileCatalog()
        c.changed(key(7, 10, 20), 100)
        c.changed(key(), 200)
        c.finishInventory()
        val cell =
            FlightTileMapModel.cells(bounds(), 8, c.snapshot(), listOf(request(key(9, 40, 80))))
                .single()
        assertEquals(listOf(7, 8), cell.satellite.map { it.zoom })
        assertEquals(0, cell.presentRequired)
        assertEquals(2, cell.state)
    }

    @Test
    fun completenessDependsOnRequiredSourcesNotAlwaysBothSources() {
        val c = FlightTileCatalog()
        c.changed(key(), 100)
        c.finishInventory()
        val cell =
            FlightTileMapModel.cells(bounds(), 8, c.snapshot(), listOf(request(key()))).single()
        assertTrue(cell.terrain.isEmpty())
        assertEquals(0, cell.state)
    }

    @Test
    fun unknownCacheDoesNotDisplayFalseMissingCount() {
        val cell =
            FlightTileMapModel.cells(
                    bounds(),
                    8,
                    FlightTileCatalog().snapshot(),
                    listOf(request(key())),
                )
                .single()
        assertFalse(cell.inventoryComplete)
        assertEquals(1, cell.state)
    }

    @Test
    fun wrappedCellsUseActualFilesAcrossTheDateLine() {
        val c = FlightTileCatalog()
        c.changed(key(3, 0, 2), 100)
        c.changed(key(3, 7, 2), 100)
        c.finishInventory()
        val cells =
            FlightTileMapModel.cells(
                FlightTileMapBounds(.90, 1.10, .26, .30),
                3,
                c.snapshot(),
                emptyList(),
            )
        assertEquals(
            setOf(0, 7),
            cells.filter { it.satellite.isNotEmpty() }.map { it.id.x }.toSet(),
        )
        assertEquals(setOf(7, 8), cells.map { it.displayX }.toSet())
    }

    @Test
    fun entireWorldAtZ14IsBoundedAndExplicitlyAggregated() {
        val bounds = FlightTileMapBounds(0.0, 1.0, 0.0, 1.0)
        val cells =
            FlightTileMapModel.cells(bounds, 14, FlightTileCatalog().snapshot(), emptyList())
        assertTrue(cells.size <= 512)
        assertTrue(bounds.gridZoom(14) < 14)
        assertEquals(bounds.gridZoom(14), cells.first().id.zoom)
    }
}
