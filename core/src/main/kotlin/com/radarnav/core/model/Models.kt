package com.radarnav.core.model

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.RouteGeometry

/** Güzergahtaki denetim noktası: sabit radar ya da ortalama hız koridoru. */
sealed interface Hazard {
    val id: String
    val name: String
    val province: String
    val district: String
    /** km/s; bakanlık verisinde yoksa null. */
    val speedLimit: Int?
    /** Bakanlık verisinde aktif işaretli mi (activity = 3). Pasifler de gösterilir, soluk çizilir. */
    val active: Boolean
}

/**
 * Verinin kaynağı. Sıra önceliği de belirtir: aynı yerde birden fazla kayıt varsa öndeki tutulur.
 * MINISTRY: İçişleri Bakanlığı (günlük radar uygulamaları, koridorlar) · EGM: Emniyet'in resmî EDS
 * haritası · OSM: OpenStreetMap topluluk verisi · USER: sürücünün kendi bildirdiği seyyar radar
 * (yalnızca telefonda saklanır).
 */
enum class HazardSource { MINISTRY, EGM, OSM, USER }

data class Radar(
    override val id: String,
    override val name: String,
    override val province: String,
    override val district: String,
    override val speedLimit: Int?,
    val location: LatLng,
    override val active: Boolean = true,
    /**
     * Kameranın baktığı pusula yönü (derece). OSM'de `direction` etiketi çoğunlukla (ölçümle ~%91)
     * kameranın baktığı yönü gösterir; yani denetlenen trafik bu yönün tersine akar. Bilinmiyorsa null.
     */
    val facing: Double? = null,
    val source: HazardSource = HazardSource.MINISTRY,
    /** [HazardSource.USER] için son bildirim zamanı (epoch ms). */
    val reportedAt: Long? = null,
    /** [HazardSource.USER] için aynı yerde kaç kez bildirildiği. */
    val reportCount: Int = 0,
) : Hazard

data class SpeedCorridor(
    override val id: String,
    override val name: String,
    override val province: String,
    override val district: String,
    override val speedLimit: Int?,
    val lengthKm: Double?,
    val start: LatLng,
    val end: LatLng,
    val path: List<LatLng>,
    override val active: Boolean = true,
) : Hazard

/** Rotaya yerleştirilmiş tehlike: rota başından metre cinsinden başlangıç/bitiş. */
data class RouteHazard(
    val hazard: Hazard,
    val startAlong: Double,
    val endAlong: Double,
) {
    val isCorridor: Boolean get() = hazard is SpeedCorridor
    val isOsm: Boolean get() = source == HazardSource.OSM
    val source: HazardSource get() = (hazard as? Radar)?.source ?: HazardSource.MINISTRY
}

enum class ManeuverKind {
    DEPART, STRAIGHT,
    SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN,
    KEEP_LEFT, KEEP_RIGHT,
    MERGE, ON_RAMP, OFF_RAMP,
    FORK_LEFT, FORK_RIGHT,
    ROUNDABOUT, ARRIVE,
}

/**
 * Rota adımı. [location] manevranın yapılacağı nokta, [instruction] Türkçe yönlendirme
 * ("Sağa dönün, Atatürk Bulvarı"), [distanceMeters] manevradan sonra bir sonraki
 * manevraya kadar olan yol.
 */
data class RouteStep(
    val instruction: String,
    val kind: ManeuverKind,
    val location: LatLng,
    val roadName: String? = null,
    val roundaboutExit: Int? = null,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
)

data class NavRoute(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val provider: String,
) {
    val geometry: RouteGeometry by lazy { RouteGeometry(points) }
}

data class Place(
    val name: String,
    val address: String,
    val location: LatLng,
)
