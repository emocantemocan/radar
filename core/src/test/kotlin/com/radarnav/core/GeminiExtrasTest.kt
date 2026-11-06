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
        assertTrue(WarningText.voiceStyle(Persona.SERT_ARGO, Severity.HARSH).contains("bağırarak"))
        assertTrue(WarningText.voiceStyle(Persona.KIBAR, Severity.CALM).contains("nazik"))
    }
}
