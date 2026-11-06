package com.radarnav.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.radarnav.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        secret("gemini_api_key", "aistudio.google.com/apikey adresinden ücretsiz alınabilir")
        secret("graphhopper_key", "graphhopper.com üzerinden ücretsiz (günde 500 rota)")
        secret("google_key", "Directions API etkin olmalı; kullanım ücretli olabilir")

        findPreference<EditTextPreference>("sim_speed")?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }
        listOf("osrm_url", "tile_url").forEach { key ->
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        }
    }

    /** API anahtarları gizli yazılır, özet olarak yalnızca son 4 karakter gösterilir. */
    private fun secret(key: String, hint: String) {
        findPreference<EditTextPreference>(key)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelection(it.text.length)
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val v = p.text?.trim().orEmpty()
                if (v.isEmpty()) "Girilmedi · $hint" else "Girildi (…${v.takeLast(4)})"
            }
        }
    }
}
