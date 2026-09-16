package net.osmand.test.junit

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightAssetSchedulerTest {
    private val key = FlightAssetScheduler.Key(TerrainTileId(10, 10, 10))

    @Test
    fun completionWakesAllWaitersWithoutPolling() = runBlocking {
        FlightAssetScheduler().use { scheduler ->
            val finish = CountDownLatch(1)
            scheduler.reconcile(
                listOf(
                    FlightAssetScheduler.Work(key, 16) {
                        check(finish.await(2, TimeUnit.SECONDS))
                        "ready"
                    }
                )
            )
            val first = async { scheduler.await<String>(key).getOrThrow() }
            val second = async { scheduler.await<String>(key).getOrThrow() }
            yield()
            assertFalse(first.isCompleted)
            finish.countDown()
            withTimeout(3000) {
                assertEquals("ready", first.await())
                assertEquals("ready", second.await())
                assertEquals("ready", scheduler.await<String>(key).getOrThrow())
            }
        }
    }

    @Test
    fun removedAndClosedDemandsWakeSuspendedWaiters() = runBlocking {
        for (close in listOf(false, true)) FlightAssetScheduler().use { scheduler ->
            scheduler.reconcile(
                listOf(
                    FlightAssetScheduler.Work(key, 16) {
                        CountDownLatch(1).await()
                        "unused"
                    }
                )
            )
            val waiter = async { runCatching { scheduler.await<String>(key) } }
            yield()
            if (close) scheduler.close() else scheduler.reconcile(emptyList())
            assertTrue(withTimeout(3000) { waiter.await() }.isFailure)
        }
    }
}
