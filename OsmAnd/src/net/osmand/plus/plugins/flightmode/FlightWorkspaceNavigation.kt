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
                    FlightPage.JOURNEYS,
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

    fun allows(mode: FlightSessionMode, page: FlightPage): Boolean =
        page in
            listOf(
                FlightPage.HOME,
                FlightPage.PLANS,
                FlightPage.JOURNEYS,
                FlightPage.WINDOW_SETUP,
            ) || page in pages(mode)
}
