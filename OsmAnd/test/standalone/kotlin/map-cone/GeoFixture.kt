package net.osmand.data

data class LatLon(val latitude: Double, val longitude: Double)
class RotatedTileBox {
    var pixWidth = 600
    var pixHeight = 400
    var radiusMeters = 10_000.0
    var panPixels = 0f
    fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int) = radiusMeters
    fun getPixXFromLatLon(lat: Double, lon: Double) = lon.toFloat() + panPixels
    fun getPixYFromLatLon(lat: Double, lon: Double) = lat.toFloat() + panPixels
}
