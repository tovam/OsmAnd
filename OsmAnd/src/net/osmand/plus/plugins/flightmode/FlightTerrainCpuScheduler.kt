package net.osmand.plus.plugins.flightmode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Runs independent terrain geometry jobs across the phone's CPU without starving
 * the UI and OpenGL threads. Results are returned in input order even though the
 * most expensive tiles can finish in any order.
 */
object FlightTerrainCpuScheduler {

	fun geometryWorkerCount(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int =
		(availableProcessors.coerceAtLeast(1) - RESERVED_PROCESSORS)
			.coerceAtLeast(1)
			.coerceAtMost(MAXIMUM_GEOMETRY_WORKERS)

	suspend fun <T, R> map(
		items: List<T>,
		workerCount: Int = geometryWorkerCount(),
		transform: (T) -> R
	): List<R> {
		if (items.isEmpty()) return emptyList()
		val activeWorkers = workerCount.coerceIn(1, items.size)
		if (activeWorkers == 1) {
			return items.map { item ->
				runInterruptible(Dispatchers.Default) { transform(item) }
			}
		}
		return coroutineScope {
			val work = Channel<IndexedValue<T>>(activeWorkers * 2)
			val results = Channel<IndexedResult<R>>(activeWorkers * 2)
			val producer = launch {
				items.forEachIndexed { index, item -> work.send(IndexedValue(index, item)) }
				work.close()
			}
			val workers = List(activeWorkers) {
				launch {
					for ((index, item) in work) {
						val result = runInterruptible(Dispatchers.Default) { transform(item) }
						results.send(IndexedResult(index, result))
					}
				}
			}
			val orderedResults = HashMap<Int, R>(items.size)
			repeat(items.size) {
				val result = results.receive()
				orderedResults[result.index] = result.value
			}
			producer.join()
			workers.forEach { it.join() }
			results.close()
			items.indices.map { index -> orderedResults.getValue(index) }
		}
	}

	private data class IndexedResult<T>(val index: Int, val value: T)

	private const val RESERVED_PROCESSORS = 2
	private const val MAXIMUM_GEOMETRY_WORKERS = 8
}
