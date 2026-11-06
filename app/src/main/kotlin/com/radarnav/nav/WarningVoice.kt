package com.radarnav.nav

import com.radarnav.AppGraph
import com.radarnav.UiEvent
import com.radarnav.core.alert.AlertEvent
import com.radarnav.core.alert.Stage
import com.radarnav.core.alert.WarningText
import com.radarnav.core.gemini.GeminiTextClient
import com.radarnav.core.model.SpeedCorridor
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Radar uyarılarını seslendirir. Metni Gemini seçilen kişilikle üretir; anahtar yoksa,
 * internet yoksa ya da Gemini [TIMEOUT_MS] içinde cevap vermezse düz yedek cümle okunur.
 * Gecikmeyi gizlemek için metin, uyarıdan ~1 km önce hazırlanmaya başlar (Prefetch).
 */
class WarningVoice(private val graph: AppGraph) {

    private val prefetched = HashMap<String, Deferred<String?>>()
    private val recent = ArrayDeque<String>()

    private var cachedClient: Pair<String, GeminiTextClient>? = null

    private fun client(): GeminiTextClient? {
        val s = graph.settings
        if (!s.geminiWarnings) return null
        val key = s.geminiApiKey ?: return null
        val id = "$key|${s.geminiTextModel}"
        cachedClient?.takeIf { it.first == id }?.let { return it.second }
        return GeminiTextClient(graph.http, key, s.geminiTextModel).also { cachedClient = id to it }
    }

    private fun key(e: AlertEvent, stage: Stage) = "${e.hazard.hazard.id}:$stage"

    fun reset() {
        prefetched.values.forEach { it.cancel() }
        prefetched.clear()
    }

    fun handle(event: AlertEvent) {
        if (!graph.settings.radarAlerts) return
        when (event) {
            is AlertEvent.Prefetch -> {
                if (client() == null) return
                prefetched[key(event, event.stage)] = graph.scope.async { generate(event) }
            }
            is AlertEvent.Approach -> graph.scope.launch {
                val pre = prefetched.remove(key(event, event.stage))
                val text = if (pre != null) {
                    withTimeoutOrNull(TIMEOUT_MS) { pre.await() }
                } else {
                    withTimeoutOrNull(TIMEOUT_MS) { generate(event) }
                }
                say(event, text, urgent = event.stage == Stage.NEAR)
            }
            else -> graph.scope.launch {
                say(event, withTimeoutOrNull(TIMEOUT_MS) { generate(event) }, urgent = false)
            }
        }
    }

    private suspend fun generate(event: AlertEvent): String? {
        val c = client() ?: return null
        val persona = graph.settings.persona
        return runCatching {
            c.generate(WarningText.systemInstruction(persona), WarningText.userPrompt(event, recent.toList()))
        }.getOrNull()?.let(WarningText::clean)?.takeIf { it.length in 3..400 }
    }

    private fun say(event: AlertEvent, generated: String?, urgent: Boolean) {
        val text = generated ?: WarningText.fallback(event)
        if (generated != null) {
            recent.addLast(generated)
            while (recent.size > 6) recent.removeFirst()
        }
        graph.speaker.speak(text, urgent)
        val h = event.hazard.hazard
        val what = if (h is SpeedCorridor) "Hız koridoru" else "Radar"
        val title = h.speedLimit?.let { "$what · Sınır $it" } ?: what
        graph.emit(UiEvent.RadarAlert(title, text))
    }

    companion object {
        const val TIMEOUT_MS = 2500L
    }
}
