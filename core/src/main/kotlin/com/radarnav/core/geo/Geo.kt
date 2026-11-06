package com.radarnav.core.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Enlem/boylam çifti (derece). */
data class LatLng(val lat: Double, val lon: Double) {
    override fun toString(): String = "%.6f,%.6f".format(java.util.Locale.US, lat, lon)
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun toRad(deg: Double) = deg * PI / 180.0
    fun toDeg(rad: Double) = rad * 180.0 / PI

    /** İki nokta arasındaki büyük daire mesafesi (metre). */
    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = toRad(b.lat - a.lat)
        val dLon = toRad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(toRad(a.lat)) * cos(toRad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** a'dan b'ye başlangıç yönü (0-360 derece, kuzey = 0). */
    fun bearing(a: LatLng, b: LatLng): Double {
        val phi1 = toRad(a.lat)
        val phi2 = toRad(b.lat)
        val dLon = toRad(b.lon - a.lon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (toDeg(atan2(y, x)) + 360.0) % 360.0
    }

    /** Kısa mesafeler için doğrusal ara değer. */
    fun interpolate(a: LatLng, b: LatLng, f: Double): LatLng =
        LatLng(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
}
