package com.radarnav.core.gemini

import com.radarnav.core.net.HttpException
import com.radarnav.core.net.LenientJson
import com.radarnav.core.net.arr
import com.radarnav.core.net.bool
import com.radarnav.core.net.fetchString
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Gemini REST generateContent – radar uyarı cümlelerini üretmek için. */
class GeminiTextClient(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    @Volatile
    private var thinkingConfigSupported = true

    suspend fun generate(systemInstruction: String, prompt: String, temperature: Double = 1.1): String {
        val body = buildBody(systemInstruction, prompt, temperature, thinkingConfigSupported)
        val text = try {
            post(body)
        } catch (e: HttpException) {
            // Bazı modeller thinkingBudget=0 kabul etmez; bir kez onsuz dene
            if (e.code == 400 && thinkingConfigSupported) {
                thinkingConfigSupported = false
                post(buildBody(systemInstruction, prompt, temperature, false))
            } else {
                throw e
            }
        }
        return text
    }

    private suspend fun post(body: JsonObject): String {
        val req = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return extractText(LenientJson.parseToJsonElement(client.fetchString(req)))
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"

        fun buildBody(system: String, prompt: String, temperature: Double, withThinking: Boolean) = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt) } }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                put("maxOutputTokens", 400)
                if (withThinking) putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
            }
            putJsonArray("safetySettings") {
                addJsonObject {
                    put("category", "HARM_CATEGORY_HARASSMENT")
                    put("threshold", "BLOCK_ONLY_HIGH")
                }
            }
        }

        fun extractText(json: JsonElement): String {
            val candidate = json.obj()?.get("candidates").arr()?.firstOrNull().obj()
                ?: throw IllegalStateException("Gemini yanıt vermedi")
            val parts = candidate["content"].obj()?.get("parts").arr().orEmpty()
            val text = parts.mapNotNull { it.obj() }
                .filter { it.bool("thought") != true }
                .mapNotNull { it.str("text") }
                .joinToString("")
                .trim()
            if (text.isEmpty()) throw IllegalStateException("Gemini boş yanıt döndü")
            return text
        }
    }
}
