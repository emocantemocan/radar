package com.radarnav.core.report

import com.radarnav.core.geo.Geo
import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.RouteGeometry
import com.radarnav.core.model.HazardSource
import com.radarnav.core.model.Radar
import com.radarnav.core.net.LenientJson
import com.radarnav.core.net.arr
import com.radarnav.core.net.dbl
import com.radarnav.core.net.obj
import com.radarnav.core.osm.CameraSource
import com.radarnav.core.osm.TextStore
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/** Sürücünün kendi gördüğü seyyar radar. [count]: aynı yerde kaç kez bildirildi. */
data class UserReport(val location: LatLng, val at: Long, val count: Int = 1)

/**
 * Sürücünün bildirdiği seyyar radarlar. Yalnızca telefonda ([store]) tutulur, hiçbir sunucuya
 * gönderilmez. Seyyar ekipler genelde aynı noktalara tekrar kurulduğu için sonraki geçişlerde
 * "geçen sefer burada radar vardı" diye uyarılır. [maxAgeMillis]'ten eski kayıtlar unutulur.
 */
class UserReportStore(
    private val store: TextStore,
    private val maxAgeMillis: Long = 180L * 24 * 3600 * 1000,
    /** Bu mesafedeki eski kayıt yeni bildirimle birleştirilir (tarih yenilenir, sayı artar). */
    private val mergeMeters: Double = 150.0,
    private val clock: () -> Long = System::currentTimeMillis,
) : CameraSource {

    private val lock = Any()
    private var cache: List<UserReport>? = null

    fun all(): List<UserReport> = synchronized(lock) {
        val now = clock()
        (cache ?: load().also { cache = it }).filter { now - it.at < maxAgeMillis }
    }

    fun count(): Int = all().size

    /** Bildirimi kaydeder; yakında eski kayıt varsa onu günceller. Güncel kaydı döner. */
    fun report(location: LatLng, at: Long = clock()): UserReport = synchronized(lock) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { Geo.distance(it.location, location) <= mergeMeters }
        val r = if (i >= 0) {
            // Konum son bildirime kayar: ekip aynı yerin biraz ötesine kurulabilir
            UserReport(location, at, list[i].count + 1).also { list[i] = it }
        } else {
            UserReport(location, at).also { list += it }
        }
        save(list)
        r
    }

    /** [location]'a [withinMeters] mesafedeki kayıtları siler ("burada radar yok"). Silinen sayısı. */
    fun removeNear(location: LatLng, withinMeters: Double = 300.0): Int = synchronized(lock) {
        val list = all()
        val keep = list.filter { Geo.distance(it.location, location) > withinMeters }
        if (keep.size != list.size) save(keep)
        list.size - keep.size
    }

    fun clear() = synchronized(lock) { save(emptyList()) }

    override suspend fun along(geometry: RouteGeometry): List<Radar> = all().map { toRadar(it) }

    private fun save(list: List<UserReport>) {
        cache = list
        store.write(KEY, encode(list))
    }

    private fun load(): List<UserReport> = store.read(KEY)?.let { runCatching { decode(it) }.getOrNull() }.orEmpty()

    companion object {
        const val KEY = "user_reports"
        const val ID_PREFIX = "user:"

        fun toRadar(r: UserReport) = Radar(
            id = ID_PREFIX + String.format(Locale.US, "%.5f_%.5f", r.location.lat, r.location.lon),
            name = "",
            province = "",
            district = "",
            speedLimit = null,
            location = r.location,
            source = HazardSource.USER,
            reportedAt = r.at,
            reportCount = r.count,
        )

        fun encode(list: List<UserReport>): String = buildJsonObject {
            putJsonArray("reports") {
                list.forEach { r ->
                    addJsonObject {
                        put("lat", r.location.lat)
                        put("lng", r.location.lon)
                        put("at", r.at)
                        put("count", r.count)
                    }
                }
            }
        }.toString()

        fun decode(s: String): List<UserReport> =
            LenientJson.parseToJsonElement(s).obj()?.get("reports").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val lat = o.dbl("lat") ?: return@mapNotNull null
                val lng = o.dbl("lng") ?: return@mapNotNull null
                val at = o.dbl("at")?.toLong() ?: return@mapNotNull null
                UserReport(LatLng(lat, lng), at, (o.dbl("count") ?: 1.0).toInt().coerceAtLeast(1))
            }

        /** "bugün", "dün", "5 gün önce", "3 hafta önce", "2 ay önce". */
        fun ageText(at: Long, now: Long): String {
            val days = ((now - at).coerceAtLeast(0) / (24L * 3600 * 1000)).toInt()
            return when {
                days == 0 -> "bugün"
                days == 1 -> "dün"
                days < 14 -> "$days gün önce"
                days < 60 -> "${days / 7} hafta önce"
                else -> "${days / 30} ay önce"
            }
        }
    }
}
