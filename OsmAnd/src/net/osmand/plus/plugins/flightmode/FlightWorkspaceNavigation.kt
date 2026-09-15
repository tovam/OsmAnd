package net.osmand.plus.plugins.flightmode

/** Navigation is a function of the selected flight, never of the last tab visited. */
internal object FlightWorkspaceNavigation {
    fun pages(mode: FlightSessionMode): List<FlightPage> =
        when (mode) {
            FlightSessionMode.PREPARE ->
                listOf(
                    FlightPage.HOME,
                    FlightPage.PREPARE,
                    FlightPage.MAP,
                    FlightPage.WINDOW,
                    FlightPage.SATELLITE,
                )
            FlightSessionMode.REPLAY ->
                listOf(
                    FlightPage.HOME,
                    FlightPage.MAP,
                    FlightPage.WINDOW,
                    FlightPage.SATELLITE,
                    FlightPage.SENSORS,
                    FlightPage.PHOTO,
                    FlightPage.JOURNAL,
                )
            FlightSessionMode.LIVE ->
                listOf(
                    FlightPage.HOME,
                    FlightPage.MAP,
                    FlightPage.WINDOW,
                    FlightPage.SATELLITE,
                    FlightPage.SENSORS,
                    FlightPage.PHOTO,
                    FlightPage.LIVE,
                )
        }

    fun resumePage(state: FlightUiState): FlightPage =
        if (state.sessionMode == FlightSessionMode.PREPARE) FlightPage.PREPARE else FlightPage.MAP

    fun libraryPage(mode: FlightSessionMode): FlightPage =
        when (mode) {
            FlightSessionMode.PREPARE -> FlightPage.PLANS
            FlightSessionMode.REPLAY -> FlightPage.JOURNEYS
            FlightSessionMode.LIVE -> FlightPage.HOME
        }

    fun backPage(page: FlightPage, mode: FlightSessionMode): FlightPage? =
        when (page) {
            FlightPage.HOME -> null
            FlightPage.JOURNEYS,
            FlightPage.PLANS -> FlightPage.HOME
            FlightPage.PREPARE -> FlightPage.PLANS
            FlightPage.JOURNAL -> FlightPage.MAP
            FlightPage.MAP -> libraryPage(mode)
            FlightPage.WINDOW_SETUP -> FlightPage.WINDOW
            else -> FlightPage.MAP
        }

    fun allows(mode: FlightSessionMode, page: FlightPage): Boolean =
        page in
            listOf(
                FlightPage.HOME,
                FlightPage.PLANS,
                FlightPage.JOURNEYS,
                FlightPage.WINDOW_SETUP,
            ) || page in pages(mode)
}
