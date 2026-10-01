package net.osmand.plus.plugins.flightmode

import org.json.JSONArray
import org.json.JSONObject

/** Inclusive UTC timestamps. Ignoring a period never removes recorded GPS or photo data. */
data class FlightTimeRange(val startMillis: Long, val endMillis: Long) {
    fun contains(time: Long): Boolean = time in startMillis..endMillis
}

internal fun normalizedFlightTimeRanges(ranges: List<FlightTimeRange>): List<FlightTimeRange> {
    val result = mutableListOf<FlightTimeRange>()
    for (range in
        ranges
            .filter { it.startMillis > 0 && it.endMillis >= it.startMillis }
            .sortedBy { it.startMillis }) {
        val previous = result.lastOrNull()
        if (
            previous != null &&
                (range.startMillis <= previous.endMillis ||
                    previous.endMillis < Long.MAX_VALUE &&
                        range.startMillis == previous.endMillis + 1)
        ) {
            result[result.lastIndex] =
                previous.copy(endMillis = maxOf(previous.endMillis, range.endMillis))
        } else result += range
    }
    return result
}

internal fun FlightTrip.recording(): FlightTrip = recordedSource ?: this

internal fun FlightTrip.isTimeIgnored(time: Long): Boolean =
    ignoredTimeRanges.any { it.contains(time) }

/** One-time presentation projection; sample indices and photo associations stay canonical. */
internal fun visibleFlightTrip(trip: FlightTrip, ranges: List<FlightTimeRange>): FlightTrip {
    val source = trip.recording()
    val ignored = normalizedFlightTimeRanges(ranges)
    if (ignored.isEmpty() || !source.hasUsableTimestamps) return source
    val samples = mutableListOf<FlightSample>()
    val legs = mutableListOf<FlightLeg>()
    var previousSourceIndex = -1
    var legStart = 0
    var distance = 0.0
    var totalDistance = 0.0
    fun finishLeg() {
        if (samples.size <= legStart) return
        val first = samples[legStart]
        val last = samples.last()
        legs +=
            FlightLeg(
                legs.size,
                source.legs
                    .firstOrNull { it.index == source.samples[previousSourceIndex].legIndex }
                    ?.name
                    .orEmpty(),
                legStart,
                samples.lastIndex,
                distance,
                first.timestampMillis,
                last.timestampMillis,
            )
        totalDistance += distance
        distance = 0.0
        legStart = samples.size
    }
    source.samples.forEachIndexed { index, sample ->
        if (ignored.any { it.contains(sample.timestampMillis) }) return@forEachIndexed
        val previous = source.samples.getOrNull(previousSourceIndex)
        val cut =
            previous != null &&
                (index != previousSourceIndex + 1 ||
                    ignored.any {
                        it.startMillis <= sample.timestampMillis &&
                            it.endMillis >= previous.timestampMillis
                    })
        if (previous != null && (cut || sample.legIndex != previous.legIndex)) finishLeg()
        if (previous != null && !cut && sample.legIndex == previous.legIndex) {
            distance +=
                FlightTerrainTilePlanner.distanceKm(
                    previous.latitude,
                    previous.longitude,
                    sample.latitude,
                    sample.longitude,
                ) * 1000.0
        }
        samples += sample.copy(legIndex = legs.size, excludedBefore = cut)
        previousSourceIndex = index
    }
    finishLeg()
    return source.copy(
        samples = samples,
        legs = legs,
        totalDistanceMeters = totalDistance,
        recordedSource = source,
        ignoredTimeRanges = ignored,
    )
}

internal fun flightProgressOutsideIgnoredTime(
    trip: FlightTrip,
    progress: Float,
    forward: Boolean,
): Float {
    if (trip.samples.isEmpty() || trip.ignoredTimeRanges.isEmpty()) return progress
    val start = trip.samples.first().timestampMillis
    val end = trip.samples.last().timestampMillis
    val target = start + ((end - start).toDouble() * progress).toLong()
    var low = 0
    var high = trip.samples.lastIndex
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (trip.samples[middle].timestampMillis < target) low = middle + 1 else high = middle - 1
    }
    val upper = trip.samples[low.coerceIn(0, trip.samples.lastIndex)]
    val lower = trip.samples[(low - 1).coerceIn(0, trip.samples.lastIndex)]
    if (!upper.excludedBefore || target <= lower.timestampMillis || target >= upper.timestampMillis)
        return progress
    val point = if (forward) upper else lower
    return trip.progressFor(point)
}

internal fun displayedFlightSpans(trip: FlightTrip?, spans: List<FlightSpan>): List<FlightSpan> {
    return displayedFlightSpanEntries(trip, spans).map { it.second }
}

internal fun displayedFlightSpanEntries(
    trip: FlightTrip?,
    spans: List<FlightSpan>,
): List<Pair<Int, FlightSpan>> {
    val source = trip?.recordedSource ?: return spans.mapIndexed { index, span -> index to span }
    if (trip.samples.isEmpty()) return emptyList()
    val start = source.samples.first().timestampMillis
    val duration = source.durationMillis ?: return spans.mapIndexed { index, span -> index to span }
    val visibleStart = trip.samples.first().timestampMillis
    val visibleEnd = trip.samples.last().timestampMillis
    return spans.mapIndexedNotNull { index, span ->
        val safe = span.normalized()
        val from = start + (duration * safe.startProgress.toDouble()).toLong()
        val to = start + (duration * safe.endProgress.toDouble()).toLong()
        if (to < visibleStart || from > visibleEnd) null
        else
            index to
                FlightSpan(
                    ((from - visibleStart).toDouble() /
                            (visibleEnd - visibleStart).coerceAtLeast(1L))
                        .toFloat()
                        .coerceIn(0f, 1f),
                    ((to - visibleStart).toDouble() / (visibleEnd - visibleStart).coerceAtLeast(1L))
                        .toFloat()
                        .coerceIn(0f, 1f),
                )
    }
}

internal fun flightTimeRangesToJson(ranges: List<FlightTimeRange>): JSONArray =
    JSONArray().apply {
        normalizedFlightTimeRanges(ranges).forEach { range ->
            put(
                JSONObject().put("startMillis", range.startMillis).put("endMillis", range.endMillis)
            )
        }
    }

internal fun flightTimeRangesFromJson(json: JSONArray?): List<FlightTimeRange> =
    normalizedFlightTimeRanges(
        (0 until (json?.length() ?: 0)).mapNotNull { index ->
            json?.optJSONObject(index)?.let {
                FlightTimeRange(it.optLong("startMillis"), it.optLong("endMillis"))
            }
        }
    )
