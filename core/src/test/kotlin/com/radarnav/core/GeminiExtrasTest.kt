package com.radarnav.core

import com.radarnav.core.alert.Persona
import com.radarnav.core.alert.Severity
import com.radarnav.core.alert.WarningText
import com.radarnav.core.gemini.GeminiModels
import com.radarnav.core.gemini.GeminiTts
import com.radarnav.core.gemini.LiveProtocol
import com.radarnav.core.net.LenientJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiExtrasTest {

    private val models = GeminiModels.parse(
        LenientJson.parseToJsonElement(
            """{"models":[
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-live-2.5-flash","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-2.5-flash-native-audio-latest","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}
            ]}""",
        ),
    )

    @Test
    fun `model secimi ayardaki yoksa en uygununu bulur`() {
        assertEquals("gemini-2.5-flash-native-audio-latest", GeminiModels.pickLive(models, "eski-model-adi"))
        assertEquals("gemini-live-2.5-flash", GeminiModels.pickLive(models, "gemini-live-2.5-flash"))
        assertEquals("gemini-2.5-flash-preview-tts", GeminiModels.pickTts(models, null))
        assertEquals("gemini-flash-latest", GeminiModels.pickText(models, "yok"))
        assertEquals("gemini-2.5-flash", GeminiModels.pickText(models, "gemini-2.5-flash"))
    }

    @Test
    fun `tts yaniti pcm sese cevrilir`() {
        val pcm = ByteArray(480) { it.toByte() }
        val json = """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000",
            "data":"${Base64.getEncoder().encodeToString(pcm)}"}}]}}]}"""
        val audio = GeminiTts.parse(LenientJson.parseToJsonElement(json))
        assertEquals(24_000, audio.sampleRate)
        assertTrue(audio.data.contentEquals(pcm))
        val body = GeminiTts.body("Sinirli söyle: yavaşla", "Puck")
        assertEquals("AUDIO", body["generationConfig"]!!.jsonObject["responseModalities"].toString().trim('[', ']', '"'))
    }

    @Test
    fun `canli metin mesaji realtimeInput ile gider`() {
        val msg = LenientJson.parseToJsonElement(LiveProtocol.realtimeTextMessage("Merhaba")).jsonObject
        assertEquals("Merhaba", msg["realtimeInput"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ses tonu kisilik ve sertlige gore`() {
        assertTrue(WarningText.voiceStyle(Persona.SERT_ARGO, Severity.HARSH).contains("shouting"))
        assertTrue(WarningText.voiceStyle(Persona.KIBAR, Severity.CALM).contains("polite"))
    }

    @Test
    fun `tts yonergesi metinden ayri satirda`() {
        val p = GeminiTts.prompt("Radar 500 metre ileride.", "taxi driver; sarcastic")
        assertTrue(p.startsWith("# Style: taxi driver; sarcastic\n"))
        assertTrue(p.endsWith("\nRadar 500 metre ileride."))
        assertEquals("Düz metin", GeminiTts.prompt("Düz metin", null))
    }

    /** 2026-11 itibarıyla gerçek bir anahtarın gördüğü liste (kısaltılmış). */
    private val realModels = GeminiModels.parse(
        LenientJson.parseToJsonElement(
            """{"models":[
              {"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.5-pro-preview-tts","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.8-flash-tts","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.8-flash-lite-tts","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.5-transcribe-live","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-2.5-flash-native-audio-preview-09-2025","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-3.1-flash-live-preview","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-3.8-live","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-3.8-live-extended-thinking","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-robotics-er-2-streaming-preview","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-3.5-live-translate-preview","supportedGenerationMethods":["bidiGenerateContent"]}
            ]}""",
        ),
    )

    @Test
    fun `eski varsayilan model adlari otomatik secime birakilir`() {
        // Eski sürüm ayarlara bu adları yazmıştı; en yeni sohbet modeli seçilmeli
        assertEquals("gemini-3.8-live", GeminiModels.pickLive(realModels, "gemini-2.5-flash-native-audio-preview-09-2025"))
        assertEquals("gemini-3.8-flash-lite-tts", GeminiModels.pickTts(realModels, "gemini-2.5-flash-preview-tts"))
        // Kullanıcının elle seçtiği model korunur
        assertEquals("gemini-3.1-flash-live-preview", GeminiModels.pickLive(realModels, "gemini-3.1-flash-live-preview"))
        assertEquals("gemini-3.8-flash-tts", GeminiModels.pickTts(realModels, "gemini-3.8-flash-tts"))
        assertEquals("gemini-flash-latest", GeminiModels.pickText(realModels, null))
    }

    @Test
    fun `sohbete uygun olmayan live modelleri secilmez`() {
        val only = realModels.filter { "transcribe" in it.id || "translate" in it.id || "robotics" in it.id || "extended" in it.id }
        assertEquals(null, GeminiModels.pickLive(only, null))
        assertEquals(3.8, GeminiModels.versionOf("gemini-3.8-flash-lite-tts"), 0.0)
        assertEquals(0.0, GeminiModels.versionOf("gemini-flash-latest"), 0.0)
    }

    @Test
    fun `hiz siniri uyari cumlesinde yoksa eklenir`() {
        assertEquals("Yavaşlayın lütfen. Sınır 82.", WarningText.ensureLimit("Yavaşlayın lütfen.", 82))
        assertEquals("Sınır 82, yavaşla!", WarningText.ensureLimit("Sınır 82, yavaşla!", 82))
        assertEquals("seksen iki ile git koçum", WarningText.ensureLimit("seksen iki ile git koçum", 82))
        // 182 içinde 82 geçmesi sayılmaz
        assertEquals("Hızın 182! Sınır 82.", WarningText.ensureLimit("Hızın 182!", 82))
        assertEquals("Metin", WarningText.ensureLimit("Metin", null))
        assertEquals("yüz on", WarningText.numberWords(110))
        assertEquals("doksan", WarningText.numberWords(90))
    }
}
