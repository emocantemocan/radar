package com.radarnav.car

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.radarnav.AppGraph
import com.radarnav.R
import com.radarnav.UiEvent
import com.radarnav.core.alert.Severity
import com.radarnav.core.routing.TurkishInstructions
import com.radarnav.nav.NavProgress
import com.radarnav.nav.NavState
import com.radarnav.ui.UiFormat
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android Auto haritasının üstündeki gösterge katmanı: sol altta hız kutusu, sınır levhası ve
 * "Radar · 1,2 km" hapı; altta sertlik kademesine göre renklenen uyarı kartı.
 */
@SuppressLint("ViewConstructor")
class CarHudView(context: Context, private val graph: AppGraph) : View(context) {

    /** Araç ekranında içerik çizilebilecek alan (Android Auto'nun kartları dışında). */
    var visibleArea: Rect? = null
        set(value) {
            field = value
            invalidate()
        }

    private var lastAlert: UiEvent.RadarAlert? = null
    private var lastAlertAt = 0L

    private val d = resources.displayMetrics.density
    private fun color(res: Int) = context.getColor(res)

    private val display: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.space_grotesk) }
        .getOrNull()?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
    private val body: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter) }
        .getOrNull() ?: Typeface.DEFAULT

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun onAlert(e: UiEvent.RadarAlert) {
        lastAlert = e
        lastAlertAt = System.currentTimeMillis()
        invalidate()
        postDelayed({ invalidate() }, ALERT_SHOW_MS + 100)
    }

    override fun onDraw(canvas: Canvas) {
        val progress = (graph.controller.state.value as? NavState.Navigating)?.progress ?: freeDriveProgress() ?: return
        val area = visibleArea ?: Rect(0, 0, width, height)
        val hudRight = drawSpeedHud(canvas, area, progress)
        drawAlertCard(canvas, area, progress, hudRight)
    }

    /** Rotasız sürüşte koridordaysak HUD'u (hız, koridor sınırı, ortalama) aynı çizim yoluyla göster. */
    private fun freeDriveProgress(): NavProgress? {
        if (graph.controller.isNavigating) return null
        val c = graph.controller.freeCorridor.value ?: return null
        val fix = graph.location.fixes.value ?: return null
        return NavProgress(
            location = fix.location, bearing = fix.bearing ?: 0f, speedKmh = fix.speedKmh, speedFromCar = fix.speedFromCar,
            along = 0.0, remainingMeters = c.remainingMeters, remainingSeconds = 0.0, nextStep = null, distanceToStep = 0.0,
            currentLimit = c.hazard.hazard.speedLimit, nextHazard = null, distanceToHazard = null, corridor = c, rerouting = false,
        )
    }

    private fun roundRect(canvas: Canvas, r: RectF, radius: Float, bg: Int, border: Int?, borderDp: Float = 1f) {
        fill.color = bg
        canvas.drawRoundRect(r, radius, radius, fill)
        if (border != null) {
            stroke.color = border
            stroke.strokeWidth = borderDp * d
            val half = stroke.strokeWidth / 2
            canvas.drawRoundRect(RectF(r.left + half, r.top + half, r.right - half, r.bottom - half), radius, radius, stroke)
        }
    }

    /** Sol alt: hap, hız kutusu, sınır levhası. Sağ kenarını döner. */
    private fun drawSpeedHud(canvas: Canvas, area: Rect, p: NavProgress): Float {
        val margin = 14 * d
        val limit = p.currentLimit
        val over = limit != null && p.speedKmh > limit

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
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = body
            isFakeBoldText = true
            textSize = 15 * d
            color = 0xFFFFFFFF.toInt()
        }
        val layout = StaticLayout.Builder.obtain(alert.text, 0, alert.text.length, bodyPaint, (w - 2 * pad).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val h = pad + 16 * d + layout.height + 8 * d + 16 * d + pad
        val card = RectF(left, area.bottom - 14 * d - h, right, area.bottom - 14 * d)
        roundRect(canvas, card, 20 * d, color(R.color.surface), borderColor, if (severity >= Severity.SASSY) 2f else 1f)

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

        canvas.save()
        canvas.translate(card.left + pad, card.top + pad + 16 * d)
        layout.draw(canvas)
        canvas.restore()

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
        const val ALERT_SHOW_MS = 12_000L
    }
}
