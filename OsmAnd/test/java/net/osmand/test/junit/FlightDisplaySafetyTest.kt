package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic positions only: no phone, network, cached imagery or personal flight journal. */
class FlightDisplaySafetyTest {
    private fun mesh(
        tile: TerrainTileId,
        heights: List<Float>,
        available: Boolean = true,
    ): FlightTerrainMesh {
        val vertices = FloatArray(4 * 9)
        heights.forEachIndexed { i, height -> vertices[i * 9 + 6] = height }
        return FlightTerrainMesh(
            tile,
            vertices,
            shortArrayOf(0, 2, 1, 1, 2, 3),
            terrainAvailable = available,
            gridQuads = 1,
        )
    }

    @Test
    fun groundFollowsTheCurrentEyeAcrossBothMeshTriangles() {
        val tile = TerrainTileId(10, 550, 360)
        val mesh = mesh(tile, listOf(0f, 1000f, 2000f, 4000f))
        fun elevation(x: Double, y: Double) =
            FlightTerrainFloor.elevationAt(
                listOf(mesh),
                FlightTerrainTilePlanner.tileYToLatitude(tile.y + y, tile.zoom),
                FlightTerrainTilePlanner.tileXToLongitude(tile.x + x, tile.zoom),
            )!!
        assertEquals(300f, elevation(0.1, 0.1), 0.1f)
        assertEquals(2800f, elevation(0.7, 0.8), 0.1f)
    }

    @Test
    fun groundUsesDetailedTileAndNeverUsesMissingOrUnrelatedTerrain() {
        val parent = TerrainTileId(9, 275, 180)
        val child = TerrainTileId(10, 550, 360)
        val lat = FlightTerrainTilePlanner.tileYToLatitude(360.5, 10)
        val lon = FlightTerrainTilePlanner.tileXToLongitude(550.5, 10)
        val coarse = mesh(parent, List(4) { 100f })
        val fine = mesh(child, List(4) { 1300f })
        assertEquals(1300f, FlightTerrainFloor.elevationAt(listOf(fine, coarse), lat, lon)!!, 0.01f)
        assertEquals(
            100f,
            FlightTerrainFloor.elevationAt(
                listOf(coarse, fine.copy(terrainAvailable = false)),
                lat,
                lon,
            )!!,
            0.01f,
        )
        assertNull(FlightTerrainFloor.elevationAt(listOf(fine), 0.0, 0.0))
        assertNull(
            FlightTerrainFloor.elevationAt(listOf(fine.copy(vertices = floatArrayOf())), lat, lon)
        )
    }

    private fun sample(time: Long = 100_000, speed: Float = 230f, accuracy: Float = 5f) =
        FlightSample(0, 0, time, 45.0, 10.0, 10_000.0, speed, 90f, accuracy)

    @Test
    fun retryRecreatesRendererAndPreservesErrorUntilExplicitRetry() {
        val initial = FlightRendererRecovery()
        val failed = initial.failed("shader failed")
        assertEquals("shader failed", failed.copy().error)
        assertEquals(0, failed.revision)
        val retried = failed.retry()
        assertEquals(1, retried.revision)
        assertNull(retried.error)
        assertEquals(2, retried.failed("context lost").retry().revision)
    }

    @Test
    fun gpsHealthDistinguishesMissingRestoredFreshAndExpiredFixes() {
        assertEquals(FlightFixHealth.WAITING, FlightLiveSafety.fixHealth(null, 0, 100_000))
        assertEquals(FlightFixHealth.STALE, FlightLiveSafety.fixHealth(sample(), 0, 100_000))
        assertEquals(FlightFixHealth.FRESH, FlightLiveSafety.fixHealth(sample(), 1000, 16_000))
        assertEquals(FlightFixHealth.STALE, FlightLiveSafety.fixHealth(sample(), 1000, 16_001))
        assertEquals(FlightFixHealth.STALE, FlightLiveSafety.fixHealth(sample(), 1000, 999))
    }

    @Test
    fun restoredFixWithoutMonotonicTimeIsNotExtrapolated() {
        val predictor = FlightLivePredictor()
        val fix = sample()
        predictor.accept(fix, 500_000, fixReceivedAt = null)
        val later = predictor.position(503_000)!!
        assertEquals(fix.latitude, later.latitude, 1e-9)
        assertEquals(fix.longitude, later.longitude, 1e-9)
    }

    @Test
    fun reopeningAnOldFixDoesNotStartAnotherPredictionWindow() {
        val predictor = FlightLivePredictor()
        predictor.accept(sample(), 100_000, fixReceivedAt = 10_000)
        val reopening = predictor.position(100_000)!!
        val later = predictor.position(103_000)!!
        assertEquals(reopening.latitude, later.latitude, 1e-9)
        assertEquals(reopening.longitude, later.longitude, 1e-9)
    }

    @Test
    fun currentFixStillPredictsSmoothlyWithoutChangingRecordedFix() {
        val predictor = FlightLivePredictor()
        val fix = sample()
        predictor.accept(fix, 1000)
        assertTrue(predictor.position(2000)!!.longitude > fix.longitude)
        assertEquals(10.0, fix.longitude, 0.0)
        val capped = predictor.position(6000)!!
        assertEquals(capped.longitude, predictor.position(600_000)!!.longitude, 0.0)
    }

    @Test
    fun finalMeasuredFixIsFlushedOnlyOnce() {
        val first = sample()
        val last = sample(101_000)
        assertSame(last, FlightLiveSafety.finalFix(first, last))
        assertSame(first, FlightLiveSafety.finalFix(null, first))
        assertNull(FlightLiveSafety.finalFix(last, last))
        assertNull(FlightLiveSafety.finalFix(last, first))
        assertNull(FlightLiveSafety.finalFix(first, null))
    }

    @Test
    fun cabinOcclusionCanAlwaysBeRecoveredByRecentering() {
        for (side in FlightCabinSide.values()) for (aspect in listOf(0.5f, 1f, 2f)) {
            val placement = FlightWindowPlacement(side = side)
            assertTrue(FlightWindowAperture.project(placement, FlightWindowLook(), aspect).visible)
            assertFalse(
                FlightWindowAperture.project(placement, FlightWindowLook(yawDegrees = 180f), aspect)
                    .visible
            )
        }
        assertTrue(
            FlightWindowAperture.project(FlightWindowPlacement(), FlightWindowLook(), 0f).visible
        )
    }

    @Test
    fun gpsAcquiredMidFlightNeedsExplicitConfirmationThenLandingWorks() {
        val config = FlightPreparation(stopMinutes = 5)
        val firstFix = sample()
        val waiting = FlightTrackingState().accept(firstFix, firstFix.timestampMillis, config)
        assertEquals(FlightTrackingPhase.WAITING, waiting.phase)
        var confirmed = waiting.confirmAirborne(firstFix, firstFix.timestampMillis, config)
        assertEquals(FlightTrackingPhase.AIRBORNE, confirmed.phase)
        for (second in 1..301) {
            val fix = sample(firstFix.timestampMillis + second * 1000, speed = 5f)
            confirmed = confirmed.accept(fix, fix.timestampMillis, config)
        }
        assertEquals(FlightTrackingPhase.LANDED, confirmed.phase)
    }

    @Test
    fun confirmationCannotUseMissingStaleSlowOrInaccurateFixes() {
        val waiting = FlightTrackingState()
        val config = FlightPreparation()
        assertEquals(waiting, waiting.confirmAirborne(sample(), 120_000, config))
        assertEquals(waiting, waiting.confirmAirborne(sample(speed = 0f), 100_000, config))
        assertEquals(waiting, waiting.confirmAirborne(sample(accuracy = 500f), 100_000, config))
        assertEquals(waiting, waiting.confirmAirborne(sample(accuracy = -1f), 100_000, config))
        assertEquals(
            waiting,
            waiting.confirmAirborne(sample().copy(horizontalAccuracyMeters = null), 100_000, config),
        )
        val stopped = waiting.copy(phase = FlightTrackingPhase.STOPPED)
        assertEquals(stopped, stopped.confirmAirborne(sample(), 100_000, config))
    }
}
