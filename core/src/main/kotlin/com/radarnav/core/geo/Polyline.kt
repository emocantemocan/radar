package com.radarnav.core.geo

import kotlin.math.pow
import kotlin.math.roundToLong

/** Google "encoded polyline" biçimi (OSRM, GraphHopper ve Google Directions kullanır). */
object Polyline {

    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        val factor = 10.0.pow(precision)
        val result = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var shift = 0
            var value = 0L
            var b: Int
            do {
                b = encoded[index++].code - 63
                value = value or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (value and 1L != 0L) (value shr 1).inv() else value shr 1

            shift = 0
            value = 0L
            do {
                b = encoded[index++].code - 63
                value = value or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (value and 1L != 0L) (value shr 1).inv() else value shr 1

            result.add(LatLng(lat / factor, lon / factor))
        }
        return result
    }

    fun encode(points: List<LatLng>, precision: Int = 5): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else v shl 1
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        sb.append((value + 63).toInt().toChar())
    }
}
