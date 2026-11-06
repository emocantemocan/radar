package com.radarnav.core.live

import com.radarnav.core.ministry.HazardFinder
import com.radarnav.core.model.Radar
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.OsrmRouting
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Teşhis: bakanlığın döndürdüğü her kaydın bizim rotaya uzaklığı. RADARNAV_DEBUG=1 ile çalışır. */
class HazardDebugTest {
    @Test
    fun dump() = runBlocking {
        assumeTrue(System.getenv("RADARNAV_DEBUG") == "1")
        val t = LiveServicesTest
        val route = OsrmRouting(t.http).route(t.KIZILAY, t.ESKISEHIR)
        val g = route.geometry
        val anchors = HazardFinder(t.ministry, t.directory).anchorPoints(g)
        val ds = anchors.mapNotNull { t.directory.nearest(it) }.distinctBy { it.id }
        println("[teşhis] çapalar=${anchors.size} ilçeler=${ds.map { it.name + "/" + it.cityName }}")
        val pairs = if (ds.size == 1) listOf(ds[0] to ds[0]) else ds.zipWithNext()
        for ((a, b) in pairs) {
            val d = t.ministry.createRoute(a, b)
            println("[teşhis] ${a.name}->${b.name}: RadarCount=${d.radarCount} radars=${d.radars.size} (aktif ${d.activeRadarCount}) koridor=${d.corridors.size} koordinat=${d.coordinates.size}")
            d.radars.forEach { r ->
                val p = g.project(r.location)
                println("[teşhis]   radar ${r.name.take(40)} sınır=${r.speedLimit} aktif=${r.active} km=${"%.1f".format(p.along / 1000)} uzaklık=${p.crossTrack.toInt()} m")
            }
            d.corridors.forEach { c ->
                val s = g.project(c.start); val e = g.project(c.end)
                println("[teşhis]   koridor ${c.name.take(40)} uzunluk=${c.lengthKm} başlangıç uzaklık=${s.crossTrack.toInt()} bitiş uzaklık=${e.crossTrack.toInt()}")
            }
        }
    }
}
