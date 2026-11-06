package com.radarnav.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.LruCache
import com.radarnav.AppGraph
import com.radarnav.core.gemini.GeminiTts
import com.radarnav.core.gemini.PcmAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uyarı ve yol tarifi sesi. Gemini seslendirmesi (TTS) açıksa cümleler doğal, tonlamalı bir sesle
 * okunur; ses hazırlanamazsa (internet yok, kota, gecikme) telefonun kendi sesi yedek olarak kullanılır.
 * Hazırlanan sesler önbellekte tutulur ("Sağa dönün" gibi tekrar eden cümleler anında çalar).
 */
class VoiceOutput(private val graph: AppGraph) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = LruCache<String, PcmAudio>(64)
    private val player = NavAudioPlayer(graph.app)
    private var client: Pair<String, GeminiTts>? = null

    /** Ardışık hatalardan sonra bir süre Gemini sesini denememek için (gecikme olmasın). */
    @Volatile
    private var coolDownUntil = 0L

    private fun cacheKey(text: String, style: String?) = "${graph.settings.geminiVoice}|$style|$text"

    private suspend fun tts(): GeminiTts? {
        val s = graph.settings
        if (!s.geminiVoiceOutput) return null
        val key = s.geminiApiKey ?: return null
        if (System.currentTimeMillis() < coolDownUntil) return null
        val model = graph.gemini.ttsModel()
        val id = "$key|$model"
        client?.takeIf { it.first == id }?.let { return it.second }
        return GeminiTts(graph.http, key, model).also { client = id to it }
    }

    /** Sesi hazırlar ve önbelleğe koyar (hata olursa null). */
    suspend fun prepare(text: String, style: String?): PcmAudio? {
        if (text.isBlank()) return null
        cache.get(cacheKey(text, style))?.let { return it }
        val c = tts() ?: return null
        return try {
            c.synthesize(text, graph.settings.geminiVoice, style).also {
                cache.put(cacheKey(text, style), it)
                coolDownUntil = 0
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            coolDownUntil = System.currentTimeMillis() + 3 * 60_000L
            null
        }
    }

    /** Arka planda hazırlar (ör. sıradaki dönüş cümlesi). */
    fun prefetch(text: String, style: String?) {
        if (cache.get(cacheKey(text, style)) != null) return
        scope.launch { prepare(text, style) }
    }

    /**
     * Okur: Gemini sesi [budgetMs] içinde hazır olursa onu, değilse telefonun sesini kullanır.
     * [urgent] ise çalmakta olanı keser.
     */
    fun speak(text: String, style: String?, urgent: Boolean = false, budgetMs: Long = 1_500) {
        if (text.isBlank()) return
        scope.launch {
            val audio = cache.get(cacheKey(text, style)) ?: withTimeoutOrNull(budgetMs) { prepare(text, style) }
            if (audio != null) player.play(audio, urgent) else graph.speaker.speak(text, urgent)
        }
    }

    /** Ayarlardaki ses denemesi için: hata mesajını döndürür. */
    suspend fun test(text: String, style: String?): String? {
        val key = graph.settings.geminiApiKey ?: return "Gemini API anahtarı girilmemiş"
        return try {
            val audio = GeminiTts(graph.http, key, graph.gemini.ttsModel())
                .synthesize(text, graph.settings.geminiVoice, style)
            player.play(audio, urgent = true)
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.message ?: e.javaClass.simpleName
        }
    }
}

/**
 * Hazır PCM seslerini sırayla, navigasyon ses kanalından çalar; çalarken müziği kısar (ducking).
 */
class NavAudioPlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val queue = Channel<PcmAudio>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var generation = 0

    @Volatile
    private var current: AudioTrack? = null

    init {
        scope.launch {
            for (clip in queue) playClip(clip)
        }
    }

    fun play(clip: PcmAudio, urgent: Boolean) {
        if (urgent) {
            generation++
            while (queue.tryReceive().isSuccess) { /* sıradakileri at */ }
            runCatching { current?.stop() }
        }
        queue.trySend(clip)
    }

    private suspend fun playClip(clip: PcmAudio) {
        val gen = generation
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(clip.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(clip.data.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return
        try {
            track.write(clip.data, 0, clip.data.size)
            audio.requestAudioFocus(focus)
            current = track
            track.play()
            val totalFrames = clip.data.size / 2
            val deadline = System.currentTimeMillis() + totalFrames * 1000L / clip.sampleRate + 1_000
            while (gen == generation && track.playbackHeadPosition < totalFrames && System.currentTimeMillis() < deadline) {
                delay(50)
            }
        } finally {
            current = null
            runCatching { track.stop() }
            track.release()
            if (queue.isEmpty) audio.abandonAudioFocusRequest(focus)
        }
    }
}
