package net.osmand.plus.plugins.flightmode

import android.content.Context

class FlightWindowPlacementStore(context: Context) {

	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun load(): FlightWindowPlacement {
		val side = preferences.getString(KEY_SIDE, null)
			?.let { saved -> FlightCabinSide.entries.firstOrNull { it.name == saved } }
			?: FlightCabinSide.LEFT
		return FlightWindowPlacement(
			side = side,
			forwardOffsetMeters = preferences.getFloat(KEY_FORWARD_OFFSET, 0f),
			verticalOffsetMeters = preferences.getFloat(KEY_VERTICAL_OFFSET, 0f),
			zoom = preferences.getFloat(KEY_ZOOM, 1f),
			cabinTransparent = preferences.getBoolean(KEY_CABIN_TRANSPARENT, false)
		).clamped()
	}

	fun save(placement: FlightWindowPlacement) {
		val safePlacement = placement.clamped()
		preferences.edit()
			.putString(KEY_SIDE, safePlacement.side.name)
			.putFloat(KEY_FORWARD_OFFSET, safePlacement.forwardOffsetMeters)
			.putFloat(KEY_VERTICAL_OFFSET, safePlacement.verticalOffsetMeters)
			.putFloat(KEY_ZOOM, safePlacement.zoom)
			.putBoolean(KEY_CABIN_TRANSPARENT, safePlacement.cabinTransparent)
			.apply()
	}

	private companion object {
		const val PREFERENCES_NAME = "flight_mode_preferences"
		const val KEY_SIDE = "window_cabin_side"
		const val KEY_FORWARD_OFFSET = "window_forward_offset_m"
		const val KEY_VERTICAL_OFFSET = "window_vertical_offset_m"
		const val KEY_ZOOM = "window_view_zoom"
		const val KEY_CABIN_TRANSPARENT = "window_cabin_transparent"
	}
}
