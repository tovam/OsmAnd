package net.osmand.plus.settings.backend;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks published builds of this fork; installation is handled from its GitHub release page. */
public final class GitHubAppUpdateManager {

	public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, ERROR }

	public interface Listener {
		void onAppUpdateStateChanged(@NonNull Snapshot snapshot);
	}

	public static final class Snapshot {
		@NonNull public final State state;
		@Nullable public final String releaseTitle;
		@Nullable public final String releasePageUrl;
		@Nullable public final String releaseNotes;
		@Nullable public final String error;

		private Snapshot(@NonNull State state, @Nullable GitHubAppRelease release, @Nullable String error) {
			this.state = state;
			releaseTitle = release == null ? null : release.title;
			releasePageUrl = release == null ? null : release.pageUrl;
			releaseNotes = release == null ? null : release.notes;
			this.error = error;
		}
	}

	private static final int MAX_API_RESPONSE_BYTES = 2 * 1024 * 1024;
	private static volatile GitHubAppUpdateManager instance;
	private final Context context;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
	private volatile Snapshot snapshot = new Snapshot(State.IDLE, null, null);

	private GitHubAppUpdateManager(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	@NonNull
	public static GitHubAppUpdateManager get(@NonNull Context context) {
		GitHubAppUpdateManager result = instance;
		if (result == null) {
			synchronized (GitHubAppUpdateManager.class) {
				result = instance;
				if (result == null) {
					result = new GitHubAppUpdateManager(context);
					instance = result;
				}
			}
		}
		return result;
	}

	@NonNull
	public Snapshot getSnapshot() {
		return snapshot;
	}

	public void addListener(@NonNull Listener listener) {
		listeners.add(listener);
		listener.onAppUpdateStateChanged(snapshot);
	}

	public void removeListener(@NonNull Listener listener) {
		listeners.remove(listener);
	}

	public synchronized void checkForUpdate() {
		if (snapshot.state == State.CHECKING) {
			return;
		}
		publish(State.CHECKING, null, null);
		executor.execute(this::runCheck);
	}

	public long getInstalledVersionCode() {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
		} catch (PackageManager.NameNotFoundException e) {
			return -1;
		}
	}

	@NonNull
	public String getInstalledVersionName() {
		try {
			String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
			return name == null ? "?" : name;
		} catch (PackageManager.NameNotFoundException e) {
			return "?";
		}
	}

	private void runCheck() {
		HttpURLConnection connection = null;
		try {
			long installed = getInstalledVersionCode();
			if (installed < 0) {
				publish(State.ERROR, null, context.getString(R.string.fork_app_update_version_error));
				return;
			}
			connection = (HttpURLConnection) new URL(GitHubAppRelease.RELEASES_API).openConnection();
			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(15_000);
			connection.setReadTimeout(30_000);
			connection.setRequestProperty("User-Agent", "OsmAnd-Smart-Updater/1");
			connection.setRequestProperty("Accept", "application/vnd.github+json");
			connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
			int status = connection.getResponseCode();
			if (status != HttpURLConnection.HTTP_OK) {
				boolean rateLimited = status == 429 || (status == 403
						&& "0".equals(connection.getHeaderField("X-RateLimit-Remaining")));
				publish(State.ERROR, null, rateLimited
						? context.getString(R.string.fork_app_update_rate_limit)
						: context.getString(R.string.fork_app_update_http_error, status));
				return;
			}
			JSONArray releases = new JSONArray(readUtf8(connection.getInputStream()));
			GitHubAppRelease release = GitHubAppRelease.latestNewer(releases, installed);
			publish(release == null ? State.UP_TO_DATE : State.AVAILABLE, release, null);
		} catch (JSONException e) {
			publish(State.ERROR, null, context.getString(R.string.fork_app_update_response_error));
		} catch (IOException | SecurityException e) {
			publish(State.ERROR, null, context.getString(R.string.fork_app_update_network_error));
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@NonNull
	private static String readUtf8(@NonNull InputStream source) throws IOException, JSONException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[16 * 1024];
		try (InputStream input = new BufferedInputStream(source)) {
			int count;
			while ((count = input.read(buffer)) != -1) {
				if (output.size() + count > MAX_API_RESPONSE_BYTES) {
					throw new JSONException("Response exceeds maximum size");
				}
				output.write(buffer, 0, count);
			}
		}
		return output.toString("UTF-8");
	}

	private void publish(@NonNull State state, @Nullable GitHubAppRelease release, @Nullable String error) {
		Snapshot next = new Snapshot(state, release, error);
		snapshot = next;
		mainHandler.post(() -> {
			for (Listener listener : listeners) {
				listener.onAppUpdateStateChanged(next);
			}
		});
	}
}
