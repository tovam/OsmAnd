package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightAutomaticTimesTest {
    private val epoch = 1800000000000L

    private fun journey(vararg phases: Pair<Int, Float>): FlightTrip {
        val samples = mutableListOf<FlightSample>()
        var longitude = 0.0
        phases.forEach { (seconds, speed) ->
            repeat(seconds / 10) {
                longitude += speed / 3.6 * 10 / 111000.0
                samples +=
                    FlightSample(
                        samples.size,
                        0,
                        epoch + samples.size * 10000L,
                        0.0,
                        longitude,
                        1000.0,
                        speed / 3.6f,
                        90f,
                        5f,
                    )
            }
        }
        return recordedFlightTrip("Synthetic", samples)
    }

    @Test
    fun boardingTaxiFlightTaxiAndLongTailAreDetectedWithoutDiscardingRawData() {
        val raw =
            journey(
                600 to 0f,
                180 to 20f,
                60 to 0f,
                120 to 30f,
                600 to 700f,
                240 to 25f,
                14400 to 0f,
            )
        val flights = FlightAutomaticTimes.flights(raw)
        assertEquals(1, flights.size)
        assertTrue(flights.single().startMillis < epoch + 600000)
        assertTrue(flights.single().endMillis >= epoch + 1790000)
        val visible = replayFlightTrip(raw, emptyList())
        assertSame(raw, visible.recording())
        assertTrue(visible.durationMillis!! < 1300000)
        assertEquals(raw.samples.size, visible.recording().samples.size)
    }

    @Test
    fun twoFlightsHaveSmallSeparatorRealTimesAndCanonicalPhotoPositions() {
        val raw =
            journey(
                600 to 0f,
                180 to 20f,
                3600 to 700f,
                180 to 20f,
                14400 to 0f,
                180 to 20f,
                1800 to 700f,
                180 to 20f,
                600 to 0f,
            )
        val flights = FlightAutomaticTimes.flights(raw)
        assertEquals(2, flights.size)
        val visible = replayFlightTrip(raw, emptyList())
        assertTrue(visible.durationMillis!! < 6500000)
        assertEquals(2, visible.legs.size)
        val second = visible.samples.first { it.excludedBefore }
        val previous = visible.samples[visible.samples.indexOf(second) - 1]
        assertTrue(visible.progressFor(second) - visible.progressFor(previous) < .01f)
        val photoIndex = second.index + 5.5
        val progress = FlightSampleInterpolator.progressAt(visible, photoIndex)!!
        assertEquals(
            photoIndex,
            FlightSampleInterpolator.positionAtProgress(visible, progress)!!,
            .01,
        )
        for (i in 0..1000) assertFalse(
            visible.isTimeIgnored(
                FlightReplayEngine(visible).snapshotAt(i / 1000f).sample.timestampMillis
            )
        )
    }

    @Test
    fun singleSpikeWalkingAndGpsLossDoNotConfirmFlightsOrLandings() {
        val raw = journey(600 to 0f, 10 to 900f, 600 to 5f)
        assertTrue(FlightAutomaticTimes.flights(raw).isEmpty())
        assertSame(raw, replayFlightTrip(raw, emptyList()))
        val airborne = journey(600 to 700f, 600 to 0f)
        val unreliable =
            airborne.copy(
                samples =
                    airborne.samples.map {
                        if (it.speedMetersPerSecond == 0f) it.copy(horizontalAccuracyMeters = 900f)
                        else it
                    }
            )
        assertEquals(
            unreliable.samples.last().timestampMillis,
            FlightAutomaticTimes.flights(unreliable).single().endMillis,
        )
    }

    @Test
    fun restorationAndManualExclusionsRemainIndependent() {
        val raw = journey(600 to 0f, 180 to 20f, 600 to 700f, 180 to 20f, 600 to 0f)
        val auto = FlightAutomaticTimes.excluded(raw)
        val restored = replayFlightTrip(raw, emptyList(), restored = auto)
        assertSame(raw, restored)
        val manual = FlightTimeRange(raw.samples[5].timestampMillis, raw.samples[8].timestampMillis)
        val corrected = replayFlightTrip(raw, listOf(manual), restored = auto)
        assertTrue(corrected.isTimeIgnored(raw.samples[6].timestampMillis))
        assertFalse(corrected.isTimeIgnored(raw.samples[10].timestampMillis))
        assertSame(raw, replayFlightTrip(raw, emptyList(), automatic = false))
    }

    @Test
    fun stationaryPositionFallbackHandlesGpxWithoutSpeed() {
        val raw = journey(600 to 0f, 180 to 20f, 600 to 700f, 180 to 20f, 600 to 0f)
        val gpx =
            raw.copy(
                samples =
                    raw.samples.map {
                        it.copy(speedMetersPerSecond = null, horizontalAccuracyMeters = null)
                    }
            )
        assertEquals(1, FlightAutomaticTimes.flights(gpx).size)
        assertTrue(replayFlightTrip(gpx, emptyList()).durationMillis!! < gpx.durationMillis!!)
    }

    @Test
    fun configuredStillSpeedAppliesEquallyToLiveAndReplayWithPositionNoise() {
        val source = journey(600 to 0f, 180 to 20f, 600 to 700f, 180 to 20f, 600 to 0f)
        val noisy = source.copy(samples = source.samples.map { sample ->
            if (sample.timestampMillis >= epoch + 1560000) sample.copy(speedMetersPerSecond = 4f / 3.6f) else sample
        })
        val config = FlightPreparation(stopSpeedKmh = 5)
        assertEquals(noisy.samples.last().timestampMillis, FlightAutomaticTimes.flights(noisy).single().endMillis)
        assertTrue(FlightAutomaticTimes.flights(noisy, config).single().endMillis < epoch + 1600000)
        var live = FlightTrackingState(phase = FlightTrackingPhase.AIRBORNE)
        noisy.samples.filter { it.timestampMillis >= epoch + 1560000 }.forEach { sample ->
            live = live.accept(sample, sample.timestampMillis, config)
        }
        assertEquals(FlightTrackingPhase.LANDED, live.phase)
    }

    @Test
    fun gpsJitterAndIsolatedSpeedSpikesDoNotMoveGateBoundaries() {
        val raw = journey(600 to 0f, 180 to 20f, 600 to 700f, 180 to 20f, 600 to 0f)
        val noisy = raw.copy(samples = raw.samples.map { sample ->
            if (sample.speedMetersPerSecond == 0f) sample.copy(
                latitude = if (sample.index % 2 == 0) .00002 else -.00002,
                speedMetersPerSecond = if (sample.index % 17 == 0) 250f else .1f)
            else sample
        })
        val expected = FlightAutomaticTimes.flights(raw).single()
        val detected = FlightAutomaticTimes.flights(noisy).single()
        assertTrue(kotlin.math.abs(expected.startMillis - detected.startMillis) <= 20000)
        assertTrue(kotlin.math.abs(expected.endMillis - detected.endMillis) <= 20000)
    }

    @Test
    fun compressedStepsSkipOnlySeparatorsInBothDirections() {
        val raw = journey(600 to 700f)
        val visible =
            visibleFlightTrip(raw, listOf(FlightTimeRange(epoch + 120000, epoch + 400000)))
        val next = visible.samples.first { it.excludedBefore }
        val prev = visible.samples[visible.samples.indexOf(next) - 1]
        val engine = FlightReplayEngine(visible)
        assertEquals(
            next.timestampMillis,
            engine
                .snapshotAt(stepFlightReplayProgress(visible, visible.progressFor(prev), 1000))
                .sample
                .timestampMillis,
        )
        assertEquals(
            prev.timestampMillis,
            engine
                .snapshotAt(stepFlightReplayProgress(visible, visible.progressFor(next), -1000))
                .sample
                .timestampMillis,
        )
    }
}
