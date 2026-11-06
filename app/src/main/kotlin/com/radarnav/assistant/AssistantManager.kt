package com.radarnav.assistant

import com.radarnav.AppGraph
import com.radarnav.UiEvent
import com.radarnav.audio.AudioInput
import com.radarnav.audio.PcmPlayer
import com.radarnav.core.gemini.GeminiLiveSession
import com.radarnav.core.gemini.LiveConfig
import com.radarnav.core.gemini.LiveListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AssistantStatus { IDLE, CONNECTING, LISTENING, SPEAKING }

/**
 * Gemini Live sesli asistanı. Butonla başlar/biter; mikrofon sesini gerçek zamanlı
 * gönderir, Gemini'nin sesini çalar ve sürüş bilgisine araçlar (function calling)
 * üzerinden erişir.
 */
class AssistantManager(private val graph: AppGraph) {

    private val _status = MutableStateFlow(AssistantStatus.IDLE)
    val status: StateFlow<AssistantStatus> = _status

    /** Son konuşma metni (ekranda altyazı olarak gösterilir). */
    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption

    /** Konuşma balonları için: kullanıcının ve Gemini'nin son cümlesi. */
    private val _userText = MutableStateFlow("")
    val userText: StateFlow<String> = _userText
    private val _modelText = MutableStateFlow("")
    val modelText: StateFlow<String> = _modelText

    /** Mikrofon sessizde mi (Gemini konuşurken araya girmesin diye). */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted
    private var userTurnOpen = false
    private var modelTurnOpen = false

    fun toggleMute() {
        _muted.value = !_muted.value
    }

    /** Öneri çipleri gibi yazılı bir soruyu gönderir. */
    fun ask(text: String) {
        val s = session ?: return
        _userText.value = text
        _modelText.value = ""
        modelBuffer.clear()
        userTurnOpen = false
        lastActivity = System.currentTimeMillis()
        s.sendText(text)
    }

    private var session: GeminiLiveSession? = null
    private var input: AudioInput? = null
    private var player: PcmPlayer? = null
    private var micJob: Job? = null
    private var watchdog: Job? = null
    @Volatile
    private var lastActivity = 0L
    private val modelBuffer = StringBuilder()

    /** Her oturuma yeni numara: eski oturumdan gecikmeli gelen olaylar yeni oturumu etkilemesin. */
    private var generation = 0

    val isRunning: Boolean get() = _status.value != AssistantStatus.IDLE

    fun toggle(inputFactory: () -> AudioInput) {
        if (isRunning) stop() else start(inputFactory)
    }

    fun start(inputFactory: () -> AudioInput) {
        if (isRunning) return
        val key = graph.settings.geminiApiKey
        if (key == null) {
            val msg = "Asistan için Ayarlar'dan Gemini API anahtarı girin."
            graph.speaker.speak(msg)
            graph.emit(UiEvent.Message(msg))
            return
        }
        _status.value = AssistantStatus.CONNECTING
        _caption.value = "Bağlanıyor…"
        lastActivity = System.currentTimeMillis()

        val tools = AssistantTools(graph)
        val config = LiveConfig(
            model = graph.settings.geminiLiveModel,
            voiceName = graph.settings.geminiVoice,
            systemInstruction = tools.systemInstruction(),
            tools = AssistantTools.DECLARATIONS,
        )
        val gen = ++generation
        player = PcmPlayer()
        session = GeminiLiveSession(graph.http, key, config, tools, listener(inputFactory, gen), graph.scope)
            .also { it.connect() }

        watchdog = graph.scope.launch {
            while (isActive) {
                delay(5_000)
                val idleFor = System.currentTimeMillis() - lastActivity
                if (_status.value == AssistantStatus.CONNECTING && idleFor > 15_000) {
                    fail("Gemini'ye bağlanılamadı.")
                } else if (idleFor > IDLE_TIMEOUT_MS) {
                    graph.emit(UiEvent.Message("Asistan sessizlik nedeniyle kapandı"))
                    stop()
                }
            }
        }
    }

    private fun listener(inputFactory: () -> AudioInput, gen: Int) = object : LiveListener {
        private val current get() = gen == generation

        override fun onReady() {
            graph.scope.launch {
                if (!current) return@launch
                val mic = inputFactory()
                if (!mic.start()) {
                    fail("Mikrofon açılamadı (izin verildiğinden emin olun).")
                    return@launch
                }
                input = mic
                _status.value = AssistantStatus.LISTENING
                _caption.value = "Dinliyorum…"
                lastActivity = System.currentTimeMillis()
                micJob = graph.scope.launch(Dispatchers.IO) {
                    val buf = ByteArray(3200) // 100 ms
                    while (isActive) {
                        val n = mic.read(buf)
                        if (n > 0) {
                            if (!_muted.value) session?.sendAudio(buf, n)
                        } else if (n < 0) break
                    }
                }
                session?.sendText("Sürücü seni az önce butona basarak çağırdı. Tarzına uygun çok kısa bir selam ver ve dinle.")
            }
        }

        override fun onAudio(pcm24k: ByteArray) {
            if (!current) return
            lastActivity = System.currentTimeMillis()
            player?.write(pcm24k)
            if (_status.value != AssistantStatus.SPEAKING) graph.scope.launch { _status.value = AssistantStatus.SPEAKING }
        }

        override fun onInterrupted() {
            if (!current) return
            player?.interrupt()
            graph.scope.launch { _status.value = AssistantStatus.LISTENING }
        }

        override fun onTurnComplete() {
            graph.scope.launch {
                // Kalan ses çalınsın diye kısa bekle
                delay(600)
                if (_status.value == AssistantStatus.SPEAKING) _status.value = AssistantStatus.LISTENING
                modelTurnOpen = false
            }
        }

        override fun onUserTranscript(text: String) {
            lastActivity = System.currentTimeMillis()
            graph.scope.launch {
                if (!userTurnOpen) {
                    userTurnOpen = true
                    _userText.value = ""
                }
                _userText.value = (_userText.value + text).trimStart()
                _caption.value = "Sen: ${_userText.value.trim()}"
            }
        }

        override fun onModelTranscript(text: String) {
            graph.scope.launch {
                userTurnOpen = false
                if (!modelTurnOpen) {
                    modelTurnOpen = true
                    modelBuffer.clear()
                }
                modelBuffer.append(text)
                _modelText.value = modelBuffer.toString().trim()
                _caption.value = _modelText.value
            }
        }

        override fun onToolCall(name: String) {
            lastActivity = System.currentTimeMillis()
        }

        override fun onClosed(reason: String?) {
            graph.scope.launch { if (current) cleanup() }
        }

        override fun onError(message: String) {
            graph.scope.launch { if (current) fail(message) }
        }
    }

    private fun fail(message: String) {
        graph.emit(UiEvent.Message(message))
        stop()
    }

    fun stop() {
        generation++
        session?.close()
        cleanup()
    }

    private fun cleanup() {
        micJob?.cancel()
        watchdog?.cancel()
        input?.stop()
        player?.release()
        input = null
        player = null
        session = null
        micJob = null
        watchdog = null
        modelBuffer.clear()
        _status.value = AssistantStatus.IDLE
        _caption.value = ""
        _userText.value = ""
        _modelText.value = ""
        _muted.value = false
        userTurnOpen = false
        modelTurnOpen = false
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 90_000L
    }
}
