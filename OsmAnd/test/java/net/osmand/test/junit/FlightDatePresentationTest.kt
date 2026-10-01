package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightPhotoTime
import net.osmand.plus.plugins.flightmode.FlightPhotoTimestampParser
import net.osmand.plus.plugins.flightmode.FlightPhotoAttachment
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import net.osmand.plus.plugins.flightmode.FlightDateRange
import net.osmand.plus.plugins.flightmode.FlightDateSource
import net.osmand.plus.plugins.flightmode.FlightPreparation
import net.osmand.plus.plugins.flightmode.FlightSample
import net.osmand.plus.plugins.flightmode.FlightTrip
import net.osmand.plus.plugins.flightmode.flightDateRange
import net.osmand.plus.plugins.flightmode.formatFlightDates
import org.junit.Assert.*
import org.junit.Test

class FlightDatePresentationTest {
    private val departure = time("2026-09-29T22:00:00Z")
    private val arrival = time("2026-09-30T02:00:00Z")
    private val schedule = FlightPreparation(departureMillis = departure, arrivalMillis = arrival)

    @Test
    fun confirmedPhotoOffsetsDoNotDependOnThePhoneTimezoneAndCorrectionsNeverCompound() {
        val old = java.util.TimeZone.getDefault()
        try {
            val filename = "IMG_20260930_123000.jpg"
            assertNull(FlightPhotoTimestampParser.parse(filename))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))
            val first = FlightPhotoTimestampParser.parse(filename, 330)!!
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(first, FlightPhotoTimestampParser.parse(filename, 330)!!)
            assertEquals(345, FlightPhotoTime.parseOffset("+05:45")!!)
            assertEquals(-210, FlightPhotoTime.parseOffset("-03:30")!!)
            assertNull(FlightPhotoTime.parseOffset("+14:15"))
            assertNull(FlightPhotoTime.parseOffset("-05:60"))
            assertNull(FlightPhotoTimestampParser.parse("IMG_20260230_123000.jpg", 0))
            val photo = FlightPhotoAttachment("p", filename, "synthetic", first, null,
                captureUtcOffsetMinutes = 330, captureLocalTimestampMillis = FlightPhotoTimestampParser.parseLocal(filename))
            val corrected = FlightPhotoTime.correctedTimestamp(photo, 0, 120)!!
            assertEquals(first + 210 * 60_000L, corrected)
            assertEquals(corrected, FlightPhotoTime.correctedTimestamp(photo.copy(timestampMillis = corrected,
                captureUtcOffsetMinutes = 120), 0, 120)!!)
            assertEquals(first, FlightPhotoTime.correctedTimestamp(photo.copy(timestampMillis = corrected,
                captureUtcOffsetMinutes = 120), 0, 330)!!)
        } finally { java.util.TimeZone.setDefault(old) }
    }

    @Test
    fun recordedFlightUsesActualTimesInsteadOfSchedule() {
        val actual =
            flightDateRange(
                trip(departure + 60_000, arrival + 120_000),
                schedule,
                false,
                false,
                false,
            )!!
        assertEquals(departure + 60_000, actual.startMillis)
        assertEquals(arrival + 120_000, actual.endMillis)
        assertEquals(FlightDateSource.RECORDED, actual.source)
    }

    @Test
    fun partiallyDatedOrUnorderedGpxStillShowsAvailableDateRange() {
        val actual = flightDateRange(trip(0, arrival, -1, departure), null, false, false, false)!!
        assertEquals(departure, actual.startMillis)
        assertEquals(arrival, actual.endMillis)
    }

    @Test
    fun preparationUsesScheduleNotSyntheticPreviewClock() {
        val actual = flightDateRange(trip(1, 2), schedule, true, false, false)!!
        assertEquals(departure, actual.startMillis)
        assertEquals(arrival, actual.endMillis)
        assertEquals(FlightDateSource.PLANNED, actual.source)
    }

    @Test
    fun undatedPreviewDoesNotInventFlightDates() {
        assertNull(
            flightDateRange(trip(departure, arrival), FlightPreparation(), true, false, false)
        )
    }

    @Test
    fun liveSimulationUsesPlannedDatesAndExplicitSimulationSource() {
        val actual =
            flightDateRange(
                trip(departure + 86400000, arrival + 86400000),
                schedule,
                false,
                true,
                true,
            )!!
        assertEquals(departure, actual.startMillis)
        assertEquals(arrival, actual.endMillis)
        assertEquals(FlightDateSource.SIMULATED, actual.source)
    }

    @Test
    fun simulationWithoutScheduleIsNeverLabeledAsRecorded() {
        val actual = flightDateRange(trip(departure, arrival), null, false, true, false)!!
        assertEquals(FlightDateSource.SIMULATED, actual.source)
    }

    @Test
    fun ongoingRecordingDoesNotClaimLastGpsFixIsArrival() {
        val actual = flightDateRange(trip(departure, arrival), schedule, false, false, true)!!
        assertEquals(departure, actual.startMillis)
        assertNull(actual.endMillis)
        assertTrue(actual.ongoing)
    }

    @Test
    fun missingRecordedTimestampsUseClearlyLabeledScheduleOrNothing() {
        val undated = trip(0, 0)
        assertEquals(
            FlightDateSource.PLANNED,
            flightDateRange(undated, schedule, false, false, false)!!.source,
        )
        assertNull(flightDateRange(undated, null, false, false, false))
        assertNull(flightDateRange(null, null, false, false, false))
    }

    @Test
    fun singleTimestampHasNoInventedArrival() {
        val actual = flightDateRange(trip(departure), null, false, false, false)!!
        assertNull(actual.endMillis)
        assertNull(formatFlightDates(actual, Locale.FRANCE).end)
    }

    @Test
    fun incompleteOrInvalidPlannedArrivalIsNotShown() {
        for (end in listOf(0L, departure - 1)) {
            assertNull(
                flightDateRange(null, schedule.copy(arrivalMillis = end), true, false, false)!!
                    .endMillis
            )
        }
    }

    @Test
    fun sameDayCompactCaptionDoesNotDuplicateDate() {
        val text = formatFlightDates(range(departure, departure + 60_000), Locale.FRANCE)
        assertEquals("29 sept. 2026", text.compact)
        assertEquals("29 sept. 2026 · 22:00 UTC", text.start)
        assertEquals("29 sept. 2026 · 22:01 UTC", text.end)
    }

    @Test
    fun overnightFlightShowsBothDates() {
        assertEquals(
            "29–30 sept. 2026",
            formatFlightDates(range(departure, arrival), Locale.FRANCE).compact,
        )
    }

    @Test
    fun monthAndYearCrossingsRemainUnambiguous() {
        val month = range(time("2026-09-30T22:00:00Z"), time("2026-10-01T02:00:00Z"))
        assertEquals("30 sept. → 1 oct. 2026", formatFlightDates(month, Locale.FRANCE).compact)
        val year = range(time("2026-12-31T22:00:00Z"), time("2027-01-01T02:00:00Z"))
        assertEquals("31 déc. 2026 → 1 janv. 2027", formatFlightDates(year, Locale.FRANCE).compact)
    }

    @Test
    fun endpointOffsetsControlLocalDatesIncludingHalfHourZones() {
        val dated = range(departure, arrival).copy(startOffsetMinutes = 120, endOffsetMinutes = 330)
        val text = formatFlightDates(dated, Locale.FRANCE)
        assertEquals("30 sept. 2026", text.compact)
        assertEquals("30 sept. 2026 · 00:00 UTC+02:00", text.start)
        assertEquals("30 sept. 2026 · 07:30 UTC+05:30", text.end)
    }

    @Test
    fun unknownGpxTimezoneUsesUtcIndependentOfPhoneTimezone() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val dates = flightDateRange(trip(departure, arrival), null, false, false, false)!!
            val text = formatFlightDates(dates, Locale.US)
            assertEquals("29–30 Sep 2026", text.compact)
            assertEquals("29 Sep 2026 · 22:00 UTC", text.start)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun internationalDateLineKeepsEndpointDatesInFlightOrder() {
        val dates =
            range(departure, arrival).copy(startOffsetMinutes = 720, endOffsetMinutes = -600)
        val text = formatFlightDates(dates, Locale.FRANCE)
        assertEquals("30 sept. → 29 sept. 2026", text.compact)
        assertEquals("29 sept. 2026 · 16:00 UTC-10:00", text.end)
    }

    private fun time(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun range(start: Long, end: Long?) =
        FlightDateRange(start, end, 0, 0, FlightDateSource.RECORDED)

    private fun trip(vararg times: Long): FlightTrip =
        FlightTrip(
            name = "Synthetic flight",
            samples =
                times.mapIndexed { index, timestamp ->
                    FlightSample(index, 0, timestamp, 48.0, 2.0, null, null, null, null)
                },
            legs = emptyList(),
            hasUsableTimestamps =
                times.size > 1 &&
                    times.all { it > 0 } &&
                    times.toList().zipWithNext().all { it.first <= it.second },
            totalDistanceMeters = 0.0,
            sourceDescription = "Synthetic date tests",
        )
}
