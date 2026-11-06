package com.radarnav.core.egm

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.RouteGeometry
import com.radarnav.core.model.HazardSource
import com.radarnav.core.model.Radar
import com.radarnav.core.net.LenientJson
import com.radarnav.core.net.arr
import com.radarnav.core.net.dbl
import com.radarnav.core.net.fetchString
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import com.radarnav.core.osm.CameraSource
import com.radarnav.core.osm.TextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Emniyet Genel Müdürlüğü'nün resmî EDS haritası (onlineislemler.egm.gov.tr/trafik/sayfalar/edsharita.aspx).
 * Sayfa ~1.500 sabit denetim noktasını (kavşak, kırmızı ışık, hız kameraları) `var markers = [...]`
 * olarak gömülü taşır; konum ve açıklama var, hız sınırı ve sayısal yön yok.
 *
 * Uygulamaya gömülü [snapshot] hemen kullanılır; sayfa en fazla [maxAgeMillis]'te bir (varsayılan 7 gün)
 * arka planda yeniden indirilip [store]'a yazılır. Ortalama hız koridoru (OHTS) uçları atlanır:
 * koridorlar bakanlıktan tüm güzergahıyla geliyor.
 */
class EgmEdsSource(
    private val client: OkHttpClient,
    private val store: TextStore,
    private val snapshot: (() -> String?)? = null,
    private val pageUrl: String = PAGE_URL,
    private val maxAgeMillis: Long = 7L * 24 * 3600 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CameraSource {

    @Volatile
    private var points: List<Radar>? = null
    private val refreshing = AtomicBoolean(false)

    private fun current(): List<Radar> {
        points?.let { return it }
        val stored = store.read(KEY)?.let { decode(it) }
        val list = stored?.second ?: snapshot?.invoke()?.let { runCatching { parseJson(it) }.getOrNull() } ?: emptyList()
        points = list
        if (stored == null || clock() - stored.first > maxAgeMillis) refreshInBackground()
        return list
    }

    private fun refreshInBackground() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val html = client.fetchString(Request.Builder().url(pageUrl).build())
                val fresh = parseHtml(html)
                // Sayfa bozuk/boş geldiyse elimizdekini koru
                if (fresh.size >= 100) {
                    store.write(KEY, "${clock()}\n${toJson(fresh)}")
                    points = fresh
                }
            } catch (_: Exception) {
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Rotanın çevresindeki (kaba kutu) noktalar; asıl eleme HazardFinder.place'te yapılır. */
    override suspend fun along(geometry: RouteGeometry): List<Radar> {
        val pts = geometry.points
        if (pts.isEmpty()) return emptyList()
        val pad = 0.01
        val minLat = pts.minOf { it.lat } - pad
        val maxLat = pts.maxOf { it.lat } + pad
        val minLon = pts.minOf { it.lon } - pad
        val maxLon = pts.maxOf { it.lon } + pad
        return current().filter { it.location.lat in minLat..maxLat && it.location.lon in minLon..maxLon }
    }

    private fun decode(s: String): Pair<Long, List<Radar>>? = runCatching {
        val nl = s.indexOf('\n')
        s.substring(0, nl).toLong() to parseJson(s.substring(nl + 1))
    }.getOrNull()

    companion object {
        const val PAGE_URL = "https://onlineislemler.egm.gov.tr/trafik/sayfalar/edsharita.aspx"
        private const val KEY = "egm_eds"
        private val ITEM = Regex("\"Aciklama\":\\s*'(.*?)',\\s*\"lat\":\\s*'([^']*)',\\s*\"lng\":\\s*'([^']*)'", RegexOption.DOT_MATCHES_ALL)

        /** EGM sayfasının HTML'inden noktalar (OHTS koridor uçları hariç). */
        fun parseHtml(html: String): List<Radar> {
            val a = html.indexOf("var markers")
            if (a < 0) return emptyList()
            val b = html.indexOf("];", a).takeIf { it > a } ?: html.length
            return merge(
                ITEM.findAll(html.substring(a, b)).mapNotNull { m ->
                    point(m.groupValues[1], m.groupValues[2].replace(',', '.').toDoubleOrNull(), m.groupValues[3].replace(',', '.').toDoubleOrNull())
                }.toList(),
            )
        }

        /** Aynı konumda farklı yönler için ayrı kayıtlar var: tek noktada birleştir, açıklamaları yan yana yaz. */
        private fun merge(points: List<Radar>): List<Radar> =
            points.groupBy { it.id }.map { (_, same) ->
                if (same.size == 1) same[0] else same[0].copy(name = same.map { it.name }.distinct().joinToString(" | "))
            }

        /** Uygulamaya gömülü JSON: {"points":[{"a":açıklama,"lat":..,"lng":..}]} */
        fun parseJson(text: String): List<Radar> =
            merge(
                LenientJson.parseToJsonElement(text).obj()?.get("points").arr().orEmpty().mapNotNull { e ->
                    val o = e.obj() ?: return@mapNotNull null
                    point(o.str("a").orEmpty(), o.dbl("lat"), o.dbl("lng"))
                },
            )

        fun toJson(points: List<Radar>): String = buildJsonObject {
            put("points", JsonArray(points.map { r ->
                buildJsonObject { put("a", r.name); put("lat", r.location.lat); put("lng", r.location.lon) }
            }))
        }.toString()

        private fun point(desc: String, lat: Double?, lng: Double?): Radar? {
            lat ?: return null
            lng ?: return null
            val d = desc.split(Regex("\\s+")).joinToString(" ").trim()
            if (d.uppercase().contains("OHTS")) return null
            if (lat !in 35.5..42.5 || lng !in 25.5..45.0) return null
            // Kimlik konumdan türetilir: EGM sayfası kimlik vermiyor, güncellemelerde aynı nokta aynı kalsın
            val id = "egm:%.5f_%.5f".format(java.util.Locale.US, lat, lng)
            return Radar(id, d, "", "", null, LatLng(lat, lng), active = true, source = HazardSource.EGM)
        }
    }
}

/** Birden fazla sabit kamera kaynağını birleştirir; biri hata verirse diğerleri yine gelir. */
class CompositeCameraSource(private val sources: List<CameraSource>) : CameraSource {
    override suspend fun along(geometry: RouteGeometry): List<Radar> {
        var lastError: Exception? = null
        var anyOk = false
        val out = ArrayList<Radar>()
        for (s in sources) {
            try {
                out += s.along(geometry)
                anyOk = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
        }
        if (!anyOk && lastError != null) throw lastError
        return out
    }
}
