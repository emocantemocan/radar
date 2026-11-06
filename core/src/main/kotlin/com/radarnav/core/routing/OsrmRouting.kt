package com.radarnav.core.routing

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.Polyline
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.RouteStep
import com.radarnav.core.net.arr
import com.radarnav.core.net.dbl
import com.radarnav.core.net.fetchJson
import com.radarnav.core.net.int
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** OSRM (varsayılan: herkese açık router.project-osrm.org, anahtarsız). */
class OsrmRouting(
    private val client: OkHttpClient,
    baseUrl: String = DEFAULT_URL,
) : RoutingProvider {
    private val base = baseUrl.trimEnd('/')
    override val name = "OSRM"

    override suspend fun route(from: LatLng, to: LatLng): NavRoute {
        val url = "$base/route/v1/driving/${from.lonLatStr()};${to.lonLatStr()}" +
            "?overview=full&geometries=polyline6&steps=true&alternatives=false"
        val json = try {
            client.fetchJson(Request.Builder().url(url).get().build())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw RoutingException("OSRM rota servisine ulaşılamadı: ${e.message}", e)
        }
        return parse(json)
    }

    companion object {
        const val DEFAULT_URL = "https://router.project-osrm.org"

        fun parse(json: JsonElement): NavRoute {
            val root = json.obj() ?: throw RoutingException("OSRM: geçersiz yanıt")
            if (root.str("code") != "Ok") {
                throw RoutingException("OSRM rota bulamadı: ${root.str("message") ?: root.str("code")}")
            }
            val route = root["routes"].arr()?.firstOrNull().obj() ?: throw RoutingException("OSRM: rota yok")
            val points = Polyline.decode(route.str("geometry") ?: "", 6)
            if (points.size < 2) throw RoutingException("OSRM: rota geometrisi boş")
            val steps = route["legs"].arr().orEmpty().flatMap { leg ->
                leg.obj()?.get("steps").arr().orEmpty().mapNotNull { parseStep(it.obj()) }
            }
            return NavRoute(
                points = points,
                distanceMeters = route.dbl("distance") ?: 0.0,
                durationSeconds = route.dbl("duration") ?: 0.0,
                steps = steps,
                provider = "OSRM",
            )
        }

        private fun parseStep(step: JsonObject?): RouteStep? {
            step ?: return null
            val m = step["maneuver"].obj() ?: return null
            val loc = m["location"].arr() ?: return null
            if (loc.size < 2) return null
            val lon = loc[0].jsonPrimitive.doubleOrNull ?: return null
            val lat = loc[1].jsonPrimitive.doubleOrNull ?: return null
            val type = m.str("type").orEmpty()
            val modifier = m.str("modifier").orEmpty()
            val exit = m.int("exit")
            val kind = kindOf(type, modifier)
            val road = step.str("name")?.takeIf { it.isNotBlank() }
                ?: step.str("ref")?.takeIf { it.isNotBlank() }
                ?: step.str("destinations")?.takeIf { it.isNotBlank() }
            return RouteStep(
                instruction = TurkishInstructions.build(kind, road, exit),
                kind = kind,
                location = LatLng(lat, lon),
                roadName = road,
                roundaboutExit = exit,
                distanceMeters = step.dbl("distance") ?: 0.0,
                durationSeconds = step.dbl("duration") ?: 0.0,
            )
        }

        fun kindOf(type: String, modifier: String): ManeuverKind {
            fun byModifier(left: ManeuverKind, right: ManeuverKind, default: ManeuverKind = ManeuverKind.STRAIGHT) =
                when {
                    modifier.contains("left") -> left
                    modifier.contains("right") -> right
                    else -> default
                }
            return when (type) {
                "depart" -> ManeuverKind.DEPART
                "arrive" -> ManeuverKind.ARRIVE
                "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> ManeuverKind.ROUNDABOUT
                "merge" -> ManeuverKind.MERGE
                "on ramp" -> ManeuverKind.ON_RAMP
                "off ramp" -> ManeuverKind.OFF_RAMP
                "fork" -> byModifier(ManeuverKind.FORK_LEFT, ManeuverKind.FORK_RIGHT)
                else -> when (modifier) {
                    "uturn" -> ManeuverKind.UTURN
                    "sharp left" -> ManeuverKind.SHARP_LEFT
                    "left" -> ManeuverKind.LEFT
                    "slight left" -> if (type == "continue" || type == "new name") ManeuverKind.KEEP_LEFT else ManeuverKind.SLIGHT_LEFT
                    "sharp right" -> ManeuverKind.SHARP_RIGHT
                    "right" -> ManeuverKind.RIGHT
                    "slight right" -> if (type == "continue" || type == "new name") ManeuverKind.KEEP_RIGHT else ManeuverKind.SLIGHT_RIGHT
                    else -> ManeuverKind.STRAIGHT
                }
            }
        }
    }
}
