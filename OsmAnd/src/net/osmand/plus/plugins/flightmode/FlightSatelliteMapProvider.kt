package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import net.osmand.core.android.MapRendererView
import net.osmand.core.jni.*
import net.osmand.plus.utils.AndroidUtils

/**
 * Native map overlay, using the same durable satellite files as the flight renderer. No network, no
 * duplicate cache and no foreground bitmap decoding. Missing detail magnifies a cached parent; if
 * none exists OSM stays visible underneath.
 */
internal class FlightSatelliteMapProvider(context: Context) : interface_ImageMapLayerProvider() {
    private val root =
        File(context.applicationContext.filesDir, FlightSatelliteSource.CACHE_DIRECTORY)

    override fun getDesiredStubsStyle() = MapStubStyle.Unspecified

    override fun getMinZoom() = ZoomLevel.swigToEnum(0)

    override fun getMaxZoom() = ZoomLevel.swigToEnum(22)

    override fun getMinVisibleZoom() = getMinZoom()

    override fun getMaxVisibleZoom() = getMaxZoom()

    override fun supportsNaturalObtainData() = true

    override fun supportsNaturalObtainDataAsync() = false

    override fun supportsObtainImage() = false

    override fun getTileSize() = 256L

    override fun getTileDensityFactor() = 1f

    override fun getAlphaChannelPresence() = AlphaChannelPresence.Unknown

    override fun obtainImageData(
        request: IMapTiledDataProvider.Request,
        bytes: SWIGTYPE_p_QByteArray,
    ): Long {
        val zoom = request.zoom.swigValue()
        val x = request.tileId.x
        val y = request.tileId.y
        for (parentZoom in zoom downTo 0) {
            if (request.queryController?.isAborted == true) return 0
            val delta = zoom - parentZoom
            val file = File(root, "$parentZoom/${x shr delta}/${y shr delta}.jpg")
            if (!file.isFile) continue
            val source = BitmapFactory.decodeFile(file.path) ?: continue
            try {
                val image =
                    if (delta == 0) source
                    else
                        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { target ->
                            val children = (1L shl delta).toFloat()
                            val childX = x % (1L shl delta)
                            val childY = y % (1L shl delta)
                            // Scale/translate the parent without rounding a sub-pixel source crop
                            // to zero.
                            Canvas(target)
                                .drawBitmap(
                                    source,
                                    null,
                                    RectF(
                                        -childX * 256f,
                                        -childY * 256f,
                                        (children - childX) * 256f,
                                        (children - childY) * 256f,
                                    ),
                                    Paint(Paint.FILTER_BITMAP_FLAG),
                                )
                        }
                try {
                    SwigUtilities.appendToQByteArray(
                        bytes,
                        AndroidUtils.getByteArrayFromBitmap(image),
                    )
                    return (image.height.toLong() shl 32) or image.width.toLong()
                } finally {
                    if (image !== source) image.recycle()
                }
            } finally {
                source.recycle()
            }
        }
        return 0
    }
}

/** Scoped ownership: never modifies the user's raster-source/opacity preferences. */
internal class FlightSatelliteMapOverlay(private val context: Context) {
    private var renderer: MapRendererView? = null
    private var provider: FlightSatelliteMapProvider? = null

    fun update(target: MapRendererView?, opacity: Float) {
        if (target !== renderer) {
            clear()
            renderer = target
        }
        val map = renderer ?: return
        if (opacity <= 0f) {
            clear()
            return
        }
        if (provider == null) {
            provider =
                FlightSatelliteMapProvider(context).also {
                    map.setMapLayerProvider(LAYER, it.instantiateProxy(true))
                    it.swigReleaseOwnership()
                }
        }
        map.setMapLayerConfiguration(
            LAYER,
            MapLayerConfiguration().apply { opacityFactor = opacity.coerceIn(0f, 1f) },
        )
    }

    fun clear() {
        renderer?.resetMapLayerProvider(LAYER)
        provider = null
        renderer = null
    }

    companion object {
        private const val LAYER = 5500
    }
}
