package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure library-row fixtures: no device alarms, journals, network, or user data. */
class FlightLibraryPresentationTest {
    private val now = 100_000L
    private val id = "synthetic"

    @Test
    fun summaryAndPlanClassificationPreserveSimulationMetadata() {
        val simulated = journey(
            simulation = true,
            plan = FlightPlan(emptyList(), preparation = FlightPreparation(departureMillis = 1234L)),
        ).copy(
            offlineAssets = FlightOfflineAssets(
                terrainTiles = listOf(TerrainTileId(12, 1, 2)),
                standardSatelliteTiles = listOf(TerrainTileId(12, 3, 4), TerrainTileId(12, 5, 6)),
            ),
        )
        val emptyRecorded = journey(simulation = false)
        val simulatedSummary = simulated.toLibrarySummary()
        val recordedSummary = emptyRecorded.toLibrarySummary()

        assertTrue(simulatedSummary.simulation)
        assertEquals(0, simulatedSummary.sampleCount)
        assertEquals(1234L, simulatedSummary.departureMillis)
        assertEquals(1, simulatedSummary.terrainTileCount)
        assertEquals(2, simulatedSummary.satelliteTileCount)
        assertFalse(FlightLibraryRow(simulatedSummary, null, null).isPlan())
        assertTrue(FlightLibraryRow(recordedSummary, null, null).isPlan())
    }

    @Test
    fun realRecordingDistinguishesFreshWaitingStaleAndError() {
        val fresh = FlightLiveState(
            journeyId = id,
            latest = sample(),
            lastFixElapsed = now - 1_000L,
            running = true,
        )
        val waiting = fresh.copy(latest = null)
        val stale = fresh.copy(lastFixElapsed = now - FlightLiveSafety.FRESH_FIX_MILLIS - 1L)
        val error = fresh.copy(error = "synthetic failure")

        assertEquals(FlightLibraryGpsState.RECORDING, gps(live = fresh))
        assertEquals(FlightLibraryGpsState.WAITING, gps(live = waiting))
        assertEquals(FlightLibraryGpsState.STALE, gps(live = stale))
        assertEquals(FlightLibraryGpsState.ERROR, gps(live = error))
    }

    @Test
    fun simulationOverridesRealGpsAndExposesPausedState() {
        val running = FlightLiveState(journeyId = id, running = true, simulation = true)
        val paused = running.copy(simulationPaused = true)
        val backgroundPaused = running.copy(simulationBackgroundPaused = true)

        assertEquals(FlightLibraryGpsState.SIMULATING, gps(live = running))
        assertEquals(FlightLibraryGpsState.SIMULATION_PAUSED, gps(live = paused))
        assertEquals(FlightLibraryGpsState.SIMULATION_PAUSED, gps(live = backgroundPaused))
    }

    @Test
    fun scheduleStateSeparatesLoadingPermissionsAndOverdue() {
        val future = FlightLocalSchedule(now + 120_000L, offsetMinutes = 0)
        val overdue = FlightLocalSchedule(now - 60_001L, offsetMinutes = 0)

        assertEquals(
            FlightLibraryGpsState.UNCHECKED,
            gps(schedules = mapOf(id to future), schedulesLoaded = false),
        )
        assertEquals(
            FlightLibraryGpsState.SCHEDULE_BLOCKED,
            gps(schedules = mapOf(id to future), permissionsReady = false),
        )
        assertEquals(
            FlightLibraryGpsState.SCHEDULED,
            gps(schedules = mapOf(id to future)),
        )
        assertEquals(
            FlightLibraryGpsState.SCHEDULE_OVERDUE,
            gps(schedules = mapOf(id to overdue)),
        )
        assertEquals(FlightLibraryGpsState.OFF, gps())
    }

    @Test
    fun onlyTheDeviceAlarmArmsAPlanAndRemoteOnlyRowsAreOff() {
        val automaticPlan = journey(plan = FlightPlan(emptyList(), preparation = FlightPreparation(automatic = true)))
        assertTrue(automaticPlan.plan.preparation!!.automatic)

        assertEquals(FlightLibraryGpsState.OFF, gps())
        assertEquals(
            FlightLibraryGpsState.SCHEDULED,
            gps(schedules = mapOf(id to FlightLocalSchedule(now + 120_000L, 0))),
        )
        assertEquals(FlightLibraryGpsState.OFF, gps(localId = null))
    }

    @Test
    fun anotherActiveJourneyDoesNotMakeThisRowRecording() {
        val otherLive = FlightLiveState(
            journeyId = "other",
            latest = sample(),
            lastFixElapsed = now - 1_000L,
            running = true,
        )

        assertEquals(FlightLibraryGpsState.OFF, gps(live = otherLive))
    }

    @Test
    fun scheduleSettingsIgnoreAutomaticAndOfflineBandsButDetectDatesAndThresholds() {
        val base = FlightPreparation(
            departureMillis = 10_000L,
            arrivalMillis = 20_000L,
            departureOffsetMinutes = 60,
            arrivalOffsetMinutes = 90,
            startMinutesBefore = 20,
            airborneGainMeters = 1_500,
            airborneSpeedKmh = 220,
            stopSpeedKmh = 45,
            stopMinutes = 35,
            bands = listOf(FlightOfflineBand(50, 12, 12)),
        )
        assertFalse(flightScheduleSettingsDiffer(base.copy(automatic = true), base))
        assertFalse(
            flightScheduleSettingsDiffer(
                base.copy(bands = listOf(FlightOfflineBand(300, 8, 8))),
                base,
            )
        )

        listOf(
            base.copy(departureMillis = base.departureMillis + 1),
            base.copy(arrivalMillis = base.arrivalMillis + 1),
            base.copy(departureOffsetMinutes = base.departureOffsetMinutes + 1),
            base.copy(arrivalOffsetMinutes = base.arrivalOffsetMinutes + 1),
            base.copy(startMinutesBefore = base.startMinutesBefore + 1),
            base.copy(airborneGainMeters = base.airborneGainMeters + 1),
            base.copy(airborneSpeedKmh = base.airborneSpeedKmh + 1),
            base.copy(stopSpeedKmh = base.stopSpeedKmh + 1),
            base.copy(stopMinutes = base.stopMinutes + 1),
        ).forEach { changed ->
            assertTrue(flightScheduleSettingsDiffer(changed, base))
        }
        assertTrue(flightScheduleSettingsDiffer(base, null))
    }

    private fun gps(
        localId: String? = id,
        live: FlightLiveState = FlightLiveState(),
        schedules: Map<String, FlightLocalSchedule> = emptyMap(),
        schedulesLoaded: Boolean = true,
        permissionsReady: Boolean = true,
    ) = flightLibraryGpsState(
        localId = localId,
        live = live,
        schedules = schedules,
        schedulesLoaded = schedulesLoaded,
        permissionsReady = permissionsReady,
        elapsedMillis = now,
        wallMillis = now,
    )

    private fun journey(
        simulation: Boolean = false,
        plan: FlightPlan = FlightPlan(emptyList()),
    ) = FlightJourney(
        id = id,
        name = "Synthetic",
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
        plan = plan,
        trip = FlightTrip("Synthetic", emptyList(), emptyList(), false, 0.0, "test"),
        flightSpans = emptyList(),
        photos = emptyList(),
        simulation = simulation,
    )

    private fun sample() = FlightSample(
        index = 0,
        legIndex = 0,
        timestampMillis = now,
        latitude = 45.0,
        longitude = 10.0,
        altitudeMeters = 12_000.0,
        speedMetersPerSecond = 220f,
        bearingDegrees = 90f,
        horizontalAccuracyMeters = 4f,
    )
}
