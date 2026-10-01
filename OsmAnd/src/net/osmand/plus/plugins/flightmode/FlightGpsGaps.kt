package net.osmand.plus.plugins.flightmode

/** A measured edge whose samples are separated by missing fixes or a recorded leg boundary. */
internal data class FlightGpsGap(val fromIndex: Int, val from: FlightSample, val to: FlightSample)

/** A route stroke is kept uniform in color so native and Canvas rendering agree. */
internal data class FlightRouteSegment(val range: IntRange, val isGap: Boolean)

/**
 * Finds missing-fix edges in recorded samples. A timestamp outlier is a gap when its interval is
 * strictly greater than three times the median positive interval in the recorded trajectory.
 * Leg boundaries are always gaps because the recorder has no continuous physical segment there.
 */
internal fun flightGpsGaps(
    samples: List<FlightSample>,
    recordedSampleCount: Int = samples.size,
): List<FlightGpsGap> {
    val count = recordedSampleCount.coerceIn(0, samples.size)
    if (count < 2) return emptyList()
    val positiveIntervals = (1 until count).mapNotNull { index ->
        if (samples[index].excludedBefore || samples[index].timestampMillis <= 0L || samples[index - 1].timestampMillis <= 0L)
            return@mapNotNull null
        (samples[index].timestampMillis - samples[index - 1].timestampMillis)
            .takeIf { it > 0L }
    }.sorted()
    val median = positiveIntervals.medianOrNull()
    return (0 until count - 1).mapNotNull { index ->
        val from = samples[index]
        val to = samples[index + 1]
        if (to.excludedBefore) return@mapNotNull null
        val interval = to.timestampMillis - from.timestampMillis
        val crossesLegBoundary = from.legIndex != to.legIndex
        val hasMissingFixes = median != null && from.timestampMillis > 0L && to.timestampMillis > 0L && interval > 0L &&
            interval.toDouble() > median.toDouble() * 3.0
        if (crossesLegBoundary || hasMissingFixes) FlightGpsGap(index, from, to) else null
    }
}

/** Splits a sample path into same-color runs, preserving each original sample position. */
internal fun flightRouteSegments(
    samples: List<FlightSample>,
    gaps: List<FlightGpsGap>? = null,
    recorded: Boolean = true,
): List<FlightRouteSegment> {
    if (samples.size < 2) return emptyList()
    if (!recorded) {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        for (index in 1 until samples.size) {
            if (samples[index].excludedBefore || samples[index].legIndex != samples[index - 1].legIndex) {
                ranges += start until index
                start = index
            }
        }
        ranges += start..samples.lastIndex
        return ranges.map { FlightRouteSegment(it, isGap = false) }
    }
    val gapEdges = (gaps ?: flightGpsGaps(samples)).mapNotNull { gap ->
        gap.fromIndex.takeIf { samples.getOrNull(it) == gap.from && samples.getOrNull(it + 1) == gap.to }
    }.toSet()
    val segments = mutableListOf<FlightRouteSegment>()
    val starts = listOf(0) + (1 until samples.size).filter { samples[it].excludedBefore }
    starts.forEachIndexed { chunk, first ->
        val last = (starts.getOrNull(chunk + 1) ?: samples.size) - 1
        var start = first
        var isGap = first in gapEdges
        for (edge in first + 1 until last) {
            val edgeIsGap = edge in gapEdges
            if (edgeIsGap != isGap) {
                segments += FlightRouteSegment(start..edge, isGap)
                start = edge
                isGap = edgeIsGap
            }
        }
        segments += FlightRouteSegment(start..last, isGap)
    }
    return segments
}

private fun List<Long>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val middle = size / 2
    return if (size % 2 == 1) this[middle].toDouble()
    else (this[middle - 1].toDouble() + this[middle].toDouble()) / 2.0
}
