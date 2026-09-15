package net.osmand.test.junit

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic journals only. Opening is a local action regardless of the server/version state. */
class FlightLocalNavigationTest {
    private val local = FlightJourneySummary("phone", "Synthetic flight", 20, 40, 3)
    private val remote =
        FlightCloudEntry("server", "Synthetic flight", "a".repeat(64), 500, 20, emptySet(), 40)
    private val binding = FlightCloudBinding(local.id, remote.id, remote.revision, 20, true)

    @Test
    fun localOpeningIsNeverGatedByUnsavedEditsOrServerVersions() {
        for (current in listOf(null, local.id, "another-flight")) {
            for (dirty in listOf(false, true)) {
                val state = FlightUiState(journeyId = current, journeyDirty = dirty)
                for (entry in listOf(null, remote)) {
                    for (copy in
                        listOf(
                            null,
                            binding,
                            binding.copy(revision = "old"),
                            binding.copy(localUpdatedAt = 1),
                        )) {
                        assertTrue(FlightLibraryRow(local, entry, copy).canOpenLocal(state))
                    }
                }
            }
        }
    }

    @Test
    fun onlyLocalOpeningAndRemovalTemporarilyDisableLocalOpen() {
        val row = FlightLibraryRow(local, remote, binding)
        assertFalse(row.canOpenLocal(FlightUiState(loadingTrip = true)))
        assertFalse(row.canOpenLocal(FlightUiState(), localRemovalInProgress = true))
        assertFalse(FlightLibraryRow(null, remote, null).canOpenLocal(FlightUiState()))
        assertTrue(
            row.canOpenLocal(FlightUiState(savingJourney = true, journeySaveError = "disk error"))
        )
    }

    @Test
    fun saveCompletesBeforeLoadingAnotherJournal() = runBlocking {
        val events = mutableListOf<String>()
        openLocalFlight(
            false,
            { events += "save-phone" },
            {
                events += "load-phone"
                "other"
            },
            { events += "apply-$it" },
            { fail("Must not resume") },
        )
        assertEquals(listOf("save-phone", "load-phone", "apply-other"), events)
    }

    @Test
    fun failedLocalSaveKeepsCurrentJournalAndNeverLoadsTheOtherOne() = runBlocking {
        var selected = "current"
        try {
            openLocalFlight<String>(
                false,
                { throw IOException("synthetic disk full") },
                {
                    fail("Must not load")
                    "other"
                },
                { selected = it },
                { fail("Must not resume") },
            )
            fail("Expected save failure")
        } catch (expected: IOException) {
            assertEquals("synthetic disk full", expected.message)
        }
        assertEquals("current", selected)
    }

    @Test
    fun failedLoadPreservesBothSavedChangesAndCurrentSelection() = runBlocking {
        var saved = false
        var selected = "current"
        try {
            openLocalFlight<String>(
                false,
                { saved = true },
                { throw IOException("synthetic missing file") },
                { selected = it },
                { fail("Must not resume") },
            )
            fail("Expected load failure")
        } catch (_: IOException) {}
        assertTrue(saved)
        assertEquals("current", selected)
    }

    @Test
    fun resumingNeverReloadsNorResetsUnsavedEditsAndTimeline() = runBlocking {
        var resumed = false
        openLocalFlight<String>(
            true,
            { fail("Must not save or replace an open journal") },
            {
                fail("Must not reload")
                "unused"
            },
            { fail("Must not apply from disk") },
            { resumed = true },
        )
        assertTrue(resumed)
    }

    @Test
    fun slowDiskWriteIsAwaitedBeforeSwitching() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loaded = false
        val operation = launch {
            openLocalFlight(
                false,
                {
                    writing.complete(Unit)
                    release.await()
                },
                {
                    loaded = true
                    local
                },
                {},
                {},
            )
        }
        writing.await()
        assertFalse(loaded)
        release.complete(Unit)
        operation.join()
        assertTrue(loaded)
    }

    @Test
    fun browserAndSelectedJournalAreNotSiblingTabs() {
        val pages = FlightWorkspaceNavigation.pages(FlightSessionMode.REPLAY)
        assertTrue(FlightPage.JOURNAL in pages)
        assertFalse(FlightPage.JOURNEYS in pages)
        assertFalse(
            FlightPage.JOURNAL in FlightWorkspaceNavigation.pages(FlightSessionMode.PREPARE)
        )
        assertFalse(FlightPage.JOURNAL in FlightWorkspaceNavigation.pages(FlightSessionMode.LIVE))
        assertEquals(
            FlightPage.JOURNEYS,
            FlightWorkspaceNavigation.backPage(FlightPage.MAP, FlightSessionMode.REPLAY),
        )
        assertEquals(
            FlightPage.PLANS,
            FlightWorkspaceNavigation.backPage(FlightPage.MAP, FlightSessionMode.PREPARE),
        )
        assertEquals(
            FlightPage.HOME,
            FlightWorkspaceNavigation.backPage(FlightPage.MAP, FlightSessionMode.LIVE),
        )
        assertEquals(
            FlightPage.HOME,
            FlightWorkspaceNavigation.backPage(FlightPage.JOURNEYS, FlightSessionMode.REPLAY),
        )
        assertEquals(
            FlightPage.MAP,
            FlightWorkspaceNavigation.backPage(FlightPage.JOURNAL, FlightSessionMode.REPLAY),
        )
        assertNull(FlightWorkspaceNavigation.backPage(FlightPage.HOME, FlightSessionMode.REPLAY))
    }

    @Test
    fun resumeTargetsMatchTheSelectedSessionNotTheLastLibrary() {
        assertEquals(
            FlightPage.PREPARE,
            FlightWorkspaceNavigation.resumePage(
                FlightUiState(sessionMode = FlightSessionMode.PREPARE)
            ),
        )
        for (mode in listOf(FlightSessionMode.REPLAY, FlightSessionMode.LIVE)) {
            assertEquals(
                FlightPage.MAP,
                FlightWorkspaceNavigation.resumePage(FlightUiState(sessionMode = mode)),
            )
        }
    }

    @Test
    fun versionBadgesDistinguishLocalServerAndBothWithoutBlockingOpening() {
        val row = FlightLibraryRow(local, remote, binding)
        assertEquals(FlightVersionState.SENT, row.versionState(false, true))
        assertEquals(FlightVersionState.UNVERIFIED, row.versionState(false, false))
        assertEquals(FlightVersionState.LOCAL_CHANGED, row.versionState(true, true))
        assertEquals(
            FlightVersionState.SERVER_CHANGED,
            row.copy(binding = binding.copy(revision = "old")).versionState(false, true),
        )
        assertEquals(
            FlightVersionState.BOTH_CHANGED,
            row.copy(binding = binding.copy(revision = "old")).versionState(true, true),
        )
        assertEquals(
            FlightVersionState.PARTIAL,
            row.copy(binding = binding.copy(allLocalPhotosIncluded = false))
                .versionState(false, true),
        )
        assertEquals(FlightVersionState.NOT_SENT, row.copy(remote = null).versionState(false, true))
        assertEquals(
            FlightVersionState.SERVER_ONLY,
            row.copy(local = null).versionState(false, true),
        )
    }

    @Test
    fun phoneAndServerFiltersKeepSharedCopiesInBothViews() {
        val shared = FlightLibraryRow(local, remote, binding)
        assertTrue(shared.matchesLocation(0))
        assertTrue(shared.matchesLocation(1))
        assertTrue(shared.matchesLocation(2))
        assertFalse(shared.copy(local = null).matchesLocation(1))
        assertFalse(shared.copy(remote = null).matchesLocation(2))
    }

    @Test
    fun backgroundRecorderMeasurementsDoNotKeepLocalEditsDirtyForever() {
        val original = FlightUiState(journeyId = "live", sessionMode = FlightSessionMode.LIVE)
        val updated =
            original.copy(
                trip = recordedFlightTrip("new recorder snapshot", emptyList()),
                batteryHistory = listOf(FlightBatteryPoint(123456, 50f, false)),
            )
        assertTrue(
            updated.hasSameJournalContentAs(
                original,
                includeTrip = false,
                includeMeasurements = false,
            )
        )
        assertFalse(updated.hasSameJournalContentAs(original))
        assertFalse(
            updated
                .copy(journeyName = "actual user edit")
                .hasSameJournalContentAs(original, includeTrip = false, includeMeasurements = false)
        )
    }
}
