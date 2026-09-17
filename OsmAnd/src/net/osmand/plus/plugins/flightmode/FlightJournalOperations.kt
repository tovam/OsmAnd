package net.osmand.plus.plugins.flightmode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** UI-thread session identity survives a first save, but not leaving/replacing the selected flight. */
internal class FlightJournalOperations {
    private var generation = 0L

    fun capture(): Long = generation
    fun isCurrent(token: Long): Boolean = token == generation
    fun invalidate() { generation++ }

    /** Keep ownership of a completed IO result even when cancellation wins on the return to Main. */
    suspend fun <T : Any> loadOwned(
        token: Long,
        load: () -> T,
        publish: (T) -> Unit,
        discard: (T) -> Unit,
    ) {
        var result: T? = null
        var published = false
        try {
            withContext(Dispatchers.IO) { result = load() }
            if (isCurrent(token)) {
                publish(requireNotNull(result))
                published = true
            }
        } finally {
            val owned = result
            if (!published && owned != null) withContext(NonCancellable + Dispatchers.IO) { discard(owned) }
        }
    }
}

/** A replacement that did not happen must release flags owned by invalidated operations. */
internal fun FlightUiState.afterAbandonedJournalNavigation(): FlightUiState = copy(
    loadingTrip = false,
    savingPreparation = false,
    simulationLoading = false,
    storageUsageLoading = false,
)

internal fun FlightUiState.afterFailedJournalNavigation(message: String): FlightUiState =
    afterAbandonedJournalNavigation().copy(tripLoadError = message)
