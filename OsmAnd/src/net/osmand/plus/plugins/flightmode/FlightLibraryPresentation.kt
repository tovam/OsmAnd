package net.osmand.plus.plugins.flightmode

/** Device-owned alarm state, never inferred from a downloaded plan's automatic flag. */
data class FlightLocalSchedule(val startMillis: Long, val offsetMinutes: Int)

internal fun FlightJourney.toLibrarySummary() =
    FlightJourneySummary(
        id,
        name,
        updatedAtMillis,
        trip.samples.size,
        photos.size,
        simulation,
        plan.preparation?.departureMillis?.takeIf { it > 0 },
        offlineAssets.terrainTileCount,
        offlineAssets.standardSatelliteTileCount,
    )

internal enum class FlightLibraryGpsState {
    RECORDING,
    WAITING,
    STALE,
    SIMULATING,
    SIMULATION_PAUSED,
    SCHEDULED,
    SCHEDULE_BLOCKED,
    SCHEDULE_OVERDUE,
    UNCHECKED,
    ERROR,
    OFF,
}

/** All inputs are already in memory. A list row never opens a journal or queries a server. */
internal fun flightLibraryGpsState(
    localId: String?,
    live: FlightLiveState,
    schedules: Map<String, FlightLocalSchedule>,
    schedulesLoaded: Boolean,
    permissionsReady: Boolean,
    elapsedMillis: Long,
    wallMillis: Long,
): FlightLibraryGpsState {
    if (localId == null) return FlightLibraryGpsState.OFF
    if (localId == live.journeyId) {
        if (live.running && live.simulation)
            return if (live.simulationPaused || live.simulationBackgroundPaused)
                FlightLibraryGpsState.SIMULATION_PAUSED
            else FlightLibraryGpsState.SIMULATING
        if (live.error != null) return FlightLibraryGpsState.ERROR
        if (live.running)
            return when (
                FlightLiveSafety.fixHealth(live.latest, live.lastFixElapsed, elapsedMillis)
            ) {
                FlightFixHealth.WAITING -> FlightLibraryGpsState.WAITING
                FlightFixHealth.STALE -> FlightLibraryGpsState.STALE
                FlightFixHealth.FRESH -> FlightLibraryGpsState.RECORDING
            }
    }
    val schedule = schedules[localId]
    return when {
        !schedulesLoaded -> FlightLibraryGpsState.UNCHECKED
        schedule == null -> FlightLibraryGpsState.OFF
        !permissionsReady -> FlightLibraryGpsState.SCHEDULE_BLOCKED
        schedule.startMillis < wallMillis - 60_000L -> FlightLibraryGpsState.SCHEDULE_OVERDUE
        else -> FlightLibraryGpsState.SCHEDULED
    }
}

internal fun FlightLibraryRow.isPlan(): Boolean =
    local?.let { !it.simulation && it.sampleCount == 0 } ?: (remote!!.samples == 0)

/** Offline bands do not change the alarm or the flight detection settings. */
internal fun flightScheduleSettingsDiffer(
    armed: FlightPreparation,
    current: FlightPreparation?,
): Boolean =
    armed.copy(automatic = false, bands = emptyList()) !=
        (current ?: FlightPreparation()).copy(automatic = false, bands = emptyList())
