package com.radarnav.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.radarnav.AppGraph
import com.radarnav.R
import com.radarnav.UiEvent
import com.radarnav.core.alert.Severity
import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.WebMercator
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.TurkishInstructions
import com.radarnav.nav.NavProgress
import com.radarnav.nav.NavState
import com.radarnav.nav.RoutePlan
import com.radarnav.ui.UiFormat
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android Auto ekranına haritayı kendimiz çizeriz (navigasyon uygulamalarında harita
 * uygulamanın sorumluluğundadır). Tasarım: "gece sürüşü yol levhası": koyu OSM karoları,
 * mavi rota, rota üzerinde levha tarzı radarlar, kehribar hız koridorları, sol altta hız ve
 * sınır, altta sertlik kademesine göre renklenen uyarı kartı.
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

    /** Son radar uyarısı (kartta [ALERT_SHOW_MS] boyunca gösterilir). */
    private var lastAlert: UiEvent.RadarAlert? = null
    private var lastAlertAt = 0L

    private val tileListener: () -> Unit = { requestRender() }
    private val density get() = carContext.resources.displayMetrics.density
    private fun color(res: Int) = carContext.getColor(res)

    private val display: Typeface = runCatching { ResourcesCompat.getFont(carContext, R.font.space_grotesk) }
        .getOrNull()?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
    private val body: Typeface = runCatching { ResourcesCompat.getFont(carContext, R.font.inter) }
        .getOrNull() ?: Typeface.DEFAULT

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val routeCasing = strokePaint(color(R.color.bg_deep), 12f)
    private val routePaint = strokePaint(color(R.color.route), 7f)
    private val corridorCasing = strokePaint(color(R.color.bg_deep), 13f)
    private val corridorPaint = strokePaint(color(R.color.corridor_orange), 8f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

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
        owner.lifecycleScope.launch {
            graph.events.collect { e ->
                if (e is UiEvent.RadarAlert) {
                    lastAlert = e
                    lastAlertAt = System.currentTimeMillis()
                    requestRender()
                    handler.postDelayed({ requestRender() }, ALERT_SHOW_MS + 100)
                }
            }
        }
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
        canvas.drawColor(color(R.color.bg))
        val area = visibleArea ?: Rect(0, 0, width, height)

        val me = myLocation()
        val navigating = graph.controller.isNavigating
        val centerKnown = currentCenter()
        val center = centerKnown ?: LatLng(39.0, 35.0)
        val effectiveZoom = if (centerKnown == null) 6 else zoom

        // Takip modunda konum, görünür alanın alt-orta kısmında dursun (önü görünsün)
        val anchorX = area.exactCenterX()
        val anchorY = if (follow && navigating) area.top + area.height() * 0.62f else area.exactCenterY()
        val cz = effectiveZoom.toDouble()
        val anchorWorld = WebMercator.toPixel(center, cz)
        val viewCenterX = anchorWorld.x + (width / 2f - anchorX)
        val viewCenterY = anchorWorld.y + (height / 2f - anchorY)

        fun toScreen(p: LatLng): Pair<Float, Float> {
            val w = WebMercator.toPixel(p, cz)
            return (w.x - viewCenterX + width / 2.0).toFloat() to (w.y - viewCenterY + height / 2.0).toFloat()
        }

        // 1) Karolar (koyu harita açıksa TileCache onları gece görünümüne çevirmiş olur)
        for (t in WebMercator.visibleTiles(viewCenterX, viewCenterY, width, height, effectiveZoom)) {
            val bmp = graph.tiles.get(t.tile) ?: continue
            val l = t.left.toFloat()
            val tp = t.top.toFloat()
            canvas.drawBitmap(bmp, null, RectF(l, tp, l + WebMercator.TILE_SIZE, tp + WebMercator.TILE_SIZE), tilePaint)
        }

        // 2) Rota, koridorlar ve rota üzerindeki tüm radarlar
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
                // Üst üste binen noktaları atla (uzun rotalarda hız için)
                if (!first && abs(x - lastX) < 1.5f && abs(y - lastY) < 1.5f) continue
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
                    canvas.drawPath(cp, corridorCasing)
                    canvas.drawPath(cp, corridorPaint)
                }
            }
            val iconSize = (30 * density).roundToInt()
            fun visible(x: Float, y: Float) = x > -iconSize && y > -iconSize && x < width + iconSize && y < height + iconSize
            for (h in plan.hazards) {
                if (!h.isCorridor || h.endAlong < along) continue
                val (x, y) = toScreen(geometry.pointAt(h.endAlong))
                if (!visible(x, y)) continue
                val bmp = UiFormat.corridorEndBitmap(carContext, (iconSize * 0.85f).roundToInt())
                canvas.drawBitmap(bmp, x - bmp.width / 2f, y - bmp.height / 2f, null)
            }
            for (h in plan.hazards) {
                if (h.startAlong < along - 30) continue
                val (x, y) = toScreen(geometry.pointAt(h.startAlong))
                if (!visible(x, y)) continue
                val bmp = UiFormat.hazardBitmap(carContext, h.hazard.speedLimit, h.isCorridor, iconSize)
                canvas.drawBitmap(bmp, x - iconSize / 2f, y - iconSize / 2f, null)
            }
        }

        // 3) Konum oku
        if (me != null) {
            val (x, y) = toScreen(me.first)
            drawArrow(canvas, x, y, me.second)
        }

        // 4) Hız, sınır ve uyarı kartı
        val progress = (graph.controller.state.value as? NavState.Navigating)?.progress
        if (progress != null) {
            val hudRight = drawSpeedHud(canvas, area, progress)
            drawAlertCard(canvas, area, progress, hudRight)
        }

        // 5) Harita atfı
        text.typeface = body
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 9 * density
        text.color = 0xB3FFFFFF.toInt()
        canvas.drawText(carContext.getString(R.string.osm_attribution), area.right - 8 * density, area.bottom - 4 * density, text)
        text.textAlign = Paint.Align.LEFT
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
        fill.color = color(R.color.brand)
        canvas.drawPath(path, fill)
        stroke.strokeWidth = 2.5f * density
        stroke.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    private fun roundRect(canvas: Canvas, r: RectF, radius: Float, bg: Int, border: Int?, borderDp: Float = 1f) {
        fill.color = bg
        canvas.drawRoundRect(r, radius, radius, fill)
        if (border != null) {
            stroke.color = border
            stroke.strokeWidth = borderDp * density
            val half = stroke.strokeWidth / 2
            canvas.drawRoundRect(RectF(r.left + half, r.top + half, r.right - half, r.bottom - half), radius, radius, stroke)
        }
    }

    /** Sol alt: "Radar · 1,2 km" hapı, hız kutusu ve sınır levhası. Sağ kenarını döner. */
    private fun drawSpeedHud(canvas: Canvas, area: Rect, p: NavProgress): Float {
        val d = density
        val margin = 14 * d
        val limit = p.currentLimit
        val over = limit != null && p.speedKmh > limit

        // Hız kutusu
        val boxW = 96 * d
        val boxH = 82 * d
        val box = RectF(area.left + margin, area.bottom - margin - boxH, area.left + margin + boxW, area.bottom - margin)
        roundRect(canvas, box, 20 * d, color(R.color.surface), color(R.color.border))
        text.textAlign = Paint.Align.CENTER
        text.typeface = display
        text.textSize = 42 * d
        text.color = if (over) color(R.color.speed_over) else 0xFFFFFFFF.toInt()
        canvas.drawText(p.speedKmh.roundToInt().toString(), box.centerX(), box.top + 44 * d, text)
        text.typeface = body
        text.textSize = 11 * d
        text.color = color(R.color.text_secondary)
        canvas.drawText("km/sa", box.centerX(), box.top + 60 * d, text)
        text.textSize = 9 * d
        text.color = color(R.color.text_tertiary)
        canvas.drawText(if (p.speedFromCar) "Araç hızı" else "GPS hızı", box.centerX(), box.top + 73 * d, text)
        var right = box.right

        // Sınır levhası
        if (limit != null) {
            val r = 34 * d
            val cx = box.right + 10 * d + r
            val cy = box.centerY()
            fill.color = color(R.color.radar_red)
            canvas.drawCircle(cx, cy, r, fill)
            fill.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx, cy, r - 8 * d, fill)
            text.typeface = display
            text.textSize = 26 * d
            text.color = 0xFF111111.toInt()
            canvas.drawText(limit.toString(), cx, cy + 9 * d, text)
            right = cx + r
        }

        // Sıradaki radar / koridor hapı
        val corridor = p.corridor
        val next = p.nextHazard
        val pill: Triple<String, Int, Int>? = when {
            corridor != null -> Triple(
                "Koridor · kalan ${TurkishInstructions.shortDistance(corridor.remainingMeters)}",
                R.color.chip_corridor_bg, R.color.chip_corridor_text,
            )
            next != null && (p.distanceToHazard ?: Double.MAX_VALUE) <= 30_000 -> {
                val dist = (p.distanceToHazard ?: 0.0).coerceAtLeast(0.0)
                Triple(
                    "${if (next.isCorridor) "Koridor" else "Radar"} · ${TurkishInstructions.shortDistance(dist)}",
                    if (next.isCorridor) R.color.chip_corridor_bg else R.color.chip_radar_bg,
                    if (next.isCorridor) R.color.chip_corridor_text else R.color.chip_radar_text,
                )
            }
            else -> null
        }
        if (pill != null) {
            text.typeface = body
            text.isFakeBoldText = true
            text.textSize = 13 * d
            text.textAlign = Paint.Align.LEFT
            val tw = text.measureText(pill.first)
            val h = 30 * d
            val r = RectF(box.left, box.top - 10 * d - h, box.left + tw + 28 * d, box.top - 10 * d)
            roundRect(canvas, r, h / 2, color(pill.second), null)
            text.color = color(pill.third)
            canvas.drawText(pill.first, r.left + 14 * d, r.centerY() + 5 * d, text)
            text.isFakeBoldText = false
        }
        text.textAlign = Paint.Align.LEFT
        return right
    }

    /** Alt orta: son uyarının metni ve anlık hıza göre sertlik kademesi. */
    private fun drawAlertCard(canvas: Canvas, area: Rect, p: NavProgress, hudRight: Float) {
        val alert = lastAlert ?: return
        if (System.currentTimeMillis() - lastAlertAt > ALERT_SHOW_MS) return
        val d = density
        val left = hudRight + 16 * d
        val right = min(area.right - 14 * d, left + 360 * d)
        val w = right - left
        if (w < 200 * d) return

        val inCorridor = p.corridor?.hazard?.takeIf { it.hazard.id == alert.hazardId }
        val target = inCorridor ?: p.nextHazard?.takeIf { it.hazard.id == alert.hazardId }
        val limit = target?.hazard?.speedLimit
        val severity = if (target != null) {
            Severity.of(if (inCorridor != null) p.corridor?.averageKmh ?: p.speedKmh else p.speedKmh, limit)
        } else alert.severity
        val level = UiFormat.severityLevel(severity)
        val labelColor = color(UiFormat.severityLabelColor(severity))
        val borderColor = color(UiFormat.severityBorderColor(severity))

        val pad = 12 * d
        // Ana metin (en fazla 2 satır)
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = body
            isFakeBoldText = true
            textSize = 15 * d
            color = 0xFFFFFFFF.toInt()
        }
        val textWidth = (w - 2 * pad).toInt()
        val layout = StaticLayout.Builder.obtain(alert.text, 0, alert.text.length, bodyPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val h = pad + 16 * d + layout.height + 8 * d + 16 * d + pad
        val card = RectF(left, area.bottom - 14 * d - h, right, area.bottom - 14 * d)
        roundRect(canvas, card, 20 * d, color(R.color.surface), borderColor, if (severity >= Severity.SASSY) 2f else 1f)

        // Başlık satırı: "RADAR · 1,2 KM · SERT ARGO" ve "Seviye 4/4"
        text.typeface = body
        text.isFakeBoldText = true
        text.textSize = 11 * d
        text.color = labelColor
        text.textAlign = Paint.Align.LEFT
        val kind = alert.title.substringBefore(" ·").uppercase(TurkishInstructions.TR)
        val distance = if (target != null && inCorridor == null) p.distanceToHazard else null
        val distText = distance?.let { " · " + TurkishInstructions.shortDistance(it.coerceAtLeast(0.0)).uppercase(TurkishInstructions.TR) }.orEmpty()
        canvas.drawText("$kind$distText · ${UiFormat.personaTag(graph.settings.persona)}", card.left + pad, card.top + pad + 11 * d, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("Seviye $level/4", card.right - pad, card.top + pad + 11 * d, text)
        text.textAlign = Paint.Align.LEFT
        text.isFakeBoldText = false

        // Metin
        canvas.save()
        canvas.translate(card.left + pad, card.top + pad + 16 * d)
        layout.draw(canvas)
        canvas.restore()

        // Kademe çubukları (dolu çubuk sayısı = kademe) + ayrıntı
        val baseY = card.bottom - pad
        var x = card.left + pad
        for (i in 0 until 4) {
            val bh = (6 + i * 3) * d
            fill.color = if (i < level) borderColor else color(R.color.sev_off)
            canvas.drawRoundRect(RectF(x, baseY - bh, x + 6 * d, baseY), 2 * d, 2 * d, fill)
            x += 9 * d
        }
        text.textSize = 12 * d
        text.color = color(R.color.text_secondary)
        val detail = buildString {
            append(UiFormat.severityName(severity))
            append(" · ${p.speedKmh.roundToInt()} km/sa")
            limit?.let {
                append(" · sınır $it")
                val diff = (p.speedKmh - it).roundToInt()
                if (diff > 0) append(" · +$diff")
            }
        }
        canvas.drawText(detail, x + 6 * d, baseY - 1 * d, text)
    }

    companion object {
        private const val ALERT_SHOW_MS = 12_000L
    }
}
