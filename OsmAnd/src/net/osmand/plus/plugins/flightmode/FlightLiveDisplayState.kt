package net.osmand.plus.plugins.flightmode

/**
 * Display-only GPS freshness latch. It never creates or moves a fix: it only keeps the newest
 * monotonic receipt timestamp seen by this composition. This prevents an older state publication
 * from making the notice oscillate while the real no-fix timeout remains authoritative.
 */
internal class FlightLiveDisplayFixTracker(
    private val freshnessMillis: Long = FlightLiveSafety.FRESH_FIX_MILLIS,
) {
    private var journeyId: String? = null
    private var newestReceiptElapsed = 0L
    private var newestSampleTimestamp = Long.MIN_VALUE

    fun health(live: FlightLiveState, nowElapsed: Long): FlightFixHealth {
        if (journeyId != live.journeyId) {
            journeyId = live.journeyId
            newestReceiptElapsed = 0L
            newestSampleTimestamp = Long.MIN_VALUE
        }
        val sample = live.latest
        val received = live.lastFixElapsed
        if (
            sample != null &&
                received > 0L &&
                received <= nowElapsed &&
                (received > newestReceiptElapsed ||
                    (received == newestReceiptElapsed &&
                        sample.timestampMillis > newestSampleTimestamp))
        ) {
            newestReceiptElapsed = received
            newestSampleTimestamp = sample.timestampMillis
        }
        if (newestReceiptElapsed <= 0L) {
            // A first sample without a monotonic receipt time is not evidence of a live GPS fix.
            return if (sample == null) FlightFixHealth.WAITING else FlightFixHealth.STALE
        }
        return if (
            nowElapsed >= newestReceiptElapsed &&
                nowElapsed - newestReceiptElapsed <= freshnessMillis
        ) {
            FlightFixHealth.FRESH
        } else {
            FlightFixHealth.STALE
        }
    }
}
