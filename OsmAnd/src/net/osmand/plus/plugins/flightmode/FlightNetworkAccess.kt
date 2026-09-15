package net.osmand.plus.plugins.flightmode

import java.io.IOException

/**
 * Session-scoped offline rehearsal gate shared by scene, minimap, calibration and cloud clients.
 */
object FlightNetworkAccess {
    private val lock = Any()
    private val owners = mutableSetOf<Any>()
    private val requests = mutableMapOf<Any, () -> Unit>()

    @JvmStatic fun isOffline(): Boolean = synchronized(lock) { owners.isNotEmpty() }

    fun requireOnline() {
        if (isOffline()) throw IOException("offline_simulation")
    }

    /** Close callbacks are returned so socket shutdown never stalls the UI thread. */
    fun block(owner: Any): List<() -> Unit> =
        synchronized(lock) {
            owners += owner
            requests.values.toList().also { requests.clear() }
        }

    fun release(owner: Any) {
        synchronized(lock) { owners -= owner }
    }

    fun register(request: Any, cancel: () -> Unit) =
        synchronized(lock) {
            requireOnline()
            requests[request] = cancel
        }

    fun unregister(request: Any) {
        synchronized(lock) { requests.remove(request) }
    }
}
