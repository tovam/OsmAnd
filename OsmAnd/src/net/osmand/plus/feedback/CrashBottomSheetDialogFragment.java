package net.osmand.plus.feedback;

import static net.osmand.aidlapi.OsmAndCustomizationConstants.FRAGMENT_CRASH_ID;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import net.osmand.aidlapi.OsmAndCustomizationConstants;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.MenuBottomSheetDialogFragment;
import net.osmand.plus.base.bottomsheetmenu.simpleitems.LongDescriptionItem;
import net.osmand.plus.base.bottomsheetmenu.simpleitems.TitleItem;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.utils.AndroidUtils;

public class CrashBottomSheetDialogFragment extends MenuBottomSheetDialogFragment {

	private static final String TAG = OsmAndCustomizationConstants.FRAGMENT_CRASH_ID;

	@Override
	public void createMenuItems(Bundle savedInstanceState) {
		items.add(new TitleItem(getString(R.string.shared_string_crash)));
		items.add(new LongDescriptionItem(getString(R.string.local_report_previous_crash)));
	}

	@Override
	protected int getRightBottomButtonTextId() {
		return R.string.local_crash_report;
	}

	@Override
	protected void onRightBottomButtonClick() {
		app.getFeedbackHelper().sendCrashLog();
		dismiss();
	}

	public static boolean shouldShow(@Nullable OsmandSettings settings, @NonNull MapActivity activity) {
		OsmandApplication app = activity.getApp();
		if (app.getAppCustomization().isFeatureEnabled(FRAGMENT_CRASH_ID)) {
			return !app.getRoutingHelper().isFollowingMode()
					&& app.getAppInitializer().checkPreviousRunsForExceptions(activity, settings != null);
		}
		return false;
	}

	public static void showInstance(@NonNull FragmentManager fragmentManager) {
		if (AndroidUtils.isFragmentCanBeAdded(fragmentManager, TAG)) {
			CrashBottomSheetDialogFragment fragment = new CrashBottomSheetDialogFragment();
			fragment.show(fragmentManager, TAG);
		}
	}
}
