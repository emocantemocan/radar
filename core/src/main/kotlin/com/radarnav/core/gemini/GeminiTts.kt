package com.radarnav.core.gemini

import com.radarnav.core.net.LenientJson
import com.radarnav.core.net.arr
import com.radarnav.core.net.fetchString
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** Ham PCM (16-bit, mono, little-endian) ses. */
class PcmAudio(val data: ByteArray, val sampleRate: Int)

/**
 * Gemini seslendirme (TTS): uyarı ve yol tarifi cümlelerini doğal, tonlamalı sesle okur.
 * Tarz/ton cümlenin başına yazılan yönergeyle verilir ("Sinirli bir taksici gibi söyle: ...").
 */
class GeminiTts(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    suspend fun synthesize(text: String, voice: String, style: String?): PcmAudio {
        val prompt = if (style.isNullOrBlank()) text else "$style: $text"
        val req = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body(prompt, voice).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return parse(LenientJson.parseToJsonElement(client.fetchString(req)))
    }

    companion object {
        fun body(prompt: String, voice: String) = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject { putJsonArray("parts") { addJsonObject { put("text", prompt) } } }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } }
                }
            }
        }

        fun parse(json: JsonElement): PcmAudio {
            val parts = json.obj()?.get("candidates").arr()?.firstOrNull().obj()?.get("content").obj()?.get("parts").arr().orEmpty()
            val inline = parts.firstNotNullOfOrNull { it.obj()?.get("inlineData").obj() }
                ?: throw IllegalStateException("Gemini TTS ses döndürmedi")
            val mime = inline.str("mimeType").orEmpty()
            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
            val data = Base64.getDecoder().decode(inline.str("data") ?: throw IllegalStateException("Boş ses"))
            return PcmAudio(data, rate)
        }
    }
}
