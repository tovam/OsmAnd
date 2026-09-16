package net.osmand.test.junit

import kotlinx.coroutines.*
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** Real reconciler with a synthetic no-I/O repository, never device tiles or user trips. */
class FlightStreamingVisibilityTest {
    private fun demand(latitude: Double = 48.0) =
        FlightSceneDemand(
            FlightSample(
                index = 0,
                legIndex = 0,
                timestampMillis = 1000L,
                latitude = latitude,
                longitude = 2.0,
                altitudeMeters = 10_000.0,
                speedMetersPerSecond = null,
                bearingDegrees = null,
                horizontalAccuracyMeters = null,
            ),
            null,
            FlightSceneStreamingConfiguration(300, FlightSatelliteQuality.STANDARD, 12, 11, false),
            setOf(FlightSceneConsumer.WINDOW),
            FlightSceneMotion.MANUAL,
        )

    @Test
    fun hiddenDemandDoesNotStartWorkAndResumeUsesLatestAircraft() = runBlocking {
        val repository = FlightTerrainRepository()
        var scene: FlightTerrainScene? = null
        val engine = FlightSceneStreamingEngine(this, repository, { scene }, { scene = it }, {})
        try {
            engine.setForeground(false)
            engine.submit(demand())
            engine.submit(demand(49.0))
            yield()
            assertEquals(0, repository.loads)
            assertFalse(repository.enabled)
            engine.setForeground(true)
            yield()
            assertEquals(1, repository.loads)
            assertEquals(49.0, scene!!.centerLatitude, 0.0)
        } finally {
            engine.close()
        }
    }

    @Test
    fun completedSceneSurvivesPauseWithoutReloading() = runBlocking {
        val repository = FlightTerrainRepository()
        var scene: FlightTerrainScene? = null
        val engine = FlightSceneStreamingEngine(this, repository, { scene }, { scene = it }, {})
        try {
            engine.submit(demand())
            yield()
            val before = scene
            engine.setForeground(false)
            assertSame(before, scene)
            engine.setForeground(true)
            yield()
            assertEquals(1, repository.loads)
            assertSame(before, scene)
        } finally {
            engine.close()
        }
    }

    @Test
    fun activeLoadAndCorridorAreCancelledThenResumedOnlyOnVisibility() = runBlocking {
        val repository = FlightTerrainRepository()
        val first = CompletableDeferred<Unit>()
        var loadCancelled = false
        repository.loading = {
            first.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                loadCancelled = true
            }
        }
        val engine = FlightSceneStreamingEngine(this, repository, { null }, {}, {})
        var backgroundRuns = 0
        try {
            engine.submit(demand())
            first.await()
            engine.scheduleBackgroundWork(0) { backgroundRuns++ }
            engine.setForeground(false)
            yield()
            assertTrue(loadCancelled)
            assertFalse(engine.isBusy)
            assertEquals(0, backgroundRuns)
            repository.loading = {}
            engine.setForeground(true)
            yield()
            yield()
            assertEquals(2, repository.loads)
            assertEquals(1, backgroundRuns)
        } finally {
            engine.close()
        }
    }
}
