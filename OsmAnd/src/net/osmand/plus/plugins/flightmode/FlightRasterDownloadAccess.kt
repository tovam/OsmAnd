package net.osmand.plus.plugins.flightmode

import java.net.HttpURLConnection
import net.osmand.map.MapTileDownloader

/** Keeps the native map on the same offline policy without changing the user's map settings. */
object FlightRasterDownloadAccess : MapTileDownloader.DownloadAccess {
    override fun isAllowed() = !FlightNetworkAccess.isOffline()

    override fun opened(connection: HttpURLConnection) {
        FlightNetworkAccess.register(connection) { connection.disconnect() }
    }

    override fun closed(connection: HttpURLConnection) = FlightNetworkAccess.unregister(connection)
}
