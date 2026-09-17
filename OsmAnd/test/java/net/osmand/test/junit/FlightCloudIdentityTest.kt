package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightCloudBinding
import net.osmand.plus.plugins.flightmode.FlightCloudEntry
import net.osmand.plus.plugins.flightmode.FlightJourneySummary
import net.osmand.plus.plugins.flightmode.FlightLibraryRow
import net.osmand.plus.plugins.flightmode.shortFlightJourneyId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic identity resolution only; no device storage or cloud connection is used. */
class FlightCloudIdentityTest {

    private val local = FlightJourneySummary("new-local-copy-on-b", "Synthetic flight", 20, 0, 0)
    private val remote = FlightCloudEntry("flight-created-on-a", "Synthetic flight", "a".repeat(64), 500, 20, emptySet(), 0)
    private val binding = FlightCloudBinding(local.id, remote.id, remote.revision, local.updatedAtMillis, true)

    @Test
    fun cloudEntryIdentityWinsOverTheNewLocalCopyId() {
        val row = FlightLibraryRow(local, remote, binding)

        assertEquals(remote.id, row.logicalCloudId)
        assertEquals("flight-c…on-a", shortFlightJourneyId(requireNotNull(row.logicalCloudId)))
    }

    @Test
    fun rememberedBindingKeepsTheCloudIdentityWhileOffline() {
        assertEquals(remote.id, FlightLibraryRow(local, null, binding).logicalCloudId)
    }

    @Test
    fun neverUploadedJourneyUsesItsFutureCloudId() {
        val unuploaded = FlightJourneySummary("created-on-this-phone", "Draft", 30, 0, 0)

        assertEquals(unuploaded.id, FlightLibraryRow(unuploaded, null, null).logicalCloudId)
    }
}
