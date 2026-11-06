package com.radarnav.core.gemini

import com.radarnav.core.net.LenientJson
import com.radarnav.core.net.arr
import com.radarnav.core.net.bool
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Gemini'nin çağırabileceği bir fonksiyon (function calling). */
data class ToolDeclaration(
    val name: String,
    val description: String,
    /** Parametre adı -> (tip, açıklama). Tip: STRING, INTEGER, NUMBER, BOOLEAN. */
    val params: Map<String, Pair<String, String>> = emptyMap(),
    val required: List<String> = emptyList(),
)

data class LiveConfig(
    val model: String = DEFAULT_LIVE_MODEL,
    val voiceName: String = "Puck",
    val systemInstruction: String,
    val tools: List<ToolDeclaration> = emptyList(),
) {
    companion object {
        const val DEFAULT_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-09-2025"
    }
}

data class FunctionCall(val id: String?, val name: String, val args: JsonObject)

sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    class Audio(val pcm: ByteArray) : LiveServerEvent
    data object Interrupted : LiveServerEvent
    data object TurnComplete : LiveServerEvent
    data class InputTranscript(val text: String) : LiveServerEvent
    data class OutputTranscript(val text: String) : LiveServerEvent
    data class ToolCall(val calls: List<FunctionCall>) : LiveServerEvent
    data class ToolCallCancelled(val ids: List<String>) : LiveServerEvent
    data class GoAway(val timeLeft: String?) : LiveServerEvent
    data class Error(val message: String) : LiveServerEvent
}

/**
 * Gemini Live API (BidiGenerateContent WebSocket) mesajları.
 * https://ai.google.dev/api/live
 */
object LiveProtocol {
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    private const val WS_PATH =
        "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    fun url(apiKey: String, host: String = "wss://generativelanguage.googleapis.com") = "$host$WS_PATH?key=$apiKey"

    fun setupMessage(config: LiveConfig): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", if (config.model.startsWith("models/")) config.model else "models/${config.model}")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", config.voiceName) }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", config.systemInstruction) } }
            }
            if (config.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            config.tools.forEach { t -> add(declaration(t)) }
                        }
                    }
                }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
        }
    }.toString()

    private fun declaration(t: ToolDeclaration): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        if (t.params.isNotEmpty()) {
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    t.params.forEach { (name, spec) ->
                        putJsonObject(name) {
                            put("type", spec.first)
                            put("description", spec.second)
                        }
                    }
                }
                if (t.required.isNotEmpty()) {
                    putJsonArray("required") { t.required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                }
            }
        }
    }

    fun audioMessage(pcm16k: ByteArray, length: Int = pcm16k.size): String {
        val b64 = Base64.getEncoder().encodeToString(if (length == pcm16k.size) pcm16k else pcm16k.copyOf(length))
        return buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", b64)
                    put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                }
            }
        }.toString()
    }

    /** Metinle konuşma başlatmak/bilgi vermek için (ör. "Kendini kısaca tanıt"). */
    fun textMessage(text: String): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", text) } }
                }
            }
            put("turnComplete", true)
        }
    }.toString()

    fun toolResponse(responses: List<Pair<FunctionCall, JsonObject>>): String = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                responses.forEach { (call, result) ->
                    addJsonObject {
                        call.id?.let { put("id", it) }
                        put("name", call.name)
                        put("response", result)
                    }
                }
            }
        }
    }.toString()

    /** Tek bir sunucu mesajı birden fazla olay içerebilir (ör. ses + transkript). */
    fun parse(text: String): List<LiveServerEvent> {
        val root = runCatching { LenientJson.parseToJsonElement(text).obj() }.getOrNull()
            ?: return listOf(LiveServerEvent.Error("Çözümlenemeyen mesaj"))
        return parse(root)
    }

    fun parse(root: JsonObject): List<LiveServerEvent> {
        val out = ArrayList<LiveServerEvent>()
        if (root.containsKey("setupComplete")) out.add(LiveServerEvent.SetupComplete)
        root["serverContent"].obj()?.let { sc ->
            if (sc.bool("interrupted") == true) out.add(LiveServerEvent.Interrupted)
            sc["inputTranscription"].obj()?.str("text")?.takeIf { it.isNotBlank() }
                ?.let { out.add(LiveServerEvent.InputTranscript(it)) }
            sc["outputTranscription"].obj()?.str("text")?.takeIf { it.isNotBlank() }
                ?.let { out.add(LiveServerEvent.OutputTranscript(it)) }
            sc["modelTurn"].obj()?.get("parts").arr().orEmpty().forEach { p ->
                val inline = p.obj()?.get("inlineData").obj() ?: return@forEach
                val mime = inline.str("mimeType").orEmpty()
                val data = inline.str("data") ?: return@forEach
                if (mime.startsWith("audio/")) out.add(LiveServerEvent.Audio(Base64.getDecoder().decode(data)))
            }
            if (sc.bool("turnComplete") == true) out.add(LiveServerEvent.TurnComplete)
        }
        root["toolCall"].obj()?.let { tc ->
            val calls = tc["functionCalls"].arr().orEmpty().mapNotNull { c ->
                val o = c.obj() ?: return@mapNotNull null
                FunctionCall(o.str("id"), o.str("name") ?: return@mapNotNull null, o["args"].obj() ?: JsonObject(emptyMap()))
            }
            if (calls.isNotEmpty()) out.add(LiveServerEvent.ToolCall(calls))
        }
        root["toolCallCancellation"].obj()?.let { c ->
            out.add(LiveServerEvent.ToolCallCancelled(c["ids"].arr().orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }))
        }
        root["goAway"].obj()?.let { out.add(LiveServerEvent.GoAway(it.str("timeLeft"))) }
        root["error"].obj()?.let { out.add(LiveServerEvent.Error(it.str("message") ?: "Bilinmeyen hata")) }
        return out
    }
}
