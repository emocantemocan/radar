package com.radarnav.nav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * Yön (heading) kaynağı: hareket halindeyken GPS/rota yönü, dururken ya da çok yavaşken
 * telefonun pusulası (rotation vector sensörü). Değerler yumuşatılır ki harita titremesin.
 */
class HeadingProvider(private val context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val holders = HashSet<String>()
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private val _compass = MutableStateFlow<Float?>(null)
    /** Pusula yönü (derece, kuzey = 0), sensör yoksa null. */
    val compass: StateFlow<Float?> = _compass

    private var smoothed: Float? = null
    private var lastFused: Float = 0f

    fun acquire(tag: String) {
        if (holders.add(tag) && holders.size == 1) {
            rotationSensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun release(tag: String) {
        if (holders.remove(tag) && holders.isEmpty()) {
            sensors?.unregisterListener(this)
            _compass.value = null
            smoothed = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Ekran döndürülmüşse eksenleri ona göre çevir
        @Suppress("DEPRECATION")
        val displayRotation = runCatching {
            context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        }.getOrDefault(Surface.ROTATION_0)
        val (x, y) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
        val prev = smoothed
        smoothed = if (prev == null) azimuth else blend(prev, azimuth, 0.15f)
        _compass.value = smoothed
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Navigasyon/harita için birleşik yön: hızlıyken GPS ya da rota yönü,
     * yavaşken pusula; ikisi de yoksa son bilinen yön. Ani sıçramalar yumuşatılır.
     */
    fun fused(speedKmh: Double, gpsBearing: Float?, routeBearing: Float?): Float {
        val target = when {
            speedKmh >= 8 -> gpsBearing ?: routeBearing ?: _compass.value
            else -> _compass.value ?: routeBearing ?: gpsBearing
        } ?: return lastFused
        lastFused = blend(lastFused, target, if (speedKmh >= 8) 0.6f else 0.3f)
        return lastFused
    }

    companion object {
        /** Açısal ortalama: 359° ile 1° arasında doğru yönden geçer. */
        fun blend(from: Float, to: Float, factor: Float): Float {
            var diff = (to - from) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            if (abs(diff) < 0.5f) return to
            return ((from + diff * factor) % 360f + 360f) % 360f
        }
    }
}
