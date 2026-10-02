import net.osmand.plus.settings.backend.GitHubAppRelease;
import org.json.JSONArray;
import org.json.JSONObject;

public class GitHubAppReleaseTest {
	private static int assertions;
	private static final String APK = "OsmAnd-androidFull-opengl-arm64-debug.apk";

	public static void main(String[] args) throws Exception {
		JSONObject newer = release("smart-build-132-1");
		GitHubAppRelease parsed = GitHubAppRelease.parse(newer);
		check(parsed != null && parsed.versionCode == 6_001_321L, "CI version code");
		check(parsed.pageUrl.equals("https://github.com/tovam/OsmAnd/releases/tag/smart-build-132-1"), "Owned release page");
		check(parsed.notes.equals("New update screen"), "Trimmed release notes");
		check(parsed.title.equals("132.1 — OsmAnd Smart OpenGL"), "Release title");
		newer.put("html_url", "https://example.invalid/other-fork");
		check(GitHubAppRelease.parse(newer).pageUrl.equals(parsed.pageUrl), "Untrusted page cannot replace fork link");

		JSONArray releases = new JSONArray().put(release("smart-build-99-1"))
				.put(release("smart-build-100-1")).put("malformed entry").put(release("smart-build-100-2"));
		check(GitHubAppRelease.latestNewer(releases, 6_000_991L).versionCode == 6_001_002L, "Numeric order and retry");
		check(GitHubAppRelease.latestNewer(releases, 6_001_002L) == null, "Installed build is not an update");
		check(GitHubAppRelease.latestNewer(releases, 6_001_003L) == null, "Older release is not an update");
		check(GitHubAppRelease.latestNewer(new JSONArray(), 6_000_001L) == null, "No published releases");
		check(GitHubAppRelease.parse(release("smart-build-132-1").put("prerelease", false)) != null, "Stable fork releases also accepted");
		check(GitHubAppRelease.parse(release("smart-build-132-1").put("draft", true)) == null, "Draft ignored");
		for (String tag : new String[] {"v5.0", "smart-build-0-1", "smart-build-132-0", "smart-build-132-10",
				"smart-build-9223372036854775807-1", "smart-build-99999999999999999999999999-1"}) {
			check(GitHubAppRelease.parse(release(tag)) == null, "Invalid tag: " + tag);
		}
		JSONObject incomplete = release("smart-build-133-1");
		asset(incomplete).put("state", "new");
		check(GitHubAppRelease.parse(incomplete) == null, "Incomplete upload ignored");
		check(GitHubAppRelease.latestNewer(new JSONArray().put(incomplete).put(newer), 6_001_311L).versionCode == 6_001_321L,
				"Newest usable release selected while later upload is incomplete");
		for (String url : new String[] {"https://github.com/osmandapp/OsmAnd/releases/download/smart-build-132-1/" + APK,
				"https://github.com/tovam/OsmAnd/releases/download/smart-build-131-1/" + APK,
				"http://github.com/tovam/OsmAnd/releases/download/smart-build-132-1/" + APK}) {
			JSONObject wrong = release("smart-build-132-1");
			asset(wrong).put("browser_download_url", url);
			check(GitHubAppRelease.parse(wrong) == null, "Wrong APK origin ignored");
		}
		JSONObject wrongArchitecture = release("smart-build-132-1");
		asset(wrongArchitecture).put("name", "OsmAnd-x86.apk");
		check(GitHubAppRelease.parse(wrongArchitecture) == null, "Wrong architecture ignored");
		for (long size : new long[] {0, -1, 751L * 1024L * 1024L}) {
			JSONObject wrongSize = release("smart-build-132-1");
			asset(wrongSize).put("size", size);
			check(GitHubAppRelease.parse(wrongSize) == null, "Invalid APK size ignored");
		}
		check(GitHubAppRelease.parse(release("smart-build-132-1").put("assets", new JSONArray())) == null, "Missing APK ignored");
		JSONObject optionalText = release("smart-build-132-1").put("name", JSONObject.NULL).put("body", JSONObject.NULL);
		check(GitHubAppRelease.parse(optionalText).title.equals("smart-build-132-1"), "Missing title falls back to tag");
		check(GitHubAppRelease.parse(optionalText).notes.isEmpty(), "Missing notes are empty");
		System.out.println("GitHub app release checks passed: " + assertions);
	}

	private static JSONObject release(String tag) throws Exception {
		JSONObject apk = new JSONObject().put("name", APK).put("state", "uploaded").put("size", 200_000_000L)
				.put("browser_download_url", GitHubAppRelease.RELEASES_PAGE + "/download/" + tag + "/" + APK);
		return new JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", true)
				.put("name", "132.1 — OsmAnd Smart OpenGL").put("body", "  New update screen\n ")
				.put("assets", new JSONArray().put(apk));
	}

	private static JSONObject asset(JSONObject release) throws Exception {
		return release.getJSONArray("assets").getJSONObject(0);
	}

	private static void check(boolean condition, String message) {
		assertions++;
		if (!condition) throw new AssertionError(message);
	}
}
