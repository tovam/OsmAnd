package net.osmand.plus.plugins.flightmode

import kotlin.math.abs

/** Field-of-view ratios compare focal length / sensor width, never focal length alone. */
internal object FlightCameraOptics {
    fun fieldScale(focalMm: Float?, sensorWidthMm: Float?): Float =
        if (
            focalMm != null &&
                sensorWidthMm != null &&
                focalMm.isFinite() &&
                sensorWidthMm.isFinite() &&
                focalMm > 0f &&
                sensorWidthMm > 0f
        )
            focalMm / sensorWidthMm
        else 0f

    fun mainScale(scales: List<Float>, fallback: Float): Float =
        scales.filter { it.isFinite() && it > 0f }.minByOrNull { abs(it * 36f - 24f) } ?: fallback

    data class Preference(val front: Boolean, val locked: Boolean, val ratio: Float)

    fun defaultIndex(lenses: List<Preference>): Int =
        lenses.indices.minWithOrNull(
            compareBy<Int> { lenses[it].front }
                .thenBy { !lenses[it].locked }
                .thenBy { abs(lenses[it].ratio - 1f) }
        ) ?: 0
}
