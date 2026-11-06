package com.radarnav.core.place

import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.Place
import com.radarnav.core.net.arr
import com.radarnav.core.net.dbl
import com.radarnav.core.net.fetchJson
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** Adres/yer arama: OpenStreetMap Nominatim (anahtarsız, Türkiye ile sınırlı). */
class PlaceSearch(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
) {
    suspend fun search(query: String, near: LatLng? = null, limit: Int = 8): List<Place> {
        val b = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("countrycodes", "tr")
            .addQueryParameter("accept-language", "tr")
            .addQueryParameter("limit", limit.toString())
        if (near != null) {
            // Yakındaki sonuçları öne çıkar (sınırlamadan)
            val d = 1.5
            b.addQueryParameter(
                "viewbox",
                String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", near.lon - d, near.lat + d, near.lon + d, near.lat - d),
            )
        }
        return parse(client.fetchJson(Request.Builder().url(b.build()).get().build()))
    }

    companion object {
        fun parse(json: JsonElement): List<Place> = json.arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val lat = o.dbl("lat") ?: return@mapNotNull null
            val lon = o.dbl("lon") ?: return@mapNotNull null
            val display = o.str("display_name").orEmpty()
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: display.substringBefore(',')
            Place(name = name, address = display, location = LatLng(lat, lon))
        }
    }
}
