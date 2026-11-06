package com.radarnav.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.radarnav.core.gemini.LiveProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 16 kHz, mono, 16-bit PCM mikrofon kaynağı (telefon ya da araba mikrofonu). */
interface AudioInput {
    fun start(): Boolean
    fun read(buffer: ByteArray): Int
    fun stop()
}

/** Telefon mikrofonu; yankı engelleme açık (asistan hoparlörden konuşurken kendini duymasın). */
class PhoneMicInput : AudioInput {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        val rate = LiveProtocol.INPUT_SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400),
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        r.startRecording()
        record = r
        return true
    }

    override fun read(buffer: ByteArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    override fun stop() {
        runCatching { record?.stop() }
        record?.release()
        aec?.release()
        ns?.release()
        record = null
    }
}

/** Gemini'nin 24 kHz PCM sesini çalar. Kesinti (kullanıcı araya girdi) olunca tamponu boşaltır. */
class PcmPlayer(sampleRate: Int = LiveProtocol.OUTPUT_SAMPLE_RATE) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                sampleRate,
            ),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    @Volatile
    private var generation = 0

    @Volatile
    var lastWriteAt = 0L
        private set

    init {
        track.play()
        scope.launch {
            for (chunk in queue) {
                val gen = generation
                var offset = 0
                while (offset < chunk.size && gen == generation) {
                    val n = track.write(chunk, offset, chunk.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                lastWriteAt = System.currentTimeMillis()
            }
        }
    }

    fun write(pcm: ByteArray) {
        queue.trySend(pcm)
    }

    /** Çalınmakta olan sesi hemen keser. */
    fun interrupt() {
        generation++
        while (queue.tryReceive().isSuccess) { /* boşalt */ }
        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
    }

    fun release() {
        queue.close()
        scope.cancel()
        runCatching {
            track.stop()
            track.release()
        }
    }
}
