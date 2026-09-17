package net.osmand.test.junit

import java.io.IOException
import net.osmand.plus.plugins.flightmode.FlightArchiveInventory
import net.osmand.plus.plugins.flightmode.flightPhotoStorageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FlightArchiveInventoryTest {
    @Test
    fun longExportedAndImportedNamesAreNotTruncatedOrAliased() {
        val name = "a".repeat(140) + "_" + "b".repeat(136) + ".jpg"
        val inventory = FlightArchiveInventory()
        inventory.accept("photos/$name", false, false)
        inventory.requirePhotoManifest(listOf(name))
        assertEquals(name, flightPhotoStorageName(name))
        for (invalid in listOf("../private.jpg", ".", "..", "/tmp/x", "a/b", "a\\b", "")) {
            assertThrows(IOException::class.java) { flightPhotoStorageName(invalid) }
        }
    }

    @Test
    fun acceptsPortableManifestGpxPhotosAndValidatedTiles() {
        val inventory = FlightArchiveInventory()
        inventory.accept("journey.json", false, false)
        inventory.accept("track.gpx", false, false)
        inventory.accept("photos/", true, false)
        inventory.accept("photos/point_IMG_20260901_120000.jpg", false, false)
        inventory.accept("offline/terrain/8/1/2.png", false, true)
        inventory.requirePhotoManifest(listOf("point_IMG_20260901_120000.jpg"))
    }

    @Test
    fun rejectsUnknownEntriesBeforeDecompressingTheirPayload() {
        assertThrows(IOException::class.java) { FlightArchiveInventory().accept("junk.bin", false, false) }
    }

    @Test
    fun rejectsDuplicateManifestAndCollidingPhotoPaths() {
        val inventory = FlightArchiveInventory()
        inventory.accept("journey.json", false, false)
        assertThrows(IOException::class.java) { inventory.accept("journey.json", false, false) }
        for (name in listOf("photos/a/file.jpg", "photos/../file.jpg", "photos/./file.jpg", "photos/.hidden", "photos/a\\b.jpg")) {
            assertThrows(name, IOException::class.java) { FlightArchiveInventory().accept(name, false, false) }
        }
    }

    @Test
    fun missingImportedPhotoCannotFallBackToExistingLocalMedia() {
        assertThrows(IOException::class.java) { FlightArchiveInventory().requirePhotoManifest(listOf("existing.jpg")) }
    }

    @Test
    fun rejectsUnreferencedOrMultiplyReferencedPhotos() {
        val inventory = FlightArchiveInventory()
        inventory.accept("photos/test.jpg", false, false)
        assertThrows(IOException::class.java) { inventory.requirePhotoManifest(emptyList()) }
        assertThrows(IOException::class.java) { inventory.requirePhotoManifest(listOf("test.jpg", "test.jpg")) }
    }
}
