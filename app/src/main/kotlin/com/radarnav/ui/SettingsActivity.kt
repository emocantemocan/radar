package com.radarnav.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
        setSupportActionBar(findViewById(R.id.settingsToolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Android 15 kenardan kenara çizer: başlık durum çubuğunun, liste gezinme çubuğunun üstünde kalsın
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }
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
        secret("google_key", "Routes API etkin olmalı; kullanım ücretli olabilir")
        secret("carto_key", "Yalnızca 'CARTO koyu' harita seçiliyse gerekir")

        findPreference<EditTextPreference>("sim_speed")?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }
        findPreference<Preference>("test_ministry")?.setOnPreferenceClickListener {
            testMinistry()
            true
        }
        findPreference<Preference>("test_gemini")?.setOnPreferenceClickListener {
            testGemini()
            true
        }
        findPreference<Preference>("disclaimer")?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(com.radarnav.core.legal.Disclaimer.TITLE)
                .setMessage(com.radarnav.core.legal.Disclaimer.TEXT)
                .setPositiveButton("Tamam", null)
                .show()
            true
        }
        findPreference<Preference>("clear_user_reports")?.apply {
            val reports = requireContext().graph.userReports
            fun refresh() {
                summary = "${reports.count()} kayıt · yalnızca bu telefonda tutulur, hiçbir yere gönderilmez"
            }
            refresh()
            setOnPreferenceClickListener {
                if (reports.count() == 0) return@setOnPreferenceClickListener true
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Seyyar radar kayıtları silinsin mi?")
                    .setMessage("${reports.count()} kayıt silinecek. Bu geri alınamaz.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Sil") { _, _ ->
                        reports.clear()
                        refresh()
                    }
                    .show()
                true
            }
        }
        findPreference<Preference>("test_voice")?.setOnPreferenceClickListener {
            testVoice()
            true
        }
        listOf("osrm_url", "tile_url", "carto_url", "map_style_url").forEach { key ->
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        }
    }

    /** Seçili ses ve tarzla örnek bir radar uyarısı okur. */
    private fun testVoice() {
        val g = requireContext().graph
        val persona = g.settings.persona
        val text = "Abi yavaş, 500 metre sonra radar var, sınır 82!"
        viewLifecycleOwner.lifecycleScope.launch {
            if (!g.settings.geminiVoiceOutput) {
                g.speaker.speak(text, urgent = true)
                return@launch
            }
            val err = g.voice.test(text, com.radarnav.core.alert.WarningText.voiceStyle(persona, com.radarnav.core.alert.Severity.SASSY))
            if (err != null) {
                android.widget.Toast.makeText(requireContext(), "Gemini sesi çalınamadı: $err", android.widget.Toast.LENGTH_LONG).show()
                g.speaker.speak(text, urgent = true)
            }
        }
    }

    /** Gemini anahtarını, modelleri ve sesli sohbet bağlantısını adım adım dener. */
    private fun testGemini() {
        val g = requireContext().graph
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Gemini testi")
            .setMessage("Deneniyor…")
            .setPositiveButton("Tamam", null)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val log = StringBuilder()
            fun line(ok: Boolean, text: String) {
                log.append(if (ok) "✔ " else "✘ ").append(text).append("\n\n")
                dialog.setMessage(log.toString().trim())
            }
            val key = g.settings.geminiApiKey
            if (key == null) {
                line(false, "Gemini API anahtarı girilmemiş")
                return@launch
            }
            try {
                val models = com.radarnav.core.gemini.GeminiModels(g.http, key).list()
                val live = g.gemini.liveModel()
                val tts = g.gemini.ttsModel()
                val text = g.gemini.textModel()
                line(true, "Anahtar geçerli (${models.size} model). Sohbet: $live · Ses: $tts · Metin: $text")
                if (models.none { it.id == live && "bidiGenerateContent" in it.methods }) {
                    line(false, "Anahtarın erişebildiği sesli sohbet (Live) modeli bulunamadı")
                }
                val reply = com.radarnav.core.gemini.GeminiTextClient(g.http, key, text)
                    .generate("Kısa cevap ver.", "Sadece 'tamam' yaz.")
                line(true, "Metin üretimi çalışıyor (cevap: ${reply.take(40)})")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                line(false, e.message ?: e.javaClass.simpleName)
                return@launch
            }
            line(true, "Sesli sohbet deneniyor (en fazla 20 sn)…")
            line(true, liveRoundTrip(key, g.gemini.liveModel()))
        }
    }

    /** Live oturumu açıp "Merhaba de" ister; gelen ses/yazıyı özetler. */
    private suspend fun liveRoundTrip(key: String, model: String): String {
        val g = requireContext().graph
        val done = kotlinx.coroutines.CompletableDeferred<String>()
        var audioBytes = 0
        val transcript = StringBuilder()
        var ready = false
        var session: com.radarnav.core.gemini.GeminiLiveSession? = null
        val listener = object : com.radarnav.core.gemini.LiveListener {
            override fun onReady() {
                ready = true
                session?.sendText("Sadece 'Merhaba, buradayım' de.")
            }
            override fun onAudio(pcm24k: ByteArray) { audioBytes += pcm24k.size }
            override fun onModelTranscript(text: String) { transcript.append(text) }
            override fun onTurnComplete() { done.complete("ok") }
            override fun onError(message: String) { done.complete("hata: $message") }
            override fun onClosed(reason: String?) { done.complete("kapandı: ${reason.orEmpty()}") }
        }
        val config = com.radarnav.core.gemini.LiveConfig(
            model = model, voiceName = g.settings.geminiVoice, systemInstruction = "Türkçe ve çok kısa konuş.",
        )
        session = com.radarnav.core.gemini.GeminiLiveSession(
            g.http, key, config, { _, _ -> kotlinx.serialization.json.JsonObject(emptyMap()) }, listener, viewLifecycleOwner.lifecycleScope,
        )
        session.connect()
        val result = kotlinx.coroutines.withTimeoutOrNull(20_000) { done.await() } ?: "zaman aşımı"
        session.close()
        val heard = transcript.toString().trim().take(80)
        return when {
            !ready -> "Sesli sohbet bağlanamadı ($model): $result"
            audioBytes > 0 -> "Sesli sohbet çalışıyor: " +
                String.format(java.util.Locale.forLanguageTag("tr-TR"), "%.1f", audioBytes / 48_000.0) + " sn ses geldi" + (if (heard.isNotEmpty()) " (\"$heard\")" else "")
            else -> "Bağlantı kuruldu ama cevap gelmedi ($model): $result"
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
