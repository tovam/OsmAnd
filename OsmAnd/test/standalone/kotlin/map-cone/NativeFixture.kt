package net.osmand.core.jni

data class PointI(val x: Int, val y: Int)
class QVectorPointI : ArrayList<PointI>()
enum class ZoomLevel { ZoomLevel0, ZoomLevel31 }
class PolygonsCollection(val minZoom: ZoomLevel, val maxZoom: ZoomLevel) {
    val polygons = mutableListOf<Polygon>()
}
class Polygon(var points: QVectorPointI, val fillColor: Int) {
    var updates = 0
    @JvmName("replacePoints")
    fun setPoints(value: QVectorPointI) { points = value; updates++ }
}
class PolygonBuilder {
    private var points = QVectorPointI()
    private var color = 0
    fun setPolygonId(value: Int) = this
    fun setBaseOrder(value: Int) = this
    fun setIsHidden(value: Boolean) = this
    fun setPoints(value: QVectorPointI) = apply { points = value }
    fun setFillColor(value: Int) = apply { color = value }
    fun buildAndAddToCollection(provider: PolygonsCollection) = Polygon(points, color).also { provider.polygons += it }
}
