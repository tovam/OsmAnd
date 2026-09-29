package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightGpsGapsTest {
    private fun sample(index: Int, time: Long, leg: Int = 0) = FlightSample(
        index, leg, time, 48.0, 2.0 + index * 0.01, 1000.0 + index,
        200f, 90f, 5f,
    )

    @Test fun gapsUseStrictThreeTimesMedianOfPositiveRecordedIntervals() {
        val samples = listOf(
            sample(0, 1000), sample(1, 2000), sample(2, 3000),
            sample(3, 8000), sample(4, 9000), sample(5, 8000),
        )
        assertEquals(listOf(2), flightGpsGaps(samples).map { it.fromIndex })
        assertEquals(listOf(2), flightGpsGaps(samples, recordedSampleCount = 5).map { it.fromIndex })
    }

    @Test fun exactlyThreeMedianIntervalsDoesNotCountAsMissingFixes() {
        val samples = listOf(sample(0, 1000), sample(1, 2000), sample(2, 5000), sample(3, 6000))
        assertTrue(flightGpsGaps(samples).isEmpty())
    }

    @Test fun unknownTimestampsDoNotInventALongGap() {
        val samples = listOf(sample(0, 10000), sample(1, 11000), sample(2, 0), sample(3, 12000), sample(4, 13000))
        assertTrue(flightGpsGaps(samples).isEmpty())
    }

    @Test fun legBoundariesAreMarkedEvenWhenTimestampsAreUnusable() {
        val samples = listOf(sample(0, 1000), sample(1, 1000), sample(2, 900, leg = 1))
        assertEquals(listOf(1), flightGpsGaps(samples).map { it.fromIndex })
    }

    @Test fun mapSegmentsColorGapEdgesAndKeepTheirOriginalEndpoints() {
        val samples = listOf(sample(0, 1000), sample(1, 2000), sample(2, 6000), sample(3, 7000))
        val segments = flightRouteSegments(samples)
        assertEquals(listOf(false, true, false), segments.map { it.isGap })
        assertEquals(listOf(0..1, 1..2, 2..3), segments.map { it.range })
        assertTrue(segments.all { it.range.last < samples.size })
    }

    @Test fun onlyRecordedPrefixContributesGaps() {
        val recorded = listOf(sample(0, 1000), sample(1, 2000), sample(2, 3000))
        val displayed = recorded + sample(3, 100_000)
        assertTrue(flightGpsGaps(displayed, recordedSampleCount = recorded.size).isEmpty())
    }

    @Test fun theoreticalLegBoundariesStayBlackAndDisconnected() {
        val samples = listOf(
            sample(0, 1000, leg = 0), sample(1, 2000, leg = 0),
            sample(2, 8000, leg = 1), sample(3, 9000, leg = 1),
        )
        val segments = flightRouteSegments(samples, recorded = false)
        assertEquals(listOf(false, false), segments.map { it.isGap })
        assertEquals(listOf(0..1, 2..3), segments.map { it.range })
    }
}
