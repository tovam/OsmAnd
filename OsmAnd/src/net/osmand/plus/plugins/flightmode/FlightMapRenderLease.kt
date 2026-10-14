package net.osmand.plus.plugins.flightmode

/** Pause an obscured map once and resume only the same renderer if it is still attached. */
internal class FlightMapRenderLease<T : Any>(
    private val pause: (T) -> Unit,
    private val resume: (T) -> Unit,
) {
    private var paused: T? = null

    fun update(renderer: T?, visible: Boolean) {
        if (paused !== renderer) paused = null
        if (visible) {
            release(renderer, resumeRendering = true)
        } else if (renderer != null && paused == null) {
            pause(renderer)
            paused = renderer
        }
    }

    fun release(renderer: T?, resumeRendering: Boolean) {
        val owned = paused
        paused = null
        if (resumeRendering && owned != null && owned === renderer) resume(owned)
    }
}
