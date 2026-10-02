package net.osmand.plus.plugins.flightmode

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

internal enum class FlightDateSource {
    RECORDED,
    PLANNED,
    SIMULATED,
}

internal data class FlightDateRange(
    val startMillis: Long,
    val endMillis: Long?,
    val startOffsetMinutes: Int,
    val endOffsetMinutes: Int,
    val source: FlightDateSource,
    val ongoing: Boolean = false,
)

internal data class FlightDateText(val compact: String, val start: String, val end: String?)

/** Use flight timestamps, never file/save dates, the playhead, or the predicted live future. */
internal fun flightDateRange(
    trip: FlightTrip?,
    preparation: FlightPreparation?,
    planned: Boolean,
    simulated: Boolean,
    ongoing: Boolean,
): FlightDateRange? {
    fun schedule(): FlightDateRange? {
        val p = preparation?.takeIf { it.departureMillis > 0L } ?: return null
        return FlightDateRange(
            p.departureMillis,
            p.scheduledFlights().last().arrivalMillis.takeIf { it >= p.departureMillis },
            p.departureOffsetMinutes,
            p.scheduledFlights().last().arrivalOffsetMinutes,
            if (simulated) FlightDateSource.SIMULATED else FlightDateSource.PLANNED,
        )
    }
    // Preview samples may use an invented clock when no schedule was entered.
    if (planned) return schedule()
    if (simulated)
        schedule()?.let {
            return it
        }

    var start: Long? = null
    var end: Long? = null
    for (sample in trip?.samples.orEmpty()) {
        val timestamp = sample.timestampMillis.takeIf { it > 0L } ?: continue
        start = minOf(start ?: timestamp, timestamp)
        end = maxOf(end ?: timestamp, timestamp)
    }
    return start?.let {
        FlightDateRange(
            it,
            end?.takeUnless { ongoing || it == start },
            preparation?.departureOffsetMinutes ?: 0,
            preparation?.scheduledFlights()?.lastOrNull()?.arrivalOffsetMinutes ?: 0,
            if (simulated) FlightDateSource.SIMULATED else FlightDateSource.RECORDED,
            ongoing,
        )
    } ?: schedule()
}

/** Unknown source timezones are shown in UTC, not silently changed to the viewing phone's zone. */
internal fun formatFlightDates(range: FlightDateRange, locale: Locale): FlightDateText {
    fun format(millis: Long, offset: Int, pattern: String): String =
        SimpleDateFormat(pattern, locale)
            .apply { timeZone = SimpleTimeZone(offset.coerceIn(-720, 840) * 60_000, "flight-date") }
            .format(Date(millis))
    fun start(pattern: String) = format(range.startMillis, range.startOffsetMinutes, pattern)
    fun end(pattern: String) =
        format(requireNotNull(range.endMillis), range.endOffsetMinutes, pattern)
    fun zone(offset: Int) = if (offset == 0) "UTC" else "UTC${FlightPreparation.offsetText(offset)}"

    val compact =
        when {
            range.endMillis == null || start("yyyy-MM-dd") == end("yyyy-MM-dd") ->
                start("d MMM yyyy")
            start("yyyy-MM") == end("yyyy-MM") && start("dd") < end("dd") ->
                "${start("d")}–${end("d MMM yyyy")}"
            start("yyyy") == end("yyyy") -> "${start("d MMM")} → ${end("d MMM yyyy")}"
            else -> "${start("d MMM yyyy")} → ${end("d MMM yyyy")}"
        }
    return FlightDateText(
        compact,
        "${start("d MMM yyyy · HH:mm")} ${zone(range.startOffsetMinutes)}",
        range.endMillis?.let { "${end("d MMM yyyy · HH:mm")} ${zone(range.endOffsetMinutes)}" },
    )
}
