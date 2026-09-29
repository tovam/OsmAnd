package net.osmand.plus.feedback;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.Version;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.util.Algorithms;
import net.osmand.util.CrashReportText;

import org.apache.commons.logging.Log;

import java.io.File;
import java.io.IOException;

public class FeedbackHelper {

	private static final Log log = PlatformUtil.getLog(FeedbackHelper.class);

	public static final String EXCEPTION_PATH = "exception.log";
	private static final int MAX_VISIBLE_CRASH_LOG_BYTES = 256 * 1024;

	private final OsmandApplication app;
	private final ExceptionHandler exceptionHandler;
	private final NativeCrashHandler nativeCrashHandler;

	public FeedbackHelper(@NonNull OsmandApplication app) {
		this.app = app;
		exceptionHandler = new ExceptionHandler(app);
		nativeCrashHandler = new NativeCrashHandler(app);
	}

	@Nullable
	public File getCrashLog() {
		return exceptionHandler.getCrashLog();
	}

	public boolean hasCrashLogs() {
		return getCrashLog() != null || nativeCrashHandler.hasCrashLogs();
	}

	@NonNull
	public String getCopyableCrashReport() {
		return getCopyableCrashReport(getCrashLog());
	}

	@NonNull
	public String getCopyableCrashReport(@Nullable File crashLog) {
		StringBuilder report = new StringBuilder(getDeviceInfo());
		report.append("\nBuild version : ").append(Version.getFullVersionWithReleaseDate(app));
		LocalCrashDiagnostics.appendTo(report, app);
		report.append("\n\n").append(crashLog != null ? crashLog.getName() : EXCEPTION_PATH).append(":\n");
		String crashText = crashLog != null ? readCrashLogTail(crashLog) : null;
		if (Algorithms.isEmpty(crashText)) {
			report.append(app.getString(R.string.data_is_not_available));
		} else {
			report.append(crashText);
		}
		return report.toString();
	}

	@Nullable
	private String readCrashLogTail(@NonNull File file) {
		try {
			return CrashReportText.readUtf8Tail(file, MAX_VISIBLE_CRASH_LOG_BYTES);
		} catch (IOException | RuntimeException e) {
			log.error(e);
			return null;
		}
	}

	// Keep upstream call sites compatible, but never send crash reports from this fork.
	public void sendCrashLog() {
		LocalCrashReportActivity.show(app, null);
	}

	public void sendCrashLog(@NonNull File file) {
		LocalCrashReportActivity.show(app, file);
	}

	public void sendSupportEmail(@NonNull String screenName) {
		sendSupportEmail(screenName, null);
	}

	public void sendSupportEmail(@NonNull String screenName, @Nullable String additional) {
		String info = getDeviceInfo();
		if (!Algorithms.isEmpty(additional)) {
			info = info + "\n" + additional;
		}
		Intent emailIntent = new Intent(Intent.ACTION_SEND)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.putExtra(Intent.EXTRA_EMAIL, new String[] {"support@osmand.net"})
				.putExtra(Intent.EXTRA_SUBJECT, screenName)
				.putExtra(Intent.EXTRA_TEXT, info);
		emailIntent.setSelector(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")));
		AndroidUtils.startActivityIfSafe(app, emailIntent);
	}

	public String getDeviceInfo() {
		StringBuilder text = new StringBuilder();
		text.append("Device : ").append(Build.DEVICE);
		text.append("\nBrand : ").append(Build.BRAND);
		text.append("\nManufacturer : ").append(Build.MANUFACTURER);
		text.append("\nModel : ").append(Build.MODEL);
		text.append("\nProduct : ").append(Build.PRODUCT);
		text.append("\nBuild : ").append(Build.DISPLAY);
		text.append("\nVersion : ").append(Build.VERSION.RELEASE);
		text.append("\nApp Version : ").append(Version.getAppName(app));

		PackageInfo info = getPackageInfo();
		if (info != null) {
			text.append("\nApk Version : ").append(info.versionName).append(" ").append(info.versionCode);
		}
		return text.toString();
	}

	public void setupExceptionHandler() {
		exceptionHandler.installAsDefaultHandler();
	}

	public void saveExceptionSilent(@NonNull Thread thread, @NonNull Throwable throwable) {
		try {
			exceptionHandler.saveException(thread, throwable);
		} catch (IOException e) {
			log.error(e);
		}
	}

	@Nullable
	public PackageInfo getPackageInfo() {
		try {
			return app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
		} catch (NameNotFoundException e) {
			log.error(e);
			return null;
		}
	}
}
