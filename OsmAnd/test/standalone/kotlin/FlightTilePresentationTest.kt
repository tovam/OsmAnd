package net.osmand.test.junit

import java.util.Locale
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic inventories and cameras only; never accesses the application tile store. */
class FlightTilePresentationTest {
    private val quote =
        FlightOfflineQuote(
            listOf(FlightOfflineRequest(TerrainTileId(8, 20, 40), true, 0)),
            listOf(40.0 to 2.0, 41.0 to 3.0),
            emptyList(),
        )
    private val missing =
        FlightOfflineCoverage(0, 1, inspected = 1, estimatedRemainingBytes = 80_000)

    private fun state() = FlightUiState(offlineQuote = quote, offlineCoverage = missing)

    @Test
    fun unknownInventoryAndRefreshCannotEnableTransfersOrClaimReadiness() {
        for (s in
            listOf(
                state().copy(offlineCoverage = null),
                state().copy(offlineCoverage = missing.copy(inspected = 0)),
                state().copy(offlineCoverageRefreshing = true),
            )) {
            assertEquals(FlightTileReadiness.CHECKING, flightTileReadiness(s))
            assertEquals(FlightTileAction.CHECKING, flightTileAction(s))
            assertFalse(flightTileAction(s).enabled)
        }
    }

    @Test
    fun noRouteDoesNotShowAnEndlessInventorySpinner() {
        assertEquals(FlightTileReadiness.EMPTY, flightTileReadiness(FlightUiState()))
        assertEquals(FlightTileAction.EMPTY, flightTileAction(FlightUiState()))
        assertEquals(
            FlightTileReadiness.CHECKING,
            flightTileReadiness(FlightUiState(offlineCoverageRefreshing = true)),
        )
        assertEquals(
            FlightTileAction.CHECKING,
            flightTileAction(FlightUiState(offlineCoverageRefreshing = true)),
        )
    }

    @Test
    fun knownMissingFilesDownloadAndPausedFilesResume() {
        assertEquals(FlightTileReadiness.MISSING, flightTileReadiness(state()))
        assertEquals(FlightTileAction.DOWNLOAD, flightTileAction(state()))
        val paused =
            state()
                .copy(offlinePreloadStatus = FlightTerrainStatus(phase = FlightTerrainPhase.PAUSED))
        assertEquals(FlightTileReadiness.PAUSED, flightTileReadiness(paused))
        assertEquals(FlightTileAction.RESUME, flightTileAction(paused))
    }

    @Test
    fun runningTransferCanStillBePausedWhileInventoryRefreshes() {
        for (phase in listOf(FlightTerrainPhase.DOWNLOADING, FlightTerrainPhase.PLANNING)) {
            val active =
                state()
                    .copy(
                        offlineCoverage = null,
                        offlineCoverageRefreshing = true,
                        offlinePreloadStatus = FlightTerrainStatus(phase = phase),
                    )
            assertEquals(FlightTileReadiness.DOWNLOADING, flightTileReadiness(active))
            assertEquals(FlightTileAction.PAUSE, flightTileAction(active))
        }
    }

    @Test
    fun presentHeadersRemainDistinctFromVerifiedPixels() {
        val present = state().copy(offlineCoverage = missing.copy(satelliteStored = 1))
        assertEquals(FlightTileReadiness.PRESENT, flightTileReadiness(present))
        assertEquals(FlightTileAction.VERIFY, flightTileAction(present))
        val verified =
            present.copy(
                offlinePreloadStatus =
                    FlightTerrainStatus(
                        phase = FlightTerrainPhase.READY,
                        requestedSatelliteTiles = 1,
                        satelliteTiles = 1,
                        offlineFilesVerified = true,
                    )
            )
        assertEquals(FlightTileReadiness.VERIFIED, flightTileReadiness(verified))
        assertEquals(
            FlightTileReadiness.PRESENT,
            flightTileReadiness(
                verified.copy(
                    offlinePreloadStatus =
                        verified.offlinePreloadStatus.copy(requestedSatelliteTiles = 2)
                )
            ),
        )
    }

    @Test
    fun errorsAndOfflineSimulationNeverEnableAnUnsafeDownload() {
        val error = state().copy(offlineCoverageError = "synthetic failure")
        assertEquals(FlightTileReadiness.ERROR, flightTileReadiness(error))
        assertEquals(FlightTileAction.UNAVAILABLE, flightTileAction(error))
        assertFalse(flightTileAction(error).enabled)
        assertEquals(
            FlightTileAction.OFFLINE_TEST,
            flightTileAction(state().copy(offlineSimulation = true)),
        )
        val failedTransfer =
            state()
                .copy(
                    offlinePreloadStatus =
                        FlightTerrainStatus(
                            phase = FlightTerrainPhase.ERROR,
                            satelliteFailedTiles = 1,
                        )
                )
        assertEquals(FlightTileReadiness.ERROR, flightTileReadiness(failedTransfer))
        assertEquals(FlightTileAction.DOWNLOAD, flightTileAction(failedTransfer))
    }

    @Test
    fun smallFilesDoNotDisappearIntoRoundedZeroGigabytes() {
        assertEquals("—", flightTileSize(-1, Locale.US))
        assertEquals("0 B", flightTileSize(0, Locale.US))
        assertEquals("900 B", flightTileSize(900, Locale.US))
        assertEquals("80 kB", flightTileSize(80_000, Locale.US))
        assertEquals("2.5 MB", flightTileSize(2_500_000, Locale.US))
        assertEquals("2,5 MB", flightTileSize(2_500_000, Locale.FRANCE))
        assertEquals("2.50 GB", flightTileSize(2_500_000_000, Locale.US))
    }

    @Test
    fun inspectionCellsRemainReadableAndBoundedWithoutMutatingCamera() {
        val camera = FlightTileMapCamera(.98, .5, 8192.0)
        val bounds = camera.bounds(1080, 1800)
        val readable = flightTileInspectionZoom(19, bounds, camera.worldPixels, 56.0)
        assertTrue(camera.worldPixels / (1 shl readable) >= 56.0)
        val (xs, ys) = bounds.range(readable)
        assertTrue(xs.count().toLong() * ys.count() <= 512)
        assertTrue(flightTileInspectionZoom(19, bounds, camera.worldPixels, 112.0) < readable)
        assertEquals(3, flightTileInspectionZoom(3, bounds, camera.worldPixels, 56.0))
        assertEquals(FlightTileMapCamera(.98, .5, 8192.0), camera)
    }

    @Test
    fun mapScaleFollowsZoomAndMercatorLatitude() {
        val equator = FlightTileMapCamera(.5, .5, 8192.0)
        val meters = flightTileMetersPerPixel(equator)
        assertEquals(
            meters / 2,
            flightTileMetersPerPixel(equator.copy(worldPixels = 16384.0)),
            1e-8,
        )
        assertTrue(flightTileMetersPerPixel(equator.copy(y = .25)) < meters)
    }

    @Test
    fun terrainPaletteInterpolatesAndHandlesMissingAltitudes() {
        assertNotEquals(flightTileTerrainColor(400f), flightTileTerrainColor(800f))
        assertNotEquals(flightTileTerrainColor(800f), flightTileTerrainColor(1500f))
        assertEquals(flightTileTerrainColor(4500f), flightTileTerrainColor(9000f))
        assertEquals(flightTileTerrainColor(-1000f), flightTileTerrainColor(-5000f))
        assertEquals(0xFF18252D.toInt(), flightTileTerrainColor(Float.NaN))
        for (height in listOf(1f, 400f, 800f, 1500f, 4500f)) assertEquals(
            255,
            flightTileTerrainColor(height) ushr 24,
        )
    }
}
