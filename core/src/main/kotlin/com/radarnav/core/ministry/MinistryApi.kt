package com.radarnav.core.ministry

import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.Radar
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.net.anyCase
import com.radarnav.core.net.arr
import com.radarnav.core.net.bool
import com.radarnav.core.net.dbl
import com.radarnav.core.net.fetchJson
import com.radarnav.core.net.int
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

data class MinistryCity(val id: Int, val name: String)

data class MinistryDistrict(
    val id: Int,
    val name: String,
    val cityId: Int,
    val cityName: String,
    val location: LatLng,
)

data class MinistryRouteData(
    val fromDistrict: String?,
    val toDistrict: String?,
    val radarCount: Int,
    val controlPointCount: Int,
    val corridorCount: Int,
    val radars: List<Radar>,
    val corridors: List<SpeedCorridor>,
    val coordinates: List<LatLng>,
)

/**
 * T.C. İçişleri Bakanlığı "İller Arası Radar ve Kontrol Noktası Uygulama Sayıları"
 * sayfasının kullandığı herkese açık uç noktalar.
 */
class MinistryApi(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://www.icisleri.gov.tr",
) {
    private val apiRoot get() = "$baseUrl/ISAYWebPart/PolGenControlPointV2"

    private fun Request.Builder.ministryHeaders() = apply {
        header("User-Agent", "Mozilla/5.0 (Linux; Android 14) RadarNav")
        header("Referer", "$baseUrl/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari")
        header("Origin", baseUrl)
        header("X-Requested-With", "XMLHttpRequest")
        header("Accept", "application/json, text/javascript, */*; q=0.01")
    }

    suspend fun cities(): List<MinistryCity> {
        val json = client.fetchJson(Request.Builder().url("$apiRoot/GetCities").ministryHeaders().get().build())
        return listOf(json).unwrapList().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o.anyCase("Id").asInt() ?: return@mapNotNull null
            MinistryCity(id, o.anyCase("Name").asStr() ?: return@mapNotNull null)
        }
    }

    suspend fun districts(city: MinistryCity): List<MinistryDistrict> {
        val json = client.fetchJson(
            Request.Builder().url("$apiRoot/GetDistricts?cityId=${city.id}").ministryHeaders().get().build(),
        )
        return listOf(json).unwrapList().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o.anyCase("Id").asInt() ?: return@mapNotNull null
            val lat = o.anyCase("Latitude").asDouble() ?: return@mapNotNull null
            val lon = o.anyCase("Longitude").asDouble() ?: return@mapNotNull null
            MinistryDistrict(id, o.anyCase("Name").asStr() ?: "", city.id, city.name, LatLng(lat, lon))
        }
    }

    suspend fun createRoute(from: MinistryDistrict, to: MinistryDistrict): MinistryRouteData {
        val form = FormBody.Builder()
            .add("fromLatitude", from.location.lat.fmt())
            .add("fromLongitude", from.location.lon.fmt())
            .add("toLatitude", to.location.lat.fmt())
            .add("toLongitude", to.location.lon.fmt())
            .add("fromDistrictId", from.id.toString())
            .add("toDistrictId", to.id.toString())
            .build()
        val json = client.fetchJson(
            Request.Builder().url("$apiRoot/CreateRoute").ministryHeaders().post(form).build(),
        )
        return parseRoute(json)
    }

    companion object {
        private fun Double.fmt() = String.format(Locale.US, "%.6f", this)

        /** Hem düz dizi hem de {"data": [...]} biçimini kabul eder. */
        private fun List<JsonElement>.unwrapList(): List<JsonElement> {
            val e = first()
            e.arr()?.let { return it }
            val o = e.obj() ?: return emptyList()
            return (o.anyCase("data") ?: o.anyCase("Data")).arr() ?: emptyList()
        }

        private fun JsonElement?.asStr(): String? =
            (this as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content

        private fun JsonElement?.asInt(): Int? = asStr()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        private fun JsonElement?.asDouble(): Double? = asStr()?.replace(',', '.')?.toDoubleOrNull()

        /** Aktif kabul edilen kayıtlar: activity == 3 (ya da alan hiç yoksa). */
        private fun JsonObject.isActive(): Boolean {
            val activity = int("activity") ?: return true
            return activity == 3
        }

        fun parseRoute(json: JsonElement): MinistryRouteData {
            val root = json.obj() ?: throw IOException("Beklenmeyen bakanlık yanıtı")
            if (root.bool("success") == false) {
                throw IOException("Bakanlık servisi hata döndü: ${root.str("message").orEmpty()}")
            }
            val data = root.anyCase("data").obj() ?: root
            val radars = data.anyCase("Radars").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                if (!o.isActive()) return@mapNotNull null
                val lon = o.dbl("x") ?: return@mapNotNull null
                val lat = o.dbl("y") ?: return@mapNotNull null
                Radar(
                    id = "r:" + (o.str("id") ?: "${lat}_$lon"),
                    name = o.str("name")?.trim().orEmpty(),
                    province = o.str("provinceName")?.trim().orEmpty(),
                    district = o.str("districtName")?.trim().orEmpty(),
                    speedLimit = o.int("speedLimit")?.takeIf { it > 0 },
                    location = LatLng(lat, lon),
                )
            }
            val corridors = data.anyCase("SpeedTunnels").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                if (!o.isActive()) return@mapNotNull null
                val path = o.anyCase("coordinates").arr().orEmpty().mapNotNull { c ->
                    val co = c.obj() ?: return@mapNotNull null
                    val x = co.dbl("x") ?: return@mapNotNull null
                    val y = co.dbl("y") ?: return@mapNotNull null
                    LatLng(y, x)
                }
                val start = o.dbl("startLatY")?.let { lat -> o.dbl("startLonX")?.let { LatLng(lat, it) } }
                    ?: path.firstOrNull() ?: return@mapNotNull null
                val end = o.dbl("endLatY")?.let { lat -> o.dbl("endLonX")?.let { LatLng(lat, it) } }
                    ?: path.lastOrNull() ?: return@mapNotNull null
                SpeedCorridor(
                    id = "c:" + (o.str("id") ?: "${start}_$end"),
                    name = o.str("name")?.trim().orEmpty(),
                    province = o.str("provinceName")?.trim().orEmpty(),
                    district = o.str("districtName")?.trim().orEmpty(),
                    speedLimit = o.int("speedLimit")?.takeIf { it > 0 },
                    lengthKm = o.dbl("length"),
                    start = start,
                    end = end,
                    path = path.ifEmpty { listOf(start, end) },
                )
            }
            val coords = data.anyCase("Coordinates").arr().orEmpty().mapNotNull { c ->
                val co = c.obj() ?: return@mapNotNull null
                val x = co.dbl("x") ?: return@mapNotNull null
                val y = co.dbl("y") ?: return@mapNotNull null
                LatLng(y, x)
            }
            return MinistryRouteData(
                fromDistrict = data.str("FromDistrict"),
                toDistrict = data.str("ToDistrict"),
                radarCount = data.int("RadarCount") ?: radars.size,
                controlPointCount = data.int("ControlPointCount") ?: 0,
                corridorCount = data.int("CorridorCount") ?: corridors.size,
                radars = radars,
                corridors = corridors,
                coordinates = coords,
            )
        }
    }
}
