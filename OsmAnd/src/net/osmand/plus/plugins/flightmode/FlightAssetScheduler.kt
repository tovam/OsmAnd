package net.osmand.plus.plugins.flightmode

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import net.osmand.util.PreparedResourceQueue

/** Download/decode jobs belong to the desired resources, not to a cancelled scene collector. */
internal class FlightAssetScheduler : AutoCloseable {
    data class Key(val tile: TerrainTileId, val satelliteZoomDelta: Int = -1)

    data class Work(val key: Key, val reservedBytes: Long, val load: () -> Any)

    private val revision = MutableStateFlow(0L)
    private val queue =
        PreparedResourceQueue<Key, Result<Any>>(
            4,
            160L * 1024 * 1024,
            {},
            { revision.update { it + 1 } },
        )
    private val wanted = linkedMapOf<Key, Work>()
    private val completed = hashMapOf<Key, Result<Any>>()

    @Synchronized
    fun reconcile(work: List<Work>) {
        wanted.clear()
        work.forEach { wanted[it.key] = it }
        completed.keys.retainAll(wanted.keys)
        completed.entries.removeAll { it.value.isFailure }
        refresh()
        revision.update { it + 1 }
    }

    @Synchronized
    private fun refresh() {
        queue.reconcile(
            wanted.values
                .filter { it.key !in completed }
                .map { work ->
                    PreparedResourceQueue.Request(work.key, work.reservedBytes) {
                        runCatching(work.load)
                    }
                }
        )
    }

    suspend fun <T> await(key: Key): Result<T> {
        synchronized(this) {
            if (completed[key]?.isFailure == true) {
                completed.remove(key)
                refresh()
            }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = revision.value
            val result =
                synchronized(this) {
                    check(key in wanted) { "Obsolete resource demand" }
                    completed[key] ?: queue.take(key)?.also { completed[key] = it }
                }
            if (result != null) {
                @Suppress("UNCHECKED_CAST")
                return result as Result<T>
            }
            revision.first { it != observed }
        }
    }

    @Synchronized
    override fun close() {
        queue.close()
        wanted.clear()
        completed.clear()
        revision.update { it + 1 }
    }
}
