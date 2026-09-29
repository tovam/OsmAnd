package net.osmand.plus.plugins.flightmode

/** Heading lock always includes position lock and follow; releasing either releases heading too. */
internal fun FlightUiState.withMapFollowing(following: Boolean): FlightUiState = copy(
    mapFollowing = following,
    mapCenterLocked = mapCenterLocked && following,
    mapHeadingLocked = mapHeadingLocked && following,
)

internal fun FlightUiState.withMapCenterLocked(locked: Boolean): FlightUiState = copy(
    mapCenterLocked = locked,
    mapFollowing = locked || mapFollowing,
    mapHeadingLocked = mapHeadingLocked && locked,
)

internal fun FlightUiState.withMapHeadingLocked(locked: Boolean): FlightUiState = copy(
    mapHeadingLocked = locked,
    mapCenterLocked = locked || mapCenterLocked,
    mapFollowing = locked || mapFollowing,
)

/** World-space inputs only: panning/rotating the map must never move the cone's geo anchor. */
internal data class FlightMapViewCone(
    val latitude: Double,
    val longitude: Double,
    val azimuthDegrees: Float,
    val fieldOfViewDegrees: Float,
) {
    fun boundaryBearings(): List<Double> = (0..24).map {
        (azimuthDegrees - fieldOfViewDegrees / 2.0 + fieldOfViewDegrees * it / 24.0 + 360.0) % 360.0
    }
}

internal fun flightMapViewCone(
    sample: FlightSample?, placement: FlightWindowPlacement, look: FlightWindowLook, aspectRatio: Float,
): FlightMapViewCone? {
    sample ?: return null
    if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
        sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0) return null
    val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val bearing = sample.bearingDegrees?.takeIf { it.isFinite() } ?: 0f
    return FlightMapViewCone(
        sample.latitude, sample.longitude,
        placement.viewAzimuthDegrees(bearing, look),
        placement.horizontalFieldOfViewDegrees(aspect),
    )
}
