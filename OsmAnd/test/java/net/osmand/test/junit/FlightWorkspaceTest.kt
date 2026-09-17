package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * No device/user data: exercise the production navigation and timeline policy with synthetic fixes.
 */
class FlightWorkspaceTest {
    private val time = 1_800_000_000_000L

    @Test
    fun savedNormalPhotoZoomsKeepExactlyTheSameProjection() {
        for (zoom in listOf(0.36f, 0.4f, 1f, 1.4f, 2f, 4f)) {
            val saved = FlightWindowPlacement(zoom = zoom)
            assertEquals(saved, saved.clamped())
            assertEquals((58f / zoom).coerceIn(14f, 145f), saved.verticalFieldOfViewDegrees(), 0f)
        }
        val telephoto = FlightWindowPlacement(zoom = 58f / 3f)
        assertEquals(3f, telephoto.verticalFieldOfViewDegrees(), 0.00001f)
        val pose = FlightPhotoSpatialPose(2.5, time, 45.0, 10.0, 11000f, 80f, 180f, -15f, 3f, 1.5f)
        assertEquals(pose, pose.clampedOrNull())
    }

    @Test
    fun simulatedWaypointsNeverBecomeLandingsAndKeepOneTimeline() {
        val plan =
            FlightPlan(
                listOf(
                    FlightStop("A", 45.0, 0.0),
                    FlightStop("via", 46.0, 5.0, FlightStopType.WAYPOINT),
                    FlightStop("B", 45.0, 10.0),
                ),
                preparation =
                    FlightPreparation(departureMillis = time, arrivalMillis = time + 3_600_000),
            )
        val trip = FlightOfflinePreparation.simulation(plan)
        val engine = FlightReplayEngine(trip)
        assertEquals(time, trip.samples.first().timestampMillis)
        assertEquals(time + 3_600_000, trip.samples.last().timestampMillis)
        for (percent in 20..80) {
            val sample = engine.snapshotAt(percent / 100f).sample
            assertTrue(sample.altitudeMeters!! > 1000)
            assertEquals(0, sample.legIndex)
        }
        val nearVia =
            trip.samples.minOf {
                FlightTerrainTilePlanner.distanceKm(it.latitude, it.longitude, 46.0, 5.0)
            }
        assertTrue(nearVia < 5)
        assertEquals(
            FlightOfflinePreparation.simulationInput(plan),
            FlightOfflinePreparation.simulationInput(
                plan.copy(satelliteQuality = FlightSatelliteQuality.ULTRA_PLUS_PLUS_PLUS)
            ),
        )
    }

    @Test
    fun largeOfflineManifestIsNotRebuiltOrTraversedForUiStatistics() {
        val requests =
            List(50000) { i ->
                FlightOfflineRequest(TerrainTileId(14, i % 16384, i / 16384), i % 2 == 0, 0)
            }
        val quote = FlightOfflineQuote(requests, emptyList(), listOf(FlightOfflineBand(50, 14, 14)))
        assertSame(quote.assets, quote.assets)
        assertEquals(25000, quote.satelliteCount)
        assertEquals(25000, quote.terrainCount)
        assertEquals(3_875_000_000L, quote.estimatedBytes)
        assertTrue(quote.preview(true, listOf(1)).size <= 8192)
        assertTrue(quote.preview(false, listOf(1)).size <= 8192)
    }

    private fun fix(t: Long, altitude: Double = 12000.0) =
        FlightSample(
            0,
            0,
            t,
            45.0,
            10.0,
            altitude,
            220f,
            90f,
            4f,
            satellitesUsed = 8,
            soundDb = 72f,
        )

    @Test
    fun preparationNeverContainsLiveSensorsOrPhotos() {
        assertEquals(
            listOf(
                FlightPage.MAP,
                FlightPage.WINDOW,
                FlightPage.SATELLITE,
            ),
            FlightWorkspaceNavigation.pages(FlightSessionMode.PREPARE),
        )
        for (page in listOf(FlightPage.LIVE, FlightPage.PHOTO, FlightPage.SENSORS)) assertFalse(
            FlightWorkspaceNavigation.allows(FlightSessionMode.PREPARE, page)
        )
    }

    @Test
    fun pastNeverStartsARecorderAndAllModesUseTheSameHomeEntry() {
        assertFalse(FlightWorkspaceNavigation.allows(FlightSessionMode.REPLAY, FlightPage.LIVE))
        assertFalse(FlightWorkspaceNavigation.allows(FlightSessionMode.REPLAY, FlightPage.PREPARE))
        for (mode in FlightSessionMode.entries) {
            assertEquals(FlightPage.MAP, FlightWorkspaceNavigation.pages(mode).first())
            assertFalse(FlightPage.HOME in FlightWorkspaceNavigation.pages(mode))
            assertFalse(FlightPage.PREPARE in FlightWorkspaceNavigation.pages(mode))
            assertTrue(FlightWorkspaceNavigation.allows(mode,FlightPage.HOME))
        }
        assertEquals(FlightPage.HOME, FlightUiState().page)
    }

    @Test
    fun futureIsSeparateAndNeverInheritsSensors() {
        val a = fix(time - 30_000).copy(index = 0)
        val b = fix(time).copy(index = 1)
        val recorded = recordedFlightTrip("synthetic", listOf(a, b))
        val plan =
            FlightPlan(listOf(FlightStop("start", 45.0, 10.0), FlightStop("end", 47.0, 15.0)))
        val timeline = FlightLiveTimeline.build(plan, recorded, b)
        assertEquals(listOf(a, b), recorded.samples)
        assertEquals(a, timeline.samples[0])
        assertEquals(b, timeline.samples[1])
        assertEquals(47.0, timeline.samples.last().latitude, 1e-6)
        assertEquals(15.0, timeline.samples.last().longitude, 1e-6)
        assertEquals(0.0, timeline.samples.last().altitudeMeters!!, 1e-6)
        timeline.samples.drop(2).forEach { p ->
            assertNull(p.horizontalAccuracyMeters)
            assertNull(p.satellitesUsed)
            assertNull(p.soundDb)
        }
        assertTrue(FlightLiveTimeline.progress(timeline, time) < 1f)
        assertTrue(
            timeline.samples.zipWithNext().all { (x, y) -> y.timestampMillis > x.timestampMillis }
        )
    }

    @Test
    fun missingDestinationKeepsOnlyTheMeasuredPast() {
        val b = fix(time)
        assertEquals(listOf(b), FlightLiveTimeline.build(FlightPlan(emptyList()), null, b).samples)
    }

    @Test
    fun livePhotoAssociationNeverUsesTheFutureTimelineFraction() {
        val a = fix(time - 60_000).copy(index = 0)
        val b = fix(time).copy(index = 1)
        val recorded = recordedFlightTrip("synthetic", listOf(a, b))
        val timeline =
            FlightLiveTimeline.build(FlightPlan(listOf(FlightStop("end", 47.0, 15.0))), recorded, b)
        val middle = time - 30_000
        val progress = FlightLiveTimeline.progress(timeline, middle)
        val state =
            FlightUiState(
                sessionMode = FlightSessionMode.LIVE,
                trip = recorded,
                liveTimeline = timeline,
                replayProgress = progress,
                snapshot = FlightSnapshot(fix(middle), progress),
            )
        assertEquals(0.5, state.recordedPhotoPositionAtCursor()!!, 0.001)
        assertEquals(progress, state.progressForRecordedPhoto(0.5)!!, 0.00001f)
        assertNull(
            state
                .copy(snapshot = FlightSnapshot(fix(time + 30_000), 1f))
                .recordedPhotoPositionAtCursor()
        )
        assertEquals(
            0.5,
            state
                .copy(sessionMode = FlightSessionMode.REPLAY, replayProgress = 0.5f)
                .recordedPhotoPositionAtCursor()!!,
            0.001,
        )
    }

    @Test
    fun captureKeepsIndependentGpsAndSensorTimestamps() {
        val capture =
            FlightPhotoCapture(
                time,
                5_000_000_000L,
                fix(time - 1700),
                listOf(1f, 2f, 3f),
                4_990_000_000L,
                3,
                listOf(0f, 0f, 0f, 1f),
                4_999_000_000L,
            )
        val json = capture.toJson { JSONObject().put("time", it.timestampMillis) }
        val restored = photoCaptureFromJson(JSONObject(json.toString())) { fix(it.getLong("time")) }
        assertEquals(capture, restored)
        assertNull(photoCaptureFromJson(null) { error("No fix") })
        assertEquals(1700L, restored!!.shutterMillis - restored.fix!!.timestampMillis)
    }

    @Test
    fun futureInterpolationCannotBorrowTheLastRecordedSensorReading() {
        val current = FlightSnapshot(fix(time), 0.4f)
        assertSame(current, FlightLiveTimeline.withoutFutureMeasurements(current, time))
        val forecast =
            FlightLiveTimeline.withoutFutureMeasurements(current.copy(sample = fix(time + 1)), time)
        assertNull(forecast.sample.horizontalAccuracyMeters)
        assertNull(forecast.sample.satellitesUsed)
        assertNull(forecast.sample.soundDb)
        assertEquals(12000.0, forecast.sample.altitudeMeters!!, 0.001)
    }
}
