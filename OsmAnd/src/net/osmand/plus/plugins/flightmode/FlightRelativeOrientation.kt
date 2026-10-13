package net.osmand.plus.plugins.flightmode

/** Relative aiming avoids magnetic heading errors inside an aircraft. */
internal class FlightRelativeOrientation {
    private var yaw: Float? = null
    private var pitch = 0f

    fun reset() { yaw = null }

    fun update(nextYaw: Float, nextPitch: Float): Pair<Float, Float>? {
        if (!nextYaw.isFinite() || !nextPitch.isFinite()) return null
        val previous = yaw
        if (previous == null) { yaw = nextYaw; pitch = nextPitch; return null }
        val deltaYaw = FlightWindowLook.normalizeYaw(nextYaw - previous) * 0.25f
        val deltaPitch = (nextPitch - pitch) * 0.25f
        yaw = FlightWindowLook.normalizeYaw(previous + deltaYaw)
        pitch += deltaPitch
        return deltaYaw to deltaPitch
    }
}
