package net.osmand.plus.views.layers.base

import android.content.Context
import android.graphics.Canvas
import net.osmand.data.RotatedTileBox
import net.osmand.plus.views.OsmandMapTileView

abstract class OsmandMapLayer(context: Context) {
    protected lateinit var view: OsmandMapTileView
    protected val mapRenderer get() = view.mapRenderer
    protected val baseOrder get() = -99_800_000
    class DrawSettings
    open fun initLayer(view: OsmandMapTileView) { this.view = view }
    abstract fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings)
    open fun onPrepareBufferImage(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {}
    protected open fun cleanupResources() {}
    open fun destroyLayer() { cleanupResources() }
    abstract fun drawInScreenPixels(): Boolean
}
