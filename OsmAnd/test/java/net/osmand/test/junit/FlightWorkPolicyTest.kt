package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightWorkPolicyTest {
    @Test
    fun emptyStoppedTrialStartsANewJournalInsteadOfImmediatelyStoppingAgain() {
        for (phase in FlightTrackingPhase.entries) {
            assertTrue(FlightWorkPolicy.needsNewRecordingJournal(true, false, phase))
            assertTrue(FlightWorkPolicy.needsNewRecordingJournal(false, true, phase))
            assertEquals(
                phase == FlightTrackingPhase.STOPPED || phase == FlightTrackingPhase.LANDED,
                FlightWorkPolicy.needsNewRecordingJournal(false, false, phase),
            )
        }
    }

    @Test
    fun oldSimulationControlsCannotStopOrEditTheRealFlightAfterHandoff() {
        assertTrue(FlightWorkPolicy.acceptsControl("actual", "actual"))
        assertFalse(FlightWorkPolicy.acceptsControl("simulation", "actual"))
        assertFalse(FlightWorkPolicy.acceptsControl(null, "actual"))
        assertFalse(FlightWorkPolicy.acceptsControl("actual", null))
    }

    @Test
    fun scheduledRealFlightReplacesPausedSimulationButSimulationCannotReplaceRealFlight() {
        assertEquals(FlightStartDisposition.START, FlightWorkPolicy.startDisposition(null, false))
        assertEquals(FlightStartDisposition.START, FlightWorkPolicy.startDisposition(null, true))
        assertEquals(
            FlightStartDisposition.REPLACE_SIMULATION,
            FlightWorkPolicy.startDisposition(true, false),
        )
        assertEquals(
            FlightStartDisposition.KEEP_CURRENT,
            FlightWorkPolicy.startDisposition(false, true),
        )
        assertEquals(
            FlightStartDisposition.KEEP_CURRENT,
            FlightWorkPolicy.startDisposition(false, false),
        )
        assertEquals(
            FlightStartDisposition.KEEP_CURRENT,
            FlightWorkPolicy.startDisposition(true, true),
        )
    }

    @Test
    fun realRecordingIsIndependentOfUiAndSimulationPause() {
        for (visible in listOf(false, true)) for (paused in listOf(false, true)) assertTrue(
            FlightWorkPolicy.recordingActive(false, visible, paused)
        )
    }

    @Test
    fun simulationNeedsItsVisibleFlightAndUserConsentToPlay() {
        assertTrue(FlightWorkPolicy.recordingActive(true, true, false))
        assertFalse(FlightWorkPolicy.recordingActive(true, false, false))
        assertFalse(FlightWorkPolicy.recordingActive(true, true, true))
        assertFalse(FlightWorkPolicy.recordingActive(true, false, true))
    }

    @Test
    fun visibilityBelongsToTheSelectedJourneyAndEveryOwnerIsReleased() {
        val first = Any()
        val second = Any()
        try {
            FlightUiActivity.set(first, "synthetic-one")
            FlightUiActivity.set(second, "synthetic-one")
            FlightUiActivity.set(first, null)
            assertEquals(setOf("synthetic-one"), FlightUiActivity.journeys.value)
            FlightUiActivity.set(second, "synthetic-two")
            assertEquals(setOf("synthetic-two"), FlightUiActivity.journeys.value)
        } finally {
            FlightUiActivity.set(first, null)
            FlightUiActivity.set(second, null)
        }
        assertTrue(FlightUiActivity.journeys.value.isEmpty())
    }
}
