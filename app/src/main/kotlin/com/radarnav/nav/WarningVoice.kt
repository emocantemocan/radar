package com.radarnav.nav

import com.radarnav.AppGraph
import com.radarnav.UiEvent
import com.radarnav.core.alert.AlertEvent
import com.radarnav.core.alert.Severity
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

    /** Önceden hazırlanan metin ve hazırlandığı andaki sertlik kademesi. */
    private val prefetched = HashMap<String, Pair<Severity, Deferred<String?>>>()
    private val recent = ArrayDeque<String>()

    private var cachedClient: Pair<String, GeminiTextClient>? = null

    private suspend fun client(): GeminiTextClient? {
        val s = graph.settings
        if (!s.geminiWarnings) return null
        val key = s.geminiApiKey ?: return null
        val model = graph.gemini.textModel()
        val id = "$key|$model"
        cachedClient?.takeIf { it.first == id }?.let { return it.second }
        return GeminiTextClient(graph.http, key, model).also { cachedClient = id to it }
    }

    private fun style(severity: Severity) = WarningText.voiceStyle(graph.settings.persona, severity)

    private fun key(e: AlertEvent, stage: Stage) = "${e.hazard.hazard.id}:$stage"

    fun reset() {
        prefetched.values.forEach { it.second.cancel() }
        prefetched.clear()
    }

    fun handle(event: AlertEvent) {
        if (!graph.settings.radarAlerts) return
        when (event) {
            is AlertEvent.Prefetch -> {
                if (!graph.settings.geminiWarnings || graph.settings.geminiApiKey == null) return
                val severity = WarningText.severityOf(event)
                // Metni ve sesini önceden hazırla: uyarı anında beklemeden çalsın
                prefetched[key(event, event.stage)] = severity to graph.scope.async {
                    generate(event, severity)?.also { graph.voice.prepare(it, style(severity)) }
                }
            }
            is AlertEvent.Approach -> graph.scope.launch {
                // Sertlik uyarı anındaki gerçek hıza göre belirlenir; önceden hazırlanan metin
                // farklı bir kademe içinse (sürücü bu arada hızlandı/yavaşladı) yeniden üretilir.
                val severity = WarningText.severityOf(event)
                val pre = prefetched.remove(key(event, event.stage))
                val text = if (pre != null && pre.first == severity) {
                    withTimeoutOrNull(TIMEOUT_MS) { pre.second.await() }
                } else {
                    pre?.second?.cancel()
                    withTimeoutOrNull(TIMEOUT_MS) { generate(event, severity) }
                }
                val urgent = event.stage != Stage.FAR || severity == Severity.HARSH
                say(event, text, urgent, severity)
            }
            else -> graph.scope.launch {
                val severity = WarningText.severityOf(event)
                say(event, withTimeoutOrNull(TIMEOUT_MS) { generate(event, severity) }, severity == Severity.HARSH, severity)
            }
        }
    }

    private suspend fun generate(event: AlertEvent, severity: Severity): String? {
        val c = client() ?: return null
        val persona = graph.settings.persona
        return runCatching {
            c.generate(WarningText.systemInstruction(persona), WarningText.userPrompt(event, recent.toList(), severity))
        }.getOrNull()?.let(WarningText::clean)?.takeIf { it.length in 3..400 }
            ?.let { WarningText.ensureLimit(it, event.hazard.hazard.speedLimit) }
    }

    private fun say(event: AlertEvent, generated: String?, urgent: Boolean, severity: Severity) {
        val text = generated ?: WarningText.fallback(event)
        if (generated != null) {
            recent.addLast(generated)
            while (recent.size > 6) recent.removeFirst()
        }
        graph.voice.speak(text, style(severity), urgent, budgetMs = 2_000)
        val h = event.hazard.hazard
        val what = if (h is SpeedCorridor) "Hız koridoru" else "Radar"
        val title = h.speedLimit?.let { "$what · Sınır $it" } ?: what
        graph.emit(UiEvent.RadarAlert(title, text, severity, h.id))
    }

    companion object {
        const val TIMEOUT_MS = 2500L
    }
}
