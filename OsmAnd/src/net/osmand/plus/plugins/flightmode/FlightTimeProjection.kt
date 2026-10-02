package net.osmand.plus.plugins.flightmode

/** Piecewise real-time axis. Excluded periods occupy only a small separator, never hours. */
internal class FlightTimeProjection(samples: List<FlightSample>) {
    private data class Block(val from: Long, val to: Long, val offset: Long)

    private val blocks: List<Block>
    val durationMillis: Long

    init {
        val ranges = mutableListOf<Pair<Long, Long>>()
        var start = samples.first().timestampMillis
        samples.forEachIndexed { i, point ->
            if (i > 0 && point.excludedBefore) {
                ranges += start to samples[i - 1].timestampMillis
                start = point.timestampMillis
            }
        }
        ranges += start to samples.last().timestampMillis
        val useful = ranges.sumOf { (it.second - it.first).coerceAtLeast(0) }
        val separator =
            if (ranges.size > 1)
                (useful * 0.006 / (ranges.size - 1)).toLong().coerceIn(1000, 30_000)
            else 0L
        var offset = 0L
        blocks =
            ranges.map { (from, to) ->
                Block(from, to, offset).also { offset += (to - from).coerceAtLeast(0) + separator }
            }
        durationMillis = (offset - separator).coerceAtLeast(0)
    }

    fun progress(time: Long): Float {
        val index =
            blocks
                .binarySearch { it.from.compareTo(time) }
                .let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        val block = blocks[index]
        return ((block.offset + (time - block.from).coerceIn(0, block.to - block.from)).toDouble() /
                durationMillis.coerceAtLeast(1))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun timestamp(progress: Float, forward: Boolean = true): Long {
        val target = (durationMillis * progress.coerceIn(0f, 1f).toDouble()).toLong()
        val index =
            blocks
                .binarySearch { it.offset.compareTo(target) }
                .let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        val block = blocks[index]
        val elapsed = target - block.offset
        if (kotlin.math.abs(elapsed) <= 5) return block.from
        if (kotlin.math.abs(elapsed - (block.to - block.from)) <= 5) return block.to
        if (elapsed <= block.to - block.from || index == blocks.lastIndex)
            return (block.from + elapsed).coerceAtMost(block.to)
        return if (forward) blocks[index + 1].from else block.to
    }
}

internal fun FlightTrip.timestampAtProgress(progress: Float, forward: Boolean = true): Long {
    timeProjection?.let {
        return it.timestamp(progress, forward)
    }
    val start = samples.firstOrNull()?.timestampMillis ?: return 0L
    val end = samples.last().timestampMillis
    return start + ((end - start).toDouble() * progress.coerceIn(0f, 1f)).toLong()
}
