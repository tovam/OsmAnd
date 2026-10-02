package net.osmand.plus.plugins.flightmode

internal data class FlightReadiness(
    val itinerary: Boolean, val permissions: Boolean, val offlineVerified: Boolean,
    val storage: Boolean, val scheduled: Boolean,
) {
    val ready: Boolean get() = itinerary && permissions && offlineVerified && storage

    companion object {
        fun evaluate(state: FlightUiState, permissionsReady: Boolean, freeBytes: Long, nowMillis: Long): FlightReadiness {
            val prep = state.plan.preparation
            val itinerary = FlightOfflinePreparation.canSimulate(state.plan) && prep != null &&
                prep.validSchedule() && prep.scheduledFlights().last().arrivalMillis > nowMillis
            val verified = state.offlineQuote != null && state.offlinePreloadStatus.phase == FlightTerrainPhase.READY &&
                state.offlinePreloadStatus.offlineFilesVerified
            val remaining = state.offlineCoverage?.takeIf { it.inventoried }?.estimatedRemainingBytes
                ?: state.offlineQuote?.estimatedBytes
            val storage = freeBytes >= 256L * 1024 * 1024 && (verified || remaining != null &&
                remaining <= freeBytes - 256L * 1024 * 1024)
            return FlightReadiness(itinerary, permissionsReady, verified, storage,
                state.scheduledStartMillis != null && state.localSchedulesLoaded)
        }
    }
}
