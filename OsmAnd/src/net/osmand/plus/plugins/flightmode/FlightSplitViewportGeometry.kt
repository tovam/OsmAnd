package net.osmand.plus.plugins.flightmode

internal data class FlightViewportRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class FlightViewportPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Both rectangles use screen coordinates, so system insets are counted exactly once. */
internal fun flightViewportPadding(requested: FlightViewportRect, wrapper: FlightViewportRect): FlightViewportPadding? {
    if (wrapper.right <= wrapper.left || wrapper.bottom <= wrapper.top) return null
    val left = maxOf(requested.left, wrapper.left)
    val top = maxOf(requested.top, wrapper.top)
    val right = minOf(requested.right, wrapper.right)
    val bottom = minOf(requested.bottom, wrapper.bottom)
    if (right <= left || bottom <= top) return null
    return FlightViewportPadding(left - wrapper.left, top - wrapper.top, wrapper.right - right, wrapper.bottom - bottom)
}
