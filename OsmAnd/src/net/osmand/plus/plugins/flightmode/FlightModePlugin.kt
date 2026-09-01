package net.osmand.plus.plugins.flightmode

import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem

class FlightModePlugin(app: OsmandApplication) : OsmandPlugin(app) {

	override fun getId(): String = PLUGIN_ID

	override fun getName(): String = app.getString(R.string.flight_mode_name)

	override fun getDescription(linksEnabled: Boolean): CharSequence =
		app.getString(R.string.flight_mode_description)

	override fun getLogoResourceId(): Int = R.drawable.ic_action_aircraft

	override fun isEnableByDefault(): Boolean = true

	public override fun registerOptionsMenuItems(mapActivity: MapActivity, helper: ContextMenuAdapter) {
		helper.addItem(
			ContextMenuItem(DRAWER_ID)
				.setTitleId(R.string.flight_mode_name, mapActivity)
				.setIcon(R.drawable.ic_action_aircraft)
				.setListener { _, _, _, _ ->
					mapActivity.closeDrawer()
					FlightModeFragment.showInstance(mapActivity.supportFragmentManager)
					true
				}
		)
	}

	companion object {
		const val PLUGIN_ID = "osmand.flight_mode"
		const val DRAWER_ID = "drawer.action.flight_mode"
	}
}
