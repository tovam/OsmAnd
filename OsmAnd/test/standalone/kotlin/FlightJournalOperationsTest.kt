package net.osmand.test.junit

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import net.osmand.plus.plugins.flightmode.FlightJournalOperations
import net.osmand.plus.plugins.flightmode.FlightUiState
import net.osmand.plus.plugins.flightmode.afterFailedJournalNavigation
import net.osmand.plus.plugins.flightmode.afterAbandonedJournalNavigation
import org.junit.Assert.*
import org.junit.Test

/** Synthetic owned values stand in for new media files. No existing file or journal is accessed. */
class FlightJournalOperationsTest {
    @Test fun duplicateImportWarningCanAbandonNavigationWithoutInventingAnError() {
        val before = FlightUiState(journeyId = "synthetic-A", loadingTrip = true,
            savingPreparation = true, simulationLoading = true, storageUsageLoading = true)
        val after = before.afterAbandonedJournalNavigation()
        assertEquals(before.journeyId, after.journeyId)
        assertSame(before.liveState, after.liveState)
        assertSame(before.localSchedules, after.localSchedules)
        assertNull(after.tripLoadError)
        assertFalse(after.loadingTrip || after.savingPreparation || after.simulationLoading || after.storageUsageLoading)
    }

    @Test fun failedNavigationKeepsTheJournalButDoesNotLeaveInvalidatedSpinners() {
        val before = FlightUiState(journeyId = "synthetic-A", journeyName = "Synthetic flight",
            loadingTrip = true, savingPreparation = true, simulationLoading = true, storageUsageLoading = true)
        val after = before.afterFailedJournalNavigation("synthetic failure")
        assertEquals(before.journeyId, after.journeyId)
        assertEquals(before.journeyName, after.journeyName)
        assertSame(before.photos, after.photos)
        assertFalse(after.loadingTrip)
        assertFalse(after.savingPreparation)
        assertFalse(after.simulationLoading)
        assertFalse(after.storageUsageLoading)
        assertEquals("synthetic failure", after.tripLoadError)
    }

    @Test
    fun normalResultIsPublishedAndNeverDiscarded() = runBlocking {
        val operations = FlightJournalOperations()
        var published = ""
        var discarded = false
        operations.loadOwned(operations.capture(), { "new photo" }, { published = it }, { discarded = true })
        assertEquals("new photo", published)
        assertFalse(discarded)
    }

    @Test
    fun resultAfterLeavingAndReopeningSameFlightIsDiscarded() = runBlocking {
        val operations = FlightJournalOperations()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var published = false
        var discarded = ""
        val token = operations.capture()
        val job = launch {
            operations.loadOwned(token, {
                started.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                "owned copy from A"
            }, { published = true }, { discarded = it })
        }
        started.await()
        operations.invalidate() // A -> B
        operations.invalidate() // B -> A: same ID must not accept the stale result either.
        release.countDown()
        job.join()
        assertFalse(published)
        assertEquals("owned copy from A", discarded)
    }

    @Test
    fun cancellationDuringIoStillDiscardsCompletedCopy() = runBlocking {
        val operations = FlightJournalOperations()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var published = false
        var discarded = false
        val job = launch {
            operations.loadOwned(operations.capture(), {
                started.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                "owned copy"
            }, { published = true }, { discarded = true })
        }
        started.await()
        job.cancel()
        release.countDown()
        job.join()
        assertFalse(published)
        assertTrue(discarded)
    }

    @Test
    fun failedLoadHasNoInventedResourceToDiscard() = runBlocking {
        val operations = FlightJournalOperations()
        var discarded = false
        try {
            operations.loadOwned<String>(operations.capture(), { error("synthetic failure") }, {}, { discarded = true })
            fail("load must fail")
        } catch (_: IllegalStateException) { }
        assertFalse(discarded)
    }
}
