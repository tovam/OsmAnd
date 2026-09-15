package net.osmand.test.junit

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightCloudArchiveTest {
    @Test
    fun cloudRowsKeepCopyProvenanceAndShowServerOnlyFlights() {
        val remote =
            FlightCloudEntry("shared", "Server flight", "a".repeat(64), 100, 20, emptySet(), 10)
        val first = FlightJourneySummary("copy1", "Local edit", 30, 10, 0)
        val second = first.copy(id = "copy2", updatedAtMillis = 20)
        val bindings =
            listOf(
                FlightCloudBinding("copy1", "shared", "b".repeat(64), 10),
                FlightCloudBinding("copy2", "shared", remote.revision, 20, true),
            )
        val rows =
            flightCloudRows(
                listOf(first, second),
                listOf(remote, remote.copy(id = "elsewhere")),
                bindings,
            )
        assertEquals(3, rows.size)
        assertEquals("b".repeat(64), rows.first { it.local?.id == "copy1" }.binding?.revision)
        assertEquals(remote.revision, rows.first { it.local?.id == "copy2" }.binding?.revision)
        assertEquals("elsewhere", rows.single { it.local == null }.remote?.id)
    }

    @Test
    fun serverRecordingIsVisibleEvenWhenPhoneStillHasPlan() {
        val local = FlightJourneySummary("local", "Plan", 10, 0, 0)
        val remote =
            FlightCloudEntry("remote", "Recorded", "a".repeat(64), 100, 20, emptySet(), 100)
        val rows =
            flightCloudRows(
                listOf(local),
                listOf(remote),
                listOf(FlightCloudBinding("local", "remote", "b".repeat(64), 10)),
            )
        assertEquals(2, rows.size)
        assertEquals(0, rows.single { it.local != null }.local!!.sampleCount)
        assertEquals(100, rows.single { it.local == null }.remote!!.samples)
    }

    @Test
    fun finishedSaveCannotAcknowledgeNewerEditsOrAnotherJourney() {
        val source = FlightUiState(journeyId = "one", journeyName = "Original")
        assertTrue(source.copy(journeyDirty = true).hasSameJournalContentAs(source))
        assertFalse(source.copy(journeyName = "Edited during save").hasSameJournalContentAs(source))
        assertFalse(source.copy(journeyId = "other").hasSameJournalContentAs(source))
        val photo = FlightPhotoAttachment("p", "test.jpg", "synthetic.jpg", null, null)
        val withPhoto = source.copy(photos = listOf(photo))
        assertFalse(
            withPhoto
                .copy(photos = listOf(photo.copy(rotationDegrees = 22f)))
                .hasSameJournalContentAs(withPhoto)
        )
        val simulated = source.copy(trip = recordedFlightTrip("Simulation", emptyList()))
        assertFalse(simulated.hasSameJournalContentAs(source))
        assertTrue(simulated.hasSameJournalContentAs(source, includeTrip = false))
    }

    private fun fixture(block: (File, FlightJourneyStore, FlightJourney) -> Unit) {
        val parent = File("OsmAnd/build").also { it.mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "cloud-fixture-").toFile()
        try {
            val first =
                File(directory, "synthetic.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val second = File(directory, "excluded.jpg").also { it.writeBytes(byteArrayOf(4, 5)) }
            val plan = FlightPlan.preview().copy(preparation = FlightPreparation(automatic = true))
            val journey =
                FlightJourney(
                    "synthetic",
                    "Test",
                    1,
                    2,
                    plan,
                    recordedFlightTrip("Test", emptyList()),
                    emptyList(),
                    listOf(
                        FlightPhotoAttachment(
                            "photo-a",
                            first.name,
                            first.path,
                            null,
                            null,
                            rotationDegrees = 13.5f,
                        ),
                        FlightPhotoAttachment("photo-b", second.name, second.path, null, null),
                    ),
                    offlineAssets =
                        FlightOfflineAssets(
                            listOf(TerrainTileId(7, 1, 1)),
                            listOf(TerrainTileId(7, 2, 2)),
                        ),
                    offlineRequest = FlightOfflineAssets(listOf(TerrainTileId(7, 3, 3))),
                )
            block(directory, FlightJourneyStore(directory), journey)
        } finally {
            // Only this freshly-created synthetic fixture is owned by the test.
            check(
                directory.parentFile.canonicalFile == parent.canonicalFile &&
                    !Files.isSymbolicLink(directory.toPath())
            )
            check(directory.deleteRecursively())
        }
    }

    @Test
    fun oldGenericPlanNameIsUpdatedInCloudPayload() = fixture { dir, store, journey ->
        val plan = FlightPlan(listOf(FlightStop("Paris"), FlightStop("Podgorica")))
        store.writeCloudArchive(
            journey.copy(name = "Départ -> arrivée", plan = plan),
            emptySet(),
            File(dir, "plan.zip"),
        )
        assertEquals("Paris → Podgorica", store.serialized.name)
    }

    @Test
    fun sendsOnlySelectedPhotosAndNeverOfflineAssets() = fixture { dir, store, journey ->
        val archive = File(dir, "transfer.zip")
        store.writeCloudArchive(journey, setOf("photo-a"), archive)
        assertEquals(listOf(journey.photos[0]), store.serialized.photos)
        assertEquals(FlightOfflineAssets(), store.serialized.offlineAssets)
        assertEquals(FlightOfflineAssets(), store.serialized.offlineRequest)
        assertEquals(false, store.serialized.plan.preparation?.automatic)
        ZipFile(archive).use { zip ->
            assertEquals(
                setOf("journey.json", "photos/image-0.jpg"),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                zip.getInputStream(zip.getEntry("photos/image-0.jpg")).readBytes(),
            )
        }
        assertTrue(File(journey.photos[1].localPath).isFile)
    }

    @Test
    fun importCreatesNewIdentityAndKeepsOriginalFiles() = fixture { dir, store, journey ->
        val archive = File(dir, "transfer.zip")
        store.writeCloudArchive(journey, setOf("photo-a"), archive)
        store.imported = journey
        val imported = store.importCloudArchive(archive)
        assertNotEquals(journey.id, imported.id)
        assertEquals(1, imported.photos.size)
        assertEquals(13.5f, imported.photos.single().rotationDegrees, 0f)
        assertNotEquals(journey.photos[0].localPath, imported.photos[0].localPath)
        assertArrayEquals(
            File(journey.photos[0].localPath).readBytes(),
            File(imported.photos[0].localPath).readBytes(),
        )
        assertEquals(FlightOfflineAssets(), imported.offlineAssets)
        assertEquals(FlightOfflineAssets(), imported.offlineRequest)
        assertEquals(false, imported.plan.preparation?.automatic)
    }

    @Test
    fun recordedFlightIncludesGpx() = fixture { dir, store, journey ->
        val point = FlightSample(0, 0, 1800000000000L, 45.0, 10.0, 12000.0, 250f, 90f, 5f)
        val recorded = journey.copy(trip = recordedFlightTrip("Recorded", listOf(point)))
        val archive = File(dir, "recorded.zip")
        store.writeCloudArchive(recorded, emptySet(), archive)
        ZipFile(archive).use { assertNotNull(it.getEntry("track.gpx")) }
    }

    @Test
    fun noPhotoExportAndMissingPhotoAreExplicit() = fixture { dir, store, journey ->
        val archive = File(dir, "transfer.zip")
        store.writeCloudArchive(journey, emptySet(), archive)
        ZipFile(archive).use { assertEquals(1, it.size()) }
        val missing =
            journey.copy(
                photos = listOf(journey.photos[0].copy(localPath = File(dir, "absent.jpg").path))
            )
        assertThrows(java.io.IOException::class.java) {
            store.writeCloudArchive(missing, setOf("photo-a"), archive)
        }
    }

    @Test
    fun rejectsExtraAndTraversalEntriesBeforeImport() = fixture { dir, store, _ ->
        for (bad in listOf("../bad", "photos/../bad", "terrain/7/1/1.png", "photos/.hidden")) {
            val archive = File(dir, "transfer.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((name, value) in
                    listOf("journey.json" to "{}", "track.gpx" to "<gpx/>", bad to "test")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            assertThrows(IllegalArgumentException::class.java) { store.importCloudArchive(archive) }
            assertNull(store.saved)
        }
    }

    @Test
    fun rejectsInvalidConnectionAndNeverPrintsToken() {
        val token = "synthetic-token-0000000000000000000"
        val connection = FlightCloudConnection.validated("https://flights.example.test/", token)
        assertEquals("https://flights.example.test", connection.url)
        assertFalse(connection.toString().contains(token))
        assertFalse(FlightCloudLease(token, 123L).toString().contains(token))
        for (url in
            listOf(
                "http://flights.example.test",
                "https://user@flights.example.test",
                "https://flights.example.test?token=x",
                "https://flights.example.test/#x",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                FlightCloudConnection.validated(url, token)
            }
        }
        assertNotEquals(
            connection.scope,
            FlightCloudConnection.validated(connection.url, token + "x").scope,
        )
    }

    @Test
    fun boundedReadRejectsExcessiveResponse() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(100)).readBytesBounded(99)
        }
        assertEquals(100, ByteArrayInputStream(ByteArray(100)).readBytesBounded(100).size)
    }
}
