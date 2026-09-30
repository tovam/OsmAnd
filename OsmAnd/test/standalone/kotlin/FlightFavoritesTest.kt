package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** Identity fixtures only: no preferences, photo files, journals or network are accessed. */
class FlightFavoritesTest {
    private fun local(id: String, name: String = "Synthetic flight") =
        FlightJourneySummary(id, name, 1000L, 20, 2)

    private fun remote(id: String) =
        FlightCloudEntry(id, "Synthetic flight", "revision", 100L, 1000L, setOf("photo"), 20)

    @Test
    fun favoriteFollowsFirstUploadAndDownloadedCopies() {
        val initial = FlightLibraryRow(local("original"), null, null)
        val favorites = FlightFavorites().toggle(initial)
        val server = FlightLibraryRow(null, remote("original"), null)
        val downloaded =
            FlightLibraryRow(
                local("downloaded-copy"),
                remote("original"),
                FlightCloudBinding("downloaded-copy", "original", "revision", 1000L),
            )
        assertTrue(favorites.contains(server))
        assertTrue(favorites.contains(downloaded))
        assertTrue(favorites.contains(downloaded.copy(remote = null)))
        val removed = favorites.toggle(downloaded)
        assertFalse(removed.contains(initial))
        assertFalse(removed.contains(server))
        assertFalse(removed.contains(downloaded))
    }

    @Test
    fun removingFavoriteOnServerOnlyRowAlsoRemovesItForEveryLocalCopy() {
        val entry = remote("shared-id")
        val a =
            FlightLibraryRow(
                local("copy-a"),
                entry,
                FlightCloudBinding("copy-a", entry.id, entry.revision, 1000L),
            )
        val b =
            FlightLibraryRow(
                local("copy-b"),
                entry,
                FlightCloudBinding("copy-b", entry.id, entry.revision, 1000L),
            )
        val favorites = FlightFavorites().toggle(a)
        assertTrue(favorites.contains(b))
        val removed = favorites.toggle(FlightLibraryRow(null, entry, null))
        assertFalse(removed.contains(a))
        assertFalse(removed.contains(b))
        assertTrue(removed.journeys.isEmpty())
    }

    @Test
    fun renamingOrUpdatingFlightDoesNotLoseFavoriteOrStarSameNamedFlight() {
        val row = FlightLibraryRow(local("one"), null, null)
        val favorites = FlightFavorites().toggle(row)
        assertTrue(
            favorites.contains(
                row.copy(local = row.local!!.copy(name = "Renamed", updatedAtMillis = 9999L))
            )
        )
        assertFalse(favorites.contains(FlightLibraryRow(local("two"), null, null)))
    }

    @Test
    fun photoAndFlightFavoritesAreIndependentEvenWhenIdsMatch() {
        val row = FlightLibraryRow(local("same-id"), null, null)
        val favorites =
            FlightFavorites().toggle(row).togglePhoto("same-id").togglePhoto("other-photo")
        val removedPhoto = favorites.togglePhoto("same-id")
        assertTrue(removedPhoto.contains(row))
        assertEquals(setOf("other-photo"), removedPhoto.photos)
        val removedFlight = favorites.toggle(row)
        assertFalse(removedFlight.contains(row))
        assertEquals(setOf("same-id", "other-photo"), removedFlight.photos)
    }
}
