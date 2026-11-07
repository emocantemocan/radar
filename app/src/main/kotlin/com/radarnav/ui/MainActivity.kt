package com.radarnav.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radarnav.R
import com.radarnav.UiEvent
import com.radarnav.assistant.AssistantStatus
import com.radarnav.audio.PhoneMicInput
import com.radarnav.core.alert.Severity
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.Place
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.routing.TurkishInstructions
import com.radarnav.databinding.ActivityMainBinding
import com.radarnav.databinding.ItemHazardBinding
import com.radarnav.databinding.ItemPlaceBinding
import com.radarnav.graph
import com.radarnav.map.MapStyles
import com.radarnav.map.NavMapController
import com.radarnav.nav.NavProgress
import com.radarnav.nav.NavState
import com.radarnav.nav.RoutePlan
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Telefon arayüzü. Tasarım: "gece sürüşü yol levhası". */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val g by lazy { graph }

    /** MapLibre haritası (stil yüklenince oluşur). */
    private var mapCtl: NavMapController? = null
    private var styleKey: String? = null
    private var fittedRoute: NavRoute? = null
    private var lastAlert: UiEvent.RadarAlert? = null
    private var assistantHidden = false

    private val dp get() = resources.displayMetrics.density

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        g.location.retry()
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startAssistant() else toast("Asistan için mikrofon izni gerekli")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.map.onCreate(savedInstanceState)
        // Android 15 uygulamayı kenardan kenara çizer: durum ve gezinme çubuklarına pay bırak
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            applyInsets(bars.top, bars.bottom)
            insets
        }
        // Alt sayfa, manevra bandı ya da alt çubuk boyut değiştirince yüzen öğeleri yeniden yerleştir
        val relayout = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) b.root.post { positionFloating() }
        }
        b.sheet.addOnLayoutChangeListener(relayout)
        b.maneuverCard.addOnLayoutChangeListener(relayout)
        b.navBar.addOnLayoutChangeListener(relayout)
        setupControls()
        ensureDisclaimer { requestBasePermissions() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { g.controller.state.collect { render(it) } }
                launch {
                    g.location.fixes.collect { fix ->
                        if (fix != null && !g.controller.isNavigating) mapCtl?.update(g.controller.state.value)
                    }
                }
                launch { g.heading.compass.collect { mapCtl?.onCompass() } }
                launch {
                    g.events.collect { e ->
                        when (e) {
                            is UiEvent.Message -> toast(e.text)
                            is UiEvent.RadarAlert -> onRadarAlert(e)
                        }
                    }
                }
                launch {
                    combine(
                        g.assistant.status, g.assistant.userText, g.assistant.modelText, g.assistant.muted,
                    ) { s, u, m, mute -> AssistantUi(s, u, m, mute) }.collect { renderAssistant(it) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        b.map.onStart()
        loadStyleIfNeeded()
        g.settings.mapNote?.let { toast(it) }
        g.location.acquire("phone")
        g.heading.acquire("phone")
        render(g.controller.state.value)
    }

    override fun onResume() {
        super.onResume()
        b.map.onResume()
    }

    override fun onPause() {
        b.map.onPause()
        super.onPause()
    }

    override fun onStop() {
        b.map.onStop()
        g.location.release("phone")
        g.heading.release("phone")
        super.onStop()
    }

    override fun onDestroy() {
        b.map.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        b.map.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.map.onSaveInstanceState(outState)
    }

    private fun onRadarAlert(e: UiEvent.RadarAlert) {
        lastAlert = e
        val s = g.controller.state.value
        val progress = (s as? NavState.Navigating)?.progress
        if (progress != null) {
            renderAlertCard(progress)
        } else {
            Snackbar.make(b.root, "${e.title}\n${e.text}", 6000)
                .setBackgroundTint(getColor(UiFormat.severityColor(e.severity)))
                .setTextColor(getColor(R.color.text))
                .setTextMaxLines(4)
                .show()
        }
    }

    // ---------------- Harita (MapLibre) ----------------

    /** Ayarlardaki harita stili değiştiyse (ya da ilk açılışta) stili yükler. */
    private fun loadStyleIfNeeded() {
        val key = MapStyles.key(g.settings)
        if (key == styleKey) return
        styleKey = key
        mapCtl = null
        fittedRoute = null
        b.map.getMapAsync { map ->
            map.setStyle(MapStyles.builder(g.settings)) { style ->
                val ctl = NavMapController(this, map, style, g) { b.map.height }
                ctl.onFollowChanged = { following -> b.recenterFab.alpha = if (following) 0.6f else 1f }
                mapCtl = ctl
                render(g.controller.state.value)
            }
        }
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
            lastAlert = null
            g.controller.startNavigation()
        }
        b.cancelButton.setOnClickListener { g.controller.clearPlan() }
        b.stopButton.setOnClickListener { g.controller.stopNavigation() }
        b.micFab.setOnClickListener {
            when {
                g.assistant.isRunning -> {
                    assistantHidden = false
                    renderAssistant(currentAssistantUi())
                }
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> startAssistant()
                else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        b.recenterFab.setOnClickListener { mapCtl?.recenter() }
        // Seyyar radar: tek dokunuş kaydeder, basılı tutmak yakındaki kendi kayıtlarını siler (yalnızca telefonda)
        b.reportFab.setOnClickListener { g.controller.reportMobileRadar() }
        b.reportFab.setOnLongClickListener {
            g.controller.forgetMobileRadarHere()
            true
        }
        b.assistantStop.setOnClickListener { g.assistant.stop() }
        b.muteButton.setOnClickListener { g.assistant.toggleMute() }
        b.assistantHide.setOnClickListener {
            assistantHidden = true
            renderAssistant(currentAssistantUi())
        }
        b.suggestion1.setOnClickListener { g.assistant.ask(b.suggestion1.text.toString()) }
        b.suggestion2.setOnClickListener { g.assistant.ask(b.suggestion2.text.toString()) }
    }

    private fun startAssistant() {
        assistantHidden = false
        g.assistant.start { PhoneMicInput() }
    }

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

    /** Kullanım koşulları kabul edilmeden uygulama kullanılamaz; reddedilirse kapanır. */
    private fun ensureDisclaimer(then: () -> Unit) {
        if (g.settings.disclaimerAccepted) return then()
        MaterialAlertDialogBuilder(this)
            .setTitle(com.radarnav.core.legal.Disclaimer.TITLE)
            .setMessage(com.radarnav.core.legal.Disclaimer.TEXT)
            .setCancelable(false)
            .setNegativeButton(com.radarnav.core.legal.Disclaimer.DECLINE) { _, _ -> finishAffinity() }
            .setPositiveButton(com.radarnav.core.legal.Disclaimer.ACCEPT) { _, _ ->
                g.settings.acceptDisclaimer()
                then()
            }
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

        b.searchGroup.visibility = if (navigating) View.GONE else View.VISIBLE
        b.maneuverCard.visibility = if (navigating) View.VISIBLE else View.GONE
        b.speedPanel.visibility = if (navigating) View.VISIBLE else View.GONE
        b.navBar.visibility = if (navigating) View.VISIBLE else View.GONE
        if (!navigating) b.alertCard.visibility = View.GONE
        b.progress.visibility = if (state is NavState.Planning) View.VISIBLE else View.GONE

        when (state) {
            NavState.Idle -> {
                fittedRoute = null
                renderRecents()
            }
            is NavState.Failed -> {
                fittedRoute = null
                renderRecents()
                toast(state.message)
            }
            is NavState.Planning -> renderPlanning(state.destination)
            is NavState.Planned -> renderPlanned(state.plan)
            is NavState.Navigating -> renderNavigating(state)
        }
        b.root.post { positionFloating() }
        mapCtl?.update(state)
    }

    /**
     * Yüzen öğeleri gerçek ölçülere göre yerleştirir (üst üste binmesinler):
     * - navigasyonda hız paneli ve sağ düğmeler manevra bandının hemen altında,
     *   uyarı kartı alt çubuğun hemen üstünde;
     * - diğer durumlarda sağ düğmeler alt sayfanın hemen üstünde.
     * Her yerleşim değişikliğinde (layout listener) yeniden çağrılır.
     */
    private var insetTop = 0

    private fun applyInsets(top: Int, bottom: Int) {
        insetTop = top
        fun px(v: Int) = (v * dp).toInt()
        b.searchGroup.updatePadding(top = px(16) + top)
        b.maneuverCard.updatePadding(top = px(18) + top)
        b.navBar.updatePadding(bottom = px(4) + bottom)
        b.navBar.updateLayoutParams { height = px(88) + bottom }
        b.sheet.updatePadding(bottom = bottom)
        b.assistantPanel.updatePadding(bottom = px(28) + bottom)
        b.root.post { positionFloating() }
    }

    private fun positionFloating() {
        val navigating = g.controller.isNavigating
        val gap = (12 * dp).toInt()
        val fab = b.fabColumn.layoutParams as FrameLayout.LayoutParams
        val speed = b.speedPanel.layoutParams as FrameLayout.LayoutParams
        val alert = b.alertCard.layoutParams as FrameLayout.LayoutParams
        if (navigating) {
            val top = (if (b.maneuverCard.visibility == View.VISIBLE) b.maneuverCard.height else insetTop) + gap
            fab.gravity = Gravity.END or Gravity.TOP
            fab.topMargin = top
            fab.bottomMargin = 0
            speed.topMargin = top
            alert.bottomMargin = b.navBar.height + gap
        } else {
            fab.gravity = Gravity.END or Gravity.BOTTOM
            fab.topMargin = 0
            fab.bottomMargin = (if (b.sheet.visibility == View.VISIBLE) b.sheet.height else 0) + (16 * dp).toInt()
            // Alt sayfa uzunsa radar listesini kısalt: sağdaki düğmeler arama çubuğu ile sayfa arasına sığsın
            if (b.sheet.visibility == View.VISIBLE && b.hazardScroll.visibility == View.VISIBLE && b.root.height > 0) {
                val maxSheet = b.root.height - b.searchGroup.bottom - b.fabColumn.height - (28 * dp).toInt()
                val over = b.sheet.height - maxSheet
                val target = (b.hazardScroll.height - over).coerceAtLeast((96 * dp).toInt())
                if (over > 0 && target < b.hazardScroll.height) {
                    setScrollHeight(target) // yerleşim değişince bu fonksiyon yeniden çağrılır
                    return
                }
            }
        }
        b.fabColumn.layoutParams = fab
        b.speedPanel.layoutParams = speed
        b.alertCard.layoutParams = alert
    }

    private fun setScrollHeight(h: Int) {
        val lp = b.hazardScroll.layoutParams
        lp.height = h
        b.hazardScroll.layoutParams = lp
    }

    private fun renderRecents() {
        val recents = g.settings.recentPlaces()
        // Son hedefler yalnızca alt sayfada gösterilir (üstte ayrıca çip göstermek kalabalık yapıyordu)
        b.recentChips.removeAllViews()
        b.recentScroll.visibility = View.GONE
        // Alt sayfa: son hedefler
        if (recents.isEmpty()) {
            b.sheet.visibility = View.GONE
            return
        }
        b.sheet.visibility = View.VISIBLE
        b.sheetTitle.textSize = 18f
        b.sheetTitle.text = "Son hedefler"
        b.sheetEtaRow.visibility = View.GONE
        b.sheetChips.visibility = View.GONE
        b.noteText.visibility = View.GONE
        b.listHeader.visibility = View.GONE
        b.sheetButtons.visibility = View.GONE
        b.hazardList.removeAllViews()
        b.hazardScroll.visibility = View.VISIBLE
        setScrollHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
        val inflater = LayoutInflater.from(this)
        recents.take(3).forEach { p ->
            val row = ItemPlaceBinding.inflate(inflater, b.hazardList, false)
            row.name.text = p.name
            row.address.text = p.address
            row.root.setOnClickListener { g.controller.plan(p) }
            b.hazardList.addView(row.root)
        }
        b.hazardList.setPadding(b.hazardList.paddingLeft, 0, b.hazardList.paddingRight, (20 * dp).toInt())
    }

    private fun renderPlanning(destination: String) {
        b.recentScroll.visibility = View.GONE
        b.sheet.visibility = View.VISIBLE
        b.sheetTitle.textSize = 24f
        b.sheetTitle.text = "Rota hesaplanıyor…"
        b.sheetEtaRow.visibility = View.GONE
        b.sheetChips.visibility = View.GONE
        b.noteText.visibility = View.VISIBLE
        b.noteText.setTextColor(getColor(R.color.text_secondary))
        b.noteText.text = "$destination · güzergahtaki radarlar kontrol ediliyor"
        b.listHeader.visibility = View.GONE
        b.hazardScroll.visibility = View.GONE
        b.sheetButtons.visibility = View.VISIBLE
        b.startButton.visibility = View.GONE
    }

    private fun renderPlanned(plan: RoutePlan) {
        b.recentScroll.visibility = View.GONE
        b.sheet.visibility = View.VISIBLE
        b.sheetTitle.textSize = 24f
        b.sheetTitle.text = plan.destination.name
        b.sheetEtaRow.visibility = View.VISIBLE
        b.sheetEta.text = TurkishInstructions.duration(plan.route.durationSeconds)
        b.sheetDistance.text = "${TurkishInstructions.shortDistance(plan.route.distanceMeters)} · ${plan.route.provider}"
        b.sheetChips.visibility = View.VISIBLE
        b.chipRadars.text = "${plan.radarCount} radar"
        b.chipCorridors.text = "${plan.corridorCount} hız koridoru"
        b.chipCorridors.visibility = if (plan.corridorCount > 0) View.VISIBLE else View.GONE
        b.noteText.setTextColor(getColor(R.color.warning_text))
        b.noteText.text = plan.note.orEmpty()
        b.noteText.visibility = if (plan.note.isNullOrBlank()) View.GONE else View.VISIBLE
        b.sheetButtons.visibility = View.VISIBLE
        b.startButton.visibility = View.VISIBLE
        b.listHeader.visibility = if (plan.hazards.isEmpty()) View.GONE else View.VISIBLE
        b.listHeader.text = "ROTA ÜZERİNDEKİ RADARLAR · ${UiFormat.clock(plan.hazardsUpdatedAt)} GÜNCEL"
        if (plan.hazardsUnavailable) {
            b.noteText.visibility = View.VISIBLE
            b.noteText.text = (plan.note ?: "Radar verisi alınamadı.") + " Arka planda yeniden deneniyor; " +
                "Ayarlar → Radar servisini test et ile ayrıntıya bakabilirsin."
        }
        fillHazardList(plan.hazards)
        // Rotanın tamamını alt sayfanın üstünde göster
        if (fittedRoute !== plan.route) {
            val route = plan.route
            b.sheet.post {
                val ctl = mapCtl ?: return@post
                fittedRoute = route
                ctl.setInsets(0, 0, 0, 0)
                ctl.fitRoute(route, b.sheet.height, b.searchGroup.bottom)
            }
        }
    }

    private fun fillHazardList(items: List<RouteHazard>) {
        b.hazardList.removeAllViews()
        b.hazardList.setPadding(b.hazardList.paddingLeft, 0, b.hazardList.paddingRight, 0)
        b.hazardScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        setScrollHeight(if (items.size <= 4) ViewGroup.LayoutParams.WRAP_CONTENT else (250 * dp).toInt())
        val inflater = LayoutInflater.from(this)
        items.forEachIndexed { i, h ->
            if (i > 0) inflater.inflate(R.layout.item_divider, b.hazardList, true)
            val row = ItemHazardBinding.inflate(inflater, b.hazardList, false)
            row.limit.text = h.hazard.speedLimit?.toString() ?: "?"
            row.limit.setBackgroundResource(if (h.isCorridor) R.drawable.bg_corridor_badge else R.drawable.bg_limit_small)
            row.limit.alpha = if (h.hazard.active) 1f else 0.5f
            row.title.text = UiFormat.hazardRowTitle(h)
            row.subtitle.text = UiFormat.hazardSubtitle(h)
            row.distance.text = TurkishInstructions.shortDistance(h.startAlong)
            row.root.contentDescription = "${UiFormat.hazardRowTitle(h)}, sınır ${h.hazard.speedLimit ?: "bilinmiyor"}, " +
                "${TurkishInstructions.shortDistance(h.startAlong)} sonra"
            row.root.setOnClickListener {
                val route = (g.controller.state.value as? NavState.Planned)?.plan?.route ?: return@setOnClickListener
                mapCtl?.flyTo(route.geometry.pointAt(h.startAlong))
            }
            b.hazardList.addView(row.root)
        }
    }

    private fun renderNavigating(state: NavState.Navigating) {
        fittedRoute = null
        // Kamera: üstteki manevra bandı ve alttaki çubuk haritanın önünü kapatmasın
        mapCtl?.setInsetsIfChanged(0, b.maneuverCard.height, 0, b.navBar.height)
        b.sheet.visibility = View.GONE
        b.recentScroll.visibility = View.GONE
        val p = state.progress
        if (p == null) {
            b.maneuverDistance.text = "GPS bekleniyor…"
            b.maneuverText.text = state.plan.destination.name
            b.remainingText.text = TurkishInstructions.shortDistance(state.plan.route.distanceMeters)
            b.etaText.text = "${state.plan.radarCount} radar · ${state.plan.corridorCount} hız koridoru"
            b.speedText.text = "–"
            b.speedSource.text = ""
            return
        }
        // Manevra bandı
        b.maneuverIcon.setImageResource(UiFormat.maneuverIcon(p.nextStep?.kind))
        b.maneuverDistance.text = TurkishInstructions.shortDistance(p.distanceToStep)
        b.maneuverText.text = if (p.rerouting) "Rota yeniden hesaplanıyor…" else p.nextStep?.instruction ?: "Devam edin"

        // Hız ve sınır levhası
        val limit = p.currentLimit
        val over = limit != null && p.speedKmh > limit
        b.speedText.text = p.speedKmh.roundToInt().toString()
        b.speedText.setTextColor(getColor(if (over) R.color.speed_over else R.color.text))
        b.speedSource.text = if (p.speedFromCar) "Araç hızı" else "GPS hızı"
        b.limitBadge.visibility = if (limit != null) View.VISIBLE else View.GONE
        b.limitBadge.text = limit?.toString().orEmpty()
        b.limitBadge.contentDescription = limit?.let { "Hız sınırı $it" }

        // Alt çubuk
        val remainingRadars = state.plan.hazards.count { !it.isCorridor && it.startAlong > p.along }
        b.remainingText.text =
            "${TurkishInstructions.shortDistance(p.remainingMeters)} · ${TurkishInstructions.duration(p.remainingSeconds)}"
        b.etaText.text = "Varış ${UiFormat.clock(p.etaMillis)} · $remainingRadars radar kaldı"

        renderAlertCard(p)
    }

    /**
     * Radar uyarı kartı: sıradaki radar 2 km içindeyse ya da koridordaysak görünür.
     * Sertlik kademesi anlık hıza göre canlı hesaplanır (çerçeve, dolu çubuk sayısı, etiket).
     */
    private fun renderAlertCard(p: NavProgress) {
        val corridor = p.corridor
        val next = p.nextHazard
        val nextDistance = p.distanceToHazard
        val target: RouteHazard
        val distanceText: String
        val kindText: String
        val speedForSeverity: Double
        val detail: String
        if (corridor != null) {
            target = corridor.hazard
            distanceText = "kalan ${TurkishInstructions.shortDistance(corridor.remainingMeters)}"
            kindText = "KORİDORDASIN"
            val avg = corridor.averageKmh
            speedForSeverity = avg ?: p.speedKmh
            detail = (avg?.let { "Ortalaman ${it.roundToInt()} km/sa" } ?: "Ortalama hesaplanıyor") +
                (target.hazard.speedLimit?.let { " · sınır $it" } ?: "")
        } else if (next != null && nextDistance != null && nextDistance <= 2_000) {
            target = next
            distanceText = TurkishInstructions.shortDistance(nextDistance.coerceAtLeast(0.0))
            kindText = if (next.isCorridor) "HIZ KORİDORU" else "RADAR"
            speedForSeverity = p.speedKmh
            val limit = next.hazard.speedLimit
            detail = "${p.speedKmh.roundToInt()} km/sa" + (
                limit?.let {
                    val diff = (p.speedKmh - it).roundToInt()
                    " · sınır $it · " + if (diff > 0) "+$diff km/sa" else "sınırın altında"
                } ?: " · sınır bilinmiyor"
                )
        } else {
            b.alertCard.visibility = View.GONE
            return
        }

        val limit = target.hazard.speedLimit
        val severity = Severity.of(speedForSeverity, limit)
        val level = UiFormat.severityLevel(severity)
        val borderColor = getColor(UiFormat.severityBorderColor(severity))
        val labelColor = getColor(UiFormat.severityLabelColor(severity))

        b.alertCard.visibility = View.VISIBLE
        b.alertCard.background = GradientDrawable().apply {
            setColor(getColor(R.color.surface))
            cornerRadius = 22 * dp
            setStroke(((if (severity >= Severity.SASSY) 2 else 1) * dp).roundToInt(), borderColor)
        }
        b.alertLimit.text = limit?.toString() ?: "?"
        b.alertLimit.setBackgroundResource(if (target.isCorridor) R.drawable.bg_corridor_badge else R.drawable.bg_limit)
        b.alertKind.text = "$kindText · ${UiFormat.personaTag(g.settings.persona)}"
        b.alertKind.setTextColor(labelColor)
        b.alertDistance.text = distanceText

        val alert = lastAlert?.takeIf { it.hazardId == target.hazard.id }
        b.alertText.text = alert?.text ?: buildString {
            append(if (target.isCorridor) "Hız koridoru" else "Radar")
            if (corridor == null) append(" $distanceText ileride.") else append(" bölgesindesin.")
            limit?.let { append(" Sınır $it.") }
        }

        for (i in 0 until b.severityBars.childCount) {
            val bar = b.severityBars.getChildAt(i)
            bar.background = bar.background.mutate().also {
                it.setTint(if (i < level) borderColor else getColor(R.color.sev_off))
            }
        }
        b.severityLabel.text = "Seviye $level/4 · ${UiFormat.severityName(severity)}"
        b.severityLabel.setTextColor(labelColor)
        b.severityDetail.text = detail
        b.alertCard.contentDescription = "${b.alertKind.text}, $distanceText. ${b.alertText.text}. ${b.severityLabel.text}"
    }

    // ---------------- Gemini Live paneli ----------------

    private data class AssistantUi(val status: AssistantStatus, val user: String, val model: String, val muted: Boolean)

    private fun currentAssistantUi() = AssistantUi(
        g.assistant.status.value, g.assistant.userText.value, g.assistant.modelText.value, g.assistant.muted.value,
    )

    private fun renderAssistant(ui: AssistantUi) {
        val running = ui.status != AssistantStatus.IDLE
        if (!running) assistantHidden = false
        val showPanel = running && !assistantHidden
        b.assistantPanel.visibility = if (showPanel) View.VISIBLE else View.GONE
        b.micFab.visibility = if (showPanel) View.GONE else View.VISIBLE
        b.micFab.contentDescription = if (running) "Gemini panelini aç" else "Gemini asistanı başlat"
        if (!running) return

        val statusText = when (ui.status) {
            AssistantStatus.CONNECTING -> "Bağlanıyor"
            AssistantStatus.LISTENING -> if (ui.muted) "Mikrofon kapalı" else "Dinliyor"
            AssistantStatus.SPEAKING -> "Konuşuyor"
            AssistantStatus.IDLE -> ""
        }
        b.assistantStatus.text = "Gemini Live · ${g.settings.persona.title.substringBefore(" (")} · $statusText"
        val dotColor = when {
            ui.status == AssistantStatus.CONNECTING -> R.color.corridor_orange
            ui.muted -> R.color.text_tertiary
            else -> R.color.live_green
        }
        b.liveDot.background = b.liveDot.background.mutate().also { it.setTint(getColor(dotColor)) }
        b.userBubble.visibility = if (ui.user.isBlank()) View.GONE else View.VISIBLE
        b.userText.text = ui.user
        b.aiBubble.visibility = if (ui.model.isBlank()) View.GONE else View.VISIBLE
        b.aiText.text = ui.model
        val navigating = g.controller.isNavigating
        b.suggestion1.text = if (navigating) "Sıradaki radar nerede?" else "Beni Bolu'ya götür"
        b.suggestion2.text = if (navigating) "Ne zaman varırım?" else "Yoldaki radarları say"
        b.muteButton.setImageResource(if (ui.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        b.muteButton.contentDescription = if (ui.muted) "Mikrofonu aç" else "Mikrofonu sessize al"
        b.conversationScroll.post { b.conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
