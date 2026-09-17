package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.flightNativeTrackUpdatePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class FlightNativeTrackUpdatePlanTest {

	@Test
	fun liveAppendMutatesAttachedTrackMembersInsteadOfReplacingThem() {
		val plan = flightNativeTrackUpdatePlan(
			existingRouteStrokes = 1,
			requiredRouteStrokes = 1,
			existingPointMarkers = 3,
			requiredPointMarkers = 4,
			pointsVisible = true
		)

		assertEquals(0, plan.routeStrokesToCreate)
		assertEquals(0, plan.routeStrokesToHide)
		assertEquals(1, plan.pointMarkersToCreate)
		assertEquals(0, plan.pointMarkersToHide)
	}

	@Test
	fun shorterOrHiddenTrackOnlyHidesSurplusMembers() {
		val shorter = flightNativeTrackUpdatePlan(3, 1, 8, 2, pointsVisible = true)
		assertEquals(0, shorter.routeStrokesToCreate)
		assertEquals(2, shorter.routeStrokesToHide)
		assertEquals(0, shorter.pointMarkersToCreate)
		assertEquals(6, shorter.pointMarkersToHide)

		val hidden = flightNativeTrackUpdatePlan(1, 1, 2, 0, pointsVisible = false)
		assertEquals(0, hidden.routeStrokesToCreate)
		assertEquals(0, hidden.routeStrokesToHide)
		assertEquals(0, hidden.pointMarkersToCreate)
		assertEquals(2, hidden.pointMarkersToHide)
	}
}
