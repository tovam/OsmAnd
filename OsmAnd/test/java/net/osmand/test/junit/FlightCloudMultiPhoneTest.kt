package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightCloudBinding
import net.osmand.plus.plugins.flightmode.FlightCloudEntry
import net.osmand.plus.plugins.flightmode.FlightJourneySummary
import net.osmand.plus.plugins.flightmode.FlightLibraryRow
import net.osmand.plus.plugins.flightmode.FlightVersionState
import net.osmand.plus.plugins.flightmode.flightCloudRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic two-phone cloud state; it neither connects to a server nor opens device storage. */
class FlightCloudMultiPhoneTest {

    private val initialRemote =
        FlightCloudEntry(
            id = "flight-created-on-a",
            name = "Synthetic flight",
            revision = "a".repeat(64),
            bytes = 500,
            updatedAt = 20,
            photoIds = emptySet(),
            samples = 0,
        )
    private val phoneBCopy = FlightJourneySummary(
        id = "new-local-copy-on-b",
        name = "Synthetic flight",
        updatedAtMillis = 20,
        sampleCount = 0,
        photoCount = 0,
    )
    private val binding =
        FlightCloudBinding(
            localId = phoneBCopy.id,
            remoteId = initialRemote.id,
            revision = initialRemote.revision,
            localUpdatedAt = phoneBCopy.updatedAtMillis,
            allLocalPhotosIncluded = true,
        )

    @Test
    fun concurrentRecordingsRemainSeparateAndSurfaceABothChangedConflict() {
        val recordedOnB = phoneBCopy.copy(updatedAtMillis = 40, sampleCount = 12)
        val recordedOnA = initialRemote.copy(revision = "b".repeat(64), updatedAt = 40, samples = 10)
        val row = FlightLibraryRow(recordedOnB, recordedOnA, binding)

        assertEquals(FlightVersionState.BOTH_CHANGED, row.versionState(localDirty = false, serverVerified = true))
        assertEquals(initialRemote.id, row.binding!!.remoteId)
    }

    @Test
    fun planAndRecordedCopiesRemainVisibleInTheirSeparateLibraryCategories() {
        val recordedOnB = phoneBCopy.copy(updatedAtMillis = 40, sampleCount = 12)
        val rows = flightCloudRows(listOf(recordedOnB), listOf(initialRemote), listOf(binding))

        assertEquals(2, rows.size)
        assertTrue(rows.any { it.local?.id == recordedOnB.id && it.remote?.id == initialRemote.id })
        assertTrue(rows.any { it.local == null && it.remote?.id == initialRemote.id })
    }
}
