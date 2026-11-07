package com.radarnav.core.ministry

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.RouteGeometry
import com.radarnav.core.model.Hazard
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.Radar
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.max
import com.radarnav.core.model.HazardSource
import com.radarnav.core.net.LenientJson
import com.radarnav.core.osm.CameraSource
import com.radarnav.core.osm.TextStore
import kotlin.math.min

data class HazardResult(
    val hazards: List<RouteHazard>,
    /** Bazı bakanlık sorguları başarısız olduysa kullanıcıya gösterilecek not. */
    val warning: String?,
    /** Verinin bakanlıktan en eski alınma zamanı (önbellekten geldiyse eski olabilir). */
    val updatedAt: Long = System.currentTimeMillis(),
    /** Hiçbir sorgu başarılı olmadı ve hiç veri yok. */
    val unavailable: Boolean = false,
    /** Bakanlığın bu güzergah için bildirdiği günlük radarlı uygulama sayısı (konumu bilinmese de). */
    val ministryRadarCount: Int = 0,
    /** Rotaya yerleşen OSM sabit kamera sayısı. */
    val osmCount: Int = 0,
    /** Rotaya yerleşen EGM EDS noktası sayısı. */
    val egmCount: Int = 0,
    /** Rotaya yerleşen, sürücünün kendi bildirdiği seyyar radar sayısı. */
    val userCount: Int = 0,
    /** Bakanlık yanıtı verdi ama radar konumlarını boş gönderdi (eski kayıtlar kullanıldı). */
    val radarsWithheld: Boolean = false,
)

/**
 * Bizim navigasyon rotamız üzerindeki radar ve hız koridorlarını bulur.
 *
 * Bakanlık API'si yalnızca ilçeden ilçeye rota hesaplıyor ve kendi güzergahını kullanıyor.
 * Bizim rotamız farklı yollardan geçebileceği için rotayı ~[anchorSpacingMeters] aralıklarla
 * parçalara bölüp her parça için ayrı sorgu atıyoruz, sonra gelen noktaları bizim rotamıza
 * izdüşürüp yalnızca gerçekten üzerinden geçtiklerimizi tutuyoruz.
 *
 * Sorgu sonuçları [freshMillis] boyunca önbellekte tutulur (bakanlık verisi ~30 dakikada bir
 * güncelleniyor). Sorgu başarısız olursa daha eski önbellek kullanılır.
 */
class HazardFinder(
    private val api: MinistryApi,
    private val directory: DistrictDirectory,
    private val anchorSpacingMeters: Double = 70_000.0,
    private val radarToleranceMeters: Double = 90.0,
    private val corridorToleranceMeters: Double = 300.0,
    private val freshMillis: Long = 25L * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Sabit kameralar (OpenStreetMap); null ise yalnızca bakanlık. */
    private val cameras: CameraSource? = null,
    /** Bakanlık yanıtlarının kalıcı önbelleği: sunucu çöktüğünde ya da uygulama yeniden açıldığında kullanılır. */
    private val store: TextStore? = null,
    private val staleMillis: Long = 30L * 24 * 3600 * 1000,
    private val osmToleranceMeters: Double = 50.0,
    private val egmToleranceMeters: Double = 40.0,
    private val userToleranceMeters: Double = 60.0,
) {
    private data class Cached(val at: Long, val data: MinistryRouteData)

    private val cache = HashMap<String, Cached>()
    private val cacheLock = kotlinx.coroutines.sync.Mutex()

    private fun storeKey(key: String) = "ministry_route_$key"

    private fun readStored(key: String): Cached? = runCatching {
        val s = store?.read(storeKey(key)) ?: return null
        val nl = s.indexOf('\n')
        val at = s.substring(0, nl).toLong()
        if (clock() - at > staleMillis) return null
        Cached(at, MinistryApi.parseRoute(LenientJson.parseToJsonElement(s.substring(nl + 1))))
    }.getOrNull()

    private suspend fun cachedFor(a: MinistryDistrict, b: MinistryDistrict): Cached? {
        val key = "${a.id}-${b.id}"
        return cacheLock.withLock { cache[key] ?: readStored(key)?.also { cache[key] = it } }
    }

    private suspend fun query(a: MinistryDistrict, b: MinistryDistrict, force: Boolean): Result<Cached> {
        val key = "${a.id}-${b.id}"
        val cached = cacheLock.withLock { cache[key] ?: readStored(key)?.also { cache[key] = it } }
        if (!force && cached != null && clock() - cached.at < freshMillis) return Result.success(cached)
        return try {
            val raw = api.createRouteRaw(a, b)
            var data = MinistryApi.parseRoute(LenientJson.parseToJsonElement(raw))
            val withheld = data.radarsWithheld
            // Sunucu radar sayısını verip konumları boş gönderdiyse önceki bilinen konumları koru
            if (withheld && cached != null && cached.data.radars.isNotEmpty()) {
                data = data.copy(radars = cached.data.radars)
            }
            val fresh = Cached(clock(), data)
            cacheLock.withLock { cache[key] = fresh }
            // Konumsuz yanıt kalıcı önbellekteki konumlu yanıtın üstüne yazılmaz
            if (!withheld) runCatching { store?.write(storeKey(key), "${fresh.at}\n$raw") }
            Result.success(fresh)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Ağ yoksa ya da sunucu cevap vermiyorsa son bilinen veriyi kullan
            if (cached != null) Result.success(cached) else Result.failure(e)
        }
    }

    /**
     * [force] true ise önbellekteki taze veriyi yok sayıp bakanlığa yeniden sorar.
     * [includeInactive] false ise bakanlığın pasif işaretlediği kayıtlar atılır.
     */
    /**
     * [budgetMs]: her kaynağı en fazla bu kadar bekle. Bakanlık çöktüğünde istekler zaman aşımına
     * kadar asılı kalıp 3 kez deneniyor (dakikalar); rota planlanırken kullanıcı bunu beklememeli.
     * Süre dolarsa elde olan (önbellek + OSM) ile dönülür, arka plandaki yenileme sonra tamamlar.
     */
    suspend fun find(
        route: NavRoute,
        force: Boolean = false,
        includeInactive: Boolean = true,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): HazardResult = coroutineScope {
        val geometry = route.geometry
        val anchors = anchorPoints(geometry)
        // Sabit kameralar (OSM) bakanlık sorgularıyla paralel; hata olursa yalnızca bakanlık verisi kullanılır
        val osmJob = cameras?.let { src ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(budgetMs) { src.along(geometry) }
                        ?: throw java.io.IOException("OSM kamera sorgusu zaman aşımı")
                }.onFailure { if (it is kotlinx.coroutines.CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it }
            }
        }

        var districtError: Exception? = null
        val districts = mutableListOf<MinistryDistrict>()
        // İlçe listesi ilk kez indiriliyorsa ve bakanlık asılı kalıyorsa rota bunu da beklememeli
        val located = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(budgetMs) {
                for (p in anchors) {
                    val d = try {
                        directory.nearest(p)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        districtError = e
                        null
                    } ?: continue
                    if (districts.lastOrNull()?.id != d.id) districts.add(d)
                }
                true
            }
        }
        if (located == null && districts.isEmpty()) districtError = java.io.IOException("ilçe listesi zamanında alınamadı")

        if (districts.isEmpty()) {
            val osm = osmJob?.await()?.getOrNull().orEmpty()
            val placed = place(geometry, osm)
            val why = districtError?.message ?: "ilçe bulunamadı"
            return@coroutineScope HazardResult(
                placed, "Bakanlık radar verisi alınamadı: $why", unavailable = placed.isEmpty(),
                osmCount = placed.count { it.isOsm },
            )
        }
        // Parça parça sorgu (uzun rotada bakanlık tek sorguda her şeyi vermeyebilir) + başlangıçtan
        // varışa tek sorgu: bakanlık parça sorgularında bazı koridorları döndürmüyor (ölçüldü:
        // İzmir→Afyon'da Kula–Uşak koridoru yalnızca uçtan uca sorguda geliyor)
        val pairs = (if (districts.size == 1) listOf(districts[0] to districts[0]) else districts.zipWithNext()) +
            (if (districts.size > 2) listOf(districts.first() to districts.last()) else emptyList())

        // Bakanlık sunucusunu yormamak için en fazla 2 eşzamanlı sorgu
        val limit = Semaphore(2)
        val results = pairs.map { (a, b) ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(budgetMs) { limit.withPermit { query(a, b, force) } }
                    ?: cachedFor(a, b)?.let { Result.success(it) }
                    ?: Result.failure(java.io.IOException("Bakanlık servisi zamanında cevap vermedi"))
            }
        }.awaitAll()

        val failures = results.mapNotNull { it.exceptionOrNull() }
        val ok = results.mapNotNull { it.getOrNull() }
        val hazards = LinkedHashMap<String, Hazard>()
        ok.forEach { c ->
            c.data.radars.filter { includeInactive || it.active }.forEach { hazards.putIfAbsent(it.id, it) }
            c.data.corridors.filter { includeInactive || it.active }.forEach { hazards.putIfAbsent(it.id, it) }
        }

        val osmResult = osmJob?.await()
        osmResult?.getOrNull()?.forEach { hazards.putIfAbsent(it.id, it) }

        val placed = place(geometry, hazards.values)
        val oldest = ok.minOfOrNull { it.at } ?: clock()
        val withheld = ok.any { it.data.radarsWithheld && it.data.radars.isEmpty() }
        val ministryCount = ok.sumOf { it.data.radarCount }
        val warning = when {
            failures.isEmpty() && !withheld -> null
            ok.isEmpty() -> "Bakanlık radar servisine ulaşılamadı: ${failures.first().message}"
            failures.isNotEmpty() -> "Rotanın bazı bölümleri için radar bilgisi alınamadı (${failures.size}/${results.size}): " +
                "${failures.first().message}"
            else -> "Bakanlık bugün güzergahta $ministryCount radar uygulaması bildiriyor ama konumlarını şu an paylaşmıyor."
        }
        HazardResult(
            placed, warning, oldest,
            userCount = placed.count { it.source == HazardSource.USER },
            unavailable = ok.isEmpty() && placed.isEmpty(),
            ministryRadarCount = ministryCount,
            osmCount = placed.count { it.isOsm },
            egmCount = placed.count { it.source == HazardSource.EGM },
            radarsWithheld = withheld,
        )
    }

    companion object {
        const val DEFAULT_BUDGET_MS = 12_000L
    }

    internal fun anchorPoints(geometry: RouteGeometry): List<LatLng> {
        val length = geometry.length
        val count = max(1, (length / anchorSpacingMeters).toInt())
        return (0..count).map { geometry.pointAt(length * it / count) }
    }

    /** Tehlikeleri rotaya yerleştirir; rotadan uzak olanları atar ve sıralar. */
    fun place(geometry: RouteGeometry, hazards: Collection<Hazard>): List<RouteHazard> {
        val out = ArrayList<RouteHazard>()
        // Bakanlık her yönü ayrı kayıt olarak veriyor; ters yönün kaydı da iki ucuyla yolumuzun üstünde.
        // Yalnızca gidiş yönümüze uyan kayıt tutulur; o yoksa ters kayıt çevrilerek kullanılır.
        val reversed = ArrayList<RouteHazard>()
        for (h in hazards) {
            when (h) {
                is Radar -> {
                    val pr = geometry.project(h.location)
                    val tolerance = when (h.source) {
                        HazardSource.OSM -> osmToleranceMeters
                        // EDS'ler çoğunlukla kavşakta: dik yoldaki kamerayı almamak için dar tolerans
                        HazardSource.EGM -> egmToleranceMeters
                        HazardSource.MINISTRY -> radarToleranceMeters
                        // Bildirim sürücünün GPS konumu: yolun üstünde ama GPS hatası payı gerekir
                        HazardSource.USER -> userToleranceMeters
                    }
                    if (pr.crossTrack <= tolerance && !facesOtherLane(h, geometry.bearingAt(pr.along))) {
                        out.add(RouteHazard(h, pr.along, pr.along))
                    }
                }
                is SpeedCorridor -> {
                    val s = geometry.project(h.start)
                    val e = geometry.project(h.end)
                    if (s.crossTrack <= corridorToleranceMeters && e.crossTrack <= corridorToleranceMeters) {
                        val from = min(s.along, e.along)
                        val to = max(s.along, e.along)
                        // Koridorun gerçekten rotada olduğunu doğrula: rota üzerindeki uzunluk makul olmalı
                        val expected = h.lengthKm?.times(1000)
                        val okLength = expected == null || (to - from) >= expected * 0.5
                        if (to - from > 200 && okLength) {
                            if (s.along <= e.along) out.add(RouteHazard(h, from, to))
                            else reversed.add(RouteHazard(h.copy(start = h.end, end = h.start), from, to))
                        }
                    }
                }
            }
        }
        for (r in reversed) {
            val covered = out.any { o ->
                o.isCorridor && minOf(o.endAlong, r.endAlong) - maxOf(o.startAlong, r.startAlong) > (r.endAlong - r.startAlong) * 0.5
            }
            if (!covered) out.add(r)
        }
        return dedupe(out.sortedBy { it.startAlong })
    }

    /**
     * Kamera bizim gittiğimiz yöne bakıyorsa karşı şeridi denetliyordur. Kamera yüzü trafiğe dönük
     * olduğu için denetlenen akış yönü = bakış + 180°. Etiket hatalarına karşı yalnızca açıkça ters
     * olanlar (fark > 135°) atılır.
     */
    internal fun facesOtherLane(r: Radar, routeBearing: Double): Boolean {
        val facing = r.facing ?: return false
        val enforcedTravel = (facing + 180.0) % 360.0
        val diff = abs(((enforcedTravel - routeBearing + 540.0) % 360.0) - 180.0)
        return diff > 135.0
    }

    /** Aynı noktada birden fazla kayıt (ör. iki yön için ayrı radar) varsa tekini tut. */
    private fun dedupe(sorted: List<RouteHazard>): List<RouteHazard> {
        val out = ArrayList<RouteHazard>()
        for (h in sorted) {
            // Aynı yerde farklı kaynaklardan kayıt varsa öncelikli kaynağınki tutulur
            // (bakanlık > EGM > OSM); sınırı eksikse diğerindeki sınır aktarılır
            if (!h.isCorridor) {
                val twin = out.lastOrNull { !it.isCorridor && it.source != h.source && kotlin.math.abs(it.startAlong - h.startAlong) < 150 }
                if (twin != null) {
                    val (keep, drop) = if (h.source.ordinal < twin.source.ordinal) h to twin else twin to h
                    val r = keep.hazard as Radar
                    val merged = if (r.speedLimit == null && drop.hazard.speedLimit != null) keep.copy(hazard = r.copy(speedLimit = drop.hazard.speedLimit)) else keep
                    out[out.indexOf(twin)] = merged
                    continue
                }
            }
            val dup = out.lastOrNull { it.isCorridor == h.isCorridor }?.let { prev ->
                kotlin.math.abs(prev.startAlong - h.startAlong) < 60 &&
                    kotlin.math.abs(prev.endAlong - h.endAlong) < 300 &&
                    prev.hazard.speedLimit == h.hazard.speedLimit
            } ?: false
            if (!dup) out.add(h)
        }
        return out
    }
}
