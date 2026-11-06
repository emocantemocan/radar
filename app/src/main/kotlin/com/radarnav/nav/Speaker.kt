package com.radarnav.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Türkçe TextToSpeech. Konuşurken müziği kısar (ducking) ve navigasyon ses kanalını
 * kullanır; Android Auto'da arabanın hoparlöründen yol tarifi gibi çalınır.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private val pending = ArrayList<String>()
    private val active = AtomicInteger(0)
    private val ids = AtomicInteger(0)

    /** TTS motoru Türkçe desteklemiyorsa false. */
    var turkishAvailable = true
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = tts.setLanguage(Locale.forLanguageTag("tr-TR"))
        turkishAvailable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        // Cihazdaki en kaliteli Türkçe sesi seç (çevrimdışı çalışanları tercih et)
        runCatching {
            tts.voices?.filter { it.locale.language == "tr" && !it.features.orEmpty().contains("notInstalled") }
                ?.sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality })
                ?.firstOrNull()
                ?.let { tts.voice = it }
        }
        tts.setAudioAttributes(attributes)
        tts.setSpeechRate(1.0f)
        tts.setPitch(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
        ready = true
        synchronized(pending) {
            pending.forEach { speakNow(it, false) }
            pending.clear()
        }
    }

    private fun finished() {
        if (active.decrementAndGet() <= 0) {
            active.set(0)
            audio.abandonAudioFocusRequest(focusRequest)
        }
    }

    /** [urgent] true ise sıradaki konuşmaları keser (ör. radar son uyarısı). */
    fun speak(text: String, urgent: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) {
            synchronized(pending) { pending.add(text) }
            return
        }
        speakNow(text, urgent)
    }

    private fun speakNow(text: String, urgent: Boolean) {
        if (active.getAndIncrement() == 0) audio.requestAudioFocus(focusRequest)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (urgent) active.set(1)
        tts.speak(text, mode, params, "u${ids.incrementAndGet()}")
    }

    fun stop() {
        tts.stop()
        active.set(0)
        audio.abandonAudioFocusRequest(focusRequest)
    }
}
