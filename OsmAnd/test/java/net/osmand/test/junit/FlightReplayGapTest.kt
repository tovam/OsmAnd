package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightReplayEngine
import net.osmand.plus.plugins.flightmode.FlightSample
import net.osmand.plus.plugins.flightmode.FlightSampleInterpolator
import net.osmand.plus.plugins.flightmode.FlightTrip
import net.osmand.plus.plugins.flightmode.FlightGapTrajectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightReplayGapTest {

	@Test
	fun replayInterpolatesPositionAcrossLongRedGapAtQuarterMidpointAndThreeQuarter() {
		val trip = longGapTrip()
		val engine = FlightReplayEngine(trip)
		val trajectory = FlightGapTrajectory.buildAll(trip.samples).getValue(1)
		val start = trip.samples.first().timestampMillis
		val end = trip.samples.last().timestampMillis
		val gapStart = trip.samples[1].timestampMillis
		val gapEnd = trip.samples[2].timestampMillis

		for (fraction in listOf(0.25, 0.5, 0.75)) {
			val expectedTime = gapStart + ((gapEnd - gapStart) * fraction).toLong()
			val expectedProgress = ((expectedTime - start).toDouble() / (end - start)).toFloat()
			val snapshot = engine.snapshotAt(expectedProgress)
			val actualFraction = (snapshot.sample.timestampMillis - gapStart).toDouble() / (gapEnd - gapStart)

			assertTrue("Expected a recorded GPS gap at fraction $fraction", snapshot.dataGap)
			assertTrue(snapshot.interpolated)
			assertTrue(kotlin.math.abs(snapshot.sample.timestampMillis - expectedTime) <= 1L)
			val expected = trajectory.sampleAt(actualFraction)
			assertEquals(expected.latitude, snapshot.sample.latitude, 0.00001)
			assertEquals(expected.longitude, snapshot.sample.longitude, 0.00001)
			assertEquals(1_000.0 + (9_000.0 - 1_000.0) * actualFraction,
				snapshot.sample.altitudeMeters!!, 0.01)
		}
	}

	@Test
	fun replayPositionMovesContinuouslyAndMonotonicallyThroughoutRedGap() {
		val trip = longGapTrip()
		val engine = FlightReplayEngine(trip)
		val duration = trip.samples.last().timestampMillis - trip.samples.first().timestampMillis
		var previous = engine.snapshotAt(((trip.samples[1].timestampMillis - trip.samples.first().timestampMillis).toDouble() / duration).toFloat())

		for (step in 1..99) {
			val target = trip.samples[1].timestampMillis +
				(trip.samples[2].timestampMillis - trip.samples[1].timestampMillis) * step / 100
			val progress = ((target - trip.samples.first().timestampMillis).toDouble() / duration).toFloat()
			val current = engine.snapshotAt(progress)
			assertTrue(current.dataGap)
			assertTrue(current.sample.timestampMillis >= previous.sample.timestampMillis)
			assertTrue(current.sample.latitude >= previous.sample.latitude)
			assertTrue(current.sample.longitude >= previous.sample.longitude)
			assertTrue(kotlin.math.abs(current.sample.latitude - previous.sample.latitude) < 0.02)
			previous = current
		}
		val afterGap = engine.snapshotAt(progressAt(trip, 602_500L))
		assertFalse(afterGap.dataGap)
		assertTrue(afterGap.sample.latitude > previous.sample.latitude)
	}

	@Test
	fun replayMarksMedianGapAndLegBoundaryButPreservesExactRecordedSamples() {
		val first = sample(0, 1_000L, 48.0, 2.0, leg = 0)
		val beforeGap = sample(1, 2_000L, 48.1, 2.1, leg = 0)
		val afterGap = sample(2, 20_000L, 48.2, 2.2, leg = 0)
		val nextLeg = sample(3, 21_000L, 48.3, 2.3, leg = 1)
		val samples = listOf(first, beforeGap, afterGap, nextLeg)
		val trip = trip(samples)
		val engine = FlightReplayEngine(trip)

		assertFalse(engine.snapshotAt(progressAt(trip, 1_500L)).dataGap)
		val shortMedianGap = engine.snapshotAt(progressAt(trip, 11_000L))
		assertTrue(shortMedianGap.dataGap)
		assertTrue(shortMedianGap.interpolated)
		val legBoundary = engine.snapshotAt(progressAt(trip, 20_500L))
		assertTrue(legBoundary.dataGap)
		assertTrue(legBoundary.interpolated)

		assertSame(first, engine.snapshotAt(0f).sample)
		assertSame(nextLeg, engine.snapshotAt(1f).sample)
		val evenTimeline = trip(listOf(
			sample(0, 1_000L, 1.0, 1.0),
			sample(1, 2_000L, 2.0, 2.0),
			sample(2, 3_000L, 3.0, 3.0)
		))
		assertSame(evenTimeline.samples[1], FlightReplayEngine(evenTimeline).snapshotAt(0.5f).sample)
	}

	@Test
	fun onlyGapSnapshotsClearUnobservedGpsAndSensorFieldsAndRecordedSamplesStayUntouched() {
		val before = sample(0, 1_000L, 48.0, 2.0)
			.copy(horizontalAccuracyMeters = 7f, hdop = 0.8f, satellitesUsed = 9,
				satellitesFound = 11, soundDb = 42f, soundSpectrum = listOf(1f, 2f), vibrationHz = 50f)
		val normal = sample(1, 2_000L, 48.1, 2.1)
			.copy(horizontalAccuracyMeters = 9f, hdop = 1.2f, satellitesUsed = 8,
				satellitesFound = 10, soundDb = 44f, soundSpectrum = listOf(3f, 4f), vibrationHz = 60f)
		val after = sample(2, 602_000L, 49.1, 3.1)
			.copy(horizontalAccuracyMeters = 13f, hdop = 2f, satellitesUsed = 6,
				satellitesFound = 7, soundDb = 60f, soundSpectrum = listOf(5f, 6f), vibrationHz = 70f)
		val samples = listOf(before, normal, after)
		val original = samples.toList()
		val trip = trip(samples)
		val engine = FlightReplayEngine(trip)

		val ordinary = FlightReplayEngine(trip(listOf(before, normal))).snapshotAt(0.5f).sample
		assertEquals(8f, ordinary.horizontalAccuracyMeters!!, 0f)
		assertEquals(1f, ordinary.hdop!!, 0f)
		assertEquals(8, ordinary.satellitesUsed)
		assertEquals(10, ordinary.satellitesFound)
		assertEquals(43f, ordinary.soundDb!!, 0f)
		assertEquals(listOf(2f, 3f), ordinary.soundSpectrum)
		assertEquals(55f, ordinary.vibrationHz!!, 0f)

		val gap = engine.snapshotAt(progressAt(trip, 302_000L))
		assertTrue(gap.dataGap)
		assertNull(gap.sample.horizontalAccuracyMeters)
		assertNull(gap.sample.hdop)
		assertNull(gap.sample.satellitesUsed)
		assertNull(gap.sample.satellitesFound)
		assertNull(gap.sample.soundDb)
		assertNull(gap.sample.soundSpectrum)
		assertNull(gap.sample.vibrationHz)
		assertEquals(original, trip.samples)
		assertSame(samples, trip.samples)
	}

	@Test
	fun plannedSamplesAndLiveFutureAreNotTreatedAsRecordedGpsGaps() {
		val measuredA = sample(0, 1_000L, 48.0, 2.0)
		val measuredB = sample(1, 2_000L, 48.1, 2.1)
		val predictedA = sample(2, 602_000L, 49.0, 3.0)
		val predictedB = sample(3, 1_202_000L, 50.0, 4.0)
		val mixed = trip(listOf(measuredA, measuredB, predictedA, predictedB))
		val planned = FlightReplayEngine(mixed, recordedSampleCount = 0)
		val live = FlightReplayEngine(mixed, recordedSampleCount = 2)

		assertFalse(planned.snapshotAt(progressAt(mixed, 302_000L)).dataGap)
		assertFalse(live.snapshotAt(progressAt(mixed, 302_000L)).dataGap)
		assertFalse(live.snapshotAt(progressAt(mixed, 902_000L)).dataGap)
	}

	@Test
	fun replayInterpolatesAcrossAntimeridianUsingTheShortLongitudePath() {
		val trip = trip(listOf(
			sample(0, 1_000L, 10.0, 179.0),
			sample(1, 2_000L, 10.0, -179.0)
		))
		val middle = FlightReplayEngine(trip).snapshotAt(0.5f)
		assertEquals(180.0, kotlin.math.abs(middle.sample.longitude), 0.00001)
	}

	@Test
	fun onePointAndUntimedFallbackRemainStableAcrossLegBoundaries() {
		val only = sample(0, 1_000L, 48.0, 2.0)
		val single = trip(listOf(only))
		assertSame(only, FlightReplayEngine(single).snapshotAt(0.75f).sample)

		val a = sample(0, 0L, 10.0, 20.0, leg = 0)
		val b = sample(1, 0L, 20.0, 40.0, leg = 1)
		val untimed = trip(listOf(a, b), hasTimestamps = false)
		val middle = FlightReplayEngine(untimed).snapshotAt(0.5f)
		assertTrue(middle.dataGap)
		assertTrue(middle.interpolated)
		val expected = FlightGapTrajectory.buildAll(untimed.samples).getValue(0).sampleAt(0.5)
		assertEquals(expected.latitude, middle.sample.latitude, 0.00001)
		assertEquals(expected.longitude, middle.sample.longitude, 0.00001)
		assertSame(a, FlightReplayEngine(untimed).snapshotAt(0f).sample)
		assertSame(b, FlightReplayEngine(untimed).snapshotAt(1f).sample)
	}

	@Test
	fun photoAssociationHelpersContinueSnappingAcrossLongRecordingGaps() {
		val trip = trip(listOf(
			sample(0, 1_000L, 48.0, 2.0),
			sample(1, 601_000L, 49.0, 3.0)
		))
		assertEquals(0.0, FlightSampleInterpolator.positionAtTimestamp(trip, 301_000L, 0L)!!, 0.0)
		assertEquals(0.0, FlightSampleInterpolator.positionAtProgress(trip, 0.5f)!!, 0.0)
		val photoSample = FlightSampleInterpolator.sampleAt(trip, 0.25)!!
		assertTrue(photoSample == trip.samples[0] || photoSample == trip.samples[1])
	}

	private fun longGapTrip(): FlightTrip = trip(listOf(
		sample(0, 1_000L, 48.0, 2.0, altitude = 0.0),
		sample(1, 2_000L, 48.1, 2.1, altitude = 1_000.0),
		sample(2, 602_000L, 49.1, 3.1, altitude = 9_000.0),
		sample(3, 603_000L, 49.2, 3.2, altitude = 10_000.0)
	))

	private fun sample(
		index: Int,
		time: Long,
		latitude: Double,
		longitude: Double,
		leg: Int = 0,
		altitude: Double = 1_000.0
	) = FlightSample(
		index = index,
		legIndex = leg,
		timestampMillis = time,
		latitude = latitude,
		longitude = longitude,
		altitudeMeters = altitude,
		speedMetersPerSecond = 200f,
		bearingDegrees = 90f,
		horizontalAccuracyMeters = 5f,
		hdop = 1f,
		satellitesUsed = 8,
		satellitesFound = 10,
		soundDb = 45f,
		soundSpectrum = listOf(1f, 2f),
		vibrationHz = 55f
	)

	private fun trip(samples: List<FlightSample>, hasTimestamps: Boolean = true) = FlightTrip(
		name = "synthetic replay gap",
		samples = samples,
		legs = emptyList(),
		hasUsableTimestamps = hasTimestamps,
		totalDistanceMeters = 0.0,
		sourceDescription = "synthetic fixture"
	)

	private fun progressAt(trip: FlightTrip, timestamp: Long): Float =
		((timestamp - trip.samples.first().timestampMillis).toDouble() /
			(trip.samples.last().timestampMillis - trip.samples.first().timestampMillis)).toFloat()
}
