package net.osmand.plus.plugins.flightmode

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import java.io.IOException
import java.net.*

/** Bounded workers, completion-order progress. A slow first tile never blocks the other workers. */
internal suspend fun <T, R> flightConcurrentTransfers(
    requests: List<T>,
    parallelism: Int,
    transfer: suspend (T) -> R,
    completed: suspend (T, R) -> Unit,
    shouldContinue: () -> Boolean = { true },
) = coroutineScope {
    require(parallelism > 0)
    val next = AtomicInteger()
    val results = Channel<Pair<T, R>>(parallelism)
    val workers = List(minOf(parallelism, requests.size)) {
        launch {
            while (shouldContinue()) {
                val index = next.getAndIncrement()
                if (index >= requests.size) break
                val request = requests[index]
                results.send(request to transfer(request))
            }
        }
    }
    launch { workers.joinAll(); results.close() }
    try {
        for ((request, result) in results) completed(request, result)
    } finally {
        results.cancel()
    }
}

internal class FlightLowStorageFailure(message: String) : IOException(message)
internal class FlightTileHttpFailure(val statusCode: Int, source: String) : IOException("$source HTTP $statusCode")
internal enum class FlightTransferBlockReason { LOW_STORAGE, CONNECTION, SERVER_BUSY, OFFLINE }

/** A failed tile is not necessarily a global failure; repeated transport failures are. */
internal class FlightTransferFailurePolicy {
    @Volatile var blocked: FlightTransferBlockReason? = null
        private set
    private var consecutiveTransportFailures = 0

    fun completed(error: Throwable?) {
        if (blocked != null) return
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        blocked = when {
            causes.any { it is FlightLowStorageFailure } -> FlightTransferBlockReason.LOW_STORAGE
            causes.any { it.message == "offline_simulation" } -> FlightTransferBlockReason.OFFLINE
            causes.any { it is FlightTileHttpFailure && it.statusCode == 429 } -> FlightTransferBlockReason.SERVER_BUSY
            else -> null
        }
        if (blocked != null) return
        val transport = causes.any { it is UnknownHostException || it is ConnectException ||
            it is SocketTimeoutException || it is SocketException || it is FlightTileHttpFailure && it.statusCode >= 500 }
        consecutiveTransportFailures = if (transport) consecutiveTransportFailures + 1 else 0
        if (consecutiveTransportFailures >= 3) blocked = FlightTransferBlockReason.CONNECTION
    }
}
