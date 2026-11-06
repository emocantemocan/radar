package com.radarnav.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.lifecycle.lifecycleScope
import com.radarnav.R
import com.radarnav.graph
import kotlinx.coroutines.launch

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
        secret("carto_key", "Yalnızca 'CARTO koyu' harita seçiliyse gerekir")

        findPreference<EditTextPreference>("sim_speed")?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }
        findPreference<Preference>("test_ministry")?.setOnPreferenceClickListener {
            testMinistry()
            true
        }
        listOf("osrm_url", "tile_url", "carto_url", "map_style_url").forEach { key ->
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        }
    }

    /** Bakanlık servisini adım adım dener ve sonucu (ya da gerçek hatayı) gösterir. */
    private fun testMinistry() {
        val g = requireContext().graph
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Radar servisi testi")
            .setMessage("Bağlanılıyor…")
            .setPositiveButton("Tamam", null)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val log = StringBuilder()
            fun line(ok: Boolean, text: String) {
                log.append(if (ok) "✔ " else "✘ ").append(text).append("\n\n")
                dialog.setMessage(log.toString().trim())
            }
            try {
                val cities = g.districts.cities()
                line(true, "İl listesi alındı (${cities.size} il)")
                val from = g.districts.nearest(com.radarnav.core.geo.LatLng(39.9208, 32.8541))
                    ?: error("Ankara için ilçe bulunamadı")
                // Ankara → Bolu (~190 km, D-750/TEM; radar beklenen bir güzergah)
                val to = g.districts.nearest(com.radarnav.core.geo.LatLng(40.7392, 31.6089))
                    ?: error("Bolu için ilçe bulunamadı")
                line(true, "İlçeler alındı (${from.cityName} / ${from.name} → ${to.cityName} / ${to.name})")
                val route = g.ministry.createRoute(from, to)
                line(
                    true,
                    "Radar sorgusu çalışıyor: ${route.radars.size} radar (${route.activeRadarCount} aktif), " +
                        "${route.corridors.size} hız koridoru (${route.activeCorridorCount} aktif). " +
                        "Bakanlığın özet sayıları: ${route.radarCount} radarlı, ${route.controlPointCount} kontrol noktası, " +
                        "${route.corridorCount} koridor",
                )
                line(true, "Servis çalışıyor.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                line(false, e.message ?: com.radarnav.core.ministry.MinistryApi.describe(e))
                log.append("Ayrıntı: ${e.javaClass.name}").append(e.cause?.let { "\nNeden: ${it.javaClass.simpleName}: ${it.message}" }.orEmpty())
                dialog.setMessage(log.toString().trim())
            }
        }
    }

    /** API anahtarları gizli yazılır, özet olarak yalnızca son 4 karakter gösterilir. */
    private fun secret(key: String, hint: String) {
        findPreference<EditTextPreference>(key)?.apply {
            // Göz ikonu (pref_dialog_secret) şifre türündeki alanda göster/gizle yapar
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
