package net.osmand.plus.plugins.flightmode

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only one preparation's sockets; pausing must not disconnect a visible scene's downloads. */
internal class FlightDownloadCancellation {
    private val cancelled = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun attach(connection: HttpURLConnection) {
        connections.add(connection)
        if (cancelled.get()) {
            connections.remove(connection)
            connection.disconnect()
            throw InterruptedIOException("Preparation paused")
        }
    }

    fun detach(connection: HttpURLConnection) {
        connections.remove(connection)
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        connections.forEach { runCatching { it.disconnect() } }
        connections.clear()
    }
}
