package net.osmand.plus.settings.fragments;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import net.osmand.plus.R;
import net.osmand.plus.search.smart.FunctionGemmaModelManager;
import net.osmand.plus.settings.backend.GitHubAppUpdateManager;
import net.osmand.plus.settings.preferences.EditTextPreferenceEx;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.util.Algorithms;

import java.io.File;

public class FunctionGemmaSettingsFragment extends BaseSettingsFragment
		implements FunctionGemmaModelManager.Listener, GitHubAppUpdateManager.Listener {

	private static final String INFO = "functiongemma_info";
	private static final String ENABLED = "functiongemma_enabled";
	private static final String MODEL_URL = "functiongemma_model_url";
	private static final String SHA256 = "functiongemma_model_sha256";
	private static final String DOWNLOAD = "functiongemma_download";
	private static final String STATUS = "functiongemma_status";
	private static final String DELETE = "functiongemma_delete";
	private static final String APP_UPDATE_STATUS = "smart_app_update_status";
	private static final String APP_UPDATE_ACTION = "smart_app_update_action";
	private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

	private FunctionGemmaModelManager modelManager;
	private GitHubAppUpdateManager appUpdateManager;

	private final ActivityResultLauncher<Intent> installPermissionLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				if (appUpdateManager != null) {
					if (canInstallUnknownApps()) {
						openAndroidInstaller();
					} else {
						appUpdateManager.reportError(getString(R.string.smart_app_update_permission_denied));
					}
				}
			});

	@Override
	protected void setupPreferences() {
		modelManager = FunctionGemmaModelManager.get(app);
		appUpdateManager = GitHubAppUpdateManager.get(app);
		setPreferenceIcon(INFO, getContentIcon(R.drawable.ic_action_info_dark));
		setPreferenceIcon(DOWNLOAD, getContentIcon(R.drawable.ic_action_import));
		setPreferenceIcon(DELETE, getContentIcon(R.drawable.ic_action_delete_dark));
		setPreferenceIcon(APP_UPDATE_ACTION, getContentIcon(R.drawable.ic_action_update));

		SwitchPreferenceCompat enabled = requirePreference(ENABLED);
		enabled.setChecked(modelManager.isEnabled());
		enabled.setIconSpaceReserved(false);

		EditTextPreferenceEx url = requirePreference(MODEL_URL);
		url.setText(modelManager.getSourceUrl());
		url.setDescription(R.string.functiongemma_model_url_description);
		updateUrlSummary(url);

		EditTextPreferenceEx sha256 = requirePreference(SHA256);
		sha256.setText(modelManager.getExpectedSha256());
		sha256.setDescription(R.string.functiongemma_sha256_description);
		updateShaSummary(sha256);

		updateSnapshot(modelManager.getSnapshot());
		updateAppUpdateSnapshot(appUpdateManager.getSnapshot());
	}

	@Override
	public void onResume() {
		super.onResume();
		if (modelManager != null) {
			modelManager.addListener(this);
		}
		if (appUpdateManager != null) {
			appUpdateManager.addListener(this);
		}
	}

	@Override
	public void onPause() {
		if (modelManager != null) {
			modelManager.removeListener(this);
		}
		if (appUpdateManager != null) {
			appUpdateManager.removeListener(this);
		}
		super.onPause();
	}

	@Override
	public boolean onPreferenceChange(Preference preference, Object newValue) {
		if (modelManager == null) {
			return false;
		}
		switch (preference.getKey()) {
			case ENABLED:
				modelManager.setEnabled((Boolean) newValue);
				return true;
			case MODEL_URL:
				modelManager.setSourceUrl(String.valueOf(newValue));
				EditTextPreferenceEx urlPreference = (EditTextPreferenceEx) preference;
				urlPreference.setText(modelManager.getSourceUrl());
				updateUrlSummary(urlPreference);
				return true;
			case SHA256:
				modelManager.setExpectedSha256(String.valueOf(newValue));
				EditTextPreferenceEx shaPreference = (EditTextPreferenceEx) preference;
				shaPreference.setText(modelManager.getExpectedSha256());
				updateShaSummary(shaPreference);
				return true;
			default:
				return super.onPreferenceChange(preference, newValue);
		}
	}

	@Override
	public boolean onPreferenceClick(Preference preference) {
		if (modelManager == null) {
			return false;
		}
		if (DOWNLOAD.equals(preference.getKey())) {
			FunctionGemmaModelManager.Snapshot snapshot = modelManager.getSnapshot();
			if (snapshot.state == FunctionGemmaModelManager.State.DOWNLOADING
					|| snapshot.state == FunctionGemmaModelManager.State.VERIFYING) {
				modelManager.cancelDownload();
			} else {
				modelManager.download();
			}
			return true;
		}
		if (DELETE.equals(preference.getKey())) {
			new AlertDialog.Builder(requireContext())
					.setTitle(R.string.functiongemma_delete_title)
					.setMessage(R.string.functiongemma_delete_confirmation)
					.setNegativeButton(R.string.shared_string_cancel, null)
					.setPositiveButton(R.string.shared_string_delete,
							(dialog, which) -> modelManager.deleteModel())
					.show();
			return true;
		}
		if (APP_UPDATE_ACTION.equals(preference.getKey()) && appUpdateManager != null) {
			GitHubAppUpdateManager.State state = appUpdateManager.getSnapshot().state;
			switch (state) {
				case AVAILABLE:
					appUpdateManager.downloadUpdate();
					break;
				case DOWNLOADING:
					appUpdateManager.cancelDownload();
					break;
				case READY_TO_INSTALL:
					installVerifiedUpdate();
					break;
				case CHECKING:
				case VERIFYING:
					break;
				default:
					appUpdateManager.checkForUpdate();
					break;
			}
			return true;
		}
		return super.onPreferenceClick(preference);
	}

	@Override
	public void onModelStateChanged(@NonNull FunctionGemmaModelManager.Snapshot snapshot) {
		if (isAdded()) {
			updateSnapshot(snapshot);
		}
	}

	@Override
	public void onAppUpdateStateChanged(@NonNull GitHubAppUpdateManager.Snapshot snapshot) {
		if (isAdded()) {
			updateAppUpdateSnapshot(snapshot);
		}
	}

	private void updateSnapshot(@NonNull FunctionGemmaModelManager.Snapshot snapshot) {
		Preference download = findPreference(DOWNLOAD);
		Preference status = findPreference(STATUS);
		Preference delete = findPreference(DELETE);
		if (download == null || status == null || delete == null || modelManager == null) {
			return;
		}
		boolean busy = snapshot.state == FunctionGemmaModelManager.State.DOWNLOADING
				|| snapshot.state == FunctionGemmaModelManager.State.VERIFYING;
		download.setTitle(busy ? R.string.functiongemma_cancel_download : R.string.functiongemma_download_title);
		delete.setEnabled(!busy && modelManager.isInstalled());

		switch (snapshot.state) {
			case NOT_INSTALLED:
				status.setSummary(R.string.functiongemma_status_not_installed);
				break;
			case READY:
				status.setSummary(getString(R.string.functiongemma_status_ready,
						FunctionGemmaModelManager.formatBytes(modelManager.getModelFile().length())));
				break;
			case DOWNLOADING:
				int percent = snapshot.progressPercent();
				if (percent >= 0) {
					status.setSummary(getString(R.string.functiongemma_status_downloading_percent,
							percent, FunctionGemmaModelManager.formatBytes(snapshot.downloadedBytes),
							FunctionGemmaModelManager.formatBytes(snapshot.totalBytes)));
				} else {
					status.setSummary(getString(R.string.functiongemma_status_downloading,
							FunctionGemmaModelManager.formatBytes(snapshot.downloadedBytes)));
				}
				break;
			case VERIFYING:
				status.setSummary(R.string.functiongemma_status_verifying);
				break;
			case ERROR:
				status.setSummary(getString(R.string.functiongemma_status_error,
						Algorithms.isEmpty(snapshot.error) ? getString(R.string.res_unknown) : snapshot.error));
				break;
		}
	}

	private void updateAppUpdateSnapshot(@NonNull GitHubAppUpdateManager.Snapshot snapshot) {
		Preference status = findPreference(APP_UPDATE_STATUS);
		Preference action = findPreference(APP_UPDATE_ACTION);
		if (status == null || action == null || appUpdateManager == null) {
			return;
		}
		String installedName = appUpdateManager.getInstalledVersionName();
		long installedCode = appUpdateManager.getInstalledVersionCode();
		action.setEnabled(snapshot.state != GitHubAppUpdateManager.State.CHECKING
				&& snapshot.state != GitHubAppUpdateManager.State.VERIFYING);

		switch (snapshot.state) {
			case IDLE:
				action.setTitle(R.string.smart_app_update_check);
				status.setSummary(getString(R.string.smart_app_update_status_idle,
						installedName, installedCode));
				break;
			case CHECKING:
				action.setTitle(R.string.smart_app_update_checking);
				status.setSummary(getString(R.string.smart_app_update_status_checking,
						installedName, installedCode));
				break;
			case UP_TO_DATE:
				action.setTitle(R.string.smart_app_update_check_again);
				status.setSummary(getString(R.string.smart_app_update_status_up_to_date,
						installedName, installedCode));
				break;
			case AVAILABLE:
				action.setTitle(R.string.smart_app_update_download);
				status.setSummary(getString(R.string.smart_app_update_status_available,
						safeReleaseTitle(snapshot), snapshot.versionCode,
						GitHubAppUpdateManager.formatBytes(snapshot.totalBytes),
						installedName, installedCode));
				break;
			case DOWNLOADING:
				action.setTitle(R.string.smart_app_update_cancel_download);
				int percent = snapshot.progressPercent();
				if (percent >= 0) {
					status.setSummary(getString(R.string.smart_app_update_status_downloading_percent,
							percent, GitHubAppUpdateManager.formatBytes(snapshot.downloadedBytes),
							GitHubAppUpdateManager.formatBytes(snapshot.totalBytes)));
				} else {
					status.setSummary(getString(R.string.smart_app_update_status_downloading,
							GitHubAppUpdateManager.formatBytes(snapshot.downloadedBytes)));
				}
				break;
			case VERIFYING:
				action.setTitle(R.string.smart_app_update_verifying);
				status.setSummary(R.string.smart_app_update_status_verifying);
				break;
			case READY_TO_INSTALL:
				action.setTitle(R.string.smart_app_update_install);
				if (Algorithms.isEmpty(snapshot.error)) {
					status.setSummary(getString(R.string.smart_app_update_status_ready,
							safeReleaseTitle(snapshot), snapshot.versionCode));
				} else {
					status.setSummary(getString(R.string.smart_app_update_status_ready_after_error,
							snapshot.error, safeReleaseTitle(snapshot), snapshot.versionCode));
				}
				break;
			case ERROR:
				action.setTitle(R.string.smart_app_update_retry);
				status.setSummary(getString(R.string.smart_app_update_status_error,
						Algorithms.isEmpty(snapshot.error) ? getString(R.string.res_unknown) : snapshot.error,
						installedName, installedCode));
				break;
		}
	}

	@NonNull
	private String safeReleaseTitle(@NonNull GitHubAppUpdateManager.Snapshot snapshot) {
		return Algorithms.isEmpty(snapshot.releaseTitle)
				? (Algorithms.isEmpty(snapshot.tagName) ? "?" : snapshot.tagName)
				: snapshot.releaseTitle;
	}

	private void installVerifiedUpdate() {
		if (appUpdateManager == null) {
			return;
		}
		if (!canInstallUnknownApps()) {
			new AlertDialog.Builder(requireContext())
					.setTitle(R.string.smart_app_update_permission_title)
					.setMessage(R.string.smart_app_update_permission_message)
					.setNegativeButton(R.string.shared_string_cancel, null)
					.setPositiveButton(R.string.smart_app_update_open_settings,
							(dialog, which) -> openUnknownSourcesSettings())
					.show();
			return;
		}
		openAndroidInstaller();
	}

	private boolean canInstallUnknownApps() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
				|| app.getPackageManager().canRequestPackageInstalls();
	}

	private void openUnknownSourcesSettings() {
		Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
				Uri.parse("package:" + app.getPackageName()));
		if (intent.resolveActivity(app.getPackageManager()) == null) {
			appUpdateManager.reportError(getString(R.string.smart_app_update_settings_unavailable));
			return;
		}
		installPermissionLauncher.launch(intent);
	}

	private void openAndroidInstaller() {
		File apk = appUpdateManager == null ? null : appUpdateManager.getVerifiedApk();
		if (apk == null) {
			if (appUpdateManager != null) {
				appUpdateManager.reportError(getString(R.string.smart_app_update_apk_missing));
			}
			return;
		}
		try {
			Uri uri = AndroidUtils.getUriForFile(requireContext(), apk);
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setDataAndType(uri, APK_MIME_TYPE);
			intent.setClipData(ClipData.newRawUri("OsmAnd Smart update", uri));
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			if (intent.resolveActivity(app.getPackageManager()) == null) {
				appUpdateManager.reportError(getString(R.string.smart_app_update_installer_unavailable));
				return;
			}
			startActivity(intent);
		} catch (ActivityNotFoundException | IllegalArgumentException | SecurityException e) {
			appUpdateManager.reportError(getString(R.string.smart_app_update_installer_error,
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
		}
	}

	private void updateUrlSummary(@NonNull EditTextPreferenceEx preference) {
		String value = modelManager.getSourceUrl();
		preference.setSummary(Algorithms.isEmpty(value)
				? getString(R.string.functiongemma_model_url_empty) : value);
	}

	private void updateShaSummary(@NonNull EditTextPreferenceEx preference) {
		String value = modelManager.getExpectedSha256();
		preference.setSummary(Algorithms.isEmpty(value)
				? getString(R.string.functiongemma_sha256_optional) : value);
	}
}
