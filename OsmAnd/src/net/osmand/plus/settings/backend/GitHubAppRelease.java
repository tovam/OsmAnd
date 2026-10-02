package net.osmand.plus.settings.backend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Published, installable releases from this fork, using the CI version-code scheme. */
public final class GitHubAppRelease {

	public static final String RELEASES_PAGE = "https://github.com/tovam/OsmAnd/releases";
	public static final String RELEASES_API = "https://api.github.com/repos/tovam/OsmAnd/releases?per_page=100";
	private static final String APK_NAME = "OsmAnd-androidFull-opengl-arm64-debug.apk";
	private static final Pattern RELEASE_TAG = Pattern.compile("^smart-build-(\\d+)-(\\d+)$");
	private static final long VERSION_CODE_BASE = 6_000_000L;
	private static final long MAX_APK_BYTES = 750L * 1024L * 1024L;

	public final String title;
	public final String tagName;
	public final String pageUrl;
	public final String notes;
	public final long versionCode;

	private GitHubAppRelease(String title, String tagName, String notes, long versionCode) {
		this.title = title;
		this.tagName = tagName;
		this.pageUrl = RELEASES_PAGE + "/tag/" + tagName;
		this.notes = notes;
		this.versionCode = versionCode;
	}

	public static GitHubAppRelease latestNewer(JSONArray releases, long installedVersion) {
		GitHubAppRelease best = null;
		for (int index = 0; index < releases.length(); index++) {
			JSONObject json = releases.optJSONObject(index);
			GitHubAppRelease release = json == null ? null : parse(json);
			if (release != null && release.versionCode > installedVersion
					&& (best == null || release.versionCode > best.versionCode)) {
				best = release;
			}
		}
		return best;
	}

	public static GitHubAppRelease parse(JSONObject json) {
		if (json.optBoolean("draft", true)) {
			return null;
		}
		String tag = json.optString("tag_name", "");
		Matcher matcher = RELEASE_TAG.matcher(tag);
		if (!matcher.matches()) {
			return null;
		}
		try {
			long runNumber = Long.parseLong(matcher.group(1));
			long runAttempt = Long.parseLong(matcher.group(2));
			if (runNumber <= 0 || runAttempt <= 0 || runAttempt > 9) {
				return null;
			}
			long versionCode = Math.addExact(VERSION_CODE_BASE,
					Math.addExact(Math.multiplyExact(runNumber, 10L), runAttempt));
			JSONArray assets = json.optJSONArray("assets");
			if (assets == null) {
				return null;
			}
			String expectedDownload = RELEASES_PAGE + "/download/" + tag + "/" + APK_NAME;
			boolean apkReady = false;
			for (int index = 0; index < assets.length(); index++) {
				JSONObject asset = assets.optJSONObject(index);
				if (asset != null && APK_NAME.equals(asset.optString("name"))
						&& "uploaded".equals(asset.optString("state"))
						&& expectedDownload.equals(asset.optString("browser_download_url"))) {
					long size = asset.optLong("size", -1);
					apkReady = size > 0 && size <= MAX_APK_BYTES;
					if (apkReady) {
						break;
					}
				}
			}
			if (!apkReady) {
				return null;
			}
			String title = json.isNull("name") ? "" : json.optString("name", "").trim();
			String notes = json.isNull("body") ? "" : json.optString("body", "").trim();
			return new GitHubAppRelease(title.isEmpty() ? tag : title, tag, notes, versionCode);
		} catch (ArithmeticException | NumberFormatException e) {
			return null;
		}
	}
}
