package com.radarnav.car

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
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
import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.WebMercator
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.TurkishInstructions
import com.radarnav.nav.NavState
import com.radarnav.nav.RoutePlan
import com.radarnav.ui.UiFormat
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android Auto ekranına OpenStreetMap haritasını kendimiz çizeriz (navigasyon
 * uygulamalarında harita uygulamanın sorumluluğundadır): karolar, rota, radarlar,
 * hız koridorları, konum oku ve hız/sınır göstergesi.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    private val graph: AppGraph,
    lifecycle: Lifecycle,
) : SurfaceCallback, DefaultLifecycleObserver {

    private var surface: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private val handler = Handler(Looper.getMainLooper())
    private var renderPosted = false

    var zoom = 15
        private set
    private var follow = true
    private var manualCenter: LatLng? = null
    private var scaleAccum = 1f

    private val tileListener: () -> Unit = { requestRender() }
    private val density get() = carContext.resources.displayMetrics.density

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val nightPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.55f, 0.58f, 0.66f, 1f) })
    }
    private val routeCasing = strokePaint(0xFFFFFFFF.toInt(), 13f)
    private val routePaint = strokePaint(0xFF1A73E8.toInt(), 9f)
    private val corridorPaint = strokePaint(carContext.getColor(R.color.corridor_orange), 9f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun strokePaint(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * carContext.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        graph.tiles.addListener(tileListener)
        owner.lifecycleScope.launch { graph.controller.state.collect { requestRender() } }
        owner.lifecycleScope.launch { graph.location.fixes.collect { requestRender() } }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        graph.tiles.removeListener(tileListener)
        handler.removeCallbacksAndMessages(null)
        surface = null
    }

    // ---------------- SurfaceCallback ----------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer
        requestRender()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        requestRender()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = stableArea
        requestRender()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val c = currentCenter() ?: return
        val px = WebMercator.toPixel(c, zoom.toDouble())
        manualCenter = WebMercator.fromPixel(px.x + distanceX, px.y + distanceY, zoom.toDouble())
        follow = false
        requestRender()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        scaleAccum *= scaleFactor
        if (scaleAccum > 1.4f) {
            scaleAccum = 1f
            zoomIn()
        } else if (scaleAccum < 0.7f) {
            scaleAccum = 1f
            zoomOut()
        }
    }

    fun zoomIn() {
        zoom = min(19, zoom + 1)
        requestRender()
    }

    fun zoomOut() {
        zoom = max(4, zoom - 1)
        requestRender()
    }

    fun recenter() {
        follow = true
        manualCenter = null
        requestRender()
    }

    // ---------------- Çizim ----------------

    fun requestRender() {
        if (renderPosted) return
        renderPosted = true
        handler.post {
            renderPosted = false
            render()
        }
    }

    private fun myLocation(): Pair<LatLng, Float>? {
        val s = graph.controller.state.value
        if (s is NavState.Navigating) s.progress?.let { return it.location to it.bearing }
        return graph.location.fixes.value?.let { it.location to (it.bearing ?: 0f) }
    }

    private fun currentPlan(): RoutePlan? = when (val s = graph.controller.state.value) {
        is NavState.Planned -> s.plan
        is NavState.Navigating -> s.plan
        else -> null
    }

    private fun currentCenter(): LatLng? =
        if (!follow) manualCenter ?: myLocation()?.first else myLocation()?.first ?: currentPlan()?.origin

    private fun render() {
        val container = surface ?: return
        val s = container.surface ?: return
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            return
        } ?: return
        try {
            draw(canvas, container.width, container.height)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun draw(canvas: Canvas, width: Int, height: Int) {
        val dark = carContext.isDarkMode
        canvas.drawColor(if (dark) 0xFF1B2430.toInt() else 0xFFE8E4DC.toInt())
        val area = visibleArea ?: Rect(0, 0, width, height)

        val me = myLocation()
        val navigating = graph.controller.isNavigating
        val center = currentCenter() ?: LatLng(39.0, 35.0)
        val effectiveZoom = if (currentCenter() == null) 6 else zoom

        // Takip modunda konum, görünür alanın alt-orta kısmında dursun (önü görünsün)
        val anchorX = area.exactCenterX()
        val anchorY = if (follow && navigating) area.top + area.height() * 0.68f else area.exactCenterY()
        val cz = effectiveZoom.toDouble()
        val anchorWorld = WebMercator.toPixel(center, cz)
        val viewCenterX = anchorWorld.x + (width / 2f - anchorX)
        val viewCenterY = anchorWorld.y + (height / 2f - anchorY)

        fun toScreen(p: LatLng): Pair<Float, Float> {
            val w = WebMercator.toPixel(p, cz)
            return (w.x - viewCenterX + width / 2.0).toFloat() to (w.y - viewCenterY + height / 2.0).toFloat()
        }

        // 1) Karolar
        val paint = if (dark) nightPaint else tilePaint
        for (t in WebMercator.visibleTiles(viewCenterX, viewCenterY, width, height, effectiveZoom)) {
            val bmp = graph.tiles.get(t.tile) ?: continue
            val l = t.left.toFloat()
            val tp = t.top.toFloat()
            canvas.drawBitmap(bmp, null, RectF(l, tp, l + WebMercator.TILE_SIZE, tp + WebMercator.TILE_SIZE), paint)
        }

        // 2) Rota ve koridorlar
        val plan = currentPlan()
        if (plan != null) {
            val geometry = plan.route.geometry
            val along = (graph.controller.state.value as? NavState.Navigating)?.progress?.along ?: 0.0
            val path = Path()
            var first = true
            val startIdx = if (navigating) geometry.cumulative.indexOfFirst { it >= along }.coerceAtLeast(1) - 1 else 0
            val pts = geometry.points
            var lastX = Float.NaN
            var lastY = Float.NaN
            for (i in startIdx until pts.size) {
                val p = if (i == startIdx && navigating) geometry.pointAt(along) else pts[i]
                val (x, y) = toScreen(p)
                // Ekran dışındaki ve üst üste binen noktaları atla (uzun rotalarda hız için)
                if (!first && kotlin.math.abs(x - lastX) < 1.5f && kotlin.math.abs(y - lastY) < 1.5f) continue
                if (first) path.moveTo(x, y) else path.lineTo(x, y)
                first = false
                lastX = x
                lastY = y
            }
            canvas.drawPath(path, routeCasing)
            canvas.drawPath(path, routePaint)

            for (h in plan.hazards) {
                if (h.hazard is SpeedCorridor && h.endAlong >= along) {
                    val cp = Path()
                    geometry.slice(max(h.startAlong, along), h.endAlong).forEachIndexed { i, p ->
                        val (x, y) = toScreen(p)
                        if (i == 0) cp.moveTo(x, y) else cp.lineTo(x, y)
                    }
                    canvas.drawPath(cp, corridorPaint)
                }
            }
            val iconSize = (30 * density).roundToInt()
            // Koridor bitiş işaretleri
            for (h in plan.hazards) {
                if (!h.isCorridor || h.endAlong < along) continue
                val (x, y) = toScreen(geometry.pointAt(h.endAlong))
                if (x < -iconSize || y < -iconSize || x > width + iconSize || y > height + iconSize) continue
                val bmp = UiFormat.corridorEndBitmap(carContext, (iconSize * 0.8f).roundToInt())
                canvas.drawBitmap(bmp, x - bmp.width / 2f, y - bmp.height / 2f, null)
            }
            for (h in plan.hazards) {
                if (h.startAlong < along - 30) continue
                val (x, y) = toScreen(geometry.pointAt(h.startAlong))
                if (x < -iconSize || y < -iconSize || x > width + iconSize || y > height + iconSize) continue
                val bmp = UiFormat.hazardBitmap(carContext, h.hazard.speedLimit, h.isCorridor, iconSize)
                canvas.drawBitmap(bmp, x - iconSize / 2f, y - iconSize / 2f, null)
            }
        }

        // 3) Konum oku
        if (me != null) {
            val (x, y) = toScreen(me.first)
            drawArrow(canvas, x, y, me.second)
        }

        // 4) Hız ve sınır göstergesi (görünür alanın sol altı)
        val nav = graph.controller.state.value as? NavState.Navigating
        val progress = nav?.progress
        if (progress != null) drawSpeedHud(canvas, area, progress.speedKmh, progress.currentLimit, progress)

        // 5) OSM atfı
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 10 * density
        textPaint.color = if (dark) 0xB3FFFFFF.toInt() else 0xB3000000.toInt()
        canvas.drawText(carContext.getString(R.string.osm_attribution), area.right - 8 * density, area.bottom - 6 * density, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, bearing: Float) {
        val s = 16 * density
        canvas.save()
        canvas.rotate(bearing, x, y)
        val path = Path().apply {
            moveTo(x, y - s)
            lineTo(x + s * 0.75f, y + s * 0.8f)
            lineTo(x, y + s * 0.35f)
            lineTo(x - s * 0.75f, y + s * 0.8f)
            close()
        }
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = 0xFF1A73E8.toInt()
        canvas.drawPath(path, fillPaint)
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 2.5f * density
        fillPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, fillPaint)
        fillPaint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun drawSpeedHud(canvas: Canvas, area: Rect, speed: Double, limit: Int?, p: com.radarnav.nav.NavProgress) {
        val r = 26 * density
        val margin = 12 * density
        var cx = area.left + margin + r
        val cy = area.bottom - margin - r - 34 * density

        // Hız
        val over = limit != null && speed > limit + 2
        fillPaint.color = if (carContext.isDarkMode) 0xF0202124.toInt() else 0xF0FFFFFF.toInt()
        canvas.drawCircle(cx, cy, r, fillPaint)
        textPaint.color = if (over) 0xFFD93025.toInt() else if (carContext.isDarkMode) 0xFFFFFFFF.toInt() else 0xFF202124.toInt()
        textPaint.textSize = 20 * density
        canvas.drawText(speed.roundToInt().toString(), cx, cy + 7 * density, textPaint)
        textPaint.textSize = 8 * density
        canvas.drawText(if (p.speedFromCar) "ARAÇ" else "GPS", cx, cy + 18 * density, textPaint)

        // Sınır
        if (limit != null) {
            cx += r * 2 + margin
            fillPaint.color = 0xFFD93025.toInt()
            canvas.drawCircle(cx, cy, r, fillPaint)
            fillPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx, cy, r * 0.76f, fillPaint)
            textPaint.color = 0xFF000000.toInt()
            textPaint.textSize = 19 * density
            canvas.drawText(limit.toString(), cx, cy + 7 * density, textPaint)
        }

        // Sıradaki radar / koridor bilgisi
        val text = p.corridor?.let { c ->
            "Koridor · ort. ${c.averageKmh?.roundToInt() ?: "–"} · kalan ${TurkishInstructions.shortDistance(c.remainingMeters)}"
        } ?: p.nextHazard?.let { h ->
            val d = (p.distanceToHazard ?: 0.0).coerceAtLeast(0.0)
            if (d > 30_000) null else "${UiFormat.hazardTitle(h)} · ${TurkishInstructions.shortDistance(d)}"
        }
        if (text != null) {
            textPaint.textSize = 14 * density
            textPaint.textAlign = Paint.Align.LEFT
            val tw = textPaint.measureText(text)
            val left = area.left + margin
            val top = cy + r + 6 * density
            fillPaint.color = 0xE6202124.toInt()
            canvas.drawRoundRect(RectF(left, top, left + tw + 16 * density, top + 22 * density), 8 * density, 8 * density, fillPaint)
            textPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(text, left + 8 * density, top + 16 * density, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
    }
}
