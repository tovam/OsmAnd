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

/** Absolute targets prevent camera clamps from permanently shifting the sensor reference. */
internal class FlightPhoneAimController {
    private val orientation = FlightRelativeOrientation()
    private var baseline = FlightWindowLook()
    private var baselineBearing: Float? = null
    private var yaw = 0f
    private var pitch = 0f

    fun reset(look: FlightWindowLook, bearing: Float? = null) {
        baseline = look
        baselineBearing = bearing?.takeIf { it.isFinite() }
        yaw = 0f
        pitch = 0f
        orientation.reset()
    }

    fun update(rawYaw: Float, rawPitch: Float, current: FlightWindowLook, bearing: Float? = null): Pair<Float, Float>? {
        val delta = orientation.update(rawYaw, rawPitch) ?: return null
        yaw = FlightWindowLook.normalizeYaw(yaw + delta.first)
        pitch += delta.second
        val course = bearing?.takeIf { it.isFinite() }
        if (baselineBearing == null && course != null) baselineBearing = course
        val aircraftTurn = if (course != null && baselineBearing != null)
            FlightWindowLook.normalizeYaw(course - requireNotNull(baselineBearing)) else 0f
        val target = baseline.copy(yawDegrees = baseline.yawDegrees + yaw - aircraftTurn,
            pitchDegrees = baseline.pitchDegrees + pitch).clamped()
        val dy = FlightWindowLook.normalizeYaw(target.yawDegrees - current.yawDegrees)
        val dp = target.pitchDegrees - current.pitchDegrees
        return (dy to dp).takeIf { kotlin.math.abs(dy) >= 0.03f || kotlin.math.abs(dp) >= 0.03f }
    }
}
