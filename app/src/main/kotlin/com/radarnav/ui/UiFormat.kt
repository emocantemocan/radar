package com.radarnav.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.radarnav.R
import com.radarnav.core.alert.Persona
import com.radarnav.core.alert.Severity
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.TurkishInstructions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Telefon ve Android Auto arayüzünün ortak biçimlendirme yardımcıları ("gece sürüşü" tasarımı). */
object UiFormat {

    fun maneuverIcon(kind: ManeuverKind?): Int = when (kind) {
        ManeuverKind.LEFT, ManeuverKind.SHARP_LEFT -> R.drawable.ic_m_left
        ManeuverKind.RIGHT, ManeuverKind.SHARP_RIGHT -> R.drawable.ic_m_right
        ManeuverKind.SLIGHT_LEFT, ManeuverKind.KEEP_LEFT, ManeuverKind.FORK_LEFT -> R.drawable.ic_m_slight_left
        ManeuverKind.SLIGHT_RIGHT, ManeuverKind.KEEP_RIGHT, ManeuverKind.FORK_RIGHT,
        ManeuverKind.OFF_RAMP, ManeuverKind.ON_RAMP -> R.drawable.ic_m_slight_right
        ManeuverKind.UTURN -> R.drawable.ic_m_uturn
        ManeuverKind.ROUNDABOUT -> R.drawable.ic_m_roundabout
        ManeuverKind.ARRIVE -> R.drawable.ic_m_destination
        else -> R.drawable.ic_m_straight
    }

    // ---------------- Sertlik kademeleri ----------------

    fun severityLevel(s: Severity): Int = s.ordinal + 1

    fun severityName(s: Severity): String = when (s) {
        Severity.CALM -> "Sakin"
        Severity.LIGHT -> "Takılan"
        Severity.SASSY -> "Homurdanan"
        Severity.HARSH -> "Bağıran"
    }

    /** Koyu zeminde okunan etiket rengi. */
    fun severityLabelColor(s: Severity): Int = when (s) {
        Severity.CALM -> R.color.sev1_label
        Severity.LIGHT -> R.color.sev2_label
        Severity.SASSY -> R.color.sev3_label
        Severity.HARSH -> R.color.sev4_label
    }

    /** Kart çerçevesi ve dolu çubuk rengi. */
    fun severityBorderColor(s: Severity): Int = when (s) {
        Severity.CALM -> R.color.sev1_border
        Severity.LIGHT -> R.color.sev2_border
        Severity.SASSY -> R.color.sev3_border
        Severity.HARSH -> R.color.sev4_border
    }

    /** Snackbar zemini (beyaz yazı için yeterince koyu). */
    fun severityColor(s: Severity): Int = when (s) {
        Severity.CALM -> R.color.severity_calm
        Severity.LIGHT -> R.color.severity_light
        Severity.SASSY -> R.color.severity_sassy
        Severity.HARSH -> R.color.severity_harsh
    }

    /** "Sert argo (küfürsüz)" -> "SERT ARGO" */
    fun personaTag(p: Persona): String = p.title.substringBefore(" (").uppercase(TurkishInstructions.TR)

    fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(millis))

    // ---------------- Radar / koridor metinleri ----------------

    private fun titleCase(s: String) = s.lowercase(TurkishInstructions.TR)
        .split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.titlecase(TurkishInstructions.TR) } }

    /** Kısa tür adı: "Radar" ya da "Hız koridoru (6,4 km)". */
    fun hazardTitle(h: RouteHazard): String {
        val c = h.hazard as? SpeedCorridor
        return if (c != null) {
            "Hız koridoru" + (c.lengthKm?.let { " (${TurkishInstructions.shortDistance(it * 1000)})" } ?: "")
        } else "Radar"
    }

    /** Listede ana satır: radarın/koridorun adı; yoksa tür adı. */
    fun hazardRowTitle(h: RouteHazard): String {
        val name = h.hazard.name.trim()
        return when {
            h.hazard is SpeedCorridor && name.isNotEmpty() ->
                if (name.contains("koridor", ignoreCase = true)) name else "$name hız koridoru"
            name.isNotEmpty() -> name
            h.hazard is SpeedCorridor -> "Hız koridoru"
            else -> "Radar"
        }
    }

    /** "Ankara · Çankaya" (+ koridorsa " · 6,4 km koridor"). */
    fun hazardSubtitle(h: RouteHazard): String {
        val parts = listOf(h.hazard.province, h.hazard.district).filter { it.isNotBlank() }.map(::titleCase).toMutableList()
        (h.hazard as? SpeedCorridor)?.lengthKm?.let { parts += "${TurkishInstructions.shortDistance(it * 1000)} koridor" }
        return parts.distinct().joinToString(" · ")
    }

    // ---------------- Harita işaretleri ----------------

    private val iconCache = HashMap<String, Bitmap>()

    private fun displayTypeface(context: Context): Typeface =
        runCatching { ResourcesCompat.getFont(context, R.font.space_grotesk) }.getOrNull()
            ?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD

    /**
     * Radar: Türk hız sınırı levhası (beyaz disk, kalın kırmızı halka, siyah rakam).
     * Koridor: köşeleri yuvarlatılmış kehribar kare üzerinde sınır.
     */
    fun hazardBitmap(context: Context, limit: Int?, corridor: Boolean, sizePx: Int): Bitmap {
        val key = "$limit|$corridor|$sizePx"
        iconCache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = sizePx / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val inset = sizePx * 0.06f
        p.color = 0x66000000
        if (corridor) {
            c.drawRoundRect(RectF(inset, inset * 1.6f, sizePx - inset, sizePx - inset * 0.4f), sizePx * 0.24f, sizePx * 0.24f, p)
            p.color = context.getColor(R.color.corridor_orange)
            c.drawRoundRect(RectF(inset, inset, sizePx - inset, sizePx - inset), sizePx * 0.24f, sizePx * 0.24f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = sizePx * 0.05f
            p.color = 0xFF0B1220.toInt()
            c.drawRoundRect(RectF(inset, inset, sizePx - inset, sizePx - inset), sizePx * 0.24f, sizePx * 0.24f, p)
            p.style = Paint.Style.FILL
        } else {
            c.drawCircle(r, r + sizePx * 0.03f, r - inset * 0.5f, p)
            p.color = context.getColor(R.color.radar_red)
            c.drawCircle(r, r, r - inset, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(r, r, (r - inset) * 0.72f, p)
        }
        p.color = 0xFF111111.toInt()
        p.typeface = displayTypeface(context)
        p.textAlign = Paint.Align.CENTER
        val text = limit?.toString() ?: "!"
        p.textSize = sizePx * (if (text.length >= 3) 0.32f else 0.40f)
        c.drawText(text, r, r - (p.descent() + p.ascent()) / 2, p)
        iconCache[key] = bmp
        return bmp
    }

    /** Hız koridorunun bittiği nokta: koyu disk, kehribar halka, "SON". */
    fun corridorEndBitmap(context: Context, sizePx: Int): Bitmap {
        val key = "end|$sizePx"
        iconCache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = sizePx / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = context.getColor(R.color.corridor_orange)
        c.drawCircle(r, r, r * 0.92f, p)
        p.color = 0xFF121B2E.toInt()
        c.drawCircle(r, r, r * 0.74f, p)
        p.color = context.getColor(R.color.chip_corridor_text)
        p.typeface = displayTypeface(context)
        p.textAlign = Paint.Align.CENTER
        p.textSize = sizePx * 0.28f
        c.drawText("SON", r, r - (p.descent() + p.ascent()) / 2, p)
        iconCache[key] = bmp
        return bmp
    }
}
