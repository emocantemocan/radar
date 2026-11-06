package com.radarnav.core.osm

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.RouteGeometry
import com.radarnav.core.model.HazardSource
import com.radarnav.core.model.Radar
import com.radarnav.core.net.HttpException
import com.radarnav.core.net.LenientJson
import com.radarnav.core.net.arr
import com.radarnav.core.net.dbl
import com.radarnav.core.net.fetchString
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import kotlin.math.floor

/** Anahtar-değer metin önbelleği (uygulamada dosya, testte bellek). */
interface TextStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class InMemoryTextStore : TextStore {
    val map = HashMap<String, String>()
    override fun read(key: String) = map[key]
    override fun write(key: String, value: String) {
        map[key] = value
    }
}

/** Rota boyunca sabit hız kameraları veren kaynak (test için arayüz). */
interface CameraSource {
    suspend fun along(geometry: RouteGeometry): List<Radar>
}

/**
 * OpenStreetMap'teki sabit hız kameraları (`highway=speed_camera`), Overpass API ile.
 *
 * Türkiye [tileDeg] derecelik karelere bölünür; rotanın geçtiği her kare bir kez sorulur ve
 * [maxAgeMillis] boyunca (varsayılan 7 gün) [store]'da saklanır. Kameralar nadiren değiştiği için
 * normal kullanımda haftada bir, kare başına tek istek gider. Sunucular sırayla, aralarında
 * bekleyerek sorulur; biri meşgulse (429/5xx/zaman aşımı) diğerine geçilir.
 * Veri © OpenStreetMap katkıcıları, ODbL.
 */
class OsmCameraSource(
    private val client: OkHttpClient,
    private val store: TextStore,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val tileDeg: Double = 0.5,
    private val maxAgeMillis: Long = 7L * 24 * 3600 * 1000,
    private val politeDelayMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Uygulamaya gömülü Türkiye anlık görüntüsü (tüm sabit kameralar). Önbellekte olmayan kareler
     * için ağ beklenmeden bu kullanılır; Overpass yalnızca arka planda güncellemek içindir.
     */
    private val snapshot: (() -> List<Radar>?)? = null,
    /** Güncellemelerin çalıştığı kapsam: çağıranın zaman aşımı indirmeyi yarıda kesmesin. */
    private val scope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
) : CameraSource {

    private val lock = Mutex()
    private var refreshing: kotlinx.coroutines.Deferred<Unit>? = null

    /** Gömülü veri; yoksa ya da okunamadıysa null (o zaman eksik kareler için ağ beklenir). */
    private val snapshotByTile: Map<Tile, List<Radar>>? by lazy {
        runCatching { snapshot?.invoke()?.groupBy { tileOf(it.location) } }.getOrNull()
    }

    internal data class Tile(val x: Int, val y: Int) {
        val key get() = "osm_cam_${x}_$y"
    }

    internal fun tileOf(p: LatLng) = Tile(floor(p.lon / tileDeg).toInt(), floor(p.lat / tileDeg).toInt())

    /** Rotanın geçtiği kareler (kenara yakın kameralar için her noktanın ~1 km çevresi de). */
    internal fun tilesFor(geometry: RouteGeometry): List<Tile> {
        val out = LinkedHashSet<Tile>()
        val step = 500.0
        val pad = 0.01 // ~1 km
        var along = 0.0
        while (along <= geometry.length) {
            val p = geometry.pointAt(along)
            for (dy in listOf(-pad, 0.0, pad)) for (dx in listOf(-pad, 0.0, pad)) out.add(tileOf(LatLng(p.lat + dy, p.lon + dx)))
            along += step
        }
        out.add(tileOf(geometry.pointAt(geometry.length)))
        return out.toList()
    }

    override suspend fun along(geometry: RouteGeometry): List<Radar> {
        val tiles = tilesFor(geometry)
        val (cached, job) = lock.withLock {
            val cached = tiles.associateWith { t -> store.read(t.key)?.let { decodeCache(it) } }
            val stale = tiles.filter { t -> cached[t]?.let { clock() - it.first >= maxAgeMillis } ?: true }
            val job = if (stale.isNotEmpty()) refreshing?.takeIf { it.isActive } ?: scope.async { refresh(stale) }.also { refreshing = it } else null
            cached to job
        }
        // Hiç verisi olmayan kare varsa (ne önbellek ne gömülü veri) indirmeyi bekle; çağıranın
        // zaman aşımı yalnızca beklemeyi keser, indirme arka planda tamamlanıp önbelleğe yazılır.
        val snap = snapshotByTile
        val missing = snap == null && tiles.any { cached[it] == null }
        var error: Throwable? = null
        if (missing && job != null) {
            try {
                job.await()
            } catch (e: Exception) {
                if (e is CancellationException && job.isActive) throw e
                error = e
            }
        }
        val result = LinkedHashMap<String, Radar>()
        var anyData = false
        for (t in tiles) {
            val list = store.read(t.key)?.let { decodeCache(it) }?.second ?: cached[t]?.second
                ?: snap?.get(t) ?: if (snap != null) emptyList() else null
            if (list == null) continue
            anyData = true
            list.orEmpty().forEach { result.putIfAbsent(it.id, it) }
        }
        if (!anyData && error != null) throw IOException("OSM kamera verisi alınamadı: ${error.message}", error)
        return result.values.toList()
    }

    /** Eksik/eski karelerin hepsi tek istekte sorulur, sonuç karelere bölünüp ayrı saklanır. */
    private suspend fun refresh(stale: List<Tile>) {
        val cams = parse(LenientJson.parseToJsonElement(fetch(stale)))
        val byTile = cams.groupBy { tileOf(it.location) }
        val now = clock()
        for (t in stale) store.write(t.key, encodeCache(now, byTile[t].orEmpty()))
    }

    private suspend fun fetch(tiles: List<Tile>): String {
        val boxes = tiles.joinToString("") { t ->
            val s = t.y * tileDeg
            val w = t.x * tileDeg
            String.format(Locale.US, "node[\"highway\"=\"speed_camera\"](%.4f,%.4f,%.4f,%.4f);", s, w, s + tileDeg, w + tileDeg)
        }
        val query = "[out:json][timeout:25];($boxes);out;"
        var last: Exception? = null
        for ((i, ep) in endpoints.withIndex()) {
            if (i > 0) delay(politeDelayMs)
            try {
                val req = Request.Builder().url(ep).post(FormBody.Builder().add("data", query).build()).build()
                return client.fetchString(req)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                last = e
                val retryable = e !is HttpException || e.code == 429 || e.code >= 500
                if (!retryable) break
            }
        }
        throw last ?: IOException("Overpass sunucusu yok")
    }

    private fun encodeCache(at: Long, cams: List<Radar>): String {
        val elements = cams.joinToString(",") { c ->
            val id = c.id.removePrefix("osm:")
            val tags = buildList {
                c.speedLimit?.let { add("\"maxspeed\":\"$it\"") }
                c.facing?.let { add("\"direction\":\"${it}\"") }
                if (c.name.isNotBlank()) add("\"name\":${kotlinx.serialization.json.JsonPrimitive(c.name)}")
            }.joinToString(",")
            "{\"type\":\"node\",\"id\":\"$id\",\"lat\":${c.location.lat},\"lon\":${c.location.lon},\"tags\":{$tags}}"
        }
        return "$at\n{\"elements\":[$elements]}"
    }

    private fun decodeCache(s: String): Pair<Long, List<Radar>>? = runCatching {
        val nl = s.indexOf('\n')
        s.substring(0, nl).toLong() to parse(LenientJson.parseToJsonElement(s.substring(nl + 1)))
    }.getOrNull()

    companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )

        fun parse(json: JsonElement): List<Radar> = json.obj()?.get("elements").arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            if (o.str("type") != "node") return@mapNotNull null
            val lat = o.dbl("lat") ?: return@mapNotNull null
            val lon = o.dbl("lon") ?: return@mapNotNull null
            val tags = o["tags"].obj()
            Radar(
                id = "osm:" + (o.str("id") ?: "${lat}_$lon"),
                name = tags?.str("name") ?: tags?.str("alt_name") ?: tags?.str("ref") ?: "",
                province = "",
                district = "",
                speedLimit = parseMaxSpeed(tags?.str("maxspeed")),
                location = LatLng(lat, lon),
                active = true,
                facing = parseDirection(tags?.str("direction")),
                source = HazardSource.OSM,
            )
        }

        /** "82", "50 km/h", "50;70" → ilk sayı; "TR:urban", "none", "mph" → null. */
        fun parseMaxSpeed(v: String?): Int? {
            val s = v?.trim()?.lowercase(Locale.ROOT) ?: return null
            if ("mph" in s) return null
            return Regex("^(\\d{2,3})").find(s)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 10..160 }
        }

        private val CARDINAL = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

        /** "270", "W", "NNE" → derece; "forward"/"backward" (yola göre, tek başına anlamsız) → null. */
        fun parseDirection(v: String?): Double? {
            val s = v?.trim()?.uppercase(Locale.ROOT) ?: return null
            s.toDoubleOrNull()?.let { return ((it % 360) + 360) % 360 }
            val i = CARDINAL.indexOf(s)
            return if (i >= 0) i * 22.5 else null
        }
    }
}
