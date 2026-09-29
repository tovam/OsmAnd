package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import net.osmand.core.android.MapRendererView
import net.osmand.core.jni.PointI
import net.osmand.core.jni.PolygonBuilder
import net.osmand.core.jni.PolygonsCollection
import net.osmand.core.jni.QVectorPointI
import net.osmand.core.jni.ZoomLevel
import net.osmand.data.LatLon
import net.osmand.data.RotatedTileBox
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.plus.views.layers.base.OsmandMapLayer
import net.osmand.util.MapUtils
import kotlin.math.abs

/**
 * A geographic polygon, not a Compose screen-space overlay. The native camera projects it in the
 * same frame as the map during pans, flings, zooms and tilts, including the final gesture frame.
 */
internal class FlightViewConeLayer(context: Context) : OsmandMapLayer(context) {
    @Volatile private var cone: FlightMapViewCone? = null
    private var attached = false
    private var owner: MapRendererView? = null
    private var collection: PolygonsCollection? = null
    private var renderedCone: FlightMapViewCone? = null
    private var renderedRadius = 0.0
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CONE_COLOR }

    @Synchronized
    override fun initLayer(view: OsmandMapTileView) {
        super.initLayer(view)
        attached = true
    }

    @Synchronized
    fun update(value: FlightMapViewCone?) {
        if (cone == value) return
        cone = value
        // A look gesture does not move the map. Update the existing polygon immediately rather
        // than waiting for a map-camera event; moving the map itself needs no reprojection here.
        if (attached) {
            syncNative(view.currentRotatedTileBox)
            view.refreshMap()
        }
    }

    override fun onPrepareBufferImage(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
        super.onPrepareBufferImage(canvas, tileBox, settings)
        syncNative(tileBox)
    }

    @Synchronized
    private fun syncNative(tileBox: RotatedTileBox) {
        if (!attached) return
        val renderer = mapRenderer
        if (owner !== renderer) clearNative()
        val current = cone
        if (renderer == null || current == null) {
            clearNative()
            return
        }
        owner = renderer
        val radius = radiusMeters(tileBox)
        if (renderedCone != current || abs(radius - renderedRadius) > renderedRadius * 0.01 || collection == null) {
            val points = QVectorPointI().apply {
                vertices(current, radius).forEach {
                    add(PointI(MapUtils.get31TileNumberX(it.longitude), MapUtils.get31TileNumberY(it.latitude)))
                }
            }
            val existing = collection
            if (existing == null) {
                val provider = PolygonsCollection(ZoomLevel.ZoomLevel0, ZoomLevel.ZoomLevel31)
                PolygonBuilder().setPolygonId(CONE_POLYGON_ID).setBaseOrder(baseOrder)
                    .setIsHidden(false).setPoints(points)
                    .setFillColor(NativeUtilities.createFColorARGB(CONE_COLOR))
                    .buildAndAddToCollection(provider)
                collection = provider
            } else {
                // Polygon itself is opaque in the Java bindings. Update it through the collection
                // so its provider and native symbol identity survive look/zoom changes.
                existing.setPolygonPoints(CONE_POLYGON_ID, points)
            }
            renderedCone = current
            renderedRadius = radius
        }
        collection?.let { if (!renderer.hasSymbolsProvider(it)) renderer.addSymbolsProvider(it) }
    }

    override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
        if (mapRenderer != null) return
        val current = cone ?: return
        val path = Path()
        vertices(current, radiusMeters(tileBox)).forEachIndexed { index, point ->
            val x = tileBox.getPixXFromLatLon(point.latitude, point.longitude)
            val y = tileBox.getPixYFromLatLon(point.latitude, point.longitude)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fallbackPaint)
    }

    private fun vertices(current: FlightMapViewCone, radius: Double): List<LatLon> = buildList {
        add(LatLon(current.latitude, current.longitude))
        current.boundaryBearings().forEach { bearing ->
            add(MapUtils.greatCircleDestinationPoint(current.latitude, current.longitude, radius, bearing))
        }
    }

    private fun radiusMeters(box: RotatedTileBox): Double =
        box.getDistance(box.pixWidth / 2, box.pixHeight / 2, box.pixWidth / 2, 0)
            .takeIf { it.isFinite() }?.coerceIn(500.0, 100_000.0) ?: 10_000.0

    private fun clearNative() {
        collection?.let { owner?.removeSymbolsProvider(it) }
        collection = null
        owner = null
        renderedCone = null
        renderedRadius = 0.0
    }

    @Synchronized
    override fun cleanupResources() {
        clearNative()
        super.cleanupResources()
    }

    @Synchronized
    override fun destroyLayer() {
        attached = false
        super.destroyLayer()
    }

    override fun drawInScreenPixels(): Boolean = true

    companion object {
        const val Z_ORDER = 998f // Under flight route/aircraft symbols, above the basemap.
        private const val CONE_POLYGON_ID = 1
        private const val CONE_COLOR = 0x905099DF.toInt() // 56% opacity, previously 25%.
    }
}
