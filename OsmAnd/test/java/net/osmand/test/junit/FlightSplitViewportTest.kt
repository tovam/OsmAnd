package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightSplitViewportTest {
    @Test fun viewportAccountsForSystemInsetsAndFooterExactlyOnce() {
        assertEquals(FlightViewportPadding(0, 1216, 0, 200), flightViewportPadding(
            FlightViewportRect(0, 1240, 1080, 2200), FlightViewportRect(0, 24, 1080, 2400)))
    }
    @Test fun landscapeAndWindowOffsetsUseTheSameCoordinateFrame() {
        assertEquals(FlightViewportPadding(0, 500, 0, 80), flightViewportPadding(
            FlightViewportRect(120, 540, 2400, 960), FlightViewportRect(120, 40, 2400, 1040)))
    }
    @Test fun outsideOrEmptyViewportNeverCollapsesTheMap() {
        val wrapper = FlightViewportRect(0, 0, 100, 100)
        assertNull(flightViewportPadding(FlightViewportRect(0, 101, 100, 200), wrapper))
        assertNull(flightViewportPadding(FlightViewportRect(50, 50, 50, 80), wrapper))
        assertEquals(FlightViewportPadding(0, 0, 0, 0), flightViewportPadding(FlightViewportRect(-10, -10, 110, 110), wrapper))
    }
    @Test fun mixedWorkspaceIsAvailableInEveryModeWithoutLibraryOrPreparationActions() {
        FlightSessionMode.entries.forEach { mode ->
            assertTrue(FlightWorkspaceNavigation.allows(mode, FlightPage.MIXED))
            assertEquals(FlightPage.MAP, FlightWorkspaceNavigation.backPage(FlightPage.MIXED, mode))
            assertEquals(1, FlightWorkspaceNavigation.pages(mode).count { it == FlightPage.MIXED })
        }
    }
}
