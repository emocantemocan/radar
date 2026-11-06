package com.radarnav.core.gemini

import com.radarnav.core.net.HttpException
import com.radarnav.core.net.arr
import com.radarnav.core.net.fetchJson
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class GeminiModel(val name: String, val methods: Set<String>) {
    /** "models/gemini-x" -> "gemini-x" */
    val id: String get() = name.removePrefix("models/")
}

/**
 * Anahtarın erişebildiği Gemini modelleri. Google model adlarını sık değiştirdiği için sohbet (Live),
 * seslendirme (TTS) ve metin modelleri ayarlardaki ad bulunamazsa buradan otomatik seçilir.
 */
class GeminiModels(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    @Volatile
    private var cached: List<GeminiModel>? = null

    suspend fun list(): List<GeminiModel> {
        cached?.let { return it }
        val out = ArrayList<GeminiModel>()
        var token: String? = null
        do {
            val url = "$baseUrl/models?pageSize=200" + (token?.let { "&pageToken=$it" } ?: "")
            val json = try {
                client.fetchJson(Request.Builder().url(url).header("x-goog-api-key", apiKey).get().build())
            } catch (e: HttpException) {
                throw IllegalStateException(
                    when (e.code) {
                        400, 401, 403 -> "Gemini API anahtarı geçersiz ya da yetkisiz (HTTP ${e.code})"
                        429 -> "Gemini kotası doldu (HTTP 429)"
                        else -> "Gemini model listesi alınamadı (HTTP ${e.code})"
                    },
                    e,
                )
            }
            out += parse(json)
            token = json.obj()?.str("nextPageToken")?.takeIf { it.isNotBlank() }
        } while (token != null)
        cached = out
        return out
    }

    companion object {
        fun parse(json: JsonElement): List<GeminiModel> = json.obj()?.get("models").arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            val methods = o["supportedGenerationMethods"].arr().orEmpty().map { it.jsonPrimitive.content }.toSet()
            GeminiModel(name, methods)
        }

        /** Ayarlardaki model varsa o; yoksa sesli sohbet (bidiGenerateContent) destekleyen en uygun model. */
        fun pickLive(models: List<GeminiModel>, preferred: String?): String? {
            val live = models.filter { "bidiGenerateContent" in it.methods }
            live.firstOrNull { it.id == preferred }?.let { return it.id }
            return live.sortedWith(
                compareByDescending<GeminiModel> { "native-audio" in it.id }
                    .thenByDescending { "flash" in it.id }
                    .thenBy { "preview" in it.id }
                    .thenByDescending { it.id },
            ).firstOrNull()?.id
        }

        /** Seslendirme (TTS) modeli. */
        fun pickTts(models: List<GeminiModel>, preferred: String?): String? {
            val tts = models.filter { "tts" in it.id && "generateContent" in it.methods }
            tts.firstOrNull { it.id == preferred }?.let { return it.id }
            return tts.sortedWith(
                compareByDescending<GeminiModel> { "flash" in it.id }.thenByDescending { it.id },
            ).firstOrNull()?.id
        }

        /** Metin modeli (uyarı cümleleri). */
        fun pickText(models: List<GeminiModel>, preferred: String?): String? {
            val text = models.filter {
                "generateContent" in it.methods && "gemini" in it.id &&
                    listOf("tts", "image", "live", "audio", "embedding", "vision").none { bad -> bad in it.id }
            }
            text.firstOrNull { it.id == preferred }?.let { return it.id }
            text.firstOrNull { it.id == "gemini-flash-latest" }?.let { return it.id }
            return text.sortedWith(
                compareByDescending<GeminiModel> { "flash" in it.id }
                    .thenByDescending { "lite" !in it.id }
                    .thenBy { "preview" in it.id || "exp" in it.id }
                    .thenByDescending { it.id },
            ).firstOrNull()?.id
        }
    }
}
