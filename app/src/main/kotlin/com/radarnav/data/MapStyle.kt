package com.radarnav.data

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Anahtarsız koyu harita: standart OpenStreetMap karolarını cihazda, piksel piksel "gece"
 * görünümüne çevirir (tasarımdaki lacivert tema).
 *
 * Basit renk ters çevirme yolları zeminden koyu yapar (OSM'de yollar beyaz, zemin bej). Bunun
 * yerine açıklık (lightness) bir eğriyle eşlenir: zemin -> lacivert, beyaz yollar -> zeminden
 * açık arduvaz, koyu yazılar -> açık renk. Renk tonu korunur; nötr pikseller laciverte boyanır.
 */
object MapStyle {

    /** Açıklık eğrisi (0..1 -> 0..1), 256 basamaklı tablo. */
    private val lightnessLut: FloatArray = FloatArray(256) { i ->
        val l = i / 255f
        interpolate(
            l,
            floatArrayOf(0.00f, 0.45f, 0.70f, 0.82f, 0.90f, 0.955f, 0.985f, 1.00f),
            floatArrayOf(0.88f, 0.70f, 0.30f, 0.13f, 0.085f, 0.075f, 0.20f, 0.34f),
        )
    }

    private fun interpolate(x: Float, xs: FloatArray, ys: FloatArray): Float {
        if (x <= xs[0]) return ys[0]
        for (i in 1 until xs.size) {
            if (x <= xs[i]) {
                val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                return ys[i - 1] + t * (ys[i] - ys[i - 1])
            }
        }
        return ys.last()
    }

    /** Karoyu koyu temaya çevirir; yeni (değiştirilebilir) bir bitmap döner. */
    fun toNight(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = nightPixel(px[i])
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun nightPixel(argb: Int): Int {
        val a = argb ushr 24
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        var s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f))
        var hue = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (hue < 0) hue += 360f

        var nl = lightnessLut[(l * 255f).toInt().coerceIn(0, 255)]
        if (s < 0.30f) {
            // Nötr renkler (zemin, binalar, yollar, yazılar): laciverte boya
            hue = 220f
            s = 0.42f
        } else {
            // Renkli öğeler (su, park, ana yollar): tonunu koru, biraz canlı kalsın
            s = min(1f, s * 0.75f)
            nl += 0.08f * s
        }
        return a shl 24 or hslToRgb(hue, s, nl.coerceIn(0f, 1f))
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255f).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255f).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255f).toInt().coerceIn(0, 255)
        return ri shl 16 or (gi shl 8) or bi
    }
}
