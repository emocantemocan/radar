package com.radarnav.core.alert

import com.radarnav.core.geo.Geo
import com.radarnav.core.geo.LatLng
import com.radarnav.core.ministry.MinistryApi
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.net.LenientJson
import kotlin.math.max

/**
 * Rota olmadan (serbest sürüş) ortalama hız koridorlarını kendiliğinden yakalar.
 *
 * Bakanlık koridoru yalnızca giriş ve çıkış noktasıyla veriyor; her yön ayrı kayıt. Bir koridorun
 * girişinden ~[armMeters] yakın geçilince aday olur; sonraki ~600 m'de çıkışa gerçekten yaklaşılıyorsa
 * (karşı yönün kaydı değilse) koridora girilmiş sayılır. Gidilen yol GPS konumlarından toplanır,
 * ortalama ve uyarılar [CorridorCoach] ile rotalı moddakiyle aynı. Çıkışa [exitMeters] kala biter;
 * yoldan çıkılırsa sessizce bırakılır.
 */
class FreeCorridorMonitor(
    private val corridors: () -> List<SpeedCorridor>,
    private val config: AlertConfig = AlertConfig(),
    private val armMeters: Double = 120.0,
    private val confirmMeters: Double = 600.0,
    private val exitMeters: Double = 150.0,
) {
    private data class Candidate(val c: SpeedCorridor, val armOdo: Double, val armMs: Long, val armToEnd: Double)

    private class Active(val c: SpeedCorridor, val hazard: RouteHazard, val armOdo: Double, val length: Double, val coach: CorridorCoach)

    private var odo = 0.0
    private var last: LatLng? = null
    private val candidates = ArrayList<Candidate>()
    private var active: Active? = null
    /** Biten/bırakılan koridor: aynı geçişte tekrar başlamasın (kimlik → zaman). */
    private val recent = HashMap<String, Long>()

    var currentCorridor: CorridorProgress? = null
        private set

    fun reset() {
        last = null
        candidates.clear()
        active = null
        currentCorridor = null
    }

    fun update(p: LatLng, speedKmh: Double, nowMs: Long): List<AlertEvent> {
        last?.let { prev ->
            val d = Geo.distance(prev, p)
            // Tünelde GPS kopunca düz çizgi yaklaşık doğru; 10 km'den büyük sıçrama hatalı konumdur
            if (d < 10_000) odo += d
        }
        last = p
        recent.entries.removeAll { nowMs - it.value > 15 * 60_000 }
        val out = ArrayList<AlertEvent>()

        val a = active
        if (a != null) {
            val travelled = odo - a.armOdo
            val toEnd = Geo.distance(p, a.c.end)
            val straight = Geo.distance(a.c.start, a.c.end)
            when {
                toEnd <= exitMeters -> {
                    val avg = a.coach.average(travelled, nowMs) ?: speedKmh
                    out.add(AlertEvent.CorridorExited(a.hazard, avg))
                    finish(a.c, nowMs)
                }
                // Koridor yolundan ayrıldı (sapak, geri dönüş): uyarmadan bırak
                toEnd > straight + 2_000 || travelled > a.length * 2 + 3_000 -> finish(a.c, nowMs)
                else -> {
                    val remaining = max(a.length - travelled, toEnd)
                    val (progress, events) = a.coach.update(travelled, remaining, nowMs)
                    currentCorridor = progress
                    out.addAll(events)
                }
            }
            return out
        }

        // Adayları doğrula ya da ele
        val it = candidates.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val since = odo - c.armOdo
            val toEnd = Geo.distance(p, c.c.end)
            if (since >= confirmMeters && c.armToEnd - toEnd >= confirmMeters * 0.5) {
                candidates.clear()
                val length = c.c.lengthKm?.takeIf { l -> l > 0 }?.times(1000) ?: (Geo.distance(c.c.start, c.c.end) * 1.15)
                val hazard = RouteHazard(c.c, 0.0, length)
                val coach = CorridorCoach(hazard, length, c.armMs, config)
                active = Active(c.c, hazard, c.armOdo, length, coach)
                currentCorridor = CorridorProgress(hazard, null, max(length - since, toEnd))
                out.add(AlertEvent.CorridorEntered(hazard, speedKmh))
                return out
            }
            if (since > confirmMeters * 3 || toEnd > c.armToEnd + 200) it.remove()
        }

        // Yeni aday: girişin yakınından geçiyor muyuz?
        for (c in corridors()) {
            if (c.id in recent || candidates.any { it.c.id == c.id }) continue
            if (Geo.distance(p, c.start) <= armMeters) {
                candidates.add(Candidate(c, odo, nowMs, Geo.distance(p, c.end)))
            }
        }
        return out
    }

    private fun finish(c: SpeedCorridor, nowMs: Long) {
        recent[c.id] = nowMs
        active = null
        currentCorridor = null
    }

    companion object {
        /** `scripts/ministry_corridors.py` çıktısı: {"corridors":[bakanlık SpeedTunnels kaydı, ...]}. */
        fun parseSnapshot(text: String): List<SpeedCorridor> {
            val root = LenientJson.parseToJsonElement(text)
            val list = (root as? kotlinx.serialization.json.JsonObject)?.get("corridors") ?: return emptyList()
            val wrapped = "{\"success\":true,\"data\":{\"RadarCount\":0,\"Radars\":[],\"SpeedTunnels\":$list}}"
            return MinistryApi.parseRoute(LenientJson.parseToJsonElement(wrapped)).corridors
        }
    }
}
