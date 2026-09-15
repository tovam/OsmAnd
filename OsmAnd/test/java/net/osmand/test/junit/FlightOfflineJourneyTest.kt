package net.osmand.test.junit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightOfflineJourneyTest {
    @Test
    fun listRefreshDoesNotOverwriteANewerLocalSave() {
        val old = FlightJourneySummary("saved", "Before", 10, 0, 0)
        val updated = old.copy(name = "After", updatedAtMillis = 20)
        val added = FlightJourneySummary("new", "New flight", 30, 0, 0)
        assertEquals(
            listOf(added, updated),
            FlightJournalSummaries.mergeListing(
                listOf(old),
                listOf(updated, added),
                listOf(old),
                complete = true,
            ),
        )
    }

    @Test
    fun listRefreshDoesNotResurrectARemovedLocalCopy() {
        val removed = FlightJourneySummary("removed", "Removed", 10, 0, 0)
        assertTrue(
            FlightJournalSummaries.mergeListing(
                    listOf(removed),
                    emptyList(),
                    listOf(removed),
                    complete = false,
                )
                .isEmpty()
        )
        assertTrue(
            FlightJournalSummaries.mergeListing(
                    listOf(removed),
                    emptyList(),
                    listOf(removed),
                    complete = true,
                )
                .isEmpty()
        )
    }

    @Test
    fun nativeRasterDownloadsShareTheFlightOfflineGate() {
        val owner = Any()
        val downloader = net.osmand.map.MapTileDownloader(1)
        downloader.setDownloadAccess(FlightRasterDownloadAccess)
        FlightNetworkAccess.block(owner)
        try {
            val request =
                net.osmand.map.MapTileDownloader.DownloadRequest(
                    "https://example.invalid/tile",
                    File("synthetic-never-written"),
                    "tile",
                    0,
                    0,
                    0,
                )
            downloader.requestToDownload(request)
            assertEquals(0, downloader.remainingWorkers)
            assertFalse(downloader.isFilePendingToDownload(request.fileToSave))
            assertFalse(FlightRasterDownloadAccess.isAllowed())
        } finally {
            FlightNetworkAccess.release(owner)
        }
        assertTrue(FlightRasterDownloadAccess.isAllowed())
    }

    @Test
    fun nativeRasterConnectionsAreDisconnectedOnOfflineEntry() {
        var disconnects = 0
        val connection =
            object : java.net.HttpURLConnection(java.net.URL("https://example.invalid/tile")) {
                override fun connect() = error("No network in tests")

                override fun disconnect() {
                    disconnects++
                }

                override fun usingProxy() = false
            }
        val owner = Any()
        FlightRasterDownloadAccess.opened(connection)
        try {
            FlightNetworkAccess.block(owner).forEach { it() }
            assertEquals(1, disconnects)
        } finally {
            FlightRasterDownloadAccess.closed(connection)
            FlightNetworkAccess.release(owner)
        }
    }

    @Test
    fun alarmFailureIsNotALocalSaveFailure() = runBlocking {
        var saved = 0
        val result =
            saveFlightPreparation(
                true,
                {
                    saved++
                    "local-document"
                },
                { throw SecurityException("synthetic missing permission") },
            )
        assertEquals(1, saved)
        assertEquals("local-document", result.saved)
        assertFalse(result.armed)
        assertTrue(result.scheduleError is SecurityException)
    }

    @Test
    fun ordinarySaveDoesNotRearmOrCancelAnAlarm() = runBlocking {
        val result =
            saveFlightPreparation(
                false,
                { "local-document" },
                { fail("No alarm operation during Save") },
            )
        assertEquals("local-document", result.saved)
        assertNull(result.scheduleError)
    }

    @Test
    fun diskFailureNeverProgramsAnAlarm() = runBlocking {
        try {
            saveFlightPreparation<String>(
                true,
                { throw IOException("synthetic disk failure") },
                { fail("No alarm without saved flight") },
            )
            fail("Expected disk error")
        } catch (expected: IOException) {
            assertEquals("synthetic disk failure", expected.message)
        }
    }

    @Test
    fun offlineEntryCancelsInFlightRequestsAndRejectsAllNewConsumers() {
        val owner = Any()
        val request = Any()
        var cancelled = 0
        FlightNetworkAccess.register(request) { cancelled++ }
        try {
            val callbacks = FlightNetworkAccess.block(owner)
            assertTrue(FlightNetworkAccess.isOffline())
            callbacks.forEach { it() }
            assertEquals(1, cancelled)
            for (consumer in listOf("scene", "minimap", "calibration", "cloud", "native-map")) {
                try {
                    FlightNetworkAccess.register(consumer) { fail("Never started") }
                    fail("Offline request escaped: $consumer")
                } catch (expected: IOException) {
                    assertEquals("offline_simulation", expected.message)
                }
            }
            assertTrue(FlightNetworkAccess.block(owner).isEmpty())
        } finally {
            FlightNetworkAccess.unregister(request)
            FlightNetworkAccess.release(owner)
        }
        FlightNetworkAccess.requireOnline()
    }

    @Test
    fun oneSessionCannotEnableNetworkingForAnotherSession() {
        val first = Any()
        val second = Any()
        FlightNetworkAccess.block(first)
        FlightNetworkAccess.block(second)
        try {
            FlightNetworkAccess.release(first)
            assertTrue(FlightNetworkAccess.isOffline())
        } finally {
            FlightNetworkAccess.release(first)
            FlightNetworkAccess.release(second)
        }
        assertFalse(FlightNetworkAccess.isOffline())
    }

    @Test
    fun simulationNeedsCoordinatesButNeitherTimesNorAutomaticDeparture() {
        val plan = FlightPlan(listOf(FlightStop("A", 48.0, 2.0), FlightStop("B", 43.0, 19.0)))
        assertTrue(FlightOfflinePreparation.canSimulate(plan))
        val owner = Any()
        FlightNetworkAccess.block(owner)
        try {
            val trip = FlightOfflinePreparation.simulation(plan)
            assertTrue(trip.samples.size > 4)
            assertEquals(48.0, trip.samples.first().latitude, 0.0001)
            assertEquals(19.0, trip.samples.last().longitude, 0.0001)
            assertTrue(trip.samples.any { (it.altitudeMeters ?: 0.0) > 1000 })
            val replay = FlightReplayEngine(trip)
            assertNotEquals(
                replay.snapshotAt(0f).sample.latitude,
                replay.snapshotAt(.5f).sample.latitude,
            )
            assertNull(plan.preparation)
        } finally {
            FlightNetworkAccess.release(owner)
        }
    }

    @Test
    fun incompleteInvalidAndCoincidentPlansDoNotEnableDeadNavigationButtons() {
        for (stops in
            listOf(
                emptyList(),
                listOf(FlightStop("A")),
                listOf(FlightStop("A", 48.0, 2.0), FlightStop("B")),
                listOf(FlightStop("A", Double.NaN, 2.0), FlightStop("B", 49.0, 3.0)),
                listOf(FlightStop("A", 48.0, 2.0), FlightStop("B", 48.0, 2.0)),
            )) {
            assertFalse(FlightOfflinePreparation.canSimulate(FlightPlan(stops)))
        }
    }

    @Test
    fun legacyListingSkipsNestedArraysAndKeepsTheRightCounts() {
        val json =
            """{"id":"synthetic","name":"Manual name","updatedAtMillis":12,
            "plan":{"stops":[{"name":"A"},{"name":"B"}]},
            "trip":{"samples":[{"index":0,"soundSpectrum":[1,2,3]}, {"index":1}]},
            "photos":[{"calibration":{"points":[1,2,3]},"depth":[[4,5],[6,7]]}],
            "offlineAssets":{"tiles":[[1,2,3],[4,5,6]]}}"""
        val summary = FlightJournalSummaries.scan(json.reader(), 99)
        assertEquals("Manual name", summary.name)
        assertEquals(12L, summary.updatedAtMillis)
        assertEquals(2, summary.sampleCount)
        assertEquals(1, summary.photoCount)
    }

    @Test
    fun repeatedListingReadsOnlySmallSidecarsAndRefreshesAfterSave() {
        val parent = File("OsmAnd/build").canonicalFile.apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "synthetic-summary-").toFile()
        try {
            val file = File(directory, "synthetic.json")
            val measurements =
                (0 until 60_000).joinToString(",") {
                    "{\"index\":$it,\"latitude\":48.0,\"longitude\":2.0}"
                }
            file.writeText(
                """{"id":"synthetic","name":"Test","trip":{"samples":[$measurements]},"photos":[]}"""
            )
            val first = FlightJournalSummaries.read(file)
            assertEquals(60_000, first.sampleCount)
            assertTrue(FlightJournalSummaries.sidecar(file).length() < 1024)
            val modified = file.lastModified()
            val length = file.length()
            // Same fingerprint, unreadable body: successful reads prove the body is never parsed.
            file.writeText("x".repeat(length.toInt()))
            check(file.setLastModified(modified))
            val started = System.nanoTime()
            repeat(100) { assertEquals(first, FlightJournalSummaries.read(file)) }
            println(
                "Synthetic summary: 100 cached reads in ${(System.nanoTime()-started)/1_000_000} ms; journal $length bytes"
            )
            file.writeText(
                """{"id":"synthetic","name":"Changed","trip":{"samples":[]},"photos":[]}"""
            )
            val next = FlightJournalSummaries.read(file)
            assertEquals("Changed", next.name)
            assertEquals(0, next.sampleCount)
        } finally {
            check(
                directory.canonicalFile.parentFile == parent &&
                    directory.name.startsWith("synthetic-summary-")
            )
            directory.listFiles().orEmpty().forEach {
                check(it.isFile && !Files.isSymbolicLink(it.toPath()))
                check(it.delete())
            }
            check(directory.delete())
        }
    }
}
