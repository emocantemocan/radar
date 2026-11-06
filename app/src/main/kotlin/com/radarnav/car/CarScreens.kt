package com.radarnav.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.lifecycleScope
import com.radarnav.R
import com.radarnav.assistant.AssistantStatus
import com.radarnav.core.model.Place
import com.radarnav.core.routing.TurkishInstructions
import com.radarnav.graph
import com.radarnav.nav.NavState
import com.radarnav.nav.RoutePlan
import com.radarnav.ui.UiFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun CarContext.listLimit(default: Int = 6): Int =
    if (carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
        runCatching {
            getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(default)
    } else default

/** Ana ekran: harita + sıradaki manevra + kalan süre. */
class CarNavScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {

    private val graph = carContext.graph
    private var lastKey = ""

    init {
        lifecycleScope.launch {
            graph.controller.state.collect { s ->
                val key = keyOf(s)
                if (key != lastKey) {
                    lastKey = key
                    invalidate()
                }
            }
        }
        lifecycleScope.launch { graph.assistant.status.collect { invalidate() } }
    }

    private fun keyOf(s: NavState): String = when (s) {
        is NavState.Navigating -> {
            val p = s.progress
            if (p == null) "nav-wait" else
                "nav|${p.nextStep?.instruction}|${CarFormat.distance(p.distanceToStep).displayDistance}|" +
                    "${(p.remainingMeters / 1000).roundToInt()}|${(p.remainingSeconds / 60).roundToInt()}|${p.rerouting}"
        }
        is NavState.Planned -> "planned|${s.plan.destination.name}"
        is NavState.Planning -> "planning"
        is NavState.Failed -> "failed"
        NavState.Idle -> "idle"
    }

    private fun micAction(): Action {
        val running = graph.assistant.status.value != AssistantStatus.IDLE
        return Action.Builder()
            .setTitle(if (running) "Asistanı kapat" else "Asistan")
            .setIcon(CarFormat.icon(carContext, R.drawable.ic_mic))
            .setOnClickListener { carContext.toggleAssistant(graph) }
            .build()
    }

    override fun onGetTemplate(): Template {
        val state = graph.controller.state.value
        val strip = ActionStrip.Builder()
        if (state is NavState.Navigating) {
            strip.addAction(
                Action.Builder().setTitle("Radarlar")
                    .setOnClickListener { screenManager.push(CarHazardListScreen(carContext)) }.build(),
            )
            strip.addAction(micAction())
            strip.addAction(
                Action.Builder().setTitle("Bitir")
                    .setOnClickListener { graph.controller.stopNavigation() }.build(),
            )
        } else {
            strip.addAction(
                Action.Builder().setIcon(CarFormat.icon(carContext, R.drawable.ic_search))
                    .setOnClickListener { screenManager.push(CarSearchScreen(carContext)) }.build(),
            )
            strip.addAction(micAction())
            if (state is NavState.Planned) {
                strip.addAction(
                    Action.Builder().setTitle("Rota")
                        .setOnClickListener {
                            screenManager.push(CarRoutePreviewScreen(carContext, state.plan.destination, planned = true))
                        }.build(),
                )
            }
        }

        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(Action.Builder().setIcon(CarFormat.icon(carContext, R.drawable.ic_zoom_in)).setOnClickListener { renderer.zoomIn() }.build())
            .addAction(Action.Builder().setIcon(CarFormat.icon(carContext, R.drawable.ic_zoom_out)).setOnClickListener { renderer.zoomOut() }.build())
            .addAction(Action.Builder().setIcon(CarFormat.icon(carContext, R.drawable.ic_my_location)).setOnClickListener { renderer.recenter() }.build())
            .build()

        val b = NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setMapActionStrip(mapStrip)
            .setPanModeListener { }

        when (state) {
            is NavState.Navigating -> {
                val p = state.progress
                val step = p?.nextStep
                if (p == null || step == null) {
                    b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
                } else {
                    b.setNavigationInfo(
                        RoutingInfo.Builder()
                            .setCurrentStep(CarFormat.step(carContext, step), CarFormat.distance(p.distanceToStep))
                            .build(),
                    )
                }
                if (p != null) b.setDestinationTravelEstimate(CarFormat.travelEstimate(p.remainingMeters, p.remainingSeconds))
            }
            is NavState.Planning -> b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            else -> Unit
        }
        return b.build()
    }
}

/** Yer arama (klavye ya da arabanın sesli girişi). */
class CarSearchScreen(carContext: CarContext) : Screen(carContext) {
    private val graph = carContext.graph
    private var results: List<Place> = emptyList()
    private var loading = false
    private var message: String? = null
    private var job: Job? = null

    private fun search(text: String) {
        if (text.isBlank()) return
        job?.cancel()
        loading = true
        invalidate()
        job = lifecycleScope.launch {
            val r = runCatching { graph.controller.search(text) }
            results = r.getOrDefault(emptyList())
            message = when {
                r.isFailure -> "Arama başarısız: ${r.exceptionOrNull()?.message}"
                results.isEmpty() -> "Sonuç bulunamadı"
                else -> null
            }
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) = search(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Nereye?")
            .setShowKeyboardByDefault(false)
        if (loading) {
            b.setLoading(true)
        } else {
            val list = ItemList.Builder()
            if (results.isEmpty()) list.setNoItemsMessage(message ?: "Bir yer adı yazın ya da söyleyin")
            results.take(carContext.listLimit()).forEach { p ->
                list.addItem(
                    Row.Builder().setTitle(p.name).addText(p.address)
                        .setOnClickListener { screenManager.push(CarRoutePreviewScreen(carContext, p)) }
                        .build(),
                )
            }
            b.setItemList(list.build())
        }
        return b.build()
    }
}

/** Rota önizleme: mesafe, süre, radar sayısı ve "Başlat". */
class CarRoutePreviewScreen(
    carContext: CarContext,
    private val place: Place,
    planned: Boolean = false,
) : Screen(carContext) {
    private val graph = carContext.graph

    init {
        if (!planned) graph.controller.plan(place)
        lifecycleScope.launch { graph.controller.state.collect { invalidate() } }
    }

    private fun planOrNull(): RoutePlan? = when (val s = graph.controller.state.value) {
        is NavState.Planned -> s.plan
        is NavState.Navigating -> s.plan
        else -> null
    }

    override fun onGetTemplate(): Template {
        val state = graph.controller.state.value
        if (state is NavState.Failed) {
            return MessageTemplate.Builder(state.message)
                .setTitle("Rota bulunamadı")
                .setHeaderAction(Action.BACK)
                .addAction(Action.Builder().setTitle("Tekrar dene").setOnClickListener { graph.controller.plan(place) }.build())
                .build()
        }
        val b = RoutePreviewNavigationTemplate.Builder()
            .setTitle(place.name)
            .setHeaderAction(Action.BACK)
        val plan = planOrNull()
        if (plan == null) {
            b.setLoading(true)
            return b.build()
        }
        // RoutePreviewNavigationTemplate her satırda mesafe/süre "span"ı ister; düz metin verilirse
        // oluşturucu IllegalArgumentException fırlatır ve uygulama arabada çöker (testle yakalandı).
        val title = android.text.SpannableString("  ·  ").apply {
            setSpan(androidx.car.app.model.DurationSpan.create(plan.route.durationSeconds.toLong()), 0, 1, 0)
            setSpan(androidx.car.app.model.DistanceSpan.create(CarFormat.distance(plan.route.distanceMeters)), 4, 5, 0)
        }
        val hazardsText = buildString {
            append("${plan.radarCount} radar · ${plan.corridorCount} hız koridoru")
            if (plan.radarsWithheld && plan.ministryRadarCount > 0) append(" · bakanlık: bugün ${plan.ministryRadarCount} denetim")
        }
        val row = Row.Builder()
            .setTitle(androidx.car.app.model.CarText.create(title))
            .addText(hazardsText)
        plan.note?.let { row.addText(it) }
        b.setItemList(ItemList.Builder().addItem(row.build()).setOnSelectedListener { }.build())
        b.setNavigateAction(
            Action.Builder().setTitle("Başlat").setOnClickListener {
                graph.controller.startNavigation()
                screenManager.popToRoot()
            }.build(),
        )
        b.setActionStrip(
            ActionStrip.Builder().addAction(
                Action.Builder().setTitle("Radarlar")
                    .setOnClickListener { screenManager.push(CarHazardListScreen(carContext)) }.build(),
            ).build(),
        )
        return b.build()
    }
}

/** Güzergahtaki radar ve hız koridorlarının listesi. */
class CarHazardListScreen(carContext: CarContext) : Screen(carContext) {
    private val graph = carContext.graph

    override fun onGetTemplate(): Template {
        val items = graph.controller.upcomingHazards()
        val list = ItemList.Builder()
        if (items.isEmpty()) list.setNoItemsMessage("Güzergahta bilinen radar yok")
        val size = (40 * carContext.resources.displayMetrics.density).roundToInt()
        items.take(carContext.listLimit()).forEach { (h, d) ->
            val title = UiFormat.hazardTitle(h) + (h.hazard.speedLimit?.let { " · Sınır $it" } ?: "")
            val row = Row.Builder()
                .setTitle(title)
                .addText(if (d <= 0) "Şu an içindesiniz" else "${TurkishInstructions.shortDistance(d)} sonra")
                .setImage(CarFormat.icon(UiFormat.hazardBitmap(carContext, h.hazard.speedLimit, h.isCorridor, size, h.hazard.active)))
            UiFormat.hazardSubtitle(h).takeIf { it.isNotBlank() }?.let { row.addText(it) }
            list.addItem(row.build())
        }
        return ListTemplate.Builder()
            .setTitle("Güzergahtaki radarlar (${items.size})")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
