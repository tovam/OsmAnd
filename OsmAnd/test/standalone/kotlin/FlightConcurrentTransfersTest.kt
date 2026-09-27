package net.osmand.test.junit

import kotlinx.coroutines.*
import net.osmand.plus.plugins.flightmode.flightConcurrentTransfers
import org.junit.Assert.*
import org.junit.Test

class FlightConcurrentTransfersTest {
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
