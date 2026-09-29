package net.osmand.test.junit

import android.content.Context
import android.graphics.Canvas
import net.osmand.core.android.MapRendererView
import net.osmand.plus.plugins.flightmode.FlightMapViewCone
import net.osmand.plus.plugins.flightmode.FlightViewConeLayer
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.plus.views.layers.base.OsmandMapLayer
import org.junit.Assert.*
import org.junit.Test

/** Executes the production layer against synthetic renderer bindings; no GPU/Android/data access. */
class FlightViewConeLayerTest {
    private val cone = FlightMapViewCone(45.0, 2.0, 90f, 60f)

    @Test
    fun panUsesSameNativePolygonAndDoesNotNeedComposeOrFrameCallbacks() {
        val map = OsmandMapTileView()
        val renderer = map.mapRenderer!!
        val layer = FlightViewConeLayer(Context()).apply { initLayer(map); update(cone) }
        val provider = renderer.providers.single()
        val polygon = provider.polygons.single()
        val origin = polygon.points.first()
        repeat(100) {
            map.currentRotatedTileBox.panPixels = it * 10f
            frame(layer, map)
        }
        assertSame(provider, renderer.providers.single())
        assertSame(polygon, provider.polygons.single())
        assertEquals(origin, polygon.points.first())
        assertEquals(0, polygon.updates)
        assertEquals(1, renderer.additions)
        assertEquals(0, renderer.removals)
        assertEquals(144, polygon.fillColor ushr 24)
    }

    @Test
    fun lookChangesUpdateTheExistingPolygonImmediately() {
        val map = OsmandMapTileView()
        val layer = FlightViewConeLayer(Context()).apply { initLayer(map); update(cone) }
        val provider = map.mapRenderer!!.providers.single()
        val polygon = provider.polygons.single()
        val original = polygon.points.toList()
        layer.update(cone.copy(azimuthDegrees = 120f))
        assertEquals(1, polygon.updates)
        assertEquals(original.first(), polygon.points.first())
        assertNotEquals(original[1], polygon.points[1])
        assertSame(provider, map.mapRenderer!!.providers.single())
        assertEquals(1, map.mapRenderer!!.additions)
    }

    @Test
    fun zoomRefinesConeRangeWithoutRemovingItsProvider() {
        val map = OsmandMapTileView()
        val layer = FlightViewConeLayer(Context()).apply { initLayer(map); update(cone) }
        val provider = map.mapRenderer!!.providers.single()
        val polygon = provider.polygons.single()
        val origin = polygon.points.first()
        map.currentRotatedTileBox.radiusMeters = 25_000.0
        frame(layer, map)
        assertEquals(1, polygon.updates)
        assertEquals(origin, polygon.points.first())
        assertSame(provider, map.mapRenderer!!.providers.single())
        assertEquals(0, map.mapRenderer!!.removals)
    }

    @Test
    fun leavingMixedRemovesProviderAndDoesNotRecreateItFromLateUpdates() {
        val map = OsmandMapTileView()
        val renderer = map.mapRenderer!!
        val layer = FlightViewConeLayer(Context()).apply { initLayer(map); update(cone) }
        layer.destroyLayer()
        layer.update(cone.copy(azimuthDegrees = 180f))
        frame(layer, map)
        assertTrue(renderer.providers.isEmpty())
        assertEquals(1, renderer.removals)
        layer.initLayer(map)
        frame(layer, map)
        assertEquals(1, renderer.providers.size)
        assertEquals(2, renderer.additions)
    }

    @Test
    fun replacingRendererReleasesOldOwnerAndPublishesToNewOwner() {
        val map = OsmandMapTileView()
        val oldRenderer = map.mapRenderer!!
        val layer = FlightViewConeLayer(Context()).apply { initLayer(map); update(cone) }
        val replacement = MapRendererView()
        map.mapRenderer = replacement
        frame(layer, map)
        assertTrue(oldRenderer.providers.isEmpty())
        assertEquals(1, replacement.providers.size)
        assertEquals(1, oldRenderer.removals)
        layer.update(null)
        assertTrue(replacement.providers.isEmpty())
    }

    private fun frame(layer: FlightViewConeLayer, map: OsmandMapTileView) =
        layer.onPrepareBufferImage(Canvas(), map.currentRotatedTileBox, OsmandMapLayer.DrawSettings())
}
