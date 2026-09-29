package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightMapCameraTest {
    @Test
    fun headingLockEnablesBothCenterLockAndFollowFromFreeMap() {
        val state = FlightUiState(mapFollowing = false).withMapHeadingLocked(true)
        assertTrue(state.mapHeadingLocked)
        assertTrue(state.mapCenterLocked)
        assertTrue(state.mapFollowing)
    }

    @Test
    fun headingLockSurvivesAircraftProgressAndAChangeOfPage() {
        val state = FlightUiState().withMapHeadingLocked(true)
        val next = state.copy(page = FlightPage.MIXED, snapshot = FlightSnapshot(sample(175f), 0.6f))
        assertTrue(next.mapHeadingLocked)
        assertTrue(next.mapCenterLocked)
        assertTrue(next.mapFollowing)
        assertEquals(175f, next.snapshot!!.sample.bearingDegrees!!, 0f)
    }

    @Test
    fun northOrSecondHeadingPressReleasesOnlyTheHeading() {
        val state = FlightUiState().withMapHeadingLocked(true).withMapHeadingLocked(false)
        assertFalse(state.mapHeadingLocked)
        assertTrue(state.mapCenterLocked)
        assertTrue(state.mapFollowing)
    }

    @Test
    fun unlockingCenterAlsoUnlocksHeadingButKeepsOrdinaryFollow() {
        val state = FlightUiState().withMapHeadingLocked(true).withMapCenterLocked(false)
        assertFalse(state.mapHeadingLocked)
        assertFalse(state.mapCenterLocked)
        assertTrue(state.mapFollowing)
    }

    @Test
    fun stoppingFollowReleasesAllCameraLocksAndDoesNotRestoreThemOnRestart() {
        val off = FlightUiState().withMapHeadingLocked(true).withMapFollowing(false)
        assertFalse(off.mapHeadingLocked)
        assertFalse(off.mapCenterLocked)
        assertFalse(off.mapFollowing)
        val on = off.withMapFollowing(true)
        assertFalse(on.mapHeadingLocked)
        assertFalse(on.mapCenterLocked)
        assertTrue(on.mapFollowing)
    }

    @Test
    fun centerLockAloneDoesNotForceHeading() {
        val state = FlightUiState(mapFollowing = false).withMapCenterLocked(true)
        assertFalse(state.mapHeadingLocked)
        assertTrue(state.mapCenterLocked)
        assertTrue(state.mapFollowing)
    }

    @Test
    fun coneUsesAircraftPositionLookAndActualWindowAspectNotMapCenter() {
        val sample = sample(90f)
        val placement = FlightWindowPlacement(side = FlightCabinSide.LEFT)
        val look = FlightWindowLook(yawDegrees = 35f)
        val cone = flightMapViewCone(sample, placement, look, 1.6f)!!
        assertEquals(sample.latitude, cone.latitude, 0.0)
        assertEquals(sample.longitude, cone.longitude, 0.0)
        assertEquals(35f, cone.azimuthDegrees, 0.001f)
        assertEquals(placement.horizontalFieldOfViewDegrees(1.6f), cone.fieldOfViewDegrees, 0.001f)
        assertEquals(25, cone.boundaryBearings().size)
        assertEquals(35.0, cone.boundaryBearings()[12], 0.001)
        assertTrue(cone.boundaryBearings().all { it >= 0.0 && it < 360.0 })
    }

    @Test
    fun coneImmediatelyTracksLookSideAndZoomWithoutChangingItsOrigin() {
        val placement = FlightWindowPlacement()
        val original = flightMapViewCone(sample(), placement, FlightWindowLook(), 1f)!!
        val turned = flightMapViewCone(sample(), placement, FlightWindowLook(yawDegrees = 15f), 1f)!!
        val zoomed = flightMapViewCone(sample(), placement.copy(zoom = 4f), FlightWindowLook(), 1f)!!
        val otherSide = flightMapViewCone(sample(), placement.copy(side = FlightCabinSide.RIGHT), FlightWindowLook(), 1f)!!
        assertEquals(15f, (turned.azimuthDegrees - original.azimuthDegrees + 360) % 360, 0.001f)
        assertTrue(zoomed.fieldOfViewDegrees < original.fieldOfViewDegrees)
        assertEquals(180f, (otherSide.azimuthDegrees - original.azimuthDegrees + 360) % 360, 0.001f)
        assertEquals(original.latitude, turned.latitude, 0.0)
        assertEquals(original.longitude, turned.longitude, 0.0)
    }

    @Test
    fun noConeWithoutValidPositionAndUnmeasuredLayoutUsesSquareAspect() {
        val placement = FlightWindowPlacement()
        val look = FlightWindowLook()
        assertNull(flightMapViewCone(null, placement, look, 1f))
        assertNull(flightMapViewCone(sample().copy(latitude = Double.NaN), placement, look, 1f))
        assertEquals(flightMapViewCone(sample(), placement, look, 1f), flightMapViewCone(sample(), placement, look, Float.NaN))
    }

    private fun sample(bearing: Float = 90f) =
        FlightSample(10, 0, 10_000L, 45.0, 2.0, 10_000.0, 250f, bearing, 5f)
}
