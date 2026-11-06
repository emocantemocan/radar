package com.radarnav.assistant

import com.radarnav.core.gemini.GeminiModel
import com.radarnav.core.gemini.GeminiModels
import com.radarnav.data.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Anahtarın erişebildiği Gemini modellerini bir kez sorar ve sohbet (Live), seslendirme (TTS) ve
 * metin için kullanılacak modeli seçer. Ayarlardaki model adı geçerliyse o kullanılır; Google adı
 * değiştirdiyse en uygun yeni model otomatik seçilir.
 */
class GeminiResolver(private val http: OkHttpClient, private val settings: Settings) {
    private val lock = Mutex()
    private var key: String? = null
    private var models: List<GeminiModel>? = null

    /** Model listesi; anahtar yoksa ya da liste alınamazsa null. */
    suspend fun models(): List<GeminiModel>? = lock.withLock {
        val k = settings.geminiApiKey ?: return@withLock null
        if (k == key && models != null) return@withLock models
        val list = runCatching { GeminiModels(http, k).list() }.getOrNull() ?: return@withLock null
        key = k
        models = list
        list
    }

    suspend fun liveModel(): String =
        models()?.let { GeminiModels.pickLive(it, settings.geminiLiveModel) }
            ?: settings.geminiLiveModel.ifBlank { com.radarnav.core.gemini.LiveConfig.DEFAULT_LIVE_MODEL }

    suspend fun textModel(): String =
        models()?.let { GeminiModels.pickText(it, settings.geminiTextModel) } ?: settings.geminiTextModel

    suspend fun ttsModel(): String =
        models()?.let { GeminiModels.pickTts(it, settings.geminiTtsModel) }
            ?: settings.geminiTtsModel.ifBlank { FALLBACK_TTS }

    companion object {
        /** Model listesi alınamazsa (ör. internet yok) kullanılacak, uzun süredir var olan modeller. */
        const val FALLBACK_TTS = "gemini-2.5-flash-preview-tts"
    }
}
