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
    fun sendsOnlySelectedPhotosAndNeverOfflineAssets() = fixture { dir, store, journey ->
        val archive = File(dir, "transfer.zip")
        store.writeCloudArchive(journey, setOf("photo-a"), archive)
        assertEquals(listOf(journey.photos[0]), store.serialized.photos)
        assertEquals(FlightOfflineAssets(), store.serialized.offlineAssets)
        assertEquals(FlightOfflineAssets(), store.serialized.offlineRequest)
        assertEquals(false, store.serialized.plan.preparation?.automatic)
        ZipFile(archive).use { zip ->
            assertEquals(
                setOf("journey.json", "track.gpx", "photos/image-0.jpg"),
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
    fun noPhotoExportAndMissingPhotoAreExplicit() = fixture { dir, store, journey ->
        val archive = File(dir, "transfer.zip")
        store.writeCloudArchive(journey, emptySet(), archive)
        ZipFile(archive).use { assertEquals(2, it.size()) }
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
