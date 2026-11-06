package com.radarnav.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radarnav.R
import com.radarnav.UiEvent
import com.radarnav.assistant.AssistantStatus
import com.radarnav.audio.PhoneMicInput
import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.Place
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.TurkishInstructions
import com.radarnav.databinding.ActivityMainBinding
import com.radarnav.databinding.ItemHazardBinding
import com.radarnav.graph
import com.radarnav.nav.NavState
import com.radarnav.nav.RoutePlan
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val g by lazy { graph }

    private var routeLine: Polyline? = null
    private val hazardOverlays = ArrayList<org.osmdroid.views.overlay.Overlay>()
    private lateinit var meMarker: Marker
    private var shownRoute: NavRoute? = null
    private var shownHazards: List<RouteHazard>? = null
    private var follow = true
    private var centeredOnce = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        g.location.retry()
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) toggleAssistant() else toast("Asistan için mikrofon izni gerekli")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupMap()
        setupControls()
        requestBasePermissions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { g.controller.state.collect { render(it) } }
                launch { g.location.fixes.collect { fix -> if (fix != null && !g.controller.isNavigating) showMe(fix.location, null) } }
                launch {
                    g.events.collect { e ->
                        when (e) {
                            is UiEvent.Message -> toast(e.text)
                            is UiEvent.RadarAlert -> Snackbar.make(b.root, "${e.title}\n${e.text}", 6000).show()
                        }
                    }
                }
                launch { g.assistant.status.collect { renderAssistant(it) } }
                launch {
                    g.assistant.caption.collect { text ->
                        b.assistantCaption.text = text
                        b.assistantCaption.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        b.map.onResume()
        g.location.acquire("phone")
    }

    override fun onStop() {
        super.onStop()
        b.map.onPause()
        g.location.release("phone")
    }

    // ---------------- Harita ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        b.map.setTileSource(TileSourceFactory.MAPNIK)
        b.map.setMultiTouchControls(true)
        b.map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        b.map.isTilesScaledToDpi = true
        b.map.controller.setZoom(6.0)
        b.map.controller.setCenter(GeoPoint(39.0, 35.0))
        b.map.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_MOVE) follow = false
            false
        }
        meMarker = Marker(b.map).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_navigation)?.mutate()?.also {
                it.setTint(getColor(R.color.brand))
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isFlat = true
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    private fun showMe(p: LatLng, bearing: Float?) {
        meMarker.position = GeoPoint(p.lat, p.lon)
        meMarker.rotation = -(bearing ?: 0f)
        if (!b.map.overlays.contains(meMarker)) b.map.overlays.add(meMarker)
        if (!centeredOnce) {
            centeredOnce = true
            b.map.controller.setZoom(15.0)
            b.map.controller.setCenter(meMarker.position)
        }
        b.map.invalidate()
    }

    private fun drawPlan(plan: RoutePlan) {
        if (shownRoute === plan.route && shownHazards === plan.hazards) return
        val newRoute = shownRoute !== plan.route
        clearRoute()
        shownRoute = plan.route
        shownHazards = plan.hazards

        val pts = plan.route.points.map { GeoPoint(it.lat, it.lon) }
        val line = Polyline(b.map).apply {
            setPoints(pts)
            outlinePaint.color = getColor(R.color.brand)
            outlinePaint.strokeWidth = 14f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            setOnClickListener { _, _, _ -> true }
        }
        routeLine = line
        b.map.overlays.add(0, line)

        val geometry = plan.route.geometry
        val size = (34 * resources.displayMetrics.density).roundToInt()
        for (h in plan.hazards) {
            if (h.hazard is SpeedCorridor) {
                val corridorLine = Polyline(b.map).apply {
                    setPoints(geometry.slice(h.startAlong, h.endAlong).map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = getColor(R.color.corridor_orange)
                    outlinePaint.strokeWidth = 16f
                    setOnClickListener { _, _, _ -> true }
                }
                hazardOverlays.add(corridorLine)
                b.map.overlays.add(1, corridorLine)
            }
            val at = geometry.pointAt(h.startAlong)
            val marker = Marker(b.map).apply {
                position = GeoPoint(at.lat, at.lon)
                icon = BitmapDrawable(resources, UiFormat.hazardBitmap(this@MainActivity, h.hazard.speedLimit, h.isCorridor, size))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = UiFormat.hazardTitle(h)
                snippet = UiFormat.hazardSubtitle(h)
            }
            hazardOverlays.add(marker)
            b.map.overlays.add(marker)
        }
        if (newRoute && !g.controller.isNavigating) {
            b.map.post {
                runCatching {
                    b.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.25f), true, 80)
                }
            }
        }
        b.map.invalidate()
    }

    private fun clearRoute() {
        routeLine?.let { b.map.overlays.remove(it) }
        hazardOverlays.forEach { b.map.overlays.remove(it) }
        hazardOverlays.clear()
        routeLine = null
        shownRoute = null
        shownHazards = null
        b.map.invalidate()
    }

    // ---------------- Kontroller ----------------

    private fun setupControls() {
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        b.searchButton.setOnClickListener { doSearch() }
        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.startButton.setOnClickListener {
            if (!g.location.hasPermission() && !g.settings.simulate) {
                requestBasePermissions()
                return@setOnClickListener
            }
            follow = true
            g.controller.startNavigation()
        }
        b.cancelButton.setOnClickListener {
            if (g.controller.isNavigating) g.controller.stopNavigation() else g.controller.clearPlan()
        }
        b.micFab.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                toggleAssistant()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        b.recenterFab.setOnClickListener {
            follow = true
            g.location.current()?.let { b.map.controller.animateTo(GeoPoint(it.lat, it.lon), 16.0, 600L) }
        }
    }

    private fun toggleAssistant() = g.assistant.toggle { PhoneMicInput() }

    private fun doSearch() {
        val q = b.searchInput.text.toString().trim()
        if (q.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(b.searchInput.windowToken, 0)
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = runCatching { g.controller.search(q) }
            b.progress.visibility = View.GONE
            results.onFailure { toast("Arama başarısız: ${it.message}") }
            results.onSuccess { places ->
                when {
                    places.isEmpty() -> toast("Sonuç bulunamadı")
                    places.size == 1 -> g.controller.plan(places[0])
                    else -> pickPlace(places)
                }
            }
        }
    }

    private fun pickPlace(places: List<Place>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hedef seçin")
            .setItems(places.map { "${it.name}\n${it.address}" }.toTypedArray()) { _, i -> g.controller.plan(places[i]) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun requestBasePermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------------- Durum çizimi ----------------

    private fun render(state: NavState) {
        val navigating = state is NavState.Navigating
        if (navigating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.searchCard.visibility = if (navigating) View.GONE else View.VISIBLE
        b.maneuverCard.visibility = if (navigating) View.VISIBLE else View.GONE
        b.speedPanel.visibility = if (navigating) View.VISIBLE else View.GONE
        b.progress.visibility = if (state is NavState.Planning) View.VISIBLE else View.GONE

        when (state) {
            NavState.Idle -> {
                clearRoute()
                b.bottomCard.visibility = View.GONE
                b.map.mapOrientation = 0f
            }
            is NavState.Failed -> {
                clearRoute()
                b.bottomCard.visibility = View.GONE
                toast(state.message)
            }
            is NavState.Planning -> {
                b.bottomCard.visibility = View.VISIBLE
                b.summaryTitle.text = "Rota hesaplanıyor…"
                b.summaryText.text = "${state.destination} · radarlar kontrol ediliyor"
                b.noteText.visibility = View.GONE
                b.hazardScroll.visibility = View.GONE
                b.startButton.visibility = View.GONE
                b.cancelButton.text = getString(R.string.close)
            }
            is NavState.Planned -> renderPlanned(state.plan)
            is NavState.Navigating -> renderNavigating(state)
        }
    }

    private fun renderPlanned(plan: RoutePlan) {
        drawPlan(plan)
        b.map.mapOrientation = 0f
        b.bottomCard.visibility = View.VISIBLE
        b.summaryTitle.text = "${plan.destination.name} · ${TurkishInstructions.shortDistance(plan.route.distanceMeters)} · " +
            TurkishInstructions.duration(plan.route.durationSeconds)
        b.summaryText.text = "${plan.radarCount} radar · ${plan.corridorCount} hız koridoru · ${plan.route.provider}"
        b.noteText.text = plan.note.orEmpty()
        b.noteText.visibility = if (plan.note.isNullOrBlank()) View.GONE else View.VISIBLE
        b.startButton.visibility = View.VISIBLE
        b.cancelButton.text = getString(R.string.close)
        fillHazardList(plan.hazards.map { it to it.startAlong })
    }

    private fun renderNavigating(state: NavState.Navigating) {
        drawPlan(state.plan)
        b.bottomCard.visibility = View.VISIBLE
        b.startButton.visibility = View.GONE
        b.hazardScroll.visibility = View.GONE
        b.cancelButton.text = getString(R.string.stop_navigation)
        val p = state.progress
        if (p == null) {
            b.maneuverDistance.text = "GPS bekleniyor…"
            b.maneuverText.text = state.plan.destination.name
            b.summaryTitle.text = state.plan.destination.name
            b.summaryText.text = "${state.plan.radarCount} radar · ${state.plan.corridorCount} hız koridoru"
            b.noteText.visibility = View.GONE
            return
        }
        showMe(p.location, p.bearing)
        if (follow) {
            b.map.controller.animateTo(GeoPoint(p.location.lat, p.location.lon), 17.0, 800L)
            b.map.mapOrientation = -p.bearing
        }

        b.maneuverIcon.setImageResource(UiFormat.maneuverIcon(p.nextStep?.kind))
        b.maneuverDistance.text = TurkishInstructions.shortDistance(p.distanceToStep)
        b.maneuverText.text = if (p.rerouting) "Rota yeniden hesaplanıyor…" else p.nextStep?.instruction ?: "Devam edin"

        b.speedText.text = p.speedKmh.roundToInt().toString()
        val limit = p.currentLimit
        val over = limit != null && p.speedKmh > limit + 2
        b.speedText.setTextColor(if (over) getColor(R.color.radar_red) else getColor(R.color.panel_text))
        b.limitBadge.visibility = if (limit != null) View.VISIBLE else View.GONE
        b.limitBadge.text = limit?.toString().orEmpty()

        b.summaryTitle.text = "${TurkishInstructions.shortDistance(p.remainingMeters)} · " +
            "${TurkishInstructions.duration(p.remainingSeconds)} · Varış ${UiFormat.clock(p.etaMillis)}"
        val corridor = p.corridor
        val nextHazard = p.nextHazard
        b.summaryText.text = when {
            corridor != null -> {
                val c = corridor
                "Hız koridorundasınız · sınır ${c.hazard.hazard.speedLimit ?: "?"} · " +
                    (c.averageKmh?.let { "ortalamanız ${it.roundToInt()}" } ?: "ortalama hesaplanıyor") +
                    " · kalan ${TurkishInstructions.shortDistance(c.remainingMeters)}"
            }
            nextHazard != null -> {
                val h = nextHazard
                "Sıradaki: ${UiFormat.hazardTitle(h)} · ${TurkishInstructions.shortDistance((p.distanceToHazard ?: 0.0).coerceAtLeast(0.0))}" +
                    (h.hazard.speedLimit?.let { " · sınır $it" } ?: "")
            }
            else -> "Önünüzde bilinen radar yok"
        }
        b.noteText.visibility = View.GONE
    }

    private fun fillHazardList(items: List<Pair<RouteHazard, Double>>) {
        b.hazardList.removeAllViews()
        b.hazardScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        items.forEach { (h, along) ->
            val row = ItemHazardBinding.inflate(inflater, b.hazardList, false)
            row.limit.text = h.hazard.speedLimit?.toString() ?: "?"
            row.limit.setBackgroundResource(R.drawable.bg_limit)
            row.title.text = UiFormat.hazardTitle(h)
            row.subtitle.text = UiFormat.hazardSubtitle(h)
            row.distance.text = TurkishInstructions.shortDistance(along)
            row.root.setOnClickListener {
                follow = false
                val p = shownRoute?.geometry?.pointAt(h.startAlong) ?: return@setOnClickListener
                b.map.controller.animateTo(GeoPoint(p.lat, p.lon), 16.0, 600L)
            }
            b.hazardList.addView(row.root)
        }
    }

    private fun renderAssistant(status: AssistantStatus) {
        val color = when (status) {
            AssistantStatus.IDLE -> R.color.brand
            AssistantStatus.CONNECTING -> R.color.corridor_orange
            AssistantStatus.LISTENING -> R.color.banner
            AssistantStatus.SPEAKING -> R.color.radar_red
        }
        b.micFab.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(color))
        b.micFab.setImageResource(if (status == AssistantStatus.IDLE) R.drawable.ic_mic else R.drawable.ic_close)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
