package net.osmand.plus.feedback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.settings.enums.ThemeUsageContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Deliberately independent of map/renderer activities, so a broken renderer cannot hide the report. */
class LocalCrashReportActivity : AppCompatActivity() {

	private val model by lazy { ViewModelProvider(this)[LocalCrashReportViewModel::class.java] }
	private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
		if (uri != null) model.save(uri)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val app = application as OsmandApplication
		val night = app.daynightHelper.isNightMode(ThemeUsageContext.APP)
		setTheme(if (night) R.style.LocalCrashReportDark else R.style.LocalCrashReportLight)
		super.onCreate(savedInstanceState)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		setContentView(R.layout.local_crash_report)
		val root = findViewById<android.view.View>(R.id.local_report_root)
		ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
			insets
		}
		ViewCompat.requestApplyInsets(root)
		WindowCompat.getInsetsController(window, root).apply {
			isAppearanceLightStatusBars = !night
			isAppearanceLightNavigationBars = !night
		}
		val report = findViewById<TextView>(R.id.local_report_text)
		val status = findViewById<TextView>(R.id.local_report_status)
		val copy = findViewById<Button>(R.id.local_report_copy)
		val save = findViewById<Button>(R.id.local_report_save)
		val retry = findViewById<Button>(R.id.local_report_retry)
		findViewById<Button>(R.id.local_report_close).setOnClickListener { finish() }
		copy.setOnClickListener {
			model.state.value.text?.let { text ->
				try {
					val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
					clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.local_crash_report), text))
					app.showToastMessage(R.string.copied_to_clipboard)
				} catch (_: RuntimeException) {
					app.showToastMessage(R.string.local_report_copy_failed)
				}
			}
		}
		save.setOnClickListener {
			try {
				val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
				createDocument.launch("osmand-smart-crash-$date.txt")
			} catch (_: RuntimeException) {
				app.showToastMessage(R.string.local_report_save_failed)
			}
		}
		val file = intent.getStringExtra(EXTRA_LOG_PATH)?.let { File(it) }
		retry.setOnClickListener { model.load(file) }
		model.load(file)
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				model.state.collect { state ->
					// Do not reset selection/scroll position when only export status changes.
					if (report.text.toString() != state.text.orEmpty()) report.text = state.text.orEmpty()
					copy.isEnabled = state.text != null
					save.isEnabled = state.text != null && !state.saving
					retry.isVisible = state.text == null && !state.loading
					val message = when {
						state.loading -> R.string.shared_string_loading
						state.saving -> R.string.local_report_saving
						else -> state.message
					}
					status.isVisible = message != null
					if (message != null) status.setText(message)
				}
			}
		}
	}

	companion object {
		private const val EXTRA_LOG_PATH = "local_report_log_path"

		@JvmStatic
		fun show(context: Context, file: File?) {
			context.startActivity(Intent(context, LocalCrashReportActivity::class.java).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				file?.let { putExtra(EXTRA_LOG_PATH, it.absolutePath) }
			})
		}
	}
}
