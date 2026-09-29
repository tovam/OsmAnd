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

/** Human-readable time intervals selected without exceeding the visible map duration. */
internal fun flightTimeScaleStep(valueSeconds: Double): Double {
    if (!valueSeconds.isFinite() || valueSeconds < 1.0) return 0.0
    return FLIGHT_TIME_SCALE_STEPS.lastOrNull { it <= valueSeconds } ?: 0.0
}

internal fun flightTimeScaleLabel(seconds: Double): String = when {
    seconds >= 3600 -> "${(seconds / 3600).toInt()} h"
    seconds >= 60 -> "${(seconds / 60).toInt()} min"
    else -> "${seconds.toInt()} s"
}

private val FLIGHT_TIME_SCALE_STEPS = doubleArrayOf(
    1.0, 2.0, 5.0, 10.0, 15.0, 30.0,
    60.0, 120.0, 300.0, 600.0, 900.0, 1800.0,
    3600.0, 7200.0, 14400.0, 28800.0, 57600.0, 115200.0, 230400.0,
)
