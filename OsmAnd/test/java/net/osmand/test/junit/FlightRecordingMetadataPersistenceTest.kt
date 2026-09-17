package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightLiveState
import net.osmand.plus.plugins.flightmode.canSaveActiveRecordingMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic state only: selecting the metadata path must never depend on recorder log contents. */
class FlightRecordingMetadataPersistenceTest {

    @Test
    fun metadataOnlySaveIsLimitedToTheMatchingActiveRealRecorder() {
        val active = FlightLiveState(journeyId = "active-flight", running = true)

        assertTrue(canSaveActiveRecordingMetadata(active, "active-flight"))
        assertFalse(canSaveActiveRecordingMetadata(active, "other-flight"))
        assertFalse(canSaveActiveRecordingMetadata(active.copy(running = false), "active-flight"))
        assertFalse(canSaveActiveRecordingMetadata(active.copy(simulation = true), "active-flight"))
    }
}
