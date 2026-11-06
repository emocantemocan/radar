package com.radarnav.car

import android.annotation.SuppressLint
import androidx.car.app.CarContext
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.media.CarAudioRecord
import com.radarnav.audio.AudioInput

/**
 * Arabanın mikrofonu (Android Auto, Car API 5+). Çıkış 16 kHz mono 16-bit PCM;
 * Gemini Live'ın beklediği biçimle aynı.
 */
@RequiresCarApi(5)
class CarMicInput(private val carContext: CarContext) : AudioInput {
    private var record: CarAudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = try {
        val r = CarAudioRecord.create(carContext)
        r.startRecording()
        record = r
        true
    } catch (e: Exception) {
        false
    }

    override fun read(buffer: ByteArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    override fun stop() {
        runCatching { record?.stopRecording() }
        record = null
    }
}
