package com.radarnav.core.gemini

/**
 * Asistanın kendi sesini duymasını engeller (yarı çift yönlü). Arabada mikrofon ve hoparlör arasında
 * yankı engelleme yok; telefonda da asistan sesi yankı engelleyicinin tanımadığı kanaldan çalıyor.
 * Çalınan ses kuyruğunun ne zaman biteceği izlenir; o ana ve sonrasındaki [tailMs]'ye kadar mikrofon
 * yerine sessizlik gönderilir. Bedeli: asistan konuşurken sözü kesilemez.
 */
class EchoGate(
    private val bytesPerSecond: Int = LiveProtocol.OUTPUT_SAMPLE_RATE * 2,
    private val tailMs: Long = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var playingUntil = 0L

    /** [bytes] uzunluğunda ses çalma kuyruğuna eklendi. */
    @Synchronized
    fun onPlayback(bytes: Int) {
        val now = clock()
        val durationMs = bytes * 1000L / bytesPerSecond
        playingUntil = maxOf(playingUntil, now) + durationMs
    }

    /** Çalan ses kesildi (sürücü araya girdi ya da asistan kapandı). */
    fun onInterrupted() {
        playingUntil = clock()
    }

    /** Mikrofon sesi şu an gönderilebilir mi? */
    fun micOpen(): Boolean = clock() >= playingUntil + tailMs
}
