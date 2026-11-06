package com.radarnav.data

import android.content.Context
import androidx.preference.PreferenceManager
import com.radarnav.core.alert.Persona
import com.radarnav.core.gemini.GeminiTextClient
import com.radarnav.core.gemini.LiveConfig
import com.radarnav.core.routing.GoogleRouting
import com.radarnav.core.routing.GraphHopperRouting
import com.radarnav.core.routing.OsrmRouting
import com.radarnav.core.routing.RoutingProvider
import com.radarnav.core.geo.Geo
import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** Ayarlar ekranındaki değerlere erişim. */
class Settings(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private fun str(key: String, default: String = ""): String =
        prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() } ?: default

    private fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)

    val geminiApiKey: String? get() = str("gemini_api_key").takeIf { it.isNotEmpty() }
    val persona: Persona get() = Persona.fromKey(str("persona", "sert"))
    val geminiVoice: String get() = str("gemini_voice", "Puck")
    val geminiLiveModel: String get() = str("gemini_live_model", LiveConfig.DEFAULT_LIVE_MODEL)
    val geminiTextModel: String get() = str("gemini_text_model", GeminiTextClient.DEFAULT_MODEL)
    val geminiWarnings: Boolean get() = bool("gemini_warnings", true)

    val routingProviderKey: String get() = str("routing_provider", "osrm")
    val osrmUrl: String get() = str("osrm_url", OsrmRouting.DEFAULT_URL)
    val graphHopperKey: String? get() = str("graphhopper_key").takeIf { it.isNotEmpty() }
    val googleKey: String? get() = str("google_key").takeIf { it.isNotEmpty() }

    val radarAlerts: Boolean get() = bool("radar_alerts", true)
    val voiceGuidance: Boolean get() = bool("voice_guidance", true)

    /**
     * Harita karoları (telefon + Android Auto). Varsayılan: anahtarsız standart OSM.
     * Eski sürümün kaydettiği CARTO adresi (artık API anahtarı istiyor) otomatik olarak değiştirilir.
     */
    val tileUrl: String get() {
        val v = str("tile_url", DEFAULT_TILE_URL)
        return if (v.contains("basemaps.cartocdn.com")) DEFAULT_TILE_URL else v
    }

    /** Koyu harita: karolar cihazda gece görünümüne çevrilir (anahtar gerekmez). */
    val darkMap: Boolean get() = bool("dark_map", true)

    companion object {
        const val DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    }

    val simulate: Boolean get() = bool("simulate", false)
    val simSpeedKmh: Double get() = str("sim_speed", "100").replace(',', '.').toDoubleOrNull()?.coerceIn(10.0, 250.0) ?: 100.0

    fun setSimulate(value: Boolean) = prefs.edit().putBoolean("simulate", value).apply()

    /** Son gidilen hedefler (en yeni başta, en fazla 6). */
    fun recentPlaces(): List<Place> = runCatching {
        val arr = Json.parseToJsonElement(prefs.getString("recent_places", "[]") ?: "[]").jsonArray
        arr.mapNotNull { e ->
            val o = e.jsonObject
            val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Place(o["name"]?.jsonPrimitive?.content.orEmpty(), o["address"]?.jsonPrimitive?.content.orEmpty(), LatLng(lat, lon))
        }
    }.getOrDefault(emptyList())

    fun addRecentPlace(place: Place) {
        val list = (listOf(place) + recentPlaces().filterNot {
            it.name == place.name && Geo.distance(it.location, place.location) < 300
        }).take(6)
        val json = buildJsonArray {
            list.forEach { p ->
                add(buildJsonObject {
                    put("name", p.name)
                    put("address", p.address)
                    put("lat", p.location.lat)
                    put("lon", p.location.lon)
                })
            }
        }
        prefs.edit().putString("recent_places", json.toString()).apply()
    }

    /**
     * Seçili rota servisi. Anahtar gereken servis seçilip anahtar girilmemişse OSRM kullanılır;
     * ikinci değer kullanıcıya gösterilecek nottur.
     */
    fun routingProvider(http: OkHttpClient): Pair<RoutingProvider, String?> = when (routingProviderKey) {
        "graphhopper" -> graphHopperKey?.let { GraphHopperRouting(http, it) to null }
            ?: (OsrmRouting(http, osrmUrl) to "GraphHopper anahtarı girilmemiş, OSRM kullanıldı")
        "google" -> googleKey?.let { GoogleRouting(http, it) to null }
            ?: (OsrmRouting(http, osrmUrl) to "Google anahtarı girilmemiş, OSRM kullanıldı")
        else -> OsrmRouting(http, osrmUrl) to null
    }
}
