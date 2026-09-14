package net.osmand.test.junit

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import net.osmand.plus.plugins.flightmode.FlightDownloadCancellation
import org.junit.Assert.*
import org.junit.Test

class FlightDownloadCancellationTest {
    // No network request: this fixture only records explicit disconnect calls.
    private class Connection : HttpURLConnection(URL("https://example.invalid/tile")) {
        var disconnects = 0

        override fun disconnect() {
            disconnects++
        }

        override fun connect() = error("Network is forbidden in this test")

        override fun usingProxy() = false
    }

    @Test
    fun pauseDisconnectsOnlyItsOwnActiveRequestsAndIsIdempotent() {
        val preparation = FlightDownloadCancellation()
        val scene = FlightDownloadCancellation()
        val active = Connection()
        val finished = Connection()
        val visible = Connection()
        preparation.attach(active)
        preparation.attach(finished)
        preparation.detach(finished)
        scene.attach(visible)
        preparation.cancel()
        preparation.cancel()
        assertEquals(1, active.disconnects)
        assertEquals(0, finished.disconnects)
        assertEquals(0, visible.disconnects)
    }

    @Test
    fun lateRequestsCannotEscapeAPauseButANewSessionCanResume() {
        val paused = FlightDownloadCancellation()
        paused.cancel()
        val late = Connection()
        try {
            paused.attach(late)
            fail("A paused request escaped cancellation")
        } catch (expected: InterruptedIOException) {}
        assertEquals(1, late.disconnects)
        val resumed = FlightDownloadCancellation()
        val fresh = Connection()
        resumed.attach(fresh)
        assertEquals(0, fresh.disconnects)
        resumed.detach(fresh)
    }
}
