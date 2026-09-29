package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightVisibilityLease
import org.junit.Assert.*
import org.junit.Test

class FlightVisibilityLeaseTest {
    @Test fun rendererReplacementReleasesTheCapturedRenderer() {
        val calls = mutableListOf<String>()
        val lease = FlightVisibilityLease()
        lease.update(true) { calls += "old:$it" }
        lease.update(true) { calls += "new:$it" }
        lease.dispose()
        lease.dispose()
        assertEquals(listOf("old:true", "old:false"), calls)
    }

    @Test fun pauseReleasesOldOwnerThenResumeAcquiresNewOwner() {
        val calls = mutableListOf<String>()
        val lease = FlightVisibilityLease()
        lease.update(true) { calls += "old:$it" }
        lease.update(false) { calls += "new:$it" }
        lease.update(true) { calls += "new:$it" }
        lease.dispose()
        assertEquals(listOf("old:true", "old:false", "new:true", "new:false"), calls)
    }

    @Test fun initiallyHiddenDoesNotAcquireAnything() {
        val calls = mutableListOf<Boolean>()
        val lease = FlightVisibilityLease()
        lease.update(false) { calls += it }
        lease.dispose()
        assertEquals(listOf(false), calls)
    }
}
