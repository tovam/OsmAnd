package net.osmand.plus.plugins.flightmode

/** Dispose the very callback that acquired resources, never its replacement after recomposition. */
internal class FlightVisibilityLease {
    private var active: Boolean? = null
    private var acquired: ((Boolean) -> Unit)? = null

    fun update(visible: Boolean, action: (Boolean) -> Unit) {
        if (active == visible) return
        active = visible
        if (visible) {
            acquired = action
            action(true)
        } else {
            val release = acquired ?: action
            acquired = null
            release(false)
        }
    }

    fun dispose() {
        active = false
        val release = acquired
        acquired = null
        release?.invoke(false)
    }
}
