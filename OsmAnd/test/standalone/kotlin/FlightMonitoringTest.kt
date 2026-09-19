package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic clocks, positions and file sizes. No application files or GPS are accessed. */
class FlightMonitoringTest {
    private val tile = TerrainTileId(8, 10, 20)
    private val terrain = FlightOfflineTileKey(tile, false)
    private val satellite = FlightOfflineTileKey(tile, true)

    private fun inventory() =
        FlightOfflineInventory(
            listOf(FlightOfflineRequest(tile, false, 0), FlightOfflineRequest(tile, true, 0))
        )

    private fun sample(index: Int = 0) =
        FlightSample(index, 0, 100_000L + index * 1000L, 45.0, 10.0, 10_000.0, 230f, 90f, 5f)

    @Test
    fun newFixBetweenUiTicksIsNotReportedLost() {
        val elapsed = flightDisplayElapsed(timerElapsed = 100_000L, currentElapsed = 100_720L)
        val live =
            FlightLiveState(
                journeyId = "synthetic",
                running = true,
                latest = sample(),
                lastFixElapsed = 100_700L,
            )
        assertEquals(
            FlightFixHealth.FRESH,
            FlightLiveSafety.fixHealth(live.latest, live.lastFixElapsed, elapsed),
        )
        assertEquals(0L, live.fixAgeSeconds(elapsed))
        assertEquals(FlightTrackingPhase.WAITING, live.tracking.phase)
        assertEquals(
            FlightLibraryGpsState.RECORDING,
            flightLibraryGpsState("synthetic", live, emptyMap(), true, true, elapsed, 100_000L),
        )
    }

    @Test
    fun genuinelyStaleOrRestoredFixIsStillReportedStale() {
        val live = FlightLiveState(latest = sample(), lastFixElapsed = 1000L)
        val now = flightDisplayElapsed(16_000L, 16_001L)
        assertEquals(
            FlightFixHealth.STALE,
            FlightLiveSafety.fixHealth(live.latest, live.lastFixElapsed, now),
        )
        assertEquals(15L, live.fixAgeSeconds(now))
        assertNull(live.copy(lastFixElapsed = 0).fixAgeSeconds(now))
        assertNull(live.copy(latest = null).fixAgeSeconds(now))
    }

    @Test
    fun countersAndListUseRecordedPointsNotPredictedOrFuturePositions() {
        val recorded = recordedFlightTrip("test", List(3) { sample(it) })
        val future = recordedFlightTrip("future", List(20) { sample(it) })
        val live =
            FlightLiveState(
                journeyId = "synthetic",
                running = true,
                trip = recorded,
                latest = sample(4),
            )
        val ui =
            FlightUiState(
                journeyId = "synthetic",
                activeRecording = live,
                liveState = live.copy(trip = future),
                trip = future,
                snapshot = FlightSnapshot(sample(18), .9f),
                liveTimeline = future,
                browsingLiveTimeline = true,
            )
        val displayed = ui.recordingForSelectedFlight()
        assertSame(recorded, displayed.trip)
        assertEquals(listOf(2, 1, 0), displayed.recentRecordedPoints().map { it.index })
        assertEquals(3, displayed.trip!!.samples.size)
        assertEquals(listOf(2, 1), displayed.recentRecordedPoints(2).map { it.index })
        assertTrue(displayed.recentRecordedPoints(0).isEmpty())
        assertEquals(3, recorded.samples.size)
    }

    @Test
    fun anotherFlightsRecorderIsNeverShownAsThisFlightsGps() {
        val other =
            FlightLiveState(
                journeyId = "other",
                running = true,
                trip = recordedFlightTrip("other", listOf(sample())),
            )
        val selected =
            FlightUiState(journeyId = "selected", activeRecording = other, liveState = other)
        assertFalse(selected.recordingForSelectedFlight().running)
        assertTrue(selected.recordingForSelectedFlight().recentRecordedPoints().isEmpty())
    }

    @Test
    fun initialInventoryIsUnknownNotReady() {
        val coverage = inventory().snapshot()
        assertEquals(2, coverage.total)
        assertEquals(0, coverage.inspected)
        assertFalse(coverage.inventoried)
        assertEquals(155_000L, coverage.estimatedRemainingBytes)
        assertFalse(coverage.verifiedBy(FlightTerrainStatus(phase = FlightTerrainPhase.READY)))
    }

    @Test
    fun countAndByteTotalsSeparateSatelliteAndTerrain() {
        val index = inventory()
        index.inspected(terrain, 0, 120_000)
        index.inspected(satellite, 0, 0)
        val progress = index.snapshot()
        assertTrue(progress.inventoried)
        assertEquals(1, progress.terrainStored)
        assertEquals(0, progress.satelliteStored)
        assertEquals(1, progress.missing)
        assertEquals(120_000L, progress.storedBytes)
        assertEquals(45_000L, progress.estimatedRemainingBytes)
        assertEquals(120_000L, progress.terrainStoredBytes)
        assertEquals(0L, progress.satelliteStoredBytes)
        assertEquals(45_000L, progress.satelliteRemainingBytes)
        assertEquals(0L, progress.terrainRemainingBytes)
        assertEquals(.5f, progress.fraction, 0f)
    }

    @Test
    fun completedFilesUpdateWithoutRescanningAndRetriesDoNotDoubleCount() {
        val index = inventory()
        index.changed(terrain, 120_000)
        index.changed(satellite, 50_000)
        index.changed(satellite, 50_000)
        assertEquals(2, index.snapshot().stored)
        assertEquals(170_000L, index.snapshot().storedBytes)
        index.changed(satellite, 60_000)
        assertEquals(180_000L, index.snapshot().storedBytes)
        assertEquals(0, index.snapshot().estimatedRemainingBytes)
    }

    @Test
    fun deletionAndCorruptionBecomeMissingAgain() {
        val index = inventory()
        index.changed(terrain, 120_000)
        index.changed(satellite, 50_000)
        index.changed(satellite, 0)
        index.changed(satellite, -1)
        assertEquals(1, index.snapshot().stored)
        assertEquals(1, index.snapshot().missing)
        assertEquals(120_000L, index.snapshot().storedBytes)
        assertEquals(45_000L, index.snapshot().estimatedRemainingBytes)
        // The initial header scan must not reinstate a file just rejected by full decoding.
        assertNull(index.revision(satellite))
    }

    @Test
    fun downloadRacingInitialInventoryCannotBeOverwrittenByOldDiskRead() {
        val index = inventory()
        val revision = index.revision(satellite)!!
        index.changed(satellite, 60_000)
        index.inspected(satellite, revision, 0)
        assertEquals(1, index.snapshot().satelliteStored)
        assertEquals(60_000L, index.snapshot().storedBytes)
        index.changed(satellite, 0)
        index.inspected(satellite, revision, 60_000)
        assertEquals(0, index.snapshot().stored)
    }

    @Test
    fun duplicateRequestsAndUnrelatedFilesDoNotInflateCoverage() {
        val request = FlightOfflineRequest(tile, false, 0)
        val index = FlightOfflineInventory(listOf(request, request, request.copy(band = 1)))
        index.changed(satellite, 999_999_999)
        index.changed(terrain, 123)
        assertEquals(1, index.snapshot().total)
        assertEquals(123L, index.snapshot().storedBytes)
    }

    @Test
    fun estimatesUseObservedSizesAfterEnoughExamplesAndDoNotOverflowGb() {
        val requests = (0..9).map { FlightOfflineRequest(TerrainTileId(8, it, 10), true, 0) }
        val index = FlightOfflineInventory(requests)
        requests.take(8).forEach {
            index.changed(FlightOfflineTileKey(it.tile, true), 500_000_000L)
        }
        assertEquals(4_000_000_000L, index.snapshot().storedBytes)
        assertEquals(1_000_000_000L, index.snapshot().estimatedRemainingBytes)
    }

    @Test
    fun tinyOverviewTilesDoNotUnderestimateDetailedSatelliteOrTerrain() {
        val overview = (0..7).map { FlightOfflineRequest(TerrainTileId(3, it, 0), true, -1) }
        val detail = (0..9).map { FlightOfflineRequest(TerrainTileId(14, it, 0), true, 0) }
        val heights = detail.map { it.copy(satellite = false) }
        val index = FlightOfflineInventory(overview + detail + heights)
        overview.forEach { index.changed(FlightOfflineTileKey(it.tile, true), 1000) }
        assertEquals(450_000L, index.snapshot().satelliteRemainingBytes)
        assertEquals(1_100_000L, index.snapshot().terrainRemainingBytes)
        detail.take(8).forEach { index.changed(FlightOfflineTileKey(it.tile, true), 90_000) }
        val snapshot = index.snapshot()
        assertEquals(728_000L, snapshot.satelliteStoredBytes)
        assertEquals(180_000L, snapshot.satelliteRemainingBytes)
        assertEquals(
            snapshot.storedBytes,
            snapshot.satelliteStoredBytes + snapshot.terrainStoredBytes,
        )
        assertEquals(
            snapshot.estimatedRemainingBytes,
            snapshot.satelliteRemainingBytes + snapshot.terrainRemainingBytes,
        )
    }

    @Test
    fun inventoryHeaderProbeIsIndependentOfPausedSceneAndKeepsCompositeTexturesValid() {
        val source =
            java.io
                .File("OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightTerrainRepository.kt")
                .readText()
        val inventory =
            source
                .substringAfter("suspend fun observeOfflineCoverage")
                .substringBefore("\n\tprivate ")
        assertTrue(inventory.contains("hasReadableTileHeader(file)"))
        assertFalse(inventory.contains("isDecodableImage(file)"))
        val header =
            source.substringAfter("private fun hasReadableTileHeader").substringBefore("\n\t}")
        assertFalse(header.contains("ensureWorkActive"))
        assertTrue(header.contains("options.outWidth > 0 && options.outHeight > 0"))
    }

    @Test
    fun missingFailedOrMerelyPresentFilesNeverAdvertiseVerifiedReadiness() {
        val index = inventory()
        index.changed(terrain, 120_000)
        index.changed(satellite, 50_000)
        val complete =
            FlightTerrainStatus(
                phase = FlightTerrainPhase.READY,
                requestedTiles = 1,
                requestedSatelliteTiles = 1,
                availableTiles = 1,
                satelliteTiles = 1,
                offlineFilesVerified = true,
            )
        assertTrue(index.snapshot().verifiedBy(complete))
        assertFalse(index.snapshot().verifiedBy(complete.copy(offlineFilesVerified = false)))
        assertFalse(index.snapshot().verifiedBy(complete.copy(satelliteFailedTiles = 1)))
        assertFalse(index.snapshot().verifiedBy(complete.copy(requestedSatelliteTiles = 2)))
        assertFalse(index.snapshot().verifiedBy(complete.copy(phase = FlightTerrainPhase.PAUSED)))
        index.changed(satellite, 0)
        assertFalse(index.snapshot().verifiedBy(complete))
    }

    @Test
    fun observerUnsubscribesSoHiddenUiDoesNoWork() {
        val index = inventory()
        val stop = FlightOfflineTileChanges.observe(index::changed)
        try {
            FlightOfflineTileChanges.publish(tile, false, 120_000)
            assertEquals(1, index.snapshot().stored)
        } finally {
            stop()
        }
        FlightOfflineTileChanges.publish(tile, true, 50_000)
        assertEquals(1, index.snapshot().stored)
    }
}
