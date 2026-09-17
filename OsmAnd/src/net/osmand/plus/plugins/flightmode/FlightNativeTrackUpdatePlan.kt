package net.osmand.plus.plugins.flightmode

/**
 * Describes changes within already-attached native collections. A live append creates only the
 * new line/marker members; it never requires replacing either renderer provider.
 */
internal data class FlightNativeTrackUpdatePlan(
	val routeStrokesToCreate: Int,
	val routeStrokesToHide: Int,
	val pointMarkersToCreate: Int,
	val pointMarkersToHide: Int
)

internal fun flightNativeTrackUpdatePlan(
	existingRouteStrokes: Int,
	requiredRouteStrokes: Int,
	existingPointMarkers: Int,
	requiredPointMarkers: Int,
	pointsVisible: Boolean
): FlightNativeTrackUpdatePlan = FlightNativeTrackUpdatePlan(
	routeStrokesToCreate = (requiredRouteStrokes - existingRouteStrokes).coerceAtLeast(0),
	routeStrokesToHide = (existingRouteStrokes - requiredRouteStrokes).coerceAtLeast(0),
	pointMarkersToCreate = if (pointsVisible) {
		(requiredPointMarkers - existingPointMarkers).coerceAtLeast(0)
	} else {
		0
	},
	pointMarkersToHide = if (pointsVisible) {
		(existingPointMarkers - requiredPointMarkers).coerceAtLeast(0)
	} else {
		existingPointMarkers
	}
)
