package com.radarnav.core.routing

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.Polyline
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.RouteStep
import com.radarnav.core.net.HttpException
import com.radarnav.core.net.arr
import com.radarnav.core.net.dbl
import com.radarnav.core.net.fetchJson
import com.radarnav.core.net.int
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.log10
import kotlin.math.roundToInt

/** GraphHopper Directions API (ücretsiz anahtar: günde 500 rota). */
class GraphHopperRouting(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://graphhopper.com/api/1",
) : RoutingProvider {
    override val name = "GraphHopper"

    override suspend fun route(from: LatLng, to: LatLng): NavRoute {
        val url = "$baseUrl/route".toHttpUrl().newBuilder()
            .addQueryParameter("point", from.latLonStr())
            .addQueryParameter("point", to.latLonStr())
            .addQueryParameter("profile", "car")
            .addQueryParameter("locale", "tr")
            .addQueryParameter("instructions", "true")
            .addQueryParameter("points_encoded", "true")
            .addQueryParameter("key", apiKey)
            .build()
        val json = try {
            client.fetchJson(Request.Builder().url(url).get().build())
        } catch (e: HttpException) {
            throw RoutingException(
                if (e.code == 401) "GraphHopper API anahtarı geçersiz" else "GraphHopper hatası: ${e.message}", e,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw RoutingException("GraphHopper servisine ulaşılamadı: ${e.message}", e)
        }
        return parse(json)
    }

    companion object {
        fun parse(json: JsonElement): NavRoute {
            val root = json.obj() ?: throw RoutingException("GraphHopper: geçersiz yanıt")
            val path = root["paths"].arr()?.firstOrNull().obj()
                ?: throw RoutingException("GraphHopper rota bulamadı: ${root.str("message").orEmpty()}")
            val multiplier = path.dbl("points_encoded_multiplier") ?: 1e5
            val precision = log10(multiplier).roundToInt()
            val points = Polyline.decode(path.str("points") ?: "", precision)
            if (points.size < 2) throw RoutingException("GraphHopper: rota geometrisi boş")
            val steps = path["instructions"].arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val interval = o["interval"].arr()
                val idx = interval?.firstOrNull()?.jsonPrimitive?.intOrNull ?: 0
                val kind = kindOfSign(o.int("sign") ?: 0)
                val street = o.str("street_name")?.takeIf { it.isNotBlank() }
                val exit = o.int("exit_number")
                RouteStep(
                    instruction = o.str("text")?.takeIf { it.isNotBlank() }
                        ?: TurkishInstructions.build(kind, street, exit),
                    kind = kind,
                    location = points[idx.coerceIn(0, points.size - 1)],
                    roadName = street,
                    roundaboutExit = exit,
                    distanceMeters = o.dbl("distance") ?: 0.0,
                    durationSeconds = (o.dbl("time") ?: 0.0) / 1000.0,
                )
            }.mapIndexed { i, s -> if (i == 0 && s.kind == ManeuverKind.STRAIGHT) s.copy(kind = ManeuverKind.DEPART) else s }
            return NavRoute(
                points = points,
                distanceMeters = path.dbl("distance") ?: 0.0,
                durationSeconds = (path.dbl("time") ?: 0.0) / 1000.0,
                steps = steps,
                provider = "GraphHopper",
            )
        }

        fun kindOfSign(sign: Int): ManeuverKind = when (sign) {
            -98, -8, 8 -> ManeuverKind.UTURN
            -7 -> ManeuverKind.KEEP_LEFT
            7 -> ManeuverKind.KEEP_RIGHT
            -3 -> ManeuverKind.SHARP_LEFT
            -2 -> ManeuverKind.LEFT
            -1 -> ManeuverKind.SLIGHT_LEFT
            1 -> ManeuverKind.SLIGHT_RIGHT
            2 -> ManeuverKind.RIGHT
            3 -> ManeuverKind.SHARP_RIGHT
            4, 5 -> ManeuverKind.ARRIVE
            6 -> ManeuverKind.ROUNDABOUT
            else -> ManeuverKind.STRAIGHT
        }
    }
}
