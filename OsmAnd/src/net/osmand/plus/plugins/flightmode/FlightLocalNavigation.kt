package net.osmand.plus.plugins.flightmode

/** Switching a journal only depends on local persistence, never on a server or edit lease. */
internal suspend fun <T> openLocalFlight(
    alreadyOpen: Boolean,
    savePending: suspend () -> Unit,
    load: suspend () -> T,
    apply: (T) -> Unit,
    resume: () -> Unit,
) {
    if (alreadyOpen) {
        resume()
        return
    }
    savePending()
    val loaded = load()
    apply(loaded)
}
