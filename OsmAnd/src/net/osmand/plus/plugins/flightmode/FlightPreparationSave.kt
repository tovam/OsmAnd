package net.osmand.plus.plugins.flightmode

import kotlinx.coroutines.CancellationException

internal data class FlightPreparationSaveResult<T>(
    val saved: T,
    val armed: Boolean,
    val scheduleError: Exception? = null,
)

/** Saving a draft must succeed independently of dates, GPS permission and exact-alarm access. */
internal suspend fun <T> saveFlightPreparation(
    arm: Boolean,
    saveLocal: suspend () -> T,
    schedule: suspend (T) -> Unit,
): FlightPreparationSaveResult<T> {
    val saved = saveLocal()
    if (!arm) return FlightPreparationSaveResult(saved, false)
    return try {
        schedule(saved)
        FlightPreparationSaveResult(saved, true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FlightPreparationSaveResult(saved, false, e)
    }
}
