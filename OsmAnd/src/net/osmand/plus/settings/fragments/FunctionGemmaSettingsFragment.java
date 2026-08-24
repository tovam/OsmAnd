package net.osmand.plus.settings.fragments;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import net.osmand.plus.R;
import net.osmand.plus.search.smart.FunctionGemmaModelManager;
import net.osmand.plus.search.smart.FunctionGemmaModelManager.Snapshot;
import net.osmand.plus.search.smart.FunctionGemmaModelManager.State;
import net.osmand.plus.settings.preferences.EditTextPreferenceEx;
import net.osmand.util.Algorithms;

public class FunctionGemmaSettingsFragment extends BaseSettingsFragment
		implements FunctionGemmaModelManager.Listener {

	private static final String INFO = "functiongemma_info";
	private static final String ENABLED = "functiongemma_enabled";
	private static final String MODEL_URL = "functiongemma_model_url";
	private static final String SHA256 = "functiongemma_model_sha256";
	private static final String DOWNLOAD = "functiongemma_download";
	private static final String STATUS = "functiongemma_status";
	private static final String DELETE = "functiongemma_delete";

	private FunctionGemmaModelManager modelManager;

	@Override
	protected void setupPreferences() {
		modelManager = FunctionGemmaModelManager.get(app);
		setPreferenceIcon(INFO, getContentIcon(R.drawable.ic_action_info_dark));
		setPreferenceIcon(DOWNLOAD, getContentIcon(R.drawable.ic_action_import));
		setPreferenceIcon(DELETE, getContentIcon(R.drawable.ic_action_delete_dark));

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
	}

	@Override
	public void onResume() {
		super.onResume();
		if (modelManager != null) {
			modelManager.addListener(this);
		}
	}

	@Override
	public void onPause() {
		if (modelManager != null) {
			modelManager.removeListener(this);
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
			Snapshot snapshot = modelManager.getSnapshot();
			if (snapshot.state == State.DOWNLOADING || snapshot.state == State.VERIFYING) {
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
		return super.onPreferenceClick(preference);
	}

	@Override
	public void onModelStateChanged(@NonNull Snapshot snapshot) {
		if (isAdded()) {
			updateSnapshot(snapshot);
		}
	}

	private void updateSnapshot(@NonNull Snapshot snapshot) {
		Preference download = findPreference(DOWNLOAD);
		Preference status = findPreference(STATUS);
		Preference delete = findPreference(DELETE);
		if (download == null || status == null || delete == null || modelManager == null) {
			return;
		}
		boolean busy = snapshot.state == State.DOWNLOADING || snapshot.state == State.VERIFYING;
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
