package com.radarnav.core.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

data class TileId(val z: Int, val x: Int, val y: Int)

data class WorldPixel(val x: Double, val y: Double)

/** OSM "slippy map" karo hesapları (Android Auto harita çizimi için). */
object WebMercator {
    const val TILE_SIZE = 256
    private const val MAX_LAT = 85.05112878

    fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

    fun toPixel(p: LatLng, zoom: Double): WorldPixel {
        val size = worldSize(zoom)
        val lat = p.lat.coerceIn(-MAX_LAT, MAX_LAT)
        val x = (p.lon + 180.0) / 360.0 * size
        val latRad = Geo.toRad(lat)
        val y = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * size
        return WorldPixel(x, y)
    }

    fun fromPixel(x: Double, y: Double, zoom: Double): LatLng {
        val size = worldSize(zoom)
        val lon = x / size * 360.0 - 180.0
        val n = PI - 2.0 * PI * y / size
        val lat = Geo.toDeg(atan(0.5 * (exp(n) - exp(-n))))
        return LatLng(lat, lon)
    }

    /**
     * Merkezi [centerX],[centerY] (dünya pikseli) olan [width]x[height] boyutlu
     * görünümü kaplayan karolar.
     */
    fun visibleTiles(centerX: Double, centerY: Double, width: Int, height: Int, zoom: Int): List<PlacedTile> {
        val count = 1 shl zoom
        val left = centerX - width / 2.0
        val top = centerY - height / 2.0
        val x0 = floor(left / TILE_SIZE).toInt()
        val x1 = floor((left + width) / TILE_SIZE).toInt()
        val y0 = floor(top / TILE_SIZE).toInt().coerceAtLeast(0)
        val y1 = floor((top + height) / TILE_SIZE).toInt().coerceAtMost(count - 1)
        val out = ArrayList<PlacedTile>()
        for (ty in y0..y1) for (tx in x0..x1) {
            // Boylamda dünya etrafında sarma
            val wrapped = ((tx % count) + count) % count
            out.add(PlacedTile(TileId(zoom, wrapped, ty), tx * TILE_SIZE - left, ty * TILE_SIZE - top))
        }
        return out
    }
}

/** Görünüme yerleştirilmiş karo; [left]/[top] görünümün sol üst köşesine göre pikseldir. */
data class PlacedTile(val tile: TileId, val left: Double, val top: Double)
