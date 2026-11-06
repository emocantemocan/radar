package com.radarnav.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.radarnav.R
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.TurkishInstructions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Telefon ve Android Auto arayüzünün ortak biçimlendirme yardımcıları. */
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

    fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(millis))

    fun hazardTitle(h: RouteHazard): String {
        val c = h.hazard as? SpeedCorridor
        return if (c != null) {
            "Hız koridoru" + (c.lengthKm?.let { " (${TurkishInstructions.shortDistance(it * 1000)})" } ?: "")
        } else "Radar"
    }

    fun hazardSubtitle(h: RouteHazard): String {
        val where = listOf(h.hazard.province, h.hazard.district).filter { it.isNotBlank() }
            .joinToString(" / ") { it.lowercase(TurkishInstructions.TR).replaceFirstChar { c -> c.titlecase(TurkishInstructions.TR) } }
        return listOf(where, h.hazard.name).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }

    private val iconCache = HashMap<String, Bitmap>()

    /** Haritada radar/koridor işareti: kırmızı (radar) ya da turuncu (koridor) çerçeveli hız sınırı. */
    fun hazardBitmap(context: Context, limit: Int?, corridor: Boolean, sizePx: Int): Bitmap {
        val key = "$limit|$corridor|$sizePx"
        iconCache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = sizePx / 2f
        val ring = context.getColor(if (corridor) R.color.corridor_orange else R.color.radar_red)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x55000000
        c.drawCircle(r, r + sizePx * 0.03f, r * 0.96f, p)
        p.color = ring
        c.drawCircle(r, r, r * 0.94f, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(r, r, r * 0.70f, p)
        p.color = 0xFF000000.toInt()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val text = limit?.toString() ?: "!"
        p.textSize = sizePx * (if (text.length >= 3) 0.34f else 0.42f)
        c.drawText(text, r, r - (p.descent() + p.ascent()) / 2, p)
        iconCache[key] = bmp
        return bmp
    }
}
