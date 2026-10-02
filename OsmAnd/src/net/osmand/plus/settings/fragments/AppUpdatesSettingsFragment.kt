package net.osmand.plus.settings.fragments

import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import net.osmand.plus.R
import net.osmand.plus.settings.backend.GitHubAppRelease
import net.osmand.plus.settings.backend.GitHubAppUpdateManager
import net.osmand.plus.utils.AndroidUtils

class AppUpdatesSettingsFragment : BaseSettingsFragment(), GitHubAppUpdateManager.Listener {
    private lateinit var updateManager: GitHubAppUpdateManager

    override fun setupPreferences() {
        updateManager = GitHubAppUpdateManager.get(app)
        setPreferenceIcon(CHECK, getContentIcon(R.drawable.ic_action_update))
        setPreferenceIcon(OPEN, getContentIcon(R.drawable.ic_action_external_link))
        requirePreference<Preference>(SOURCE).summary = GitHubAppRelease.RELEASES_PAGE
        render(updateManager.snapshot)
    }

    override fun onResume() {
        super.onResume()
        updateManager.addListener(this)
        updateManager.checkForUpdate()
    }

    override fun onPause() {
        updateManager.removeListener(this)
        super.onPause()
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        return when (preference.key) {
            CHECK -> {
                updateManager.checkForUpdate()
                true
            }
            OPEN -> {
                val url = updateManager.snapshot.releasePageUrl
                if (url != null) AndroidUtils.openUrl(requireActivity(), url, isNightMode)
                true
            }
            SOURCE -> {
                AndroidUtils.openUrl(requireActivity(), GitHubAppRelease.RELEASES_PAGE, isNightMode)
                true
            }
            else -> super.onPreferenceClick(preference)
        }
    }

    override fun onAppUpdateStateChanged(snapshot: GitHubAppUpdateManager.Snapshot) {
        if (isAdded && view != null) render(snapshot)
    }

    override fun onBindPreferenceViewHolder(preference: Preference, holder: PreferenceViewHolder) {
        super.onBindPreferenceViewHolder(preference, holder)
        if (preference.key == NOTES) {
            (holder.findViewById(android.R.id.summary) as? TextView)?.maxLines = Int.MAX_VALUE
        }
    }

    private fun render(snapshot: GitHubAppUpdateManager.Snapshot) {
        val installed = updateManager.installedVersionName
        val status = requirePreference<Preference>(STATUS)
        status.summary =
            when (snapshot.state) {
                GitHubAppUpdateManager.State.IDLE ->
                    getString(R.string.fork_app_update_installed, installed)
                GitHubAppUpdateManager.State.CHECKING ->
                    getString(R.string.fork_app_update_checking)
                GitHubAppUpdateManager.State.UP_TO_DATE ->
                    getString(R.string.fork_app_update_current, installed)
                GitHubAppUpdateManager.State.AVAILABLE ->
                    getString(R.string.fork_app_update_available, snapshot.releaseTitle, installed)
                GitHubAppUpdateManager.State.ERROR ->
                    getString(R.string.fork_app_update_error, snapshot.error)
            }
        requirePreference<Preference>(CHECK).isEnabled =
            snapshot.state != GitHubAppUpdateManager.State.CHECKING
        requirePreference<Preference>(OPEN).isVisible =
            snapshot.state == GitHubAppUpdateManager.State.AVAILABLE
        requirePreference<Preference>(NOTES).apply {
            summary = snapshot.releaseNotes
            isVisible =
                snapshot.state == GitHubAppUpdateManager.State.AVAILABLE &&
                    !snapshot.releaseNotes.isNullOrBlank()
        }
    }

    companion object {
        private const val CHECK = "fork_app_update_check"
        private const val OPEN = "fork_app_update_open"
        private const val SOURCE = "fork_app_update_source"
        private const val STATUS = "fork_app_update_status"
        private const val NOTES = "fork_app_update_notes"
    }
}
