package net.osmand.plus.plugins.flightmode

/** UI timers invalidate the display; they must never be the clock used to date a newer fix. */
internal fun flightDisplayElapsed(timerElapsed: Long, currentElapsed: Long): Long =
    maxOf(timerElapsed, currentElapsed)

/** Recorded samples only. Neither the predicted aircraft nor the future timeline is a GPS fix. */
internal fun FlightUiState.recordingForSelectedFlight(): FlightLiveState =
    activeRecording.takeIf { journeyId != null && it.journeyId == journeyId }
        ?: liveState.takeIf { journeyId != null && it.journeyId == journeyId }
        ?: FlightLiveState()

internal fun FlightLiveState.fixAgeSeconds(elapsed: Long): Long? =
    lastFixElapsed
        .takeIf { latest != null && it > 0 && elapsed >= it }
        ?.let { (elapsed - it) / 1000 }

internal fun FlightLiveState.recentRecordedPoints(limit: Int = 12): List<FlightSample> =
    trip?.samples?.takeLast(limit.coerceAtLeast(0))?.asReversed() ?: emptyList()
