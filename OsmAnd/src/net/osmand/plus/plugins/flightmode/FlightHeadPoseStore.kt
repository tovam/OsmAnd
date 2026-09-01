package net.osmand.plus.plugins.flightmode

import android.content.Context

class FlightHeadPoseStore(context: Context) {

	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun load(): FlightHeadPose = FlightHeadPose(
		horizontalMeters = preferences.getFloat(KEY_HORIZONTAL, 0f),
		verticalMeters = preferences.getFloat(KEY_VERTICAL, 0f),
		distanceMeters = preferences.getFloat(KEY_DISTANCE, 0.18f)
	).clamped()

	fun save(pose: FlightHeadPose) {
		val safePose = pose.clamped()
		preferences.edit()
			.putFloat(KEY_HORIZONTAL, safePose.horizontalMeters)
			.putFloat(KEY_VERTICAL, safePose.verticalMeters)
			.putFloat(KEY_DISTANCE, safePose.distanceMeters)
			.apply()
	}

	private companion object {
		const val PREFERENCES_NAME = "flight_mode_preferences"
		const val KEY_HORIZONTAL = "window_head_horizontal_m"
		const val KEY_VERTICAL = "window_head_vertical_m"
		const val KEY_DISTANCE = "window_head_distance_m"
	}
}
