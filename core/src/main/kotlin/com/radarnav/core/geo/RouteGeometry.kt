package com.radarnav.core.geo

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rota çizgisi üzerinde mesafe hesapları: bir noktanın rotadaki konumunu
 * (baştan itibaren metre) ve rotadan sapmasını bulur.
 */
class RouteGeometry(val points: List<LatLng>) {

    init {
        require(points.size >= 2) { "Rota en az iki nokta içermeli" }
    }

    /** cumulative[i] = başlangıçtan points[i]'ye kadar olan mesafe (metre). */
    val cumulative: DoubleArray = DoubleArray(points.size).also { arr ->
        for (i in 1 until points.size) arr[i] = arr[i - 1] + Geo.distance(points[i - 1], points[i])
    }

    val length: Double get() = cumulative.last()

    data class Projection(
        /** Rota başından itibaren mesafe (metre). */
        val along: Double,
        /** Noktanın rotaya dik uzaklığı (metre). */
        val crossTrack: Double,
        /** Noktanın düştüğü segmentin indeksi. */
        val segmentIndex: Int,
        val point: LatLng,
    )

    /**
     * Noktayı rotaya izdüşürür. [hint] önceki segment indeksidir; verilirse önce
     * onun çevresine bakılır (navigasyon sırasında hızlı ve sıçramasız olması için).
     */
    fun project(p: LatLng, hint: Int = -1, window: Int = 400): Projection {
        if (hint >= 0) {
            val from = max(0, hint - 25)
            val to = min(points.size - 2, hint + window)
            val local = bestIn(p, from, to)
            if (local.crossTrack <= 80.0) return local
            val global = bestIn(p, 0, points.size - 2)
            return if (global.crossTrack < local.crossTrack) global else local
        }
        return bestIn(p, 0, points.size - 2)
    }

    private fun bestIn(p: LatLng, from: Int, to: Int): Projection {
        var best: Projection? = null
        for (i in from..to) {
            val pr = projectOnSegment(i, p)
            if (best == null || pr.crossTrack < best.crossTrack) best = pr
        }
        return best!!
    }

    private fun projectOnSegment(i: Int, p: LatLng): Projection {
        val a = points[i]
        val b = points[i + 1]
        // p merkezli yerel düzlem (equirectangular) – birkaç km için yeterince hassas
        val kx = cos(Geo.toRad(p.lat)) * Geo.EARTH_RADIUS_M * Math.PI / 180.0
        val ky = Geo.EARTH_RADIUS_M * Math.PI / 180.0
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx
        val by = (b.lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val cross = sqrt(cx * cx + cy * cy)
        val segLen = cumulative[i + 1] - cumulative[i]
        return Projection(
            along = cumulative[i] + t * segLen,
            crossTrack = cross,
            segmentIndex = i,
            point = Geo.interpolate(a, b, t),
        )
    }

    /**
     * Web Mercator düzleminde (harita motorunun çizdiği uzayda) noktaların birikimli uzunluğu.
     * MapLibre'nin `line-progress` değeri bu uzunluğa göre 0..1'dir; metre ile birebir aynı değildir
     * (enlem değiştikçe ölçek değişir).
     */
    private val mercatorCumulative: DoubleArray by lazy {
        val px = points.map { WebMercator.toPixel(it, 0.0) }
        DoubleArray(points.size).also { arr ->
            for (i in 1 until points.size) {
                arr[i] = arr[i - 1] + kotlin.math.hypot(px[i].x - px[i - 1].x, px[i].y - px[i - 1].y)
            }
        }
    }

    /** Rota başından [along] metre gidilince çizgi üzerinde varılan oran (0..1), `line-progress` cinsinden. */
    fun lineProgressAt(along: Double): Double {
        val total = mercatorCumulative.last()
        if (total <= 0 || along <= 0) return 0.0
        if (along >= length) return 1.0
        val i = segmentAt(along)
        val segLen = cumulative[i + 1] - cumulative[i]
        val f = if (segLen <= 0) 0.0 else (along - cumulative[i]) / segLen
        return (mercatorCumulative[i] + f * (mercatorCumulative[i + 1] - mercatorCumulative[i])) / total
    }

    /** Rota başından [along] metre sonraki nokta. */
    fun pointAt(along: Double): LatLng {
        if (along <= 0) return points.first()
        if (along >= length) return points.last()
        val i = segmentAt(along)
        val segLen = cumulative[i + 1] - cumulative[i]
        val f = if (segLen <= 0) 0.0 else (along - cumulative[i]) / segLen
        return Geo.interpolate(points[i], points[i + 1], f)
    }

    /** [along] metredeki yol yönü (derece). */
    fun bearingAt(along: Double): Double {
        val i = segmentAt(along.coerceIn(0.0, length))
        return Geo.bearing(points[i], points[i + 1])
    }

    /** [from]-[to] metre arasındaki rota parçası. */
    fun slice(from: Double, to: Double): List<LatLng> {
        val start = from.coerceIn(0.0, length)
        val end = to.coerceIn(start, length)
        val out = ArrayList<LatLng>()
        out.add(pointAt(start))
        val i0 = segmentAt(start) + 1
        val i1 = segmentAt(end)
        for (i in i0..i1) out.add(points[i])
        out.add(pointAt(end))
        return out
    }

    private fun segmentAt(along: Double): Int {
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= along) lo = mid else hi = mid
        }
        return lo.coerceAtMost(points.size - 2)
    }
}
