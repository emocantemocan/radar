package com.radarnav.core.routing

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.Polyline
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.RouteStep
import com.radarnav.core.net.HttpException
import com.radarnav.core.net.LenientJson
import com.radarnav.core.net.arr
import com.radarnav.core.net.dbl
import com.radarnav.core.net.fetchJson
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Routes API (`directions/v2:computeRoutes`). Eski Directions API yeni projelerde
 * açılamıyor ("legacy API"), bu yüzden Routes API kullanılır. Anahtarın Google Cloud'da
 * "Routes API" için etkin olması gerekir.
 */
class GoogleRouting(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://routes.googleapis.com/directions/v2:computeRoutes",
) : RoutingProvider {
    override val name = "Google"

    override suspend fun route(from: LatLng, to: LatLng): NavRoute {
        val body = buildJsonObject {
            putJsonObject("origin") { putLocation(from) }
            putJsonObject("destination") { putLocation(to) }
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE")
            put("polylineQuality", "HIGH_QUALITY")
            put("languageCode", "tr")
            put("regionCode", "TR")
            put("units", "METRIC")
        }.toString()
        val request = Request.Builder().url(baseUrl)
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", FIELD_MASK)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val json = try {
            client.fetchJson(request)
        } catch (e: HttpException) {
            throw RoutingException(errorText(e.code, e.message.orEmpty()), e)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw RoutingException("Google rota servisine ulaşılamadı", e)
        }
        return parse(json)
    }

    private fun JsonObjectBuilder.putLocation(p: LatLng) {
        putJsonObject("location") {
            putJsonObject("latLng") {
                put("latitude", p.lat)
                put("longitude", p.lon)
            }
        }
    }

    companion object {
        const val FIELD_MASK = "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline," +
            "routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration," +
            "routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.startLocation," +
            "routes.legs.steps.navigationInstruction"

        /** Hata yanıtını kısa, ne yapılacağını söyleyen bir nota çevirir (ham Google metni çok uzun). */
        fun errorText(code: Int, raw: String): String {
            val status = runCatching {
                val body = raw.substring(raw.indexOf('{').coerceAtLeast(0))
                LenientJson.parseToJsonElement(body).obj()?.get("error").obj()?.str("status")
            }.getOrNull()
            return when {
                status == "PERMISSION_DENIED" || code == 403 ->
                    "Google anahtarında Routes API açık değil (Google Cloud → Routes API → Etkinleştir)"
                code == 400 && raw.contains("API key", ignoreCase = true) -> "Google API anahtarı geçersiz"
                code == 429 -> "Google kotası doldu"
                else -> "Google rota hatası ($code)"
            }
        }

        fun parse(json: JsonElement): NavRoute {
            val root = json.obj() ?: throw RoutingException("Google: geçersiz yanıt")
            root["error"].obj()?.let { e -> throw RoutingException(errorText(e.dbl("code")?.toInt() ?: 0, root.toString())) }
            val route = root["routes"].arr()?.firstOrNull().obj() ?: throw RoutingException("Google rota bulamadı")
            val points = ArrayList<LatLng>()
            val steps = ArrayList<RouteStep>()
            for (legEl in route["legs"].arr().orEmpty()) {
                val leg = legEl.obj() ?: continue
                for (stepEl in leg["steps"].arr().orEmpty()) {
                    val s = stepEl.obj() ?: continue
                    val poly = Polyline.decode(s["polyline"].obj()?.str("encodedPolyline") ?: "", 5)
                    if (points.isNotEmpty() && poly.isNotEmpty() && points.last() == poly.first()) {
                        points.addAll(poly.drop(1))
                    } else {
                        points.addAll(poly)
                    }
                    val start = s["startLocation"].obj()?.get("latLng").obj()?.latLng() ?: poly.firstOrNull() ?: continue
                    val nav = s["navigationInstruction"].obj()
                    val kind = if (steps.isEmpty()) ManeuverKind.DEPART else kindOf(nav?.str("maneuver"))
                    val text = nav?.str("instructions")?.replace(Regex("\\s*\\n\\s*"), ". ")?.trim()
                    steps.add(
                        RouteStep(
                            instruction = text?.takeIf { it.isNotEmpty() } ?: TurkishInstructions.build(kind, null),
                            kind = kind,
                            location = start,
                            distanceMeters = s.dbl("distanceMeters") ?: 0.0,
                            durationSeconds = seconds(s.str("staticDuration")),
                        ),
                    )
                }
            }
            if (points.size < 2) {
                points.clear()
                points.addAll(Polyline.decode(route["polyline"].obj()?.str("encodedPolyline") ?: "", 5))
            }
            if (points.size < 2) throw RoutingException("Google: rota geometrisi boş")
            steps.add(RouteStep(TurkishInstructions.build(ManeuverKind.ARRIVE, null), ManeuverKind.ARRIVE, points.last()))
            val distance = route.dbl("distanceMeters") ?: steps.sumOf { it.distanceMeters }
            val duration = route.str("duration")?.let { seconds(it) } ?: steps.sumOf { it.durationSeconds }
            return NavRoute(points, distance, duration, steps, "Google")
        }

        /** "123s" / "12.5s" → saniye. */
        fun seconds(d: String?): Double = d?.removeSuffix("s")?.toDoubleOrNull() ?: 0.0

        private fun JsonObject.latLng(): LatLng? {
            val lat = dbl("latitude") ?: return null
            val lng = dbl("longitude") ?: return null
            return LatLng(lat, lng)
        }

        fun kindOf(maneuver: String?): ManeuverKind = when (maneuver) {
            "TURN_SLIGHT_LEFT" -> ManeuverKind.SLIGHT_LEFT
            "TURN_SHARP_LEFT" -> ManeuverKind.SHARP_LEFT
            "TURN_LEFT" -> ManeuverKind.LEFT
            "TURN_SLIGHT_RIGHT" -> ManeuverKind.SLIGHT_RIGHT
            "TURN_SHARP_RIGHT" -> ManeuverKind.SHARP_RIGHT
            "TURN_RIGHT" -> ManeuverKind.RIGHT
            "UTURN_LEFT", "UTURN_RIGHT" -> ManeuverKind.UTURN
            "RAMP_LEFT", "RAMP_RIGHT" -> ManeuverKind.ON_RAMP
            "FORK_LEFT" -> ManeuverKind.FORK_LEFT
            "FORK_RIGHT" -> ManeuverKind.FORK_RIGHT
            "MERGE" -> ManeuverKind.MERGE
            "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> ManeuverKind.ROUNDABOUT
            else -> ManeuverKind.STRAIGHT
        }
    }
}
