package com.radarnav.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.radarnav.AppGraph
import com.radarnav.R
import com.radarnav.UiEvent
import com.radarnav.audio.AudioInput
import com.radarnav.audio.PhoneMicInput
import com.radarnav.graph
import com.radarnav.nav.NavState
import kotlinx.coroutines.launch

private const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"

/** Android Auto giriş noktası (navigasyon kategorisi). */
class RadarCarAppService : CarAppService() {

    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = RadarSession()

    /** Orta ekran ve (destekleyen arabalarda) sürücü göstergesi için ayrı oturumlar. */
    override fun onCreateSession(sessionInfo: androidx.car.app.SessionInfo): Session =
        if (sessionInfo.displayType == androidx.car.app.SessionInfo.DISPLAY_TYPE_CLUSTER) RadarClusterSession() else RadarSession()
}

/**
 * Sürücü göstergesi oturumu: yalnızca harita ve sıradaki dönüş. Navigasyonu ana oturum yönetir
 * (NavigationManager orada); bu oturum aynı durumu okuyup çizer.
 */
class RadarClusterSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = try {
        CarClusterScreen(carContext, CarMapRenderer(carContext, carContext.graph, lifecycle))
    } catch (e: Throwable) {
        carContext.graph.errors.record("Android Auto: gösterge oturumu", e)
        CarErrorScreen(carContext, e)
    }
}

class RadarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = try {
        val graph = carContext.graph
        if (!graph.settings.disclaimerAccepted) CarDisclaimerScreen(carContext) { navScreen() } else navScreen()
    } catch (e: Throwable) {
        carContext.graph.errors.record("Android Auto: oturum", e)
        CarErrorScreen(carContext, e)
    }

    private fun navScreen(): Screen {
        val graph = carContext.graph
        val renderer = CarMapRenderer(carContext, graph, lifecycle)
        lifecycle.addObserver(CarNavigationBridge(carContext, graph))
        return CarNavScreen(carContext, renderer)
    }
}

/** Araba mikrofonu varsa onu (Car API 5+), yoksa telefon mikrofonunu kullanır. */
fun CarContext.micInputFactory(): () -> AudioInput = {
    if (carAppApiLevel >= CarAppApiLevels.LEVEL_5) CarMicInput(this) else PhoneMicInput()
}

fun CarContext.hasPermission(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** Asistanı açar/kapatır; mikrofon izni yoksa telefondan izin ister. */
fun CarContext.toggleAssistant(graph: AppGraph) {
    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
        requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
            if (granted.isNotEmpty()) graph.assistant.toggle(micInputFactory())
        }
        return
    }
    graph.assistant.toggle(micInputFactory())
}

/**
 * Araç ile navigasyon durumunu senkron tutar: navigasyon başladı/bitti bildirimleri,
 * gösterge paneli için yolculuk bilgisi ve radar uyarılarının araba ekranında gösterimi.
 */
private class CarNavigationBridge(
    private val carContext: CarContext,
    private val graph: AppGraph,
) : DefaultLifecycleObserver {

    private val navManager = carContext.getCarService(NavigationManager::class.java)
    private var started = false
    private var alertId = 1
    private var speedListener: OnCarDataAvailableListener<Speed>? = null

    override fun onCreate(owner: LifecycleOwner) {
        graph.location.acquire("car")
        val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION, CAR_SPEED_PERMISSION)
            .filterNot { carContext.hasPermission(it) }
        if (missing.isNotEmpty()) {
            carContext.requestPermissions(missing) { _, _ ->
                graph.location.retry()
                startCarSpeed()
            }
        }
        startCarSpeed()

        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = graph.controller.stopNavigation()

            override fun onAutoDriveEnabled() {
                // Masaüstü test ünitesinde (DHU) "otomatik sürüş" komutu
                graph.settings.setSimulate(true)
                if (graph.controller.isNavigating) graph.controller.startSimulation()
            }
        })

        owner.lifecycleScope.launch {
            graph.controller.state.collect { s ->
                if (s is NavState.Navigating) {
                    if (!started) {
                        runCatching { navManager.navigationStarted() }
                        started = true
                    }
                    s.progress?.let { p -> runCatching { navManager.updateTrip(CarFormat.trip(carContext, s.plan, p)) } }
                } else if (started) {
                    runCatching { navManager.navigationEnded() }
                    started = false
                }
            }
        }

        owner.lifecycleScope.launch {
            graph.events.collect { e ->
                when (e) {
                    is UiEvent.RadarAlert -> showAlert(e.title, e.text)
                    is UiEvent.Message -> CarToast.makeText(carContext, e.text, CarToast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Arabanın gösterge hızını dinler (Car API 3+); yoksa GPS hızı kullanılır. */
    private fun startCarSpeed() {
        if (speedListener != null || carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) return
        if (!carContext.hasPermission(CAR_SPEED_PERMISSION)) return
        val listener = OnCarDataAvailableListener<Speed> { speed ->
            val raw = speed.rawSpeedMetersPerSecond
            val display = speed.displaySpeedMetersPerSecond
            val mps = when {
                raw.status == CarValue.STATUS_SUCCESS && raw.value != null -> raw.value
                display.status == CarValue.STATUS_SUCCESS && display.value != null -> display.value
                else -> null
            }
            if (mps != null) graph.location.reportCarSpeed(kotlin.math.abs(mps.toDouble()) * 3.6)
        }
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(ContextCompat.getMainExecutor(carContext), listener)
            speedListener = listener
        }
    }

    private fun showAlert(title: String, text: String) {
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
            val alert = Alert.Builder(alertId++, CarText.create(title), 8_000)
                .setSubtitle(CarText.create(text))
                .setIcon(CarFormat.icon(carContext, R.drawable.ic_radar))
                .build()
            runCatching { carContext.getCarService(AppManager::class.java).showAlert(alert) }
        } else {
            CarToast.makeText(carContext, title, CarToast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        speedListener?.let { l ->
            runCatching { carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(l) }
        }
        speedListener = null
        if (started) runCatching { navManager.navigationEnded() }
        runCatching { navManager.clearNavigationManagerCallback() }
        graph.location.release("car")
        if (graph.assistant.isRunning) graph.assistant.stop()
    }
}
