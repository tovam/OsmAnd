package net.osmand.plus.settings.backend;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manual updater for the public OsmAnd Smart GitHub prereleases.
 *
 * The downloaded APK is never offered to Android until its package name,
 * monotonically increasing version code and signing certificate have all been
 * checked against the currently installed application.
 */
public final class GitHubAppUpdateManager {

	public enum State {
		IDLE,
		CHECKING,
		UP_TO_DATE,
		AVAILABLE,
		DOWNLOADING,
		VERIFYING,
		READY_TO_INSTALL,
		ERROR
	}

	public interface Listener {
		void onAppUpdateStateChanged(@NonNull Snapshot snapshot);
	}

	public static final class Snapshot {
		@NonNull
		public final State state;
		@Nullable
		public final String releaseTitle;
		@Nullable
		public final String tagName;
		public final long versionCode;
		public final long downloadedBytes;
		public final long totalBytes;
		@Nullable
		public final String error;

		private Snapshot(@NonNull State state, @Nullable Release release,
		                 long downloadedBytes, long totalBytes, @Nullable String error) {
			this.state = state;
			this.releaseTitle = release == null ? null : release.title;
			this.tagName = release == null ? null : release.tagName;
			this.versionCode = release == null ? -1 : release.versionCode;
			this.downloadedBytes = downloadedBytes;
			this.totalBytes = totalBytes;
			this.error = error;
		}

		public int progressPercent() {
			return totalBytes > 0 ? (int) Math.min(100, downloadedBytes * 100 / totalBytes) : -1;
		}
	}

	private static final String RELEASES_API =
			"https://api.github.com/repos/tovam/OsmAnd/releases?per_page=100";
	private static final String REQUIRED_DOWNLOAD_PREFIX =
			"https://github.com/tovam/OsmAnd/releases/download/";
	private static final Pattern RELEASE_TAG = Pattern.compile("^smart-build-(\\d+)-(\\d+)$");
	private static final long VERSION_CODE_BASE = 6_000_000L;
	private static final long MAX_APK_BYTES = 750L * 1024L * 1024L;
	private static final long FREE_SPACE_MARGIN_BYTES = 128L * 1024L * 1024L;
	private static final int MAX_API_RESPONSE_BYTES = 2 * 1024 * 1024;
	private static final int CONNECT_TIMEOUT_MS = 15_000;
	private static final int READ_TIMEOUT_MS = 30_000;
	private static final int MAX_REDIRECTS = 5;
	private static final String DIRECTORY = "app-updates";
	private static final String APK_FILENAME = "osmand-smart-update.apk";
	private static final String PARTIAL_APK_FILENAME = "osmand-smart-update.part.apk";
	private static final byte[] ZIP_MAGIC = new byte[] {'P', 'K', 3, 4};

	private static volatile GitHubAppUpdateManager instance;

	private final Context context;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
	private final AtomicBoolean cancelRequested = new AtomicBoolean();
	private volatile Snapshot snapshot = new Snapshot(State.IDLE, null, 0, 0, null);
	@Nullable
	private volatile Release availableRelease;
	@Nullable
	private volatile File verifiedApk;

	private GitHubAppUpdateManager(@NonNull Context context) {
		this.context = context.getApplicationContext();
		// A completed installation restarts the app. Clear its now-obsolete cached
		// APK (and any interrupted partial file) on the next manager creation.
		executor.execute(this::clearCachedUpdateFiles);
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

	public void checkForUpdate() {
		State state = snapshot.state;
		if (state == State.CHECKING || state == State.DOWNLOADING || state == State.VERIFYING) {
			return;
		}
		cancelRequested.set(false);
		availableRelease = null;
		verifiedApk = null;
		publish(State.CHECKING, null, 0, 0, null);
		executor.execute(this::runCheck);
	}

	public void downloadUpdate() {
		Release release = availableRelease;
		if (release == null || snapshot.state != State.AVAILABLE) {
			return;
		}
		cancelRequested.set(false);
		publish(State.DOWNLOADING, release, 0, release.size, null);
		executor.execute(() -> runDownload(release));
	}

	public void cancelDownload() {
		if (snapshot.state == State.DOWNLOADING) {
			cancelRequested.set(true);
		}
	}

	@Nullable
	public File getVerifiedApk() {
		File file = verifiedApk;
		return snapshot.state == State.READY_TO_INSTALL && file != null && file.isFile() ? file : null;
	}

	public void reportError(@NonNull String error) {
		File apk = verifiedApk;
		if (apk != null && apk.isFile()) {
			publish(State.READY_TO_INSTALL, availableRelease, apk.length(), apk.length(), error);
		} else {
			publish(State.ERROR, availableRelease, 0, 0, error);
		}
	}

	public long getInstalledVersionCode() {
		try {
			return versionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
		} catch (PackageManager.NameNotFoundException e) {
			return -1;
		}
	}

	@NonNull
	public String getInstalledVersionName() {
		try {
			String versionName = context.getPackageManager()
					.getPackageInfo(context.getPackageName(), 0).versionName;
			return versionName == null ? "?" : versionName;
		} catch (PackageManager.NameNotFoundException e) {
			return "?";
		}
	}

	private void runCheck() {
		HttpURLConnection connection = null;
		try {
			connection = openFollowingRedirects(RELEASES_API, true);
			int status = connection.getResponseCode();
			if (status < 200 || status >= 300) {
				if (status == 403 && "0".equals(connection.getHeaderField("X-RateLimit-Remaining"))) {
					throw new IOException("Limite de vérification GitHub atteinte. Réessaie plus tard.");
				}
				throw new IOException("GitHub a répondu HTTP " + status);
			}
			JSONArray releases = new JSONArray(readUtf8(connection.getInputStream(), MAX_API_RESPONSE_BYTES));
			long installedVersion = getInstalledVersionCode();
			if (installedVersion < 0) {
				throw new IOException("Impossible de lire la version installée");
			}
			Release best = null;
			for (int index = 0; index < releases.length(); index++) {
				Release release = parseRelease(releases.getJSONObject(index));
				if (release != null && release.versionCode > installedVersion
						&& (best == null || release.versionCode > best.versionCode)) {
					best = release;
				}
			}
			availableRelease = best;
			publish(best == null ? State.UP_TO_DATE : State.AVAILABLE, best, 0,
					best == null ? 0 : best.size, null);
		} catch (Exception e) {
			publish(State.ERROR, null, 0, 0, safeMessage(e));
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@Nullable
	private Release parseRelease(@NonNull JSONObject json) {
		if (json.optBoolean("draft", true) || !json.optBoolean("prerelease", false)) {
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
			JSONObject apk = null;
			for (int index = 0; index < assets.length(); index++) {
				JSONObject asset = assets.optJSONObject(index);
				String name = asset == null ? "" : asset.optString("name", "");
				if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
					if (apk != null) {
						return null;
					}
					apk = asset;
				}
			}
			if (apk == null) {
				return null;
			}
			String url = apk.optString("browser_download_url", "");
			long size = apk.optLong("size", -1);
			if (!url.startsWith(REQUIRED_DOWNLOAD_PREFIX) || size <= 0 || size > MAX_APK_BYTES) {
				return null;
			}
			String title = json.optString("name", tag).trim();
			return new Release(title.isEmpty() ? tag : title, tag, url, versionCode, size);
		} catch (ArithmeticException | NumberFormatException e) {
			return null;
		}
	}

	private void runDownload(@NonNull Release release) {
		File directory = new File(context.getCacheDir(), DIRECTORY);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			publish(State.ERROR, release, 0, 0, "Impossible de créer le dossier de mise à jour");
			return;
		}
		File partial = new File(directory, PARTIAL_APK_FILENAME);
		File target = new File(directory, APK_FILENAME);
		if (partial.exists() && !partial.delete()) {
			publish(State.ERROR, release, 0, 0, "Impossible de remplacer le téléchargement incomplet");
			return;
		}
		HttpURLConnection connection = null;
		try {
			ensureFreeSpace(directory, release.size + FREE_SPACE_MARGIN_BYTES);
			publish(State.DOWNLOADING, release, 0, release.size, null);
			connection = openFollowingRedirects(release.downloadUrl, false);
			int status = connection.getResponseCode();
			if (status < 200 || status >= 300) {
				throw new IOException("Téléchargement HTTP " + status);
			}
			long responseSize = connection.getContentLengthLong();
			if (responseSize > MAX_APK_BYTES) {
				throw new IOException("L’APK dépasse la taille autorisée");
			}
			long downloaded = copyDownload(connection.getInputStream(), partial, release);
			if (cancelRequested.get()) {
				throw new DownloadCancelledException();
			}
			if (downloaded != release.size) {
				throw new IOException("Téléchargement incomplet (taille inattendue)");
			}
			publish(State.VERIFYING, release, downloaded, release.size, null);
			verifyApk(partial, release.versionCode);
			Os.rename(partial.getAbsolutePath(), target.getAbsolutePath());
			verifiedApk = target;
			publish(State.READY_TO_INSTALL, release, target.length(), target.length(), null);
		} catch (DownloadCancelledException e) {
			if (partial.exists()) {
				partial.delete();
			}
			publish(State.AVAILABLE, release, 0, release.size, null);
		} catch (Exception e) {
			if (partial.exists()) {
				partial.delete();
			}
			verifiedApk = null;
			publish(State.ERROR, release, 0, release.size, safeMessage(e));
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private long copyDownload(@NonNull InputStream source, @NonNull File target,
	                          @NonNull Release release)
			throws IOException, DownloadCancelledException {
		long downloaded = 0;
		long lastPublishedAt = 0;
		byte[] buffer = new byte[128 * 1024];
		try (InputStream input = new BufferedInputStream(source);
		     BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
			int count;
			while ((count = input.read(buffer)) != -1) {
				if (cancelRequested.get()) {
					throw new DownloadCancelledException();
				}
				downloaded += count;
				if (downloaded > MAX_APK_BYTES || downloaded > release.size) {
					throw new IOException("Le téléchargement dépasse la taille annoncée");
				}
				output.write(buffer, 0, count);
				long now = System.currentTimeMillis();
				if (now - lastPublishedAt >= 250) {
					publish(State.DOWNLOADING, release, downloaded, release.size, null);
					lastPublishedAt = now;
				}
			}
			output.flush();
		}
		return downloaded;
	}

	private void verifyApk(@NonNull File apk, long expectedVersionCode) throws Exception {
		if (apk.length() < ZIP_MAGIC.length) {
			throw new IOException("L’APK téléchargé est vide");
		}
		byte[] header = new byte[ZIP_MAGIC.length];
		try (InputStream input = new FileInputStream(apk)) {
			if (input.read(header) != header.length || !Arrays.equals(header, ZIP_MAGIC)) {
				throw new IOException("Le fichier téléchargé n’est pas un APK");
			}
		}
		PackageManager packageManager = context.getPackageManager();
		int signatureFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
				? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
		PackageInfo archive = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), signatureFlags);
		if (archive == null) {
			throw new IOException("Android ne reconnaît pas l’APK téléchargé");
		}
		if (!context.getPackageName().equals(archive.packageName)) {
			throw new IOException("L’APK appartient à une autre application");
		}
		long installedVersion = getInstalledVersionCode();
		long archiveVersion = versionCode(archive);
		if (archiveVersion != expectedVersionCode || archiveVersion <= installedVersion) {
			throw new IOException("La version de l’APK n’est pas une mise à jour valide");
		}
		PackageInfo installed = packageManager.getPackageInfo(context.getPackageName(), signatureFlags);
		Set<String> installedCertificates = signingCertificates(installed);
		Set<String> archiveCertificates = signingCertificates(archive);
		if (installedCertificates.isEmpty() || !installedCertificates.equals(archiveCertificates)) {
			throw new IOException("La signature de l’APK ne correspond pas à l’application installée");
		}
	}

	@NonNull
	private static Set<String> signingCertificates(@NonNull PackageInfo info) throws Exception {
		Signature[] signatures;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			SigningInfo signingInfo = info.signingInfo;
			signatures = signingInfo == null ? null : signingInfo.getApkContentsSigners();
		} else {
			signatures = info.signatures;
		}
		Set<String> certificates = new HashSet<>();
		if (signatures != null) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Signature signature : signatures) {
				certificates.add(toHex(digest.digest(signature.toByteArray())));
				digest.reset();
			}
		}
		return certificates;
	}

	@NonNull
	private HttpURLConnection openFollowingRedirects(@NonNull String initialUrl, boolean apiRequest)
			throws IOException {
		URL url = URI.create(initialUrl).toURL();
		for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
			if (!"https".equalsIgnoreCase(url.getProtocol())) {
				throw new IOException("Redirection non sécurisée refusée");
			}
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("User-Agent", "OsmAnd-Smart-Updater/1");
			connection.setRequestProperty("Accept", apiRequest
					? "application/vnd.github+json" : "application/octet-stream");
			if (apiRequest) {
				connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
			}
			int status = connection.getResponseCode();
			if (status < 300 || status >= 400) {
				return connection;
			}
			String location = connection.getHeaderField("Location");
			connection.disconnect();
			if (location == null) {
				throw new IOException("Redirection sans destination");
			}
			url = new URL(url, location);
		}
		throw new IOException("Trop de redirections HTTP");
	}

	private void ensureFreeSpace(@NonNull File directory, long requiredBytes) throws IOException {
		long available = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
		if (available < requiredBytes) {
			throw new IOException("Espace insuffisant : " + formatBytes(requiredBytes)
					+ " requis, " + formatBytes(available) + " disponibles");
		}
	}

	private void clearCachedUpdateFiles() {
		File directory = new File(context.getCacheDir(), DIRECTORY);
		File partial = new File(directory, PARTIAL_APK_FILENAME);
		File apk = new File(directory, APK_FILENAME);
		if (partial.isFile()) {
			partial.delete();
		}
		if (apk.isFile()) {
			apk.delete();
		}
	}

	@NonNull
	private static String readUtf8(@NonNull InputStream source, int maxBytes) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[16 * 1024];
		try (InputStream input = new BufferedInputStream(source)) {
			int count;
			while ((count = input.read(buffer)) != -1) {
				if (output.size() + count > maxBytes) {
					throw new IOException("Réponse GitHub anormalement grande");
				}
				output.write(buffer, 0, count);
			}
		}
		return output.toString("UTF-8");
	}

	private void publish(@NonNull State state, @Nullable Release release,
	                     long downloaded, long total, @Nullable String error) {
		Snapshot next = new Snapshot(state, release, downloaded, total, error);
		snapshot = next;
		mainHandler.post(() -> {
			for (Listener listener : listeners) {
				listener.onAppUpdateStateChanged(next);
			}
		});
	}

	private static long versionCode(@NonNull PackageInfo info) {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
				? info.getLongVersionCode() : info.versionCode;
	}

	@NonNull
	private static String toHex(@NonNull byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
		}
		return result.toString();
	}

	@NonNull
	private static String safeMessage(@NonNull Exception e) {
		String message = e.getMessage();
		return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
	}

	@NonNull
	public static String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " o";
		}
		double value = bytes;
		String[] units = {"o", "Ko", "Mo", "Go"};
		int unit = 0;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
	}

	private static final class Release {
		@NonNull
		final String title;
		@NonNull
		final String tagName;
		@NonNull
		final String downloadUrl;
		final long versionCode;
		final long size;

		Release(@NonNull String title, @NonNull String tagName, @NonNull String downloadUrl,
		        long versionCode, long size) {
			this.title = title;
			this.tagName = tagName;
			this.downloadUrl = downloadUrl;
			this.versionCode = versionCode;
			this.size = size;
		}
	}

	private static final class DownloadCancelledException extends Exception {
	}
}
