package com.radarnav.nav

import android.content.Intent
import androidx.core.content.ContextCompat
import com.radarnav.AppGraph
import com.radarnav.UiEvent
import com.radarnav.core.alert.AlertEngine
import com.radarnav.core.alert.CorridorProgress
import com.radarnav.core.alert.FreeCorridorMonitor
import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.Place
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.RouteStep
import com.radarnav.core.nav.NavEvent
import com.radarnav.core.nav.NavFix
import com.radarnav.core.nav.NavigationTracker
import com.radarnav.core.routing.OsrmRouting
import com.radarnav.core.routing.RoutingException
import com.radarnav.core.routing.RoutingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RoutePlan(
    val origin: LatLng,
    val destination: Place,
    val route: NavRoute,
    val hazards: List<RouteHazard>,
    /** Radar verisi eksikse kullanıcıya gösterilecek not. */
    val note: String?,
    /** Radar verisinin bakanlıktan alındığı zaman. */
    val hazardsUpdatedAt: Long = System.currentTimeMillis(),
    /** Bakanlık servisine hiç ulaşılamadı (arka planda yeniden denenir). */
    val hazardsUnavailable: Boolean = false,
    /** Seçilen rota servisi hakkında not (OSRM'e düşüldü vb.). */
    val routeNote: String? = null,
    /** Bakanlığın bu güzergah için bildirdiği günlük radar uygulaması sayısı. */
    val ministryRadarCount: Int = 0,
    /** Bakanlık sayıyı verdi ama konumları paylaşmadı. */
    val radarsWithheld: Boolean = false,
) {
    val radarCount get() = hazards.count { !it.isCorridor }
    val corridorCount get() = hazards.count { it.isCorridor }
}

data class NavProgress(
    val location: LatLng,
    val bearing: Float,
    val speedKmh: Double,
    /** Hız arabanın göstergesinden mi geliyor (Android Auto) yoksa GPS'ten mi. */
    val speedFromCar: Boolean,
    val along: Double,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val nextStep: RouteStep?,
    val distanceToStep: Double,
    val currentLimit: Int?,
    val nextHazard: RouteHazard?,
    val distanceToHazard: Double?,
    val corridor: CorridorProgress?,
    val rerouting: Boolean,
) {
    val etaMillis: Long get() = System.currentTimeMillis() + (remainingSeconds * 1000).toLong()
}

sealed interface NavState {
    data object Idle : NavState
    data class Planning(val destination: String) : NavState
    data class Planned(val plan: RoutePlan) : NavState
    data class Navigating(val plan: RoutePlan, val progress: NavProgress?) : NavState
    data class Failed(val message: String) : NavState
}

/**
 * Uygulamanın navigasyon beyni: rota planlar, GPS konumlarını izler, yönlendirme ve
 * radar uyarılarını tetikler. Telefon arayüzü, Android Auto ve Gemini asistanı aynı
 * örneği kullanır.
 */
class NavigationController(private val graph: AppGraph) {

    private val _state = MutableStateFlow<NavState>(NavState.Idle)
    val state: StateFlow<NavState> = _state

    private val alertEngine = AlertEngine()
    private val warningVoice = WarningVoice(graph)
    private var tracker: NavigationTracker? = null
    private var fixJob: Job? = null
    private var simJob: Job? = null
    private var planJob: Job? = null
    private var rerouteJob: Job? = null
    private var hazardRefreshJob: Job? = null
    private var lastPreparedStep: RouteStep? = null
    private var lastRerouteAt = 0L
    private var lastBearing = 0f

    /**
     * Rotasız sürüşte koridor takibi: GPS açık olduğu sürece (telefon ekranı açık ya da Android
     * Auto bağlı) bir koridora girilince ortalama hızı kendiliğinden ölçer ve uyarır. Navigasyon
     * sırasında bu iş rotaya bağlı [AlertEngine]'dedir.
     */
    private val freeMonitor = FreeCorridorMonitor({ graph.knownCorridors() })
    private val _freeCorridor = MutableStateFlow<CorridorProgress?>(null)
    val freeCorridor: StateFlow<CorridorProgress?> = _freeCorridor

    init {
        graph.scope.launch { graph.location.fixes.filterNotNull().collect { onFreeFix(it) } }
    }

    private fun onFreeFix(fix: NavFix) {
        if (_state.value is NavState.Navigating) {
            if (_freeCorridor.value != null) {
                freeMonitor.reset()
                _freeCorridor.value = null
            }
            return
        }
        freeMonitor.update(fix.location, fix.speedKmh, fix.timeMs).forEach { warningVoice.handle(it) }
        _freeCorridor.value = freeMonitor.currentCorridor
    }

    val currentPlan: RoutePlan?
        get() = when (val s = _state.value) {
            is NavState.Planned -> s.plan
            is NavState.Navigating -> s.plan
            else -> null
        }

    val isNavigating: Boolean get() = _state.value is NavState.Navigating

    /** Yalnızca testler: Android Auto ekranlarını farklı durumlarda denemek için. */
    @androidx.annotation.VisibleForTesting
    internal fun setStateForTest(s: NavState) {
        _state.value = s
    }

    /**
     * Sürücü "burada seyyar radar var" dedi: konumu yalnızca telefona kaydeder, sonraki rotalarda
     * uyarılır. Sürücü genelde radarı geçtikten birkaç saniye sonra bildirir; rota üzerindeyken
     * konum hıza göre en fazla 250 m geri alınır. Konum yoksa null.
     */
    fun reportMobileRadar(announce: Boolean = true): com.radarnav.core.report.UserReport? {
        val where = currentReportLocation()
        if (where == null) {
            if (announce) graph.emit(UiEvent.Message("Konum henüz alınamadı, seyyar radar kaydedilemedi"))
            return null
        }
        val r = graph.userReports.report(where)
        if (announce) {
            val text = if (r.count > 1) "Tamam, burayı ${r.count}. kez işaretledin." else "Tamam, seyyar radar kaydedildi. Bir dahaki geçişte uyarırım."
            graph.voice.speak(text, com.radarnav.core.alert.WarningText.GUIDANCE_STYLE)
            graph.emit(UiEvent.Message("Seyyar radar kaydedildi (yalnızca bu telefonda). Silmek için düğmeye basılı tut."))
        }
        return r
    }

    /** "Burada radar yok": yakındaki kendi bildirimlerini siler. Silinen sayısı (konum yoksa null). */
    fun forgetMobileRadarHere(announce: Boolean = true): Int? {
        val where = graph.location.fixes.value?.location ?: (_state.value as? NavState.Navigating)?.progress?.location
        val n = where?.let { graph.userReports.removeNear(it) }
        if (announce) graph.emit(
            UiEvent.Message(
                when (n) {
                    null -> "Konum henüz alınamadı"
                    0 -> "Yakında kayıtlı seyyar radar yok"
                    else -> "Yakındaki $n seyyar radar kaydı silindi"
                },
            ),
        )
        return n
    }

    private fun currentReportLocation(): LatLng? {
        val nav = _state.value as? NavState.Navigating
        val p = nav?.progress
        if (nav != null && p != null && tracker?.route === nav.plan.route) {
            val back = (p.speedKmh / 3.6 * 3.0).coerceIn(0.0, 250.0)
            return nav.plan.route.geometry.pointAt((p.along - back).coerceAtLeast(0.0))
        }
        return graph.location.fixes.value?.location ?: p?.location
    }

    suspend fun search(query: String): List<Place> =
        graph.placeSearch.search(query, graph.location.current())

    /** Mevcut konumdan [place]'e rota planlar (navigasyonu başlatmaz). */
    fun plan(place: Place) {
        planJob?.cancel()
        planJob = graph.scope.launch { planNow(place) }
    }

    suspend fun planNow(place: Place): RoutePlan? {
        val origin = graph.location.current() ?: if (graph.settings.simulate) DEFAULT_SIM_ORIGIN else null
        if (origin == null) {
            _state.value = NavState.Failed("Konum alınamadı. GPS'i açıp konum izni verin.")
            return null
        }
        if (isNavigating) stopNavigation(silent = true)
        _state.value = NavState.Planning(place.name)
        return try {
            val (route, note) = computeRoute(origin, place.location)
            val hz = graph.hazardFinder.find(route, includeInactive = !graph.settings.activeRadarsOnly)
            val plan = RoutePlan(
                origin, place, route, hz.hazards,
                note = listOfNotNull(note, hz.warning).joinToString(" ").ifBlank { null },
                hazardsUpdatedAt = hz.updatedAt,
                hazardsUnavailable = hz.unavailable,
                routeNote = note,
                ministryRadarCount = hz.ministryRadarCount,
                radarsWithheld = hz.radarsWithheld,
            )
            graph.settings.addRecentPlace(place)
            graph.learnCorridors(plan.hazards)
            _state.value = NavState.Planned(plan)
            startHazardRefresh()
            plan
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = NavState.Failed(e.message ?: "Rota hesaplanamadı")
            null
        }
    }

    /** Seçili servis başarısız olursa OSRM ile tekrar dener. */
    private suspend fun computeRoute(from: LatLng, to: LatLng): Pair<NavRoute, String?> {
        val (provider, note) = graph.settings.routingProvider(graph.http)
        return try {
            provider.route(from, to) to note
        } catch (e: RoutingException) {
            if (provider is OsrmRouting) throw e
            val fallback: RoutingProvider = OsrmRouting(graph.http, graph.settings.osrmUrl)
            fallback.route(from, to) to "${provider.name} hatası (${e.message}); OSRM kullanıldı."
        }
    }

    fun clearPlan() {
        planJob?.cancel()
        if (!isNavigating) {
            hazardRefreshJob?.cancel()
            _state.value = NavState.Idle
        }
    }

    /**
     * Radar verisini arka planda kendiliğinden güncel tutar: servise ulaşılamadıysa 2 dakikada bir
     * yeniden dener, ulaşıldıysa (bakanlık verisi ~30 dk'da bir değiştiği için) 15 dakikada bir yeniler.
     */
    private fun startHazardRefresh() {
        hazardRefreshJob?.cancel()
        hazardRefreshJob = graph.scope.launch {
            while (isActive) {
                val plan = currentPlan ?: break
                delay(if (plan.hazardsUnavailable) 2 * 60_000L else 15 * 60_000L)
                val current = currentPlan ?: break
                val result = runCatching { graph.hazardFinder.find(current.route, force = true, includeInactive = !graph.settings.activeRadarsOnly, budgetMs = 90_000) }.getOrNull() ?: continue
                if (result.unavailable && result.hazards.isEmpty()) {
                    updatePlan(current.copy(note = listOfNotNull(current.routeNote, result.warning).joinToString(" ").ifBlank { null }))
                    continue
                }
                val merged = result.hazards.sortedBy { it.startAlong }
                val wasUnavailable = current.hazardsUnavailable
                updatePlan(
                    current.copy(
                        hazards = merged,
                        hazardsUpdatedAt = result.updatedAt,
                        hazardsUnavailable = false,
                        note = listOfNotNull(current.routeNote, result.warning).joinToString(" ").ifBlank { null },
                        ministryRadarCount = result.ministryRadarCount,
                        radarsWithheld = result.radarsWithheld,
                    ),
                )
                if (wasUnavailable) {
                    graph.emit(UiEvent.Message("Radar verisi alındı: ${merged.count { !it.isCorridor }} radar, ${merged.count { it.isCorridor }} koridor"))
                }
            }
        }
    }

    /** Planı (rota aynı kalarak) günceller; navigasyondaysa uyarı motoruna da yansıtır. */
    private fun updatePlan(plan: RoutePlan) {
        graph.learnCorridors(plan.hazards)
        when (val s = _state.value) {
            is NavState.Planned -> if (s.plan.route === plan.route) _state.value = NavState.Planned(plan)
            is NavState.Navigating -> if (s.plan.route === plan.route) applyRoute(plan)
            else -> Unit
        }
    }

    fun startNavigation() {
        val plan = currentPlan ?: return
        if (isNavigating) return
        tracker = NavigationTracker(plan.route)
        alertEngine.setHazards(plan.hazards, resetAlong = true)
        warningVoice.reset()
        _state.value = NavState.Navigating(plan, null)

        ContextCompat.startForegroundService(graph.app, Intent(graph.app, NavigationService::class.java))
        graph.location.acquire("nav")
        graph.heading.acquire("nav")

        fixJob?.cancel()
        fixJob = graph.scope.launch { graph.location.fixes.filterNotNull().collect { onFix(it) } }

        if (graph.settings.simulate) startSimulation()

        val radars = plan.radarCount
        val corridors = plan.corridorCount
        val summary = buildString {
            append("Rota başladı. ")
            append(com.radarnav.core.routing.TurkishInstructions.spokenDistance(plan.route.distanceMeters))
            append(". ")
            if (radars + corridors == 0) append("Yol üzerinde bilinen radar yok.")
            else {
                if (radars > 0) append("$radars radar")
                if (radars > 0 && corridors > 0) append(" ve ")
                if (corridors > 0) append("$corridors hız koridoru")
                append(" var.")
            }
            if (plan.radarsWithheld && plan.ministryRadarCount > 0) {
                append(" Bakanlık bugün bu yolda ${plan.ministryRadarCount} radar uygulaması bildiriyor, yerleri belli değil. Dikkatli sür.")
            }
        }
        if (graph.settings.voiceGuidance) graph.voice.speak(summary, com.radarnav.core.alert.WarningText.GUIDANCE_STYLE, budgetMs = 2_500)
    }

    fun stopNavigation(silent: Boolean = false) {
        if (!isNavigating) return
        fixJob?.cancel()
        simJob?.cancel()
        rerouteJob?.cancel()
        graph.location.simulationActive = false
        graph.location.release("nav")
        graph.heading.release("nav")
        tracker = null
        warningVoice.reset()
        graph.app.stopService(Intent(graph.app, NavigationService::class.java))
        hazardRefreshJob?.cancel()
        _state.value = NavState.Idle
        if (!silent) graph.emit(UiEvent.Message("Navigasyon bitti"))
    }

    /** Android Auto "otomatik sürüş" (DHU testleri) ya da ayarlar için. */
    fun startSimulation() {
        val plan = currentPlan ?: return
        simJob?.cancel()
        graph.location.simulationActive = true
        val geometry = plan.route.geometry
        val speed = graph.settings.simSpeedKmh
        simJob = graph.scope.launch {
            var along = tracker?.let { (_state.value as? NavState.Navigating)?.progress?.along } ?: 0.0
            while (isActive && along <= geometry.length) {
                graph.location.emitSimulated(
                    NavFix(
                        location = geometry.pointAt(along),
                        speedKmh = speed,
                        accuracyMeters = 5f,
                        timeMs = System.currentTimeMillis(),
                        bearing = geometry.bearingAt(along).toFloat(),
                    ),
                )
                along += speed / 3.6
                delay(1000)
            }
        }
    }

    private fun onFix(fix: NavFix) {
        val nav = _state.value as? NavState.Navigating ?: return
        val t = tracker ?: return
        val (track, navEvents) = t.update(fix)

        for (e in navEvents) when (e) {
            is NavEvent.Instruction -> if (graph.settings.voiceGuidance) {
                graph.voice.speak(e.text, com.radarnav.core.alert.WarningText.GUIDANCE_STYLE)
            }
            NavEvent.OffRoute -> reroute(fix.location)
            NavEvent.Arrived -> {
                graph.voice.speak("Varış noktanıza ulaştınız. İyi yolculuklar!", com.radarnav.core.alert.WarningText.GUIDANCE_STYLE)
                graph.scope.launch {
                    delay(4000)
                    stopNavigation()
                }
            }
        }

        // Sıradaki dönüş cümlesini Gemini sesiyle önceden hazırla (anında okunsun)
        track.nextStep?.let { step ->
            if (step !== lastPreparedStep && graph.settings.voiceGuidance) {
                lastPreparedStep = step
                graph.voice.prefetch(step.instruction, com.radarnav.core.alert.WarningText.GUIDANCE_STYLE)
            }
        }

        val alerts = alertEngine.update(track.along, fix.speedKmh, fix.timeMs)
        alerts.forEach { warningVoice.handle(it) }

        val next = alertEngine.nextHazard(track.along)
        // Hızlıyken GPS/rota yönü, dururken pusula (HeadingProvider yumuşatır)
        lastBearing = graph.heading.fused(
            fix.speedKmh, fix.bearing,
            if (track.offRoute) null else nav.plan.route.geometry.bearingAt(track.along).toFloat(),
        )
        val progress = NavProgress(
            location = if (track.offRoute) fix.location else track.snapped,
            bearing = lastBearing,
            speedKmh = fix.speedKmh,
            speedFromCar = fix.speedFromCar,
            along = track.along,
            remainingMeters = track.remainingMeters,
            remainingSeconds = track.remainingSeconds,
            nextStep = track.nextStep,
            distanceToStep = track.distanceToStep,
            currentLimit = alertEngine.applicableLimit(track.along),
            nextHazard = next?.first,
            distanceToHazard = next?.second,
            corridor = alertEngine.currentCorridor,
            rerouting = rerouteJob?.isActive == true,
        )
        _state.value = NavState.Navigating(nav.plan, progress)
    }

    private fun reroute(from: LatLng) {
        val nav = _state.value as? NavState.Navigating ?: return
        if (rerouteJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastRerouteAt < 15_000) return
        lastRerouteAt = now
        if (graph.settings.voiceGuidance) graph.voice.speak("Rota yeniden hesaplanıyor", com.radarnav.core.alert.WarningText.GUIDANCE_STYLE)
        rerouteJob = graph.scope.launch {
            try {
                val (route, _) = computeRoute(from, nav.plan.destination.location)
                // Bildiğimiz radarları yeni rotaya hemen yerleştir, sonra yeni bölümler için bakanlığa sor
                val known = graph.hazardFinder.place(route.geometry, nav.plan.hazards.map { it.hazard })
                applyRoute(nav.plan.copy(origin = from, route = route, hazards = known))
                val fresh = graph.hazardFinder.find(route, includeInactive = !graph.settings.activeRadarsOnly)
                if (fresh.hazards.isNotEmpty()) {
                    val merged = (known + fresh.hazards).distinctBy { it.hazard.id }.sortedBy { it.startAlong }
                    currentPlan?.takeIf { it.route === route }?.let {
                        applyRoute(it.copy(hazards = merged, hazardsUpdatedAt = fresh.updatedAt, hazardsUnavailable = false))
                    }
                }
                startHazardRefresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.emit(UiEvent.Message("Yeni rota hesaplanamadı: ${e.message}"))
            }
        }
    }

    private fun applyRoute(plan: RoutePlan) {
        val nav = _state.value as? NavState.Navigating ?: return
        if (tracker?.route !== plan.route) tracker = NavigationTracker(plan.route)
        alertEngine.setHazards(plan.hazards)
        _state.value = NavState.Navigating(plan, nav.progress)
    }

    /** Tüm rota tehlikeleri (rotadaki konuma göre sıralı). */
    fun upcomingHazards(): List<Pair<RouteHazard, Double>> {
        val plan = currentPlan ?: return emptyList()
        val along = (_state.value as? NavState.Navigating)?.progress?.along ?: 0.0
        return plan.hazards.filter { it.endAlong >= along }.map { it to (it.startAlong - along) }
    }

    companion object {
        /** Simülasyonda GPS yoksa başlangıç: Ankara, Kızılay. */
        val DEFAULT_SIM_ORIGIN = LatLng(39.9208, 32.8541)
    }
}
