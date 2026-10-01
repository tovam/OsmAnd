package net.osmand.test.junit

import kotlinx.coroutines.*
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightConcurrentTransfersTest {
    @Test
    fun cleanupProtectsRetainedSourcesTheirDescendantsAndPhotoRegions() {
        val reference = FlightOfflineTileKey(TerrainTileId(8, 80, 90), true)
        val region = FlightStorageRegion(0.0, 0.0, 50.0)
        val protection = FlightStorageProtection(setOf(reference), listOf(region))
        assertTrue(protection.protects(reference))
        assertTrue(protection.protects(FlightOfflineTileKey(TerrainTileId(10, 321, 361), true)))
        assertTrue(protection.protects(FlightOfflineTileKey(TerrainTileId(3, 7, 7), false)))
        assertTrue(protection.protects(FlightOfflineTileKey(TerrainTileId(14, 8192, 8192), false)))
        assertFalse(protection.protects(FlightOfflineTileKey(TerrainTileId(10, 321, 361), false)))
        assertFalse(protection.protects(FlightOfflineTileKey(TerrainTileId(14, 100, 100), true)))
    }

    @Test
    fun cleanupRechecksChangedSourceFilesAndNeverFollowsSymlinks() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"))
        val root = java.nio.file.Files.createTempDirectory(parent.toPath(), "synthetic-cleanup-").toFile()
        try {
            val file = java.io.File(root, "tile.png").apply { writeText("synthetic") }
            val candidate = FlightStorageCandidate(FlightOfflineTileKey(TerrainTileId(8, 80, 90), false), file.length(), file.lastModified())
            assertTrue(candidate.matches(root, file))
            file.appendText("changed")
            assertFalse(candidate.matches(root, file))
            val alias = java.io.File(root, "alias.png")
            java.nio.file.Files.createSymbolicLink(alias.toPath(), file.toPath())
            assertFalse(FlightStorageCandidate(candidate.key, file.length(), file.lastModified()).matches(root, alias))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cleanupGateWaitsForActiveReadersAndNestedReadsRemainSafe() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val written = java.util.concurrent.CountDownLatch(1)
        val reader = Thread { FlightTileStorageGate.read { entered.countDown(); release.await(); FlightTileStorageGate.read { } } }
        reader.start()
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val writer = Thread { FlightTileStorageGate.write { written.countDown() } }
        writer.start()
        assertFalse(written.await(20, java.util.concurrent.TimeUnit.MILLISECONDS))
        release.countDown()
        reader.join(2000); writer.join(2000)
        assertEquals(0L, written.count)
    }

    @Test
    fun pausingStopsNewRequestsAndDrainsInFlightResults() = runBlocking {
        var allowed = true
        val started = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        flightConcurrentTransfers((0..100).toList(), 3,
            transfer = { started += it; yield(); it },
            completed = { _, result -> completed += result; allowed = false },
            shouldContinue = { allowed })
        assertEquals(started.toSet(), completed.toSet())
        assertTrue(started.size <= 6)
    }

    @Test
    fun globalFailurePolicyDistinguishesMissingTilesFromConnectionAndStorageFailures() {
        val policy = FlightTransferFailurePolicy()
        repeat(20) { policy.completed(FlightTileHttpFailure(404, "Synthetic")) }
        assertNull(policy.blocked)
        repeat(2) { policy.completed(java.net.UnknownHostException("synthetic")) }
        policy.completed(null)
        assertNull(policy.blocked)
        repeat(3) { policy.completed(java.net.SocketTimeoutException("synthetic")) }
        assertEquals(FlightTransferBlockReason.CONNECTION, policy.blocked)
        policy.completed(null)
        assertNotNull(policy.blocked)
        val disk = FlightTransferFailurePolicy()
        disk.completed(FlightLowStorageFailure("synthetic"))
        assertEquals(FlightTransferBlockReason.LOW_STORAGE, disk.blocked)
    }

    @Test
    fun slowFirstTileDoesNotBlockLaterDownloadsOrProgress() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val completed = mutableListOf<Int>()
        var active = 0
        var peak = 0
        flightConcurrentTransfers(
            (0..30).toList(),
            3,
            transfer = {
                active++
                peak = maxOf(peak, active)
                if (it == 0) releaseFirst.await() else yield()
                active--
                it
            },
            completed = { _, result ->
                completed += result
                if (completed.size == 20) releaseFirst.complete(Unit)
            },
        )
        assertEquals(31, completed.size)
        assertEquals(31, completed.toSet().size)
        assertTrue(completed.indexOf(0) >= 20)
        assertTrue(peak <= 3)
    }

    @Test
    fun cancellingDownloadCancelsEveryWorker() = runBlocking {
        var stopped = 0
        val ready = CompletableDeferred<Unit>()
        var running = 0
        val job = launch {
            flightConcurrentTransfers(
                (0..30).toList(),
                3,
                transfer = {
                    running++
                    if (running == 3) ready.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped++
                    }
                },
                completed = { _, _ -> fail("Cancelled transfer must not complete") },
            )
        }
        ready.await()
        job.cancelAndJoin()
        assertEquals(3, stopped)
    }
}
