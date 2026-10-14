package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightWorkPolicy
import net.osmand.plus.plugins.flightmode.FlightPage
import org.junit.Assert.*
import org.junit.Test

class FlightCameraWorkPolicyTest {
    @Test fun cameraSuspendsOnlyVisualWork() {
        assertTrue(FlightWorkPolicy.visualWorkActive(true, false))
        assertFalse(FlightWorkPolicy.visualWorkActive(true, true))
        assertFalse(FlightWorkPolicy.visualWorkActive(false, false))
        assertFalse(FlightWorkPolicy.visualWorkActive(false, true))
        assertTrue(FlightWorkPolicy.recordingActive(false, false, false))
        assertTrue(FlightWorkPolicy.recordingActive(true, true, false))
    }

    @Test fun photoLibraryDoesNotConsumeTerrainButItsEditorDoes() {
        assertFalse(FlightWorkPolicy.terrainWorkActive(true, false, FlightPage.PHOTO, false))
        assertTrue(FlightWorkPolicy.terrainWorkActive(true, false, FlightPage.PHOTO, true))
        assertFalse(FlightWorkPolicy.terrainWorkActive(false, false, FlightPage.PHOTO, true))
        assertFalse(FlightWorkPolicy.terrainWorkActive(true, true, FlightPage.PHOTO, true))
        for (page in listOf(FlightPage.MAP, FlightPage.WINDOW, FlightPage.MIXED, FlightPage.WINDOW_SETUP)) {
            assertTrue(FlightWorkPolicy.terrainWorkActive(true, false, page, false))
        }
        // A stale modal visibility callback cannot activate terrain on another page.
        for (page in listOf(FlightPage.HOME, FlightPage.PREPARE, FlightPage.SATELLITE, FlightPage.JOURNAL)) {
            assertFalse(FlightWorkPolicy.terrainWorkActive(true, false, page, true))
        }
        assertTrue(FlightWorkPolicy.recordingActive(false, false, false))
    }

    @Test fun photoBrowsingNeedsNeitherNativeMapNorOfflineInventory() {
        assertFalse(FlightWorkPolicy.nativeMapVisible(FlightPage.PHOTO, false))
        assertFalse(FlightWorkPolicy.offlineCoverageActive(FlightPage.PHOTO))
        assertTrue(FlightWorkPolicy.offlineCoverageActive(FlightPage.SATELLITE))
        assertTrue(FlightWorkPolicy.offlineCoverageActive(FlightPage.PREPARE))
        for (page in listOf(FlightPage.MAP, FlightPage.MIXED)) {
            assertTrue(FlightWorkPolicy.nativeMapVisible(page, false))
            assertFalse(FlightWorkPolicy.nativeMapVisible(page, true))
        }
        assertFalse(FlightWorkPolicy.nativeMapVisible(FlightPage.WINDOW, false))
    }
}
