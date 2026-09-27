package net.osmand.plus.plugins.flightmode

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Bounded workers, completion-order progress. A slow first tile never blocks the other workers. */
internal suspend fun <T, R> flightConcurrentTransfers(
    requests: List<T>,
    parallelism: Int,
    transfer: suspend (T) -> R,
    completed: suspend (T, R) -> Unit,
) = coroutineScope {
    require(parallelism > 0)
    val next = AtomicInteger()
    val results = Channel<Pair<T, R>>(parallelism)
    repeat(minOf(parallelism, requests.size)) {
        launch {
            while (true) {
                val index = next.getAndIncrement()
                if (index >= requests.size) break
                val request = requests[index]
                results.send(request to transfer(request))
            }
        }
    }
    try {
        repeat(requests.size) {
            val (request, result) = results.receive()
            completed(request, result)
        }
    } finally {
        results.close()
    }
}
