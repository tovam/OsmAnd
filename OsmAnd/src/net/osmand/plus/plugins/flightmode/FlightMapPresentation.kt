package net.osmand.plus.plugins.flightmode

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A stable 1/2/5 scale, in metres or in seconds. Never exceeds the available width. */
internal fun flightScaleStep(value: Double): Double {
    if (!value.isFinite() || value <= 0.0) return 0.0
    val power = 10.0.pow(floor(log10(value)))
    return power *
        when {
            value / power >= 5 -> 5
            value / power >= 2 -> 2
            else -> 1
        }
}
