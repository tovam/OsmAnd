package net.osmand.plus.feedback

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import java.io.File
import java.io.IOException

data class LocalCrashReportState(
	val text: String? = null,
	val loading: Boolean = true,
	val saving: Boolean = false,
	val message: Int? = null
)

/** One snapshot for display, clipboard and UTF-8 export, retained across rotation/file picker. */
class LocalCrashReportViewModel(application: Application) : AndroidViewModel(application) {
	private val app = application as OsmandApplication
	private val mutableState = MutableStateFlow(LocalCrashReportState())
	val state = mutableState.asStateFlow()
	private var loadJob: Job? = null

	fun load(file: File?) {
		if (state.value.text != null || loadJob?.isActive == true) return
		mutableState.value = LocalCrashReportState()
		loadJob = viewModelScope.launch {
			try {
				val text = withContext(Dispatchers.IO) {
					if (file == null) app.feedbackHelper.copyableCrashReport
					else app.feedbackHelper.getCopyableCrashReport(file)
				}
				mutableState.value = state.value.copy(text = text, loading = false)
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				mutableState.value = state.value.copy(loading = false, message = R.string.local_report_load_failed)
			}
		}
	}

	fun save(uri: Uri) {
		if (state.value.saving) return
		mutableState.value = state.value.copy(saving = true, message = null)
		viewModelScope.launch {
			try {
				// A recreated activity can receive the file picker result while its report is still loading.
				loadJob?.join()
				val text = state.value.text ?: throw IOException("Report unavailable")
				withContext(Dispatchers.IO) {
					val output = app.contentResolver.openOutputStream(uri, "wt")
						?: throw IOException("No document output stream")
					output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
				}
				mutableState.value = state.value.copy(saving = false, message = R.string.local_report_saved)
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				mutableState.value = state.value.copy(saving = false, message = R.string.local_report_save_failed)
			}
		}
	}
}
