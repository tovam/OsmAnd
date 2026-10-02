package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic recording only; no application journals or user photos are accessed. */
class FlightIgnoredTimesTest {
    private val epoch = 1800000000000L
    private val minute = 60000L

    private fun time(index: Int) = epoch + index * minute

    private fun recording(): FlightTrip =
        recordedFlightTrip(
            "Synthetic flight",
            (0..8).map { i ->
                FlightSample(
                    i,
                    0,
                    time(i),
                    45.0 + i * .01,
                    10.0 + i * .02,
                    1000.0 + i * 100,
                    200f,
                    90f,
                    5f,
                    soundDb = i.toFloat(),
                )
            },
        )

    @Test
    fun rangesNormalizeOverlapAndPersistWithoutChangingLegacyDefaults() {
        val ranges =
            listOf(
                FlightTimeRange(time(4), time(6)),
                FlightTimeRange(time(1), time(4)),
                FlightTimeRange(time(6) + 1, time(7)),
                FlightTimeRange(0, time(1)),
                FlightTimeRange(time(5), time(2)),
            )
        val expected = listOf(FlightTimeRange(time(1), time(7)))
        assertEquals(expected, normalizedFlightTimeRanges(ranges))
        assertEquals(expected, flightTimeRangesFromJson(flightTimeRangesToJson(ranges)))
        assertTrue(flightTimeRangesFromJson(null).isEmpty())
        assertTrue(flightTimeRangesFromJson(JSONArray().put("invalid").put(JSONObject())).isEmpty())
        assertEquals(
            listOf(FlightTimeRange(1, Long.MAX_VALUE)),
            normalizedFlightTimeRanges(
                listOf(
                    FlightTimeRange(1, Long.MAX_VALUE),
                    FlightTimeRange(Long.MAX_VALUE, Long.MAX_VALUE),
                )
            ),
        )
    }

    @Test
    fun hidingPostLandingTailShortensVisibleReplayAndKeepsOriginalRecording() {
        val source = recording()
        val visible = visibleFlightTrip(source, listOf(FlightTimeRange(time(5) + 1, time(8))))
        assertEquals((0..5).toList(), visible.samples.map { it.index })
        assertEquals(5 * minute, visible.durationMillis)
        assertTrue(visible.totalDistanceMeters < source.totalDistanceMeters)
        assertSame(source, visible.recording())
        assertEquals(9, source.samples.size)
        assertTrue(source.samples.none { it.excludedBefore })
        assertSame(source, visibleFlightTrip(visible, emptyList()))
        assertEquals(visible, visibleFlightTrip(visible, visible.ignoredTimeRanges))
    }

    @Test
    fun separatePeriodsDoNotCreateMapLinesOrRedGpsGapsAcrossHiddenGround() {
        val visible =
            visibleFlightTrip(
                recording(),
                listOf(FlightTimeRange(time(2), time(3)), FlightTimeRange(time(5), time(6))),
            )
        assertEquals(listOf(0, 1, 4, 7, 8), visible.samples.map { it.index })
        assertEquals(
            listOf(false, false, true, true, false),
            visible.samples.map { it.excludedBefore },
        )
        assertEquals(3, visible.legs.size)
        assertTrue(flightGpsGaps(visible.samples).isEmpty())
        val paths = flightRoutePaths(visible.samples)
        assertEquals(
            listOf(listOf(0, 1), listOf(4), listOf(7, 8)),
            paths.map { path -> path.vertices.map { it.sample.index } },
        )
        assertTrue(paths.none { it.isGap })
        val profile = FlightProfilePlanner.fromTrip(visible)
        assertEquals(3, profile.legs.size)
        assertEquals(visible.totalDistanceMeters / 1000, profile.totalDistanceKm.toDouble(), .001)
    }

    @Test
    fun ignoredTimeBetweenSparseFixesStillBreaksTheDisplayedEdge() {
        val visible =
            visibleFlightTrip(recording(), listOf(FlightTimeRange(time(2) + 1000, time(3) - 1000)))
        assertEquals(9, visible.samples.size)
        assertTrue(visible.samples[3].excludedBefore)
        assertEquals(2, visible.legs.size)
        assertTrue(
            flightRoutePaths(visible.samples).none { path ->
                path.vertices.zipWithNext().any { (a, b) ->
                    a.sample.index == 2 && b.sample.index == 3
                }
            }
        )
    }

    @Test
    fun replayAndSingleSecondStepsSkipIgnoredTimeInBothDirections() {
        val visible = visibleFlightTrip(recording(), listOf(FlightTimeRange(time(2), time(4))))
        val replay = FlightReplayEngine(visible)
        val before = visible.progressFor(visible.samples.first { it.index == 1 })
        val after = visible.progressFor(visible.samples.first { it.index == 5 })
        val skipped = replay.snapshotAt((before + after) / 2)
        assertEquals(time(5), skipped.sample.timestampMillis)
        assertEquals(after, skipped.progress, .00001f)
        assertFalse(skipped.interpolated)
        val backward = stepFlightReplayProgress(visible, after, -1000)
        assertEquals(time(1), replay.snapshotAt(backward).sample.timestampMillis)
        val forward = stepFlightReplayProgress(visible, before, 1000)
        assertEquals(time(5), replay.snapshotAt(forward).sample.timestampMillis)
        for (i in 0..100) assertFalse(
            visible.isTimeIgnored(replay.snapshotAt(i / 100f).sample.timestampMillis)
        )
    }

    @Test
    fun photoPositionsRemainCanonicalWhenTheBeginningIsHidden() {
        val source = recording()
        val visible = visibleFlightTrip(source, listOf(FlightTimeRange(time(0), time(2))))
        val originalPhotoSample = FlightSampleInterpolator.sampleAt(source, 6.25)!!
        val displayedPhotoSample = FlightSampleInterpolator.sampleAt(visible, 6.25)!!
        assertEquals(originalPhotoSample, displayedPhotoSample)
        assertEquals(
            6.25,
            FlightSampleInterpolator.positionAtTimestamp(
                visible,
                originalPhotoSample.timestampMillis,
                0,
            )!!,
            .00001,
        )
        assertEquals(5.5, FlightSampleInterpolator.positionAtProgress(visible, .5f)!!, .001)
        assertEquals(.65f, FlightSampleInterpolator.progressAt(visible, 6.25)!!, .00001f)
        assertNull(FlightSampleInterpolator.sampleAt(visible, 1.5))
        assertNull(FlightSampleInterpolator.positionAtTimestamp(visible, time(1), 0))
    }

    @Test
    fun completeMaskAndSingleRetainedFixCanBeRestoredWithoutLosingData() {
        val source = recording()
        val empty = visibleFlightTrip(source, listOf(FlightTimeRange(time(0), time(8))))
        assertTrue(empty.samples.isEmpty())
        assertTrue(empty.legs.isEmpty())
        assertEquals(0.0, empty.totalDistanceMeters, 0.0)
        assertNull(FlightSampleInterpolator.sampleAt(empty, 3.0))
        assertSame(source, visibleFlightTrip(empty, emptyList()))
        val single =
            visibleFlightTrip(
                source,
                listOf(FlightTimeRange(time(0), time(6)), FlightTimeRange(time(7) + 1, time(8))),
            )
        assertEquals(7.0, FlightSampleInterpolator.positionAtProgress(single, .5f)!!, 0.0)
        assertEquals(time(7), FlightReplayEngine(single).snapshotAt(.5f).sample.timestampMillis)
        assertNull(FlightSampleInterpolator.progressAt(single, 3.0))
    }

    @Test
    fun noClockRecordingDoesNotApplyTimeExclusions() {
        val source = recording().copy(hasUsableTimestamps = false)
        assertSame(source, visibleFlightTrip(source, listOf(FlightTimeRange(time(0), time(8)))))
    }

    @Test
    fun spanProjectionKeepsSourceIndicesForRemovingTheCorrectSpan() {
        val spans = listOf(FlightSpan(0f, .2f), FlightSpan(.5f, .875f))
        val visible = visibleFlightTrip(recording(), listOf(FlightTimeRange(time(0), time(2))))
        val mapped = displayedFlightSpanEntries(visible, spans)
        assertEquals(listOf(1), mapped.map { it.first })
        assertEquals(FlightSpan(.2f, .8f), mapped.single().second)
    }

    @Test
    fun ignoredPeriodsParticipateInLocalAndCloudDirtyChecks() {
        val source = recording()
        val state = FlightUiState(trip = source)
        assertFalse(
            state.hasSameJournalContentAs(
                state.copy(ignoredTimeRanges = listOf(FlightTimeRange(time(2), time(3))))
            )
        )
        assertFalse(state.hasSameJournalContentAs(state.copy(automaticFlightTimes = false)))
        assertFalse(state.hasSameJournalContentAs(state.copy(restoredTimeRanges = listOf(FlightTimeRange(time(2), time(3))))))
        val journey =
            FlightJourney(
                "synthetic",
                "Synthetic",
                epoch,
                epoch,
                FlightPlan(emptyList()),
                source,
                emptyList(),
                emptyList(),
            )
        assertFalse(journey.hasSameCloudContentAs(journey.copy(automaticFlightTimes = false)))
        assertFalse(journey.hasSameCloudContentAs(journey.copy(restoredTimeRanges = listOf(FlightTimeRange(time(2), time(3))))))
        assertFalse(
            journey.hasSameCloudContentAs(
                journey.copy(ignoredTimeRanges = listOf(FlightTimeRange(time(2), time(3))))
            )
        )
    }
}
