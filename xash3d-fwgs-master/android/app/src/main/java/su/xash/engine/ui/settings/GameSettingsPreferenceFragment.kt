package su.xash.engine.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import su.xash.engine.R
import su.xash.engine.model.Game
import su.xash.engine.model.GameLibDownloader
import java.text.DateFormat
import java.util.Date

class GameSettingsPreferenceFragment(val game: Game) : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
                preferenceManager.sharedPreferencesName = game.basedir.name;
                setPreferencesFromResource(R.xml.game_preferences, rootKey);

                val packageList = findPreference<ListPreference>("package_name")!!
                packageList.entries = arrayOf(getString(R.string.app_name))
                packageList.entryValues = arrayOf(requireContext().packageName)

                if (packageList.value == null) {
                        packageList.setValueIndex(0);
                }

                val isCS = game.basedir.name.equals("cstrike", ignoreCase = true)
                                || game.basedir.name.equals("czero", ignoreCase = true)
                val isValve = game.basedir.name.equals("valve", ignoreCase = true)

                if (isCS || isValve) {
                        val enableAmxx = findPreference<SwitchPreferenceCompat>("enable_amxx")!!
                        enableAmxx.isVisible = true

                        val smaCompiler = findPreference<Preference>("sma_compiler")!!
                        smaCompiler.isVisible = true
                        smaCompiler.setOnPreferenceClickListener {
                                CompilerDialogFragment(game)
                                        .show(parentFragmentManager, "sma_compiler")
                                true
                        }
                }

                if (isCS) {
                        val enableAmxx = findPreference<SwitchPreferenceCompat>("enable_amxx")!!
                        val enableYaPBBots = findPreference<SwitchPreferenceCompat>("enable_yapb_bots")!!
                        enableYaPBBots.isVisible = true

                        // v8.1: both switches can be OFF at the same time (no forced
                        // fallback mode anymore). Turning one ON automatically turns
                        // the other OFF -- the last one you enable wins.
                        enableAmxx.setOnPreferenceChangeListener { _, newValue ->
                                if (newValue == true && enableYaPBBots.isChecked) {
                                        // AMX Mod X mode: bots go off
                                        enableYaPBBots.isChecked = false
                                }
                                true
                        }
                        enableYaPBBots.setOnPreferenceChangeListener { _, newValue ->
                                if (newValue == true && enableAmxx.isChecked) {
                                        // pure bot mode: AMX Mod X goes off
                                        enableAmxx.isChecked = false
                                }
                                true
                        }
                }

                populateDownloadedBuildInfo()

                val separatePackages = findPreference<SwitchPreferenceCompat>("separate_libraries")!!
                val clientPackage = findPreference<ListPreference>("client_package")!!
                val serverPackage = findPreference<ListPreference>("server_package")!!
                separatePackages.setOnPreferenceChangeListener { _, newValue ->
                        if (newValue == true) {
                                packageList.isVisible = false
                                clientPackage.isVisible = true
                                serverPackage.isVisible = true
                        } else {
                                packageList.isVisible = true
                                clientPackage.isVisible = false
                                serverPackage.isVisible = false
                        }

                        true
                }
        }

        private fun populateDownloadedBuildInfo() {
                val downloader = GameLibDownloader(requireContext())
                val source = downloader.getSourceInfo(game.basedir.name) ?: return
                val downloadedAt = downloader.getDownloadTime(game.basedir.name)

                val urlPref = findPreference<Preference>("source_url")!!
                urlPref.isVisible = true
                urlPref.summary = source.url ?: "—"
                urlPref.isEnabled = source.url != null
                urlPref.setOnPreferenceClickListener {
                        source.url?.let {
                                startActivity(Intent(Intent.ACTION_VIEW, it.toUri()))
                        }
                        true
                }

                val branchPref = findPreference<Preference>("source_branch")!!
                branchPref.isVisible = true
                branchPref.summary = source.branch ?: "—"

                val commitPref = findPreference<Preference>("source_commit")!!
                commitPref.isVisible = true
                commitPref.summary = source.commit ?: "—"
                commitPref.isEnabled = source.commit != null && source.url != null
                commitPref.setOnPreferenceClickListener {
                        // FIXME: GitHub-styled URL!
                        val target = "${source.url!!.trimEnd('/')}/commit/${source.commit}"
                        startActivity(Intent(Intent.ACTION_VIEW, target.toUri()))
                        true
                }

                val timePref = findPreference<Preference>("downloaded_at")!!
                timePref.isVisible = true
                timePref.summary = if (downloadedAt > 0L)
                        DateFormat.getDateTimeInstance().format(Date(downloadedAt))
                else
                        "—"
        }
}
