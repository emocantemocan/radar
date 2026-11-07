package com.radarnav.assistant

import com.radarnav.AppGraph
import com.radarnav.core.gemini.ToolDeclaration
import com.radarnav.core.gemini.ToolHandler
import com.radarnav.core.model.Radar
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.TurkishInstructions
import com.radarnav.nav.NavState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Gemini'nin sürüş bilgisine erişmek ve rotayı değiştirmek için çağırdığı fonksiyonlar. */
class AssistantTools(private val graph: AppGraph) : ToolHandler {

    fun systemInstruction(): String {
        val persona = graph.settings.persona
        return """
            Sen Türkiye'de araç kullanan bir sürücünün sesli navigasyon asistanısın; telefon ya da Android Auto üzerinden konuşuyorsun.
            Her zaman Türkçe konuş. Kişiliğin: ${persona.style}
            Sürücü yolda olduğu için cevapların kısa olsun (genelde 1-3 cümle), liste okuma, uzun anlatma.
            Hız, kalan yol, varış saati, sıradaki dönüş, sıradaki radar, hız sınırı ve koridor ortalaması gibi sorular için get_drive_status aracını çağır, tahmin yürütme.
            Güzergahtaki radarları ve hız koridorlarını sorarsa get_upcoming_hazards aracını kullan.
            Sürücü bir yere gitmek isterse ya da rotayı değiştirmek isterse navigate_to aracını hedefin adıyla çağır, sonucu kısaca bildir.
            Navigasyonu bitirmek isterse stop_navigation aracını çağır.
            Sürücü yolda seyyar radar, polis ya da çevirme gördüğünü söylerse ("burada radar var", "polis çevirmesi var") report_mobile_radar aracını çağır ve kısaca onayla; kayıt yalnızca bu telefonda tutulur, başka sürücülerle paylaşılmaz.
            Daha önce bildirdiği yerde radar olmadığını söylerse forget_mobile_radar_here aracını çağır.
            Hız yapmayı, radardan kaçmayı ya da ceza almamak için hile yapmayı asla önerme; kurallara uymasını iste.
            Sürüş dışındaki sohbetlere de katılabilirsin ama sürücünün dikkatini dağıtacak uzunlukta konuşma.
        """.trimIndent()
    }

    override suspend fun handle(name: String, args: JsonObject): JsonObject = withContext(Dispatchers.Main) {
        when (name) {
            "get_drive_status" -> driveStatus()
            "get_upcoming_hazards" -> upcoming(args["count"]?.jsonPrimitive?.intOrNull ?: 5)
            "navigate_to" -> navigateTo(args["destination"]?.jsonPrimitive?.content.orEmpty())
            "report_mobile_radar" -> {
                val r = graph.controller.reportMobileRadar(announce = false)
                buildJsonObject {
                    if (r == null) put("error", "Konum henüz alınamadı") else {
                        put("ok", true)
                        put("times_reported_here", r.count)
                        put("saved_reports_total", graph.userReports.count())
                        put("note", "Yalnızca bu telefonda saklandı; sonraki geçişlerde 2 km ve 500 m kala uyarılacak.")
                    }
                }
            }
            "forget_mobile_radar_here" -> {
                val n = graph.controller.forgetMobileRadarHere(announce = false)
                buildJsonObject { if (n == null) put("error", "Konum henüz alınamadı") else put("removed", n) }
            }
            "stop_navigation" -> {
                graph.controller.stopNavigation()
                buildJsonObject { put("ok", true) }
            }
            else -> buildJsonObject { put("error", "Bilinmeyen araç: $name") }
        }
    }

    private fun driveStatus(): JsonObject {
        val state = graph.controller.state.value
        val fix = graph.location.fixes.value
        return buildJsonObject {
            put("current_speed_kmh", (fix?.speedKmh ?: 0.0).roundToInt())
            when (state) {
                is NavState.Navigating -> {
                    put("navigating", true)
                    put("destination", state.plan.destination.name)
                    val p = state.progress
                    if (p != null) {
                        put("remaining_km", (p.remainingMeters / 100).roundToInt() / 10.0)
                        put("remaining_time", TurkishInstructions.duration(p.remainingSeconds))
                        put("arrival_time", SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(p.etaMillis)))
                        p.nextStep?.let {
                            put("next_maneuver", it.instruction)
                            put("next_maneuver_distance", TurkishInstructions.spokenDistance(p.distanceToStep))
                        }
                        p.currentLimit?.let { put("current_speed_limit_kmh", it) }
                        p.nextHazard?.let { h ->
                            putJsonObject("next_hazard") { describe(h, p.distanceToHazard ?: 0.0) }
                        }
                        p.corridor?.let { c ->
                            putJsonObject("inside_speed_corridor") {
                                put("name", c.hazard.hazard.name)
                                c.hazard.hazard.speedLimit?.let { put("limit_kmh", it) }
                                c.averageKmh?.let { put("your_average_kmh", it.roundToInt()) }
                                put("remaining_km", (c.remainingMeters / 100).roundToInt() / 10.0)
                            }
                        }
                    }
                    put("radar_count_on_route", state.plan.radarCount)
                    put("corridor_count_on_route", state.plan.corridorCount)
                }
                is NavState.Planned -> {
                    put("navigating", false)
                    put("planned_destination", state.plan.destination.name)
                    put("route_km", (state.plan.route.distanceMeters / 1000).roundToInt())
                    put("radar_count_on_route", state.plan.radarCount)
                }
                else -> put("navigating", false)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.describe(h: RouteHazard, distance: Double) {
        val r = h.hazard as? Radar
        val user = r?.source == com.radarnav.core.model.HazardSource.USER
        put("type", if (h.hazard is SpeedCorridor) "ortalama hız koridoru" else if (user) "seyyar radar (sürücünün kendi bildirimi)" else "radar")
        if (user) r?.reportedAt?.let { put("reported", com.radarnav.core.report.UserReportStore.ageText(it, System.currentTimeMillis())) }
        put("distance", TurkishInstructions.spokenDistance(distance.coerceAtLeast(0.0)))
        h.hazard.speedLimit?.let { put("speed_limit_kmh", it) }
        val where = listOf(h.hazard.province, h.hazard.district).filter { it.isNotBlank() }.joinToString(" / ")
        if (where.isNotBlank()) put("location", where)
        if (h.hazard.name.isNotBlank()) put("name", h.hazard.name)
        (h.hazard as? SpeedCorridor)?.lengthKm?.let { put("length_km", it) }
    }

    private fun upcoming(count: Int): JsonObject {
        val list = graph.controller.upcomingHazards()
        return buildJsonObject {
            put("total_remaining", list.size)
            put("radars", list.count { it.first.hazard is Radar })
            put("corridors", list.count { it.first.hazard is SpeedCorridor })
            putJsonArray("next") {
                list.take(count.coerceIn(1, 15)).forEach { (h, d) -> addJsonObject { describe(h, d) } }
            }
        }
    }

    private suspend fun navigateTo(destination: String): JsonObject {
        if (destination.isBlank()) return buildJsonObject { put("error", "Hedef belirtilmedi") }
        val places = runCatching { graph.controller.search(destination) }.getOrElse {
            return buildJsonObject { put("error", "Yer araması başarısız: ${it.message}") }
        }
        val place = places.firstOrNull() ?: return buildJsonObject { put("error", "'$destination' bulunamadı") }
        val plan = graph.controller.planNow(place)
            ?: return buildJsonObject {
                put("error", (graph.controller.state.value as? NavState.Failed)?.message ?: "Rota hesaplanamadı")
            }
        graph.controller.startNavigation()
        return buildJsonObject {
            put("ok", true)
            put("destination", place.address.ifBlank { place.name })
            put("distance_km", (plan.route.distanceMeters / 1000).roundToInt())
            put("duration", TurkishInstructions.duration(plan.route.durationSeconds))
            put("radar_count", plan.radarCount)
            put("corridor_count", plan.corridorCount)
            plan.note?.let { put("note", it) }
        }
    }

    companion object {
        val DECLARATIONS = listOf(
            ToolDeclaration(
                "get_drive_status",
                "Anlık sürüş durumu: hız, hedef, kalan mesafe ve süre, varış saati, sıradaki dönüş, " +
                    "sıradaki radar/koridor ve geçerli hız sınırı.",
            ),
            ToolDeclaration(
                "get_upcoming_hazards",
                "Güzergahta kalan radarlar ve ortalama hız koridorları (yakından uzağa).",
                mapOf("count" to ("INTEGER" to "Kaç tane listelensin (varsayılan 5)")),
            ),
            ToolDeclaration(
                "navigate_to",
                "Verilen hedefe yeni rota oluşturur ve navigasyonu hemen başlatır.",
                mapOf("destination" to ("STRING" to "Hedef adı ya da adresi, ör. 'Bolu', 'Kızılay Ankara'")),
                listOf("destination"),
            ),
            ToolDeclaration("stop_navigation", "Devam eden navigasyonu bitirir."),
            ToolDeclaration(
                "report_mobile_radar",
                "Sürücünün şu an bulunduğu yerde seyyar radar / polis çevirmesi gördüğünü kaydeder (yalnızca telefonda).",
            ),
            ToolDeclaration(
                "forget_mobile_radar_here",
                "Sürücünün bulunduğu yerin ~300 m çevresindeki kendi seyyar radar bildirimlerini siler.",
            ),
        )
    }
}
