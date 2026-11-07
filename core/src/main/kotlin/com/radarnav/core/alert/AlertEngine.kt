package com.radarnav.core.alert

import com.radarnav.core.model.RouteHazard

/** FAR = 2 km, NEAR = 500 m, FINAL = 200 m (yalnızca hâlâ sınırın üstündeyse). */
enum class Stage { FAR, NEAR, FINAL }

/**
 * Anlık hıza göre uyarının sertliği. Seçilen kişilik (argo seviyesi) tavandır:
 * "Kibar" seçiliyse HARSH'ta bile küfür olmaz.
 */
enum class Severity {
    /** Sınırın altında: sakin hatırlatma. */
    CALM,
    /** Sınırda ya da 0-5 km/s üstünde: hafif takılma. */
    LIGHT,
    /** 6-20 km/s üstünde: laf sokma, söylenme. */
    SASSY,
    /** 20+ km/s üstünde: bağırarak, en sert hâli. */
    HARSH;

    companion object {
        fun of(speedKmh: Double, limit: Int?): Severity {
            if (limit == null) return CALM
            val over = speedKmh - limit
            return when {
                over <= 0.0 -> CALM
                over <= 5.0 -> LIGHT
                over <= 20.0 -> SASSY
                else -> HARSH
            }
        }
    }
}

sealed interface AlertEvent {
    val hazard: RouteHazard

    /** Uyarı metnini önceden hazırlama zamanı (Gemini gecikmesini gizlemek için). */
    data class Prefetch(override val hazard: RouteHazard, val stage: Stage, val distance: Double, val speedKmh: Double) : AlertEvent

    /** Radara ya da koridor girişine yaklaşma (2 km ve 500 m). */
    data class Approach(override val hazard: RouteHazard, val stage: Stage, val distance: Double, val speedKmh: Double) : AlertEvent

    data class CorridorEntered(override val hazard: RouteHazard, val speedKmh: Double) : AlertEvent

    /**
     * Koridorda ortalama hız sınırın üstünde. [targetKmh]: kalan yolu en fazla bu hızla giderse
     * ortalama sınıra iner; null ise kalan yolda artık düzeltilemiyor.
     */
    data class CorridorOverAverage(
        override val hazard: RouteHazard,
        val averageKmh: Double,
        val remainingMeters: Double,
        val targetKmh: Double? = null,
    ) : AlertEvent

    /** Daha önce sınırı aşan ortalama yeniden sınırın altına indi. */
    data class CorridorBackUnder(override val hazard: RouteHazard, val averageKmh: Double, val remainingMeters: Double) : AlertEvent

    data class CorridorExited(override val hazard: RouteHazard, val averageKmh: Double) : AlertEvent
}

data class AlertConfig(
    val farMeters: Double = 2000.0,
    val nearMeters: Double = 500.0,
    val finalMeters: Double = 200.0,
    val farPrefetchMeters: Double = 3200.0,
    val nearPrefetchMeters: Double = 1300.0,
    val corridorNagIntervalMs: Long = 60_000,
    /** Ortalama, sınırı bu kadar km/s aşarsa uyar. */
    val corridorTolerance: Double = 2.0,
)

data class CorridorProgress(val hazard: RouteHazard, val averageKmh: Double?, val remainingMeters: Double)

/**
 * Rota üzerindeki konuma göre radar/koridor uyarılarını üreten durum makinesi.
 * Her tehlike için her aşama yalnızca bir kez tetiklenir; yeniden rota hesaplanınca
 * [setHazards] kimliğe göre durumu korur.
 */
class AlertEngine(private val config: AlertConfig = AlertConfig()) {

    private var hazards: List<RouteHazard> = emptyList()
    private val fired = HashMap<String, MutableSet<String>>()
    private val nearFiredAt = HashMap<String, Double>()

    private var corridorId: String? = null
    private var corridorEntryAlong = 0.0
    private var coach: CorridorCoach? = null

    /** Şu an içinde bulunulan koridor. */
    var currentCorridor: CorridorProgress? = null
        private set

    fun setHazards(list: List<RouteHazard>, resetAlong: Boolean = false) {
        hazards = list
        if (resetAlong) {
            corridorId = null
            coach = null
            currentCorridor = null
        }
    }

    fun hazards(): List<RouteHazard> = hazards

    /** Konumdan sonraki ilk tehlike ve uzaklığı. */
    fun nextHazard(along: Double): Pair<RouteHazard, Double>? =
        hazards.firstOrNull { it.startAlong > along - 5 }?.let { it to (it.startAlong - along) }

    /** Ekranda gösterilecek geçerli hız sınırı: koridordaysak koridorun, 2 km içinde radar varsa onun. */
    fun applicableLimit(along: Double): Int? {
        currentCorridor?.hazard?.hazard?.speedLimit?.let { return it }
        return nextHazard(along)?.takeIf { it.second <= config.farMeters }?.first?.hazard?.speedLimit
    }

    private fun once(h: RouteHazard, key: String): Boolean = fired.getOrPut(h.hazard.id) { HashSet() }.add(key)
    private fun has(h: RouteHazard, key: String): Boolean = fired[h.hazard.id]?.contains(key) == true

    fun update(along: Double, speedKmh: Double, nowMs: Long): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        for (h in hazards) {
            val d = h.startAlong - along
            if (d < -30) continue
            if (d > config.farPrefetchMeters) break // sıralı liste

            if (d <= config.farPrefetchMeters && d > config.farMeters * 0.6 && once(h, "pf_far")) {
                out.add(AlertEvent.Prefetch(h, Stage.FAR, d, speedKmh))
            }
            if (d <= config.nearPrefetchMeters && d > config.nearMeters * 0.5 && once(h, "pf_near")) {
                out.add(AlertEvent.Prefetch(h, Stage.NEAR, d, speedKmh))
            }
            if (d <= config.nearMeters) {
                if (once(h, "near")) {
                    fired[h.hazard.id]!!.add("far")
                    nearFiredAt[h.hazard.id] = d
                    out.add(AlertEvent.Approach(h, Stage.NEAR, d.coerceAtLeast(0.0), speedKmh))
                } else if (d <= config.finalMeters && d >= -10) {
                    // 200 m: 500 m uyarısına rağmen hâlâ sınırın üstündeyse son, en sert uyarı.
                    // 500 m uyarısı zaten çok geç geldiyse (ör. navigasyon radarın dibinde başladı) tekrar etme.
                    val limit = h.hazard.speedLimit
                    val nearAt = nearFiredAt[h.hazard.id] ?: 0.0
                    if (limit != null && speedKmh > limit && nearAt > config.finalMeters + 100 && once(h, "final")) {
                        out.add(AlertEvent.Approach(h, Stage.FINAL, d.coerceAtLeast(0.0), speedKmh))
                    }
                }
            } else if (d <= config.farMeters && !has(h, "far")) {
                once(h, "far")
                out.add(AlertEvent.Approach(h, Stage.FAR, d, speedKmh))
            }
        }
        out.addAll(updateCorridor(along, speedKmh, nowMs))
        return out
    }

    private fun updateCorridor(along: Double, speedKmh: Double, nowMs: Long): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        val inside = hazards.firstOrNull { it.isCorridor && along >= it.startAlong && along <= it.endAlong }
        val active = corridorId?.let { id -> hazards.firstOrNull { it.hazard.id == id } }

        if (active != null && (inside == null || inside.hazard.id != active.hazard.id)) {
            val avg = coach?.average(along - corridorEntryAlong, nowMs)
            if (once(active, "exit")) out.add(AlertEvent.CorridorExited(active, avg ?: speedKmh))
            corridorId = null
            coach = null
            currentCorridor = null
        }
        if (inside != null && corridorId == null && !has(inside, "exit")) {
            corridorId = inside.hazard.id
            corridorEntryAlong = along
            coach = CorridorCoach(inside, inside.endAlong - along, nowMs, config)
            if (once(inside, "enter")) out.add(AlertEvent.CorridorEntered(inside, speedKmh))
        }
        val c = coach
        if (inside != null && corridorId == inside.hazard.id && c != null) {
            val (progress, events) = c.update(along - corridorEntryAlong, inside.endAlong - along, nowMs)
            currentCorridor = progress
            out.addAll(events)
        }
        return out
    }

}

/**
 * Bir koridor geçişinde ortalama hızı izler ve uyarı kararlarını verir ([AlertEngine] kullanır).
 * Ortalama = girişten beri gidilen yol / geçen süre;
 * EDS de girişte ve çıkışta okuyup aynı şekilde hesaplar.
 *
 * - Ortalama sınırı [AlertConfig.corridorTolerance]'tan fazla aşınca uyarır, aşım sürdükçe
 *   [AlertConfig.corridorNagIntervalMs]'de bir tekrarlar ve kalan yol için hedef hızı verir.
 * - Aşımdan sonra ortalama sınıra inince bir kez "tamam" der.
 */
class CorridorCoach(
    val hazard: RouteHazard,
    /** Girişten çıkışa toplam yol (metre). */
    private val totalMeters: Double,
    private val startMs: Long,
    private val config: AlertConfig = AlertConfig(),
) {
    // İlk uyarı girişten yarım aralık sonra gelebilsin (ortalama 30 sn sonra anlamlı)
    private var lastNag = startMs - config.corridorNagIntervalMs / 2
    private var overWarned = false

    /** Girişten bu yana ortalama hız (en az 30 sn ve 300 m sonra anlamlı). */
    fun average(travelled: Double, nowMs: Long): Double? {
        val dt = (nowMs - startMs) / 1000.0
        if (dt < 30 || travelled < 300) return null
        return travelled / dt * 3.6
    }

    /**
     * Kalan [remaining] metreyi en fazla kaç km/s ile giderse toplam ortalama sınıra iner.
     * Süre artık yetmiyorsa null.
     */
    fun targetKmh(travelled: Double, remaining: Double, nowMs: Long): Double? {
        val limit = hazard.hazard.speedLimit ?: return null
        val allowed = (travelled + remaining) / (limit / 3.6)
        val left = allowed - (nowMs - startMs) / 1000.0
        if (left <= 0 || remaining <= 0) return null
        return remaining / left * 3.6
    }

    fun update(travelled: Double, remaining: Double, nowMs: Long): Pair<CorridorProgress, List<AlertEvent>> {
        val avg = average(travelled, nowMs)
        val out = ArrayList<AlertEvent>()
        val limit = hazard.hazard.speedLimit
        if (avg != null && limit != null) {
            if (avg > limit + config.corridorTolerance) {
                if (nowMs - lastNag >= config.corridorNagIntervalMs && remaining > 500) {
                    lastNag = nowMs
                    overWarned = true
                    out.add(AlertEvent.CorridorOverAverage(hazard, avg, remaining, targetKmh(travelled, remaining, nowMs)))
                }
            } else if (overWarned && avg <= limit) {
                overWarned = false
                lastNag = nowMs
                out.add(AlertEvent.CorridorBackUnder(hazard, avg, remaining))
            }
        }
        return CorridorProgress(hazard, avg, remaining) to out
    }
}
