package net.osmand.plus.views.controls.maphudbuttons;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.views.mapwidgets.configure.buttons.MapButtonState;
import net.osmand.plus.views.mapwidgets.configure.buttons.StopNavigationButtonState;

public class StopNavigationButton extends MapButton {

	private final RoutingHelper routingHelper;
	private final StopNavigationButtonState buttonState;

	public StopNavigationButton(@NonNull Context context) {
		this(context, null);
	}

	public StopNavigationButton(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public StopNavigationButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		routingHelper = app.getRoutingHelper();
		buttonState = app.getMapButtonsHelper().getStopNavigationButtonState();

		setOnClickListener(v -> {
			mapActivity.getFragmentsHelper().dismissCardDialog();
			mapActivity.getMapActions().stopNavigationActionConfirm(null);
		});
	}

	@Nullable
	@Override
	public MapButtonState getButtonState() {
		return buttonState;
	}

	@Override
	protected void updateColors(boolean nightMode) {
		setIconColor(ColorUtilities.getColor(app, R.color.color_osm_edit_delete));
		setBackgroundColors(ColorUtilities.getMapButtonBackgroundColor(getContext(), nightMode),
				ColorUtilities.getMapButtonBackgroundPressedColor(getContext(), nightMode));
	}

	@Override
	protected boolean shouldShow() {
		return showBottomButtons && (routingHelper.isFollowingMode() || routingHelper.isPauseNavigation());
	}
}
