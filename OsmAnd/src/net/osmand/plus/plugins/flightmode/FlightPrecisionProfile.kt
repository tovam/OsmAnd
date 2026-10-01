package net.osmand.plus.plugins.flightmode

/** Presets affect detail only: routes, dates, coverage radii and recording policies stay intact. */
internal enum class FlightPrecisionProfile(val satellite: FlightSatelliteQuality, val fineZoom: Int, val middleZoom: Int) {
    ECONOMY(FlightSatelliteQuality.STANDARD, 10, 9),
    BALANCED(FlightSatelliteQuality.HIGH, 12, 11),
    DETAILED(FlightSatelliteQuality.ULTRA, 14, 12);

    fun apply(plan: FlightPlan): FlightPlan = plan.copy(
        satelliteQuality = satellite, terrainFineZoom = fineZoom, terrainMiddleZoom = middleZoom,
        preparation = plan.preparation?.let { prep -> prep.copy(bands = prep.bands.mapIndexed { index, band ->
            val zoom = fineZoom - index.coerceAtMost(2) * 2
            band.copy(satelliteZoom = zoom, terrainZoom = zoom)
        }) },
    )

    fun matches(plan: FlightPlan): Boolean = apply(plan) == plan

    companion object {
        fun selected(plan: FlightPlan): FlightPrecisionProfile? = entries.firstOrNull { it.matches(plan) }
    }
}
