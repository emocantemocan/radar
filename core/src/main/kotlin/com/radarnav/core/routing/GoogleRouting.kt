package com.radarnav.core.routing

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.Polyline
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.RouteStep
import com.radarnav.core.net.arr
import com.radarnav.core.net.dbl
import com.radarnav.core.net.fetchJson
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Google Directions API (Google Maps Platform anahtarı gerekir, ücretli olabilir). */
class GoogleRouting(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://maps.googleapis.com/maps/api/directions/json",
) : RoutingProvider {
    override val name = "Google"

    override suspend fun route(from: LatLng, to: LatLng): NavRoute {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("origin", from.latLonStr())
            .addQueryParameter("destination", to.latLonStr())
            .addQueryParameter("mode", "driving")
            .addQueryParameter("language", "tr")
            .addQueryParameter("region", "tr")
            .addQueryParameter("key", apiKey)
            .build()
        val json = try {
            client.fetchJson(Request.Builder().url(url).get().build())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw RoutingException("Google rota servisine ulaşılamadı: ${e.message}", e)
        }
        return parse(json)
    }

    companion object {
        fun parse(json: JsonElement): NavRoute {
            val root = json.obj() ?: throw RoutingException("Google: geçersiz yanıt")
            val status = root.str("status")
            if (status != "OK") {
                val msg = root.str("error_message") ?: status
                throw RoutingException(
                    if (status == "REQUEST_DENIED") "Google API anahtarı reddedildi: $msg" else "Google rota bulamadı: $msg",
                )
            }
            val route = root["routes"].arr()?.firstOrNull().obj() ?: throw RoutingException("Google: rota yok")
            val points = ArrayList<LatLng>()
            val steps = ArrayList<RouteStep>()
            var distance = 0.0
            var duration = 0.0
            for (legEl in route["legs"].arr().orEmpty()) {
                val leg = legEl.obj() ?: continue
                distance += leg["distance"].obj()?.dbl("value") ?: 0.0
                duration += leg["duration"].obj()?.dbl("value") ?: 0.0
                for (stepEl in leg["steps"].arr().orEmpty()) {
                    val s = stepEl.obj() ?: continue
                    val poly = Polyline.decode(s["polyline"].obj()?.str("points") ?: "", 5)
                    if (points.isNotEmpty() && poly.isNotEmpty() && points.last() == poly.first()) {
                        points.addAll(poly.drop(1))
                    } else {
                        points.addAll(poly)
                    }
                    val start = s["start_location"].obj()?.latLng() ?: poly.firstOrNull() ?: continue
                    val kind = if (steps.isEmpty()) ManeuverKind.DEPART else kindOf(s.str("maneuver"))
                    steps.add(
                        RouteStep(
                            instruction = cleanHtml(s.str("html_instructions") ?: TurkishInstructions.build(kind, null)),
                            kind = kind,
                            location = start,
                            distanceMeters = s["distance"].obj()?.dbl("value") ?: 0.0,
                            durationSeconds = s["duration"].obj()?.dbl("value") ?: 0.0,
                        ),
                    )
                }
            }
            if (points.size < 2) {
                points.clear()
                points.addAll(Polyline.decode(route["overview_polyline"].obj()?.str("points") ?: "", 5))
            }
            if (points.size < 2) throw RoutingException("Google: rota geometrisi boş")
            steps.add(RouteStep(TurkishInstructions.build(ManeuverKind.ARRIVE, null), ManeuverKind.ARRIVE, points.last()))
            return NavRoute(points, distance, duration, steps, "Google")
        }

        private fun JsonObject.latLng(): LatLng? {
            val lat = dbl("lat") ?: return null
            val lng = dbl("lng") ?: return null
            return LatLng(lat, lng)
        }

        fun cleanHtml(html: String): String = html
            .replace(Regex("<div[^>]*>"), ". ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .replace(" .", ".")
            .trim()

        fun kindOf(maneuver: String?): ManeuverKind = when (maneuver) {
            "turn-slight-left" -> ManeuverKind.SLIGHT_LEFT
            "turn-sharp-left" -> ManeuverKind.SHARP_LEFT
            "turn-left" -> ManeuverKind.LEFT
            "turn-slight-right" -> ManeuverKind.SLIGHT_RIGHT
            "turn-sharp-right" -> ManeuverKind.SHARP_RIGHT
            "turn-right" -> ManeuverKind.RIGHT
            "uturn-left", "uturn-right" -> ManeuverKind.UTURN
            "keep-left" -> ManeuverKind.KEEP_LEFT
            "keep-right" -> ManeuverKind.KEEP_RIGHT
            "ramp-left", "ramp-right" -> ManeuverKind.ON_RAMP
            "fork-left" -> ManeuverKind.FORK_LEFT
            "fork-right" -> ManeuverKind.FORK_RIGHT
            "merge" -> ManeuverKind.MERGE
            "roundabout-left", "roundabout-right" -> ManeuverKind.ROUNDABOUT
            else -> ManeuverKind.STRAIGHT
        }
    }
}
