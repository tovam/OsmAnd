package net.osmand.plus.feedback

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import net.osmand.plus.OsmandApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Adds local graphics settings and Android process-exit metadata to a crash report. */
object LocalCrashDiagnostics {

	private const val MAX_EXIT_RECORDS = 5

	@JvmStatic
	fun appendTo(report: StringBuilder, app: OsmandApplication) {
		val settings = app.settings
		report.append("\n\nGraphics diagnostics:\n")
		report.append("OpenGL rendering enabled: ")
			.append(settings.USE_OPENGL_RENDER.get())
		report.append("\nOpenGL render failure count: ")
			.append(settings.OPENGL_RENDER_FAILED.get())

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			report.append("\nHistorical process-exit metadata: unavailable (requires Android 11 or later)")
			return
		}

		appendProcessExitHistory(report, app)
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun appendProcessExitHistory(report: StringBuilder, app: OsmandApplication) {
		report.append("\n\nRecent process exits (main process):\n")
		try {
			val manager = app.getSystemService(ActivityManager::class.java)
			if (manager == null) {
				report.append("Unavailable: activity manager is unavailable.\n")
				return
			}

			val exits = manager.getHistoricalProcessExitReasons(app.packageName, 0, 10)
				.filter { it.processName == app.packageName && isRelevantReason(it.reason) }
				.sortedByDescending { it.timestamp }
				.take(MAX_EXIT_RECORDS)

			if (exits.isEmpty()) {
				report.append("No matching crash, ANR, or initialization-failure records found.\n")
				return
			}

			for ((index, exit) in exits.withIndex()) {
				if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
					report.append("Native backtrace omitted: Android provides its trace as protobuf.\n")
				}
				report.append("Exit ").append(index + 1).append(":\n")
				report.append("  Timestamp (UTC): ").append(formatTimestamp(exit.timestamp)).append('\n')
				report.append("  Reason: ").append(reasonName(exit.reason)).append('\n')
				report.append("  Status: ").append(exit.status).append('\n')
				report.append("  Description: ").append(exit.description ?: "(none)").append('\n')
				report.append("  Importance: ").append(exit.importance).append('\n')
				report.append("  PSS kB: ").append(exit.pss).append('\n')
				report.append("  RSS kB: ").append(exit.rss).append('\n')
				report.append("  Process name: ").append(exit.processName ?: "(unknown)").append('\n')
			}
		} catch (_: Exception) {
			report.append("Unavailable: Android process-exit metadata could not be read.\n")
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun isRelevantReason(reason: Int): Boolean = when (reason) {
		ApplicationExitInfo.REASON_CRASH,
		ApplicationExitInfo.REASON_CRASH_NATIVE,
		ApplicationExitInfo.REASON_ANR,
		ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> true
		else -> false
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun reasonName(reason: Int): String = when (reason) {
		ApplicationExitInfo.REASON_CRASH -> "CRASH"
		ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
		ApplicationExitInfo.REASON_ANR -> "ANR"
		ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
		else -> "UNKNOWN ($reason)"
	}

	private fun formatTimestamp(timestamp: Long): String {
		val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
		formatter.timeZone = TimeZone.getTimeZone("UTC")
		return formatter.format(Date(timestamp))
	}
}
