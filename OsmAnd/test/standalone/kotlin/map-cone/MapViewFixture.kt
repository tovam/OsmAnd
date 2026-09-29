package net.osmand.plus.views

import net.osmand.core.android.MapRendererView
import net.osmand.data.RotatedTileBox

class OsmandMapTileView {
    var mapRenderer: MapRendererView? = MapRendererView()
    val currentRotatedTileBox = RotatedTileBox()
    var refreshes = 0
    fun refreshMap() { refreshes++ }
}
