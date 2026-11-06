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
import kotlin.math.max
import kotlin.math.min

data class HazardResult(
    val hazards: List<RouteHazard>,
    /** Bazı bakanlık sorguları başarısız olduysa kullanıcıya gösterilecek not. */
    val warning: String?,
    /** Verinin bakanlıktan en eski alınma zamanı (önbellekten geldiyse eski olabilir). */
    val updatedAt: Long = System.currentTimeMillis(),
    /** Hiçbir sorgu başarılı olmadı ve hiç veri yok. */
    val unavailable: Boolean = false,
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
) {
    private data class Cached(val at: Long, val data: MinistryRouteData)

    private val cache = HashMap<String, Cached>()
    private val cacheLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun query(a: MinistryDistrict, b: MinistryDistrict, force: Boolean): Result<Cached> {
        val key = "${a.id}-${b.id}"
        val cached = cacheLock.withLock { cache[key] }
        if (!force && cached != null && clock() - cached.at < freshMillis) return Result.success(cached)
        return try {
            val fresh = Cached(clock(), api.createRoute(a, b))
            cacheLock.withLock { cache[key] = fresh }
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
    suspend fun find(route: NavRoute, force: Boolean = false, includeInactive: Boolean = true): HazardResult = coroutineScope {
        val geometry = route.geometry
        val anchors = anchorPoints(geometry)
        var districtError: Exception? = null
        val districts = mutableListOf<MinistryDistrict>()
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

        if (districts.isEmpty()) {
            val why = districtError?.message ?: "ilçe bulunamadı"
            return@coroutineScope HazardResult(emptyList(), "Radar verisi alınamadı: $why", unavailable = true)
        }
        val pairs = if (districts.size == 1) listOf(districts[0] to districts[0]) else districts.zipWithNext()

        // Bakanlık sunucusunu yormamak için en fazla 2 eşzamanlı sorgu
        val limit = Semaphore(2)
        val results = pairs.map { (a, b) ->
            async { limit.withPermit { query(a, b, force) } }
        }.awaitAll()

        val failures = results.mapNotNull { it.exceptionOrNull() }
        val ok = results.mapNotNull { it.getOrNull() }
        val hazards = LinkedHashMap<String, Hazard>()
        ok.forEach { c ->
            c.data.radars.filter { includeInactive || it.active }.forEach { hazards.putIfAbsent(it.id, it) }
            c.data.corridors.filter { includeInactive || it.active }.forEach { hazards.putIfAbsent(it.id, it) }
        }

        val placed = place(geometry, hazards.values)
        val oldest = ok.minOfOrNull { it.at } ?: clock()
        val warning = when {
            failures.isEmpty() -> null
            ok.isEmpty() -> "Bakanlık radar servisine ulaşılamadı: ${failures.first().message}"
            else -> "Rotanın bazı bölümleri için radar bilgisi alınamadı (${failures.size}/${results.size}): " +
                "${failures.first().message}"
        }
        HazardResult(placed, warning, oldest, unavailable = ok.isEmpty())
    }

    internal fun anchorPoints(geometry: RouteGeometry): List<LatLng> {
        val length = geometry.length
        val count = max(1, (length / anchorSpacingMeters).toInt())
        return (0..count).map { geometry.pointAt(length * it / count) }
    }

    /** Tehlikeleri rotaya yerleştirir; rotadan uzak olanları atar ve sıralar. */
    fun place(geometry: RouteGeometry, hazards: Collection<Hazard>): List<RouteHazard> {
        val out = ArrayList<RouteHazard>()
        for (h in hazards) {
            when (h) {
                is Radar -> {
                    val pr = geometry.project(h.location)
                    if (pr.crossTrack <= radarToleranceMeters) out.add(RouteHazard(h, pr.along, pr.along))
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
                        if (to - from > 200 && okLength) out.add(RouteHazard(h, from, to))
                    }
                }
            }
        }
        return dedupe(out.sortedBy { it.startAlong })
    }

    /** Aynı noktada birden fazla kayıt (ör. iki yön için ayrı radar) varsa tekini tut. */
    private fun dedupe(sorted: List<RouteHazard>): List<RouteHazard> {
        val out = ArrayList<RouteHazard>()
        for (h in sorted) {
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
