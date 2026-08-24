package net.osmand.plus.search.smart;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FunctionGemmaModelManager {

	public enum State {
		NOT_INSTALLED,
		READY,
		DOWNLOADING,
		VERIFYING,
		ERROR
	}

	public interface Listener {
		void onModelStateChanged(@NonNull Snapshot snapshot);
	}

	public static final class Snapshot {
		public final State state;
		public final long downloadedBytes;
		public final long totalBytes;
		@Nullable
		public final String error;

		private Snapshot(@NonNull State state, long downloadedBytes, long totalBytes,
		                 @Nullable String error) {
			this.state = state;
			this.downloadedBytes = downloadedBytes;
			this.totalBytes = totalBytes;
			this.error = error;
		}

		public int progressPercent() {
			return totalBytes > 0 ? (int) Math.min(100, downloadedBytes * 100 / totalBytes) : -1;
		}
	}

	private static final String PREFERENCES = "functiongemma";
	private static final String KEY_URL = "model_url";
	private static final String KEY_SHA256 = "model_sha256";
	private static final String KEY_ENABLED = "enabled";
	private static final String DIRECTORY = "functiongemma";
	private static final String MODEL_FILENAME = "functiongemma-osmand.litertlm";
	private static final String TEMP_FILENAME = MODEL_FILENAME + ".part";
	private static final byte[] MAGIC = new byte[] {'L', 'I', 'T', 'E', 'R', 'T', 'L', 'M'};
	private static final long MAX_MODEL_BYTES = 1_500L * 1024L * 1024L;
	private static final long FREE_SPACE_MARGIN_BYTES = 128L * 1024L * 1024L;
	private static final int CONNECT_TIMEOUT_MS = 15_000;
	private static final int READ_TIMEOUT_MS = 30_000;
	private static final int MAX_REDIRECTS = 5;

	private static volatile FunctionGemmaModelManager instance;

	private final Context context;
	private final SharedPreferences preferences;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
	private final AtomicBoolean cancelRequested = new AtomicBoolean();
	private volatile Snapshot snapshot;

	private FunctionGemmaModelManager(@NonNull Context context) {
		this.context = context.getApplicationContext();
		preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
		snapshot = new Snapshot(getModelFile().isFile() ? State.READY : State.NOT_INSTALLED,
				0, 0, null);
	}

	@NonNull
	public static FunctionGemmaModelManager get(@NonNull Context context) {
		FunctionGemmaModelManager result = instance;
		if (result == null) {
			synchronized (FunctionGemmaModelManager.class) {
				result = instance;
				if (result == null) {
					result = new FunctionGemmaModelManager(context);
					instance = result;
				}
			}
		}
		return result;
	}

	@NonNull
	public File getModelFile() {
		return new File(new File(context.getFilesDir(), DIRECTORY), MODEL_FILENAME);
	}

	public boolean isInstalled() {
		return getModelFile().isFile();
	}

	public boolean isEnabled() {
		return preferences.getBoolean(KEY_ENABLED, true);
	}

	public void setEnabled(boolean enabled) {
		preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
		if (!enabled) {
			FunctionGemmaRuntime.release();
		}
	}

	@NonNull
	public String getSourceUrl() {
		return preferences.getString(KEY_URL, "");
	}

	public void setSourceUrl(@Nullable String url) {
		preferences.edit().putString(KEY_URL, url == null ? "" : url.trim()).apply();
	}

	@NonNull
	public String getExpectedSha256() {
		return preferences.getString(KEY_SHA256, "");
	}

	public void setExpectedSha256(@Nullable String sha256) {
		preferences.edit().putString(KEY_SHA256,
				sha256 == null ? "" : sha256.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)).apply();
	}

	@NonNull
	public Snapshot getSnapshot() {
		return snapshot;
	}

	public void addListener(@NonNull Listener listener) {
		listeners.add(listener);
		listener.onModelStateChanged(snapshot);
	}

	public void removeListener(@NonNull Listener listener) {
		listeners.remove(listener);
	}

	public void download() {
		if (snapshot.state == State.DOWNLOADING || snapshot.state == State.VERIFYING) {
			return;
		}
		String sourceUrl = getSourceUrl();
		if (!isSupportedUrl(sourceUrl)) {
			publish(State.ERROR, 0, 0, "L’URL doit commencer par http:// ou https://");
			return;
		}
		String sha256 = getExpectedSha256();
		if (!sha256.isEmpty() && !sha256.matches("[0-9a-f]{64}")) {
			publish(State.ERROR, 0, 0, "L’empreinte SHA-256 doit contenir exactement 64 caractères hexadécimaux");
			return;
		}
		cancelRequested.set(false);
		executor.execute(() -> runDownload(sourceUrl, sha256));
	}

	public void cancelDownload() {
		cancelRequested.set(true);
	}

	public void deleteModel() {
		cancelRequested.set(true);
		FunctionGemmaRuntime.release();
		executor.execute(() -> {
			File model = getModelFile();
			File partial = new File(model.getParentFile(), TEMP_FILENAME);
			boolean deleted = (!model.exists() || model.delete()) && (!partial.exists() || partial.delete());
			if (deleted) {
				publish(State.NOT_INSTALLED, 0, 0, null);
			} else {
				publish(State.ERROR, 0, 0, "Impossible de supprimer le modèle");
			}
		});
	}

	private void runDownload(@NonNull String sourceUrl, @NonNull String expectedSha256) {
		File model = getModelFile();
		File directory = model.getParentFile();
		if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
			publish(State.ERROR, 0, 0, "Impossible de créer le dossier du modèle");
			return;
		}
		File partial = new File(directory, TEMP_FILENAME);
		if (partial.exists() && !partial.delete()) {
			publish(State.ERROR, 0, 0, "Impossible de remplacer le téléchargement incomplet");
			return;
		}

		HttpURLConnection connection = null;
		try {
			connection = openFollowingRedirects(sourceUrl);
			int status = connection.getResponseCode();
			if (status < 200 || status >= 300) {
				throw new IOException("HTTP " + status);
			}
			long total = connection.getContentLengthLong();
			if (total > MAX_MODEL_BYTES) {
				throw new IOException("Le fichier dépasse la limite de 1,5 Go");
			}
			if (total > 0) {
				ensureFreeSpace(directory, total + FREE_SPACE_MARGIN_BYTES);
			}
			publish(State.DOWNLOADING, 0, total, null);
			long downloaded = copyDownload(connection.getInputStream(), partial, total);
			if (cancelRequested.get()) {
				throw new DownloadCancelledException();
			}
			publish(State.VERIFYING, downloaded, total, null);
			verifyModel(partial, expectedSha256);
			Os.rename(partial.getAbsolutePath(), model.getAbsolutePath());
			FunctionGemmaRuntime.release();
			publish(State.READY, model.length(), model.length(), null);
		} catch (DownloadCancelledException e) {
			if (partial.exists()) {
				partial.delete();
			}
			publish(model.isFile() ? State.READY : State.NOT_INSTALLED, 0, 0, null);
		} catch (Exception e) {
			if (partial.exists()) {
				partial.delete();
			}
			publish(State.ERROR, 0, 0, safeMessage(e));
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private long copyDownload(@NonNull InputStream source, @NonNull File target, long total)
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
				if (downloaded > MAX_MODEL_BYTES) {
					throw new IOException("Le fichier dépasse la limite de 1,5 Go");
				}
				output.write(buffer, 0, count);
				long now = System.currentTimeMillis();
				if (now - lastPublishedAt >= 250) {
					publish(State.DOWNLOADING, downloaded, total, null);
					lastPublishedAt = now;
				}
			}
			output.flush();
		}
		return downloaded;
	}

	@NonNull
	private HttpURLConnection openFollowingRedirects(@NonNull String initialUrl) throws IOException {
		URL url = URI.create(initialUrl).toURL();
		for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("User-Agent", "OsmAnd-Smart-Search/1");
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
			if (!isSupportedUrl(url.toString())) {
				throw new IOException("Protocole de redirection refusé");
			}
		}
		throw new IOException("Trop de redirections HTTP");
	}

	private void ensureFreeSpace(@NonNull File directory, long neededBytes) throws IOException {
		long available = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
		if (available < neededBytes) {
			throw new IOException("Espace insuffisant : " + formatBytes(neededBytes)
					+ " requis, " + formatBytes(available) + " disponibles");
		}
	}

	private void verifyModel(@NonNull File model, @NonNull String expectedSha256) throws Exception {
		if (model.length() < MAGIC.length) {
			throw new IOException("Le fichier téléchargé est vide ou incomplet");
		}
		byte[] header = new byte[MAGIC.length];
		try (FileInputStream input = new FileInputStream(model)) {
			if (input.read(header) != header.length || !Arrays.equals(header, MAGIC)) {
				throw new IOException("Ce fichier n’est pas un modèle LiteRT-LM (.litertlm)");
			}
		}
		if (!expectedSha256.isEmpty()) {
			String actual = sha256(model);
			if (!expectedSha256.equals(actual)) {
				throw new IOException("L’empreinte SHA-256 du modèle ne correspond pas");
			}
		}
	}

	@NonNull
	private String sha256(@NonNull File file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] buffer = new byte[128 * 1024];
		try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		StringBuilder result = new StringBuilder(64);
		for (byte value : digest.digest()) {
			result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
		}
		return result.toString();
	}

	private void publish(@NonNull State state, long downloaded, long total, @Nullable String error) {
		Snapshot next = new Snapshot(state, downloaded, total, error);
		snapshot = next;
		mainHandler.post(() -> {
			for (Listener listener : listeners) {
				listener.onModelStateChanged(next);
			}
		});
	}

	private static boolean isSupportedUrl(@Nullable String value) {
		if (value == null) {
			return false;
		}
		try {
			URI uri = URI.create(value.trim());
			String scheme = uri.getScheme();
			return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
		} catch (IllegalArgumentException e) {
			return false;
		}
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

	private static final class DownloadCancelledException extends Exception {
	}
}
