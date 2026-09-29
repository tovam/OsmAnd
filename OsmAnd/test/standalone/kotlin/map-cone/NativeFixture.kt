package net.osmand.core.jni

data class PointI(val x: Int, val y: Int)
class QVectorPointI : ArrayList<PointI>()
enum class ZoomLevel { ZoomLevel0, ZoomLevel31 }
class FColorARGB(val argb: Long)
class SWIGTYPE_p_std__shared_ptrT_Polygon_t
class PolygonsCollection(val minZoom: ZoomLevel, val maxZoom: ZoomLevel) {
    val polygons = mutableListOf<PolygonFixture>()
    fun setPolygonPoints(polygonId: Int, points: QVectorPointI): Boolean {
        val polygon = polygons.firstOrNull { it.id == polygonId } ?: return false
        polygon.points = points
        polygon.updates++
        return true
    }
}
// Inspection state for tests only. The real Java API does not expose a Polygon class or setPoints.
class PolygonFixture(val id: Int, var points: QVectorPointI, val fillColor: Int) {
    var updates = 0
}
class PolygonBuilder {
    private var id = 0
    private var points = QVectorPointI()
    private var color = 0
    fun setPolygonId(value: Int) = apply { id = value }
    fun setBaseOrder(value: Int) = this
    fun setIsHidden(value: Boolean) = this
    fun setPoints(value: QVectorPointI) = apply { points = value }
    fun setFillColor(value: FColorARGB) = apply { color = value.argb.toInt() }
    fun buildAndAddToCollection(provider: PolygonsCollection): SWIGTYPE_p_std__shared_ptrT_Polygon_t {
        provider.polygons += PolygonFixture(id, points, color)
        return SWIGTYPE_p_std__shared_ptrT_Polygon_t()
    }
}
