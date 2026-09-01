package net.osmand.plus.views.mapwidgets.configure.buttons;

import static net.osmand.aidlapi.OsmAndCustomizationConstants.STOP_NAVIGATION_HUD_ID;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.OPAQUE_ALPHA;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.RECTANGULAR_RADIUS_DP;
import static net.osmand.shared.grid.ButtonPositionSize.POS_BOTTOM;
import static net.osmand.shared.grid.ButtonPositionSize.POS_LEFT;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.shared.grid.ButtonPositionSize;

public class StopNavigationButtonState extends MapButtonState {

	private final CommonPreference<Boolean> visibilityPref;

	public StopNavigationButtonState(@NonNull OsmandApplication app) {
		super(app, STOP_NAVIGATION_HUD_ID);
		visibilityPref = addPreference(settings.registerBooleanPreference(id + "_state", true)).makeProfile();
	}

	@NonNull
	@Override
	public String getName() {
		return app.getString(R.string.cancel_navigation);
	}

	@NonNull
	@Override
	public String getDescription() {
		return app.getString(R.string.quick_action_start_stop_navigation_descr);
	}

	@Override
	public boolean isEnabled() {
		return visibilityPref.get();
	}

	@NonNull
	@Override
	public CommonPreference<Boolean> getVisibilityPref() {
		return visibilityPref;
	}

	@Override
	public int getDefaultLayoutId() {
		return R.layout.stop_navigation_button;
	}

	@NonNull
	@Override
	public String getDefaultIconName(@Nullable Boolean nightMode) {
		return "ic_action_stop";
	}

	@Override
	public float getDefaultOpacity() {
		return OPAQUE_ALPHA;
	}

	@Override
	public int getDefaultCornerRadius() {
		return RECTANGULAR_RADIUS_DP;
	}

	@NonNull
	@Override
	protected ButtonPositionSize setupButtonPosition(@NonNull ButtonPositionSize position) {
		return setupButtonPosition(position, POS_LEFT, POS_BOTTOM, true, false);
	}
}
