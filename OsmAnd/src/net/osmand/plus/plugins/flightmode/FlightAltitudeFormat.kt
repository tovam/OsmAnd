package net.osmand.plus.plugins.flightmode

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal fun formatFlightAltitude(meters: Double): String {
    if (!meters.isFinite()) return "—"
    val symbols = DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = '\u202f' }
    return DecimalFormat("#,##0", symbols).format(meters) + " m"
}

internal fun formatFlightDistance(meters: Double): String {
    if (!meters.isFinite() || meters <= 0.0) return "—"
    val symbols = DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = '\u202f' }
    val value = if (meters >= 1000.0) meters / 1000.0 else meters
    return DecimalFormat("#,##0.##", symbols).format(value) + if (meters >= 1000.0) " km" else " m"
}
