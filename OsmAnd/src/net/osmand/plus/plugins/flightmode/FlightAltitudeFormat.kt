package net.osmand.plus.plugins.flightmode

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal fun formatFlightAltitude(meters: Double): String {
    if (!meters.isFinite()) return "—"
    val symbols = DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = '\u202f' }
    return DecimalFormat("#,##0", symbols).format(meters) + " m"
}
