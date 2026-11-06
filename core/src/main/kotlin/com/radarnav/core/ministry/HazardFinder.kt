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
import kotlinx.coroutines.sync.withPermit
import kotlin.math.max
import kotlin.math.min

data class HazardResult(
    val hazards: List<RouteHazard>,
    /** Bazı bakanlık sorguları başarısız olduysa kullanıcıya gösterilecek not. */
    val warning: String?,
)

/**
 * Bizim navigasyon rotamız üzerindeki radar ve hız koridorlarını bulur.
 *
 * Bakanlık API'si yalnızca ilçeden ilçeye rota hesaplıyor ve kendi güzergahını kullanıyor.
 * Bizim rotamız farklı yollardan geçebileceği için rotayı ~[anchorSpacingMeters] aralıklarla
 * parçalara bölüp her parça için ayrı sorgu atıyoruz, sonra gelen noktaları bizim rotamıza
 * izdüşürüp yalnızca gerçekten üzerinden geçtiklerimizi tutuyoruz.
 */
class HazardFinder(
    private val api: MinistryApi,
    private val directory: DistrictDirectory,
    private val anchorSpacingMeters: Double = 70_000.0,
    private val radarToleranceMeters: Double = 90.0,
    private val corridorToleranceMeters: Double = 300.0,
) {

    suspend fun find(route: NavRoute): HazardResult = coroutineScope {
        val geometry = route.geometry
        val anchors = anchorPoints(geometry)
        val districts = try {
            anchors.mapNotNull { directory.nearest(it) }
                .fold(mutableListOf<MinistryDistrict>()) { acc, d -> if (acc.lastOrNull()?.id != d.id) acc.add(d); acc }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }

        if (districts.isEmpty()) {
            return@coroutineScope HazardResult(emptyList(), "İlçe listesi alınamadı, radar bilgisi yok.")
        }
        val pairs = if (districts.size == 1) listOf(districts[0] to districts[0]) else districts.zipWithNext()

        val limit = Semaphore(4)
        val results = pairs.map { (a, b) ->
            async { limit.withPermit { runCatching { api.createRoute(a, b) } } }
        }.awaitAll()

        val failed = results.count { it.isFailure }
        val hazards = LinkedHashMap<String, Hazard>()
        results.mapNotNull { it.getOrNull() }.forEach { data ->
            data.radars.forEach { hazards.putIfAbsent(it.id, it) }
            data.corridors.forEach { hazards.putIfAbsent(it.id, it) }
        }

        val placed = place(geometry, hazards.values)
        val warning = when {
            failed == 0 -> null
            failed == results.size -> "Bakanlık radar servisine ulaşılamadı. Radar uyarıları bu rotada çalışmayacak."
            else -> "Rotanın bazı bölümleri için radar bilgisi alınamadı ($failed/${results.size} sorgu başarısız)."
        }
        HazardResult(placed, warning)
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
