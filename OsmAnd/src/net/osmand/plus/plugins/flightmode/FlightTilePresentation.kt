package net.osmand.plus.plugins.flightmode

import java.util.Locale
import kotlin.math.*

internal enum class FlightTileReadiness {
    EMPTY,
    CHECKING,
    DOWNLOADING,
    PAUSED,
    MISSING,
    PRESENT,
    VERIFIED,
    ERROR,
}

/** Unknown inventory is never presented as zero missing files or verified offline coverage. */
internal fun flightTileReadiness(state: FlightUiState): FlightTileReadiness {
    val status = state.offlinePreloadStatus
    val coverage = state.offlineCoverage
    if (status.phase in listOf(FlightTerrainPhase.DOWNLOADING, FlightTerrainPhase.PLANNING))
        return FlightTileReadiness.DOWNLOADING
    if (state.offlineCoverageError != null) return FlightTileReadiness.ERROR
    if (state.offlineQuote == null && !state.offlineCoverageRefreshing)
        return FlightTileReadiness.EMPTY
    if (state.offlineQuote?.requests?.isEmpty() == true) return FlightTileReadiness.EMPTY
    if (coverage == null || !coverage.inventoried || state.offlineCoverageRefreshing)
        return FlightTileReadiness.CHECKING
    if (
        status.phase == FlightTerrainPhase.ERROR ||
            status.failedTiles + status.satelliteFailedTiles > 0
    )
        return FlightTileReadiness.ERROR
    if (status.phase == FlightTerrainPhase.PAUSED) return FlightTileReadiness.PAUSED
    if (coverage.total == 0) return FlightTileReadiness.EMPTY
    if (coverage.missing > 0) return FlightTileReadiness.MISSING
    return if (coverage.verifiedBy(status)) FlightTileReadiness.VERIFIED
    else FlightTileReadiness.PRESENT
}

internal enum class FlightTileAction(val enabled: Boolean) {
    EMPTY(false),
    CHECKING(false),
    UNAVAILABLE(false),
    OFFLINE_TEST(false),
    PAUSE(true),
    RESUME(true),
    DOWNLOAD(true),
    VERIFY(true),
}

/** Exploration never starts transfers; this action requires an explicit user click. */
internal fun flightTileAction(state: FlightUiState): FlightTileAction {
    val phase = state.offlinePreloadStatus.phase
    if (phase == FlightTerrainPhase.DOWNLOADING || phase == FlightTerrainPhase.PLANNING)
        return FlightTileAction.PAUSE
    if (state.offlineSimulation) return FlightTileAction.OFFLINE_TEST
    if (state.offlineCoverageError != null) return FlightTileAction.UNAVAILABLE
    if (state.offlineQuote == null && !state.offlineCoverageRefreshing)
        return FlightTileAction.EMPTY
    val coverage = state.offlineCoverage
    if (
        state.offlineQuote == null ||
            coverage?.inventoried != true ||
            state.offlineCoverageRefreshing
    )
        return FlightTileAction.CHECKING
    if (coverage.total == 0) return FlightTileAction.EMPTY
    if (coverage.missing == 0) return FlightTileAction.VERIFY
    return if (phase == FlightTerrainPhase.PAUSED) FlightTileAction.RESUME
    else FlightTileAction.DOWNLOAD
}

internal fun flightTileSize(bytes: Long, locale: Locale = Locale.getDefault()): String =
    when {
        bytes < 0 -> "—"
        bytes < 1000 -> "$bytes B"
        bytes < 1_000_000 -> String.format(locale, "%.0f kB", bytes / 1000.0)
        bytes < 1_000_000_000 -> String.format(locale, "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(locale, "%.2f GB", bytes / 1_000_000_000.0)
    }

internal fun flightTileInspectionZoom(
    requested: Int,
    bounds: FlightTileMapBounds,
    pixels: Double,
    minimumCellPixels: Double,
): Int {
    val readable =
        floor(log2((pixels / minimumCellPixels.coerceAtLeast(1.0)).coerceAtLeast(1.0)))
            .toInt()
            .coerceIn(0, 22)
    return min(bounds.gridZoom(requested), readable)
}

/** Ground distance at the map centre, corrected for Mercator latitude and camera zoom. */
internal fun flightTileMetersPerPixel(camera: FlightTileMapCamera): Double =
    40_075_016.686 / cosh(PI * (1 - 2 * camera.y)) / camera.worldPixels

/** Continuous altitude colours avoid the old five abrupt bands in the terrain preview. */
private val tileTerrainHeights = floatArrayOf(-1000f, -1f, 0f, 400f, 1500f, 2700f, 4500f)
private val tileTerrainColors =
    intArrayOf(
        0xFF193A58.toInt(),
        0xFF347795.toInt(),
        0xFF52785A.toInt(),
        0xFF8DA074.toInt(),
        0xFFB9AA83.toInt(),
        0xFFBAB6AC.toInt(),
        0xFFF2F3EF.toInt(),
    )

internal fun flightTileTerrainColor(meters: Float): Int {
    val heights = tileTerrainHeights
    val colors = tileTerrainColors
    if (!meters.isFinite()) return 0xFF18252D.toInt()
    if (meters <= heights.first()) return colors.first()
    for (i in 1 until heights.size) if (meters <= heights[i]) {
        val t = (meters - heights[i - 1]) / (heights[i] - heights[i - 1])
        var color = 0xFF000000.toInt()
        for (channel in 0..2) {
            val shift = channel * 8
            val a = colors[i - 1] shr shift and 255
            val b = colors[i] shr shift and 255
            color = color or ((a + (b - a) * t).roundToInt() shl shift)
        }
        return color
    }
    return colors.last()
}
