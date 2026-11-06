package com.radarnav.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.radarnav.AppGraph
import com.radarnav.R
import com.radarnav.UiEvent
import com.radarnav.map.MapStyles
import com.radarnav.map.NavMapController
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * Android Auto haritası. Navigasyon uygulamalarında haritayı uygulama çizer: arabanın ekran
 * yüzeyine bir sanal ekran (VirtualDisplay) açıp içinde telefonla aynı MapLibre haritasını
 * çalıştırırız. Böylece araba ekranında da vektör harita, yola yapışık kayan ok ve 3D eğik,
 * yön yukarı kamera olur. Üstte hız / sınır / uyarı kartı katmanı ([CarHudView]) durur.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    private val graph: AppGraph,
    lifecycle: Lifecycle,
) : SurfaceCallback, DefaultLifecycleObserver {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var hud: CarHudView? = null
    private var ctl: NavMapController? = null
    private var visibleArea: Rect? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        owner.lifecycleScope.launch {
            graph.controller.state.collect { s ->
                ctl?.update(s)
                hud?.invalidate()
            }
        }
        owner.lifecycleScope.launch {
            graph.location.fixes.collect {
                if (!graph.controller.isNavigating) ctl?.update(graph.controller.state.value)
                hud?.invalidate()
            }
        }
        owner.lifecycleScope.launch {
            graph.events.collect { e -> if (e is UiEvent.RadarAlert) hud?.onAlert(e) }
        }
        owner.lifecycleScope.launch { graph.heading.compass.collect { ctl?.onCompass() } }
        graph.heading.acquire("car")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        graph.heading.release("car")
        tearDown()
    }

    // ---------------- SurfaceCallback ----------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        tearDown()
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "RadarNavMap", surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi,
            surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        virtualDisplay = vd

        val pres = Presentation(carContext, vd.display, R.style.Theme_RadarNav)
        val ctx = pres.context
        val options = MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true)
            .logoEnabled(false)
            .compassEnabled(false)
            .attributionEnabled(true)
        val mv = MapView(ctx, options)
        val overlay = CarHudView(ctx, graph).apply { visibleArea = this@CarMapRenderer.visibleArea }
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.bg))
            addView(mv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        pres.setContentView(root)
        pres.show()
        presentation = pres
        mapView = mv
        hud = overlay

        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        mv.getMapAsync { map ->
            map.setStyle(MapStyles.builder(graph.settings)) { style ->
                if (mapView !== mv) return@setStyle
                val c = NavMapController(ctx, map, style, graph) { surfaceHeight }
                ctl = c
                applyInsets()
                c.update(graph.controller.state.value)
            }
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = tearDown()

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        hud?.visibleArea = visibleArea
        applyInsets()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    /** Android Auto'nun kartları (manevra, varış) haritanın önünü kapatmasın. */
    private fun applyInsets() {
        val a = visibleArea ?: return
        ctl?.setInsetsIfChanged(a.left, a.top, (surfaceWidth - a.right).coerceAtLeast(0), (surfaceHeight - a.bottom).coerceAtLeast(0))
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        ctl?.pan(distanceX, distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        ctl?.zoomBy(scaleFactor, focusX, focusY)
    }

    fun zoomIn() {
        ctl?.zoomIn()
    }

    fun zoomOut() {
        ctl?.zoomOut()
    }

    fun recenter() {
        ctl?.recenter()
    }

    private fun tearDown() {
        ctl = null
        mapView?.let {
            runCatching {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }
        }
        mapView = null
        hud = null
        runCatching { presentation?.dismiss() }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }
}
