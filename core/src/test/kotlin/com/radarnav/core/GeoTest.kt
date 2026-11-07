package com.radarnav.core

import com.radarnav.core.geo.Geo
import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.Polyline
import com.radarnav.core.geo.RouteGeometry
import com.radarnav.core.geo.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `haversine Ankara-Istanbul yaklasik 350 km`() {
        val ankara = LatLng(39.9334, 32.8597)
        val istanbul = LatLng(41.0082, 28.9784)
        val d = Geo.distance(ankara, istanbul)
        assertTrue("mesafe $d", d in 345_000.0..355_000.0)
    }

    @Test
    fun `polyline google ornegi cozulur`() {
        // Google'ın dokümantasyonundaki örnek
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5)
        assertEquals(-120.2, pts[0].lon, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5)
        assertEquals(-126.453, pts[2].lon, 1e-5)
    }

    @Test
    fun `polyline encode-decode precision 6`() {
        val pts = listOf(LatLng(39.123456, 32.654321), LatLng(39.2, 32.7), LatLng(38.9, 33.1))
        val back = Polyline.decode(Polyline.encode(pts, 6), 6)
        pts.zip(back).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lon, b.lon, 1e-6)
        }
    }

    private val straightRoute = RouteGeometry(
        // Doğuya giden ~3 km'lik düz yol (enlem 40)
        (0..30).map { LatLng(40.0, 32.0 + it * 0.00117) },
    )

    @Test
    fun `izdusum rota uzerindeki mesafeyi ve sapmayi verir`() {
        val total = straightRoute.length
        assertTrue(total in 2900.0..3100.0)
        // Yolun 50 m kuzeyinde, ortada bir nokta
        val mid = straightRoute.pointAt(total / 2)
        val north = LatLng(mid.lat + 50.0 / 111_195.0, mid.lon)
        val pr = straightRoute.project(north)
        assertEquals(total / 2, pr.along, 2.0)
        assertEquals(50.0, pr.crossTrack, 1.0)
    }

    @Test
    fun `ipucu ile izdusum ayni sonucu verir`() {
        val p = straightRoute.pointAt(1234.0)
        val a = straightRoute.project(p)
        val b = straightRoute.project(p, hint = 2)
        assertEquals(a.along, b.along, 0.5)
    }

    @Test
    fun `slice baslangic ve bitis noktalarini icerir`() {
        val s = straightRoute.slice(500.0, 1500.0)
        assertEquals(500.0, Geo.distance(straightRoute.points.first(), s.first()), 1.0)
        assertEquals(1500.0, Geo.distance(straightRoute.points.first(), s.last()), 1.0)
    }

    @Test
    fun `web mercator gidis donus`() {
        val p = LatLng(39.92, 32.85)
        val px = WebMercator.toPixel(p, 12.0)
        val back = WebMercator.fromPixel(px.x, px.y, 12.0)
        assertEquals(p.lat, back.lat, 1e-6)
        assertEquals(p.lon, back.lon, 1e-6)
    }

    @Test
    fun `gorunur karolar ekrani kaplar`() {
        val px = WebMercator.toPixel(LatLng(39.92, 32.85), 14.0)
        val tiles = WebMercator.visibleTiles(px.x, px.y, 800, 480, 14)
        // 800/256 -> 4-5 sütun, 480/256 -> 2-3 satır
        assertTrue(tiles.size in 8..15)
        assertTrue(tiles.all { it.left > -256 && it.left < 800 && it.top > -256 && it.top < 480 })
    }
}
