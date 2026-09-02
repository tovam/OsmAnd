package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import net.osmand.data.RotatedTileBox
import net.osmand.plus.views.layers.base.OsmandMapLayer

/**
 * Keeps the native map gestures available while preventing POI selection and
 * context menus from leaking through the flight HUD.
 */
class FlightMapInteractionBlockerLayer(context: Context) : OsmandMapLayer(context) {

	override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) = Unit

	override fun onSingleTap(point: PointF, tileBox: RotatedTileBox): Boolean = true

	override fun onLongPressEvent(point: PointF, tileBox: RotatedTileBox): Boolean = true
}
