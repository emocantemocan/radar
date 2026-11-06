package com.radarnav.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.radarnav.core.geo.Geo
import com.radarnav.core.geo.LatLng
import com.radarnav.core.nav.NavFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS konum kaynağı. Telefon arayüzü, Android Auto ve navigasyon servisi ayrı ayrı
 * [acquire] çağırır; son kullanıcı [release] edince GPS kapanır.
 * Simülasyon açıkken gerçek GPS yok sayılır ve [emitSimulated] ile sanal konum verilir.
 */
class LocationSource(private val context: Context) {

    private val lm = context.getSystemService(LocationManager::class.java)
    private val holders = HashSet<String>()
    private var running = false
    private var previous: Location? = null

    private val _fixes = MutableStateFlow<NavFix?>(null)
    val fixes: StateFlow<NavFix?> = _fixes

    @Volatile
    var simulationActive = false

    @Volatile
    private var carSpeedKmh: Double? = null

    @Volatile
    private var carSpeedAt = 0L
    private var lastCarPush = 0L

    /**
     * Android Auto'ya bağlıyken arabanın gösterge hızı. GPS hızından daha doğrudur ve
     * tünelde de çalışır; son 3 saniye içinde gelmişse GPS hızının yerine kullanılır.
     */
    fun reportCarSpeed(kmh: Double) {
        carSpeedKmh = kmh
        carSpeedAt = System.currentTimeMillis()
        // Konum güncellemesini beklemeden göstergeyi tazele (en fazla saniyede 2 kez)
        val now = System.currentTimeMillis()
        val last = _fixes.value
        if (last != null && !simulationActive && now - last.timeMs < 5_000 && now - lastCarPush >= 500) {
            lastCarPush = now
            _fixes.value = last.copy(speedKmh = kmh, speedFromCar = true)
        }
    }

    private fun freshCarSpeed(): Double? =
        carSpeedKmh?.takeIf { System.currentTimeMillis() - carSpeedAt < 3_000 }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    fun acquire(tag: String) {
        holders.add(tag)
        start()
    }

    fun release(tag: String) {
        holders.remove(tag)
        if (holders.isEmpty()) stop()
    }

    /** İzin sonradan verildiyse GPS'i başlatmak için. */
    fun retry() {
        if (holders.isNotEmpty()) start()
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running || !hasPermission()) return
        running = true
        try {
            lastKnownLocation()?.let { onLocation(it) }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            running = false
        } catch (e: IllegalArgumentException) {
            // Sağlayıcı yok (ör. emülatör)
        }
    }

    private fun stop() {
        if (!running) return
        running = false
        runCatching { lm.removeUpdates(listener) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasPermission()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun onLocation(loc: Location) {
        // GPS varken daha kaba ağ konumlarını yoksay
        if (loc.provider == LocationManager.NETWORK_PROVIDER) {
            val prev = previous
            if (prev != null && prev.provider == LocationManager.GPS_PROVIDER &&
                loc.time - prev.time < 10_000
            ) return
        }
        val prev = previous
        val speedKmh = when {
            loc.hasSpeed() -> loc.speed * 3.6
            prev != null && loc.time > prev.time ->
                Geo.distance(LatLng(prev.latitude, prev.longitude), LatLng(loc.latitude, loc.longitude)) /
                    ((loc.time - prev.time) / 1000.0) * 3.6
            else -> 0.0
        }
        previous = loc
        if (simulationActive) return
        val carSpeed = freshCarSpeed()
        _fixes.value = NavFix(
            location = LatLng(loc.latitude, loc.longitude),
            speedKmh = carSpeed ?: speedKmh,
            speedFromCar = carSpeed != null,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 50f,
            timeMs = System.currentTimeMillis(),
            bearing = if (loc.hasBearing() && speedKmh > 5) loc.bearing else null,
        )
    }

    fun emitSimulated(fix: NavFix) {
        _fixes.value = fix
    }

    /** Rota planlamak için en iyi bilinen konum. */
    fun current(): LatLng? = _fixes.value?.location
        ?: lastKnownLocation()?.let { LatLng(it.latitude, it.longitude) }
}
