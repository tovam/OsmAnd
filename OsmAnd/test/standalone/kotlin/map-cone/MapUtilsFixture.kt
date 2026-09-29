package net.osmand.util

import net.osmand.data.LatLon
import kotlin.math.cos
import kotlin.math.sin

// Deterministic coordinates for testing provider ownership/update rules, not geodesic accuracy.
object MapUtils {
    fun get31TileNumberX(longitude: Double) = ((longitude + 180) * 1_000_000).toInt()
    fun get31TileNumberY(latitude: Double) = ((latitude + 90) * 1_000_000).toInt()
    fun greatCircleDestinationPoint(lat: Double, lon: Double, distance: Double, bearing: Double): LatLon {
        val angle = Math.toRadians(bearing)
        return LatLon(lat + distance / 100_000 * cos(angle), lon + distance / 100_000 * sin(angle))
    }
}
