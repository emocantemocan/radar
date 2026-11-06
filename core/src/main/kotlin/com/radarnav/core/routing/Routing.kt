package com.radarnav.core.routing

import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.NavRoute
import java.io.IOException
import java.util.Locale

class RoutingException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface RoutingProvider {
    val name: String
    suspend fun route(from: LatLng, to: LatLng): NavRoute
}

internal fun LatLng.latLonStr() = String.format(Locale.US, "%.6f,%.6f", lat, lon)
internal fun LatLng.lonLatStr() = String.format(Locale.US, "%.6f,%.6f", lon, lat)

/** Manevra türünden Türkçe yönlendirme cümlesi üretir (OSRM metin vermediği için). */
object TurkishInstructions {
    val TR: Locale = Locale.forLanguageTag("tr-TR")

    fun build(kind: ManeuverKind, road: String?, exit: Int? = null): String {
        val base = when (kind) {
            ManeuverKind.DEPART -> "Yola çıkın"
            ManeuverKind.STRAIGHT -> "Düz devam edin"
            ManeuverKind.SLIGHT_LEFT -> "Hafif sola dönün"
            ManeuverKind.LEFT -> "Sola dönün"
            ManeuverKind.SHARP_LEFT -> "Keskin sola dönün"
            ManeuverKind.SLIGHT_RIGHT -> "Hafif sağa dönün"
            ManeuverKind.RIGHT -> "Sağa dönün"
            ManeuverKind.SHARP_RIGHT -> "Keskin sağa dönün"
            ManeuverKind.UTURN -> "U dönüşü yapın"
            ManeuverKind.KEEP_LEFT -> "Soldan devam edin"
            ManeuverKind.KEEP_RIGHT -> "Sağdan devam edin"
            ManeuverKind.MERGE -> "Yola katılın"
            ManeuverKind.ON_RAMP -> "Bağlantı yoluna girin"
            ManeuverKind.OFF_RAMP -> "Bağlantı yolundan çıkın"
            ManeuverKind.FORK_LEFT -> "Yol ayrımında soldan gidin"
            ManeuverKind.FORK_RIGHT -> "Yol ayrımında sağdan gidin"
            ManeuverKind.ROUNDABOUT ->
                if (exit != null && exit > 0) "Dönel kavşaktan $exit. çıkıştan çıkın" else "Dönel kavşağa girin"
            ManeuverKind.ARRIVE -> "Varış noktanıza ulaştınız"
        }
        val r = road?.trim().orEmpty()
        return if (r.isNotEmpty() && kind != ManeuverKind.ARRIVE) "$base, $r" else base
    }

    /** "Sağa dönün" -> "300 metre sonra sağa dönün" gibi cümleler için. */
    fun lowerFirst(text: String): String =
        text.replaceFirstChar { if (it.isUpperCase()) it.lowercase(TR) else it.toString() }

    /** Konuşma için mesafe: "300 metre", "1,5 kilometre", "12 kilometre". */
    fun spokenDistance(meters: Double): String = when {
        meters < 100 -> "${(meters / 10).toInt().coerceAtLeast(1) * 10} metre"
        meters < 1000 -> "${((meters + 25) / 50).toInt() * 50} metre"
        meters < 10_000 -> {
            val km = Math.round(meters / 100.0) / 10.0
            if (km == Math.floor(km)) "${km.toInt()} kilometre" else "${km.toString().replace('.', ',')} kilometre"
        }
        else -> "${Math.round(meters / 1000.0)} kilometre"
    }

    /** Ekran için kısa mesafe: "300 m", "1,5 km". */
    fun shortDistance(meters: Double): String = when {
        meters < 1000 -> "${((meters + 5) / 10).toInt() * 10} m"
        meters < 10_000 -> String.format(TR, "%.1f km", meters / 1000)
        else -> "${Math.round(meters / 1000.0)} km"
    }

    fun duration(seconds: Double): String {
        val totalMin = Math.round(seconds / 60.0).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0 -> "$m dk"
            m == 0 -> "$h sa"
            else -> "$h sa $m dk"
        }
    }
}
