package net.osmand.plus.feedback;

import static net.osmand.aidlapi.OsmAndCustomizationConstants.FRAGMENT_CRASH_ID;
import static net.osmand.plus.feedback.FeedbackHelper.EXCEPTION_PATH;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import net.osmand.aidlapi.OsmAndCustomizationConstants;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.MenuBottomSheetDialogFragment;
import net.osmand.plus.base.bottomsheetmenu.SimpleBottomSheetItem;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.utils.AndroidUtils;

public class CrashBottomSheetDialogFragment extends MenuBottomSheetDialogFragment {

	private static final String TAG = OsmAndCustomizationConstants.FRAGMENT_CRASH_ID;

	@Override
	public void createMenuItems(Bundle savedInstanceState) {
		View titleView = inflate(R.layout.crash_title);
		TextView crashReport = titleView.findViewById(R.id.crash_report);
		String report = app.getFeedbackHelper().getCopyableCrashReport();
		crashReport.setText(report);
		titleView.findViewById(R.id.copy_crash_report).setOnClickListener(v -> copyReport(report));
		items.add(new SimpleBottomSheetItem.Builder().setCustomView(titleView).create());
	}

	private void copyReport(@NonNull String report) {
		Context context = getContext();
		if (context == null) {
			return;
		}
		ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			clipboard.setPrimaryClip(ClipData.newPlainText(EXCEPTION_PATH, report));
			app.showToastMessage(R.string.copied_to_clipboard);
		}
	}

	@Override
	protected int getRightBottomButtonTextId() {
		return R.string.shared_string_send;
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
