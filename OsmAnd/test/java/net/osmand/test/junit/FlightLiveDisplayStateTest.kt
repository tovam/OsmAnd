package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic monotonic clocks only; no Android location provider or application data. */
class FlightLiveDisplayStateTest {
    @Test fun invalidFutureReceiptCannotPoisonLaterRealFixes() {
        val tracker = FlightLiveDisplayFixTracker()
        val live = FlightLiveState(journeyId = "flight", latest = sample(1000L), lastFixElapsed = 500_000L)
        assertEquals(FlightFixHealth.STALE, tracker.health(live, 100_000L))
        assertEquals(FlightFixHealth.FRESH, tracker.health(live.copy(lastFixElapsed = 100_001L), 100_002L))
    }
    private fun sample(timestamp: Long) =
        FlightSample(0, 0, timestamp, 45.0, 10.0, 1000.0, 20f, 90f, 5f)

    @Test
    fun missingFixStartsWaitingAndInvalidReceiptIsNotFresh() {
        val tracker = FlightLiveDisplayFixTracker()
        assertEquals(
            FlightFixHealth.WAITING,
            tracker.health(FlightLiveState(journeyId = "flight"), 100_000L),
        )
        assertEquals(
            FlightFixHealth.STALE,
            tracker.health(
                FlightLiveState(journeyId = "flight", latest = sample(1_000L)),
                100_000L,
            ),
        )
    }

    @Test
    fun olderStatePublicationCannotMakeNewestFixLookStale() {
        val tracker = FlightLiveDisplayFixTracker()
        assertEquals(
            FlightFixHealth.FRESH,
            tracker.health(
                FlightLiveState(journeyId = "flight", latest = sample(1_000L), lastFixElapsed = 100_000L),
                100_100L,
            ),
        )
        assertEquals(
            FlightFixHealth.FRESH,
            tracker.health(
                FlightLiveState(journeyId = "flight", latest = sample(2_000L), lastFixElapsed = 101_000L),
                115_500L,
            ),
        )
        assertEquals(
            FlightFixHealth.FRESH,
            tracker.health(
                FlightLiveState(journeyId = "flight", latest = sample(1_000L), lastFixElapsed = 100_000L),
                115_500L,
            ),
        )
    }

    @Test
    fun genuineSilenceStillBecomesStaleAndNewFixRecovers() {
        val tracker = FlightLiveDisplayFixTracker()
        val live = FlightLiveState(
            journeyId = "flight",
            latest = sample(1_000L),
            lastFixElapsed = 100_000L,
        )
        assertEquals(FlightFixHealth.FRESH, tracker.health(live, 115_000L))
        assertEquals(FlightFixHealth.STALE, tracker.health(live, 115_001L))
        assertEquals(
            FlightFixHealth.FRESH,
            tracker.health(live.copy(latest = sample(3_000L), lastFixElapsed = 115_100L), 115_101L),
        )
    }

    @Test
    fun aNewJourneyDoesNotInheritPreviousFix() {
        val tracker = FlightLiveDisplayFixTracker()
        assertEquals(
            FlightFixHealth.FRESH,
            tracker.health(
                FlightLiveState(journeyId = "old", latest = sample(1_000L), lastFixElapsed = 100_000L),
                100_100L,
            ),
        )
        assertEquals(
            FlightFixHealth.WAITING,
            tracker.health(FlightLiveState(journeyId = "new"), 100_100L),
        )
    }
}
