package net.osmand.plus.plugins.flightmode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The service observes actual visible flight owners, not whether the process happens to exist. */
internal object FlightUiActivity {
    private val owners = mutableMapOf<Any, String>()
    private val updates = MutableStateFlow<Set<String>>(emptySet())
    val journeys = updates.asStateFlow()

    @Synchronized
    fun set(owner: Any, journey: String?) {
        if (journey == null) owners.remove(owner) else owners[owner] = journey
        updates.value = owners.values.toSet()
    }
}

internal object FlightWorkPolicy {
    /** Camera capture keeps the flight alive, but does not consume a terrain frame behind it. */
    fun visualWorkActive(uiVisible: Boolean, cameraPreviewActive: Boolean): Boolean =
        uiVisible && !cameraPreviewActive

    /** Browsing photos consumes no terrain; the photo editor can request it explicitly. */
    fun terrainWorkActive(
        uiVisible: Boolean,
        cameraPreviewActive: Boolean,
        page: FlightPage,
        photoEditorVisible: Boolean,
    ): Boolean =
        visualWorkActive(uiVisible, cameraPreviewActive) &&
            (page == FlightPage.MAP || page == FlightPage.WINDOW ||
                page == FlightPage.MIXED || page == FlightPage.WINDOW_SETUP ||
                page == FlightPage.PHOTO && photoEditorVisible)

    fun nativeMapVisible(page: FlightPage, cameraPreviewActive: Boolean): Boolean =
        !cameraPreviewActive && (page == FlightPage.MAP || page == FlightPage.MIXED)

    /** Only pages displaying offline coverage need to maintain its inventory subscription. */
    fun offlineCoverageActive(page: FlightPage): Boolean =
        page == FlightPage.PREPARE || page == FlightPage.DETAIL ||
            page == FlightPage.JOURNAL || page == FlightPage.SATELLITE ||
            page == FlightPage.MAP || page == FlightPage.WINDOW || page == FlightPage.MIXED

    fun recordingActive(simulation: Boolean, visible: Boolean, userPaused: Boolean): Boolean =
        !simulation || (visible && !userPaused)

    /** A scheduled real flight outranks a rehearsal, but never replaces another real flight. */
    fun startDisposition(
        activeSimulation: Boolean?,
        requestedSimulation: Boolean,
    ): FlightStartDisposition =
        when {
            activeSimulation == null -> FlightStartDisposition.START
            activeSimulation && !requestedSimulation -> FlightStartDisposition.REPLACE_SIMULATION
            else -> FlightStartDisposition.KEEP_CURRENT
        }

    fun acceptsControl(requestedJourney: String?, activeJourney: String?): Boolean =
        requestedJourney != null && requestedJourney == activeJourney

    /** Even an empty, explicitly stopped test has a terminal recorder state on disk. */
    fun needsNewRecordingJournal(
        simulation: Boolean,
        hasSamples: Boolean,
        phase: FlightTrackingPhase,
    ): Boolean =
        simulation ||
            hasSamples ||
            phase == FlightTrackingPhase.STOPPED ||
            phase == FlightTrackingPhase.LANDED
}

internal enum class FlightStartDisposition {
    START,
    KEEP_CURRENT,
    REPLACE_SIMULATION,
}
