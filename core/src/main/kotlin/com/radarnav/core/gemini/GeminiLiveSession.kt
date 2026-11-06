package com.radarnav.core.gemini

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/** Gemini'nin çağırdığı fonksiyonları uygulayan taraf. */
fun interface ToolHandler {
    suspend fun handle(name: String, args: JsonObject): JsonObject
}

interface LiveListener {
    fun onReady() {}
    fun onAudio(pcm24k: ByteArray) {}
    fun onInterrupted() {}
    fun onTurnComplete() {}
    fun onUserTranscript(text: String) {}
    fun onModelTranscript(text: String) {}
    fun onToolCall(name: String) {}
    fun onClosed(reason: String?) {}
    fun onError(message: String) {}
}

/**
 * Gemini Live oturumu: mikrofon sesini (16 kHz PCM) gönderir, modelin sesini (24 kHz PCM)
 * dinleyiciye iletir ve fonksiyon çağrılarını [toolHandler] ile yanıtlar.
 */
class GeminiLiveSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val config: LiveConfig,
    private val toolHandler: ToolHandler,
    private val listener: LiveListener,
    private val scope: CoroutineScope,
) {
    private var socket: WebSocket? = null
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val isReady: Boolean get() = ready.get() && !closed.get()

    fun connect() {
        val request = Request.Builder().url(LiveProtocol.url(apiKey)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(LiveProtocol.setupMessage(config))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            // Sunucu JSON'u ikili çerçeve olarak da gönderebiliyor
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (code != 1000 && !closed.get()) listener.onError("Gemini bağlantıyı kapattı (kod $code${if (reason.isNotBlank()) ": $reason" else ""})")
                finish("kod $code ${reason.ifBlank { "" }}".trim())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                finish("kod $code ${reason.ifBlank { "" }}".trim())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = when (response?.code) {
                    400, 401, 403 -> "Gemini bağlantısı reddedildi (API anahtarını ve model adını kontrol edin)"
                    else -> "Gemini bağlantı hatası: ${t.message ?: t.javaClass.simpleName}"
                }
                if (!closed.get()) listener.onError(msg)
                finish(msg)
            }
        })
    }

    private fun handle(text: String) {
        for (event in LiveProtocol.parse(text)) {
            when (event) {
                LiveServerEvent.SetupComplete -> {
                    ready.set(true)
                    listener.onReady()
                }
                is LiveServerEvent.Audio -> listener.onAudio(event.pcm)
                LiveServerEvent.Interrupted -> listener.onInterrupted()
                LiveServerEvent.TurnComplete -> listener.onTurnComplete()
                is LiveServerEvent.InputTranscript -> listener.onUserTranscript(event.text)
                is LiveServerEvent.OutputTranscript -> listener.onModelTranscript(event.text)
                is LiveServerEvent.ToolCall -> scope.launch {
                    val responses = event.calls.map { call ->
                        listener.onToolCall(call.name)
                        val result = runCatching { toolHandler.handle(call.name, call.args) }
                            .getOrElse { e -> buildJsonObject { put("error", e.message ?: "Hata") } }
                        call to result
                    }
                    if (!closed.get()) socket?.send(LiveProtocol.toolResponse(responses))
                }
                is LiveServerEvent.ToolCallCancelled -> Unit
                is LiveServerEvent.GoAway -> Unit
                is LiveServerEvent.Error -> listener.onError(event.message)
            }
        }
    }

    fun sendAudio(pcm16k: ByteArray, length: Int = pcm16k.size) {
        if (isReady) socket?.send(LiveProtocol.audioMessage(pcm16k, length))
    }

    fun sendText(text: String) {
        if (isReady) socket?.send(LiveProtocol.realtimeTextMessage(text))
    }

    fun close() {
        if (closed.get()) return
        socket?.close(1000, "kullanıcı kapattı")
        finish(null)
    }

    private fun finish(reason: String?) {
        if (closed.compareAndSet(false, true)) {
            ready.set(false)
            listener.onClosed(reason)
        }
    }
}
