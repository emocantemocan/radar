package com.radarnav.core

import com.radarnav.core.alert.AlertEngine
import com.radarnav.core.alert.AlertEvent
import com.radarnav.core.alert.CorridorCoach
import com.radarnav.core.alert.FreeCorridorMonitor
import com.radarnav.core.alert.WarningText
import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Koridor ortalama hızı: hedef hız, düzelme bildirimi ve rotasız (serbest sürüş) takip. */
class CorridorAverageTest {

    // Kuzeye düz yol: km → koordinat
    private fun at(km: Double) = LatLng(39.0 + km * 1000.0 / 111_195.0, 32.0)

    /** 1. km'den 11. km'ye, kuzey yönü; ve aynı yolun güney yönü kaydı. */
    private val north = SpeedCorridor("c:1", "A – B", "X", "Y", 110, 10.0, at(1.0), at(11.0), emptyList())
    private val south = SpeedCorridor("c:2", "B – A", "X", "Y", 110, 10.0, at(11.02), at(1.02), emptyList())

    /** [fromKm]'den [toKm]'ye, her saniye bir GPS konumu; [speedAt] km'ye göre hız. */
    private fun freeDrive(m: FreeCorridorMonitor, fromKm: Double, toKm: Double, speedAt: (Double) -> Double): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        var km = fromKm
        var t = 0L
        val dir = if (toKm >= fromKm) 1 else -1
        while ((toKm - km) * dir >= 0) {
            val v = speedAt(km)
            out += m.update(at(km), v, t)
            km += dir * v / 3.6 / 1000.0
            t += 1000
        }
        return out
    }

    @Test
    fun `hedef hiz kalan yolda ortalamayi sinira indirecek hizdir`() {
        val h = RouteHazard(north, 0.0, 10_000.0)
        val coach = CorridorCoach(h, 10_000.0, 0L)
        // 5 km'yi 130 ile gitti (138,5 sn): kalan 5 km için izin verilen süre 327,3 - 138,5 = 188,8 sn
        val t = (5.0 / 130.0 * 3600 * 1000).toLong()
        assertEquals(95.3, coach.targetKmh(5_000.0, 5_000.0, t)!!, 0.5)
        // Süre bitmişse kurtarılamaz
        assertNull(coach.targetKmh(9_900.0, 100.0, 400_000L))
        assertEquals(95, WarningText.floorTo5(99.9))
    }

    @Test
    fun `rotasiz surus koridoru kendiliginden yakalar ve ortalama asimini hedef hizla uyarir`() {
        val m = FreeCorridorMonitor({ listOf(north, south) })
        val events = freeDrive(m, 0.0, 12.0) { 130.0 }
        val entered = events.filterIsInstance<AlertEvent.CorridorEntered>()
        assertEquals(listOf("c:1"), entered.map { it.hazard.hazard.id })
        val over = events.filterIsInstance<AlertEvent.CorridorOverAverage>()
        assertTrue("ortalama aşımı uyarısı", over.isNotEmpty())
        assertTrue("hedef hız sınırın altında", over.first().targetKmh!! < 110)
        val exit = events.filterIsInstance<AlertEvent.CorridorExited>().single()
        assertEquals(130.0, exit.averageKmh, 3.0)
        assertNull(m.currentCorridor)
    }

    @Test
    fun `ters yonde gidince karsi yonun koridoru secilir`() {
        val m = FreeCorridorMonitor({ listOf(north, south) })
        val entered = freeDrive(m, 12.0, 0.0) { 100.0 }.filterIsInstance<AlertEvent.CorridorEntered>()
        assertEquals(listOf("c:2"), entered.map { it.hazard.hazard.id })
    }

    @Test
    fun `yavaslayinca ortalama duzeldi bildirimi gelir, kurala uyana hic uyari gelmez`() {
        val m = FreeCorridorMonitor({ listOf(north) })
        // İlk 3 km 140, sonra 80: ortalama sınırın altına iner
        val events = freeDrive(m, 0.0, 12.0) { km -> if (km < 4.0) 140.0 else 80.0 }
        val overAt = events.indexOfFirst { it is AlertEvent.CorridorOverAverage }
        val backAt = events.indexOfFirst { it is AlertEvent.CorridorBackUnder }
        assertTrue(overAt >= 0 && backAt > overAt)

        val calm = freeDrive(FreeCorridorMonitor({ listOf(north) }), 0.0, 12.0) { 105.0 }
        assertTrue(calm.none { it is AlertEvent.CorridorOverAverage || it is AlertEvent.CorridorBackUnder })
        assertEquals(1, calm.count { it is AlertEvent.CorridorExited })
    }

    @Test
    fun `koridordan sapan surucu sessizce birakilir`() {
        val m = FreeCorridorMonitor({ listOf(north) })
        val events = ArrayList<AlertEvent>()
        var t = 0L
        // 1 km girişten 3 km'ye kadar koridorda, sonra doğuya sapıp 12 km uzaklaş
        var km = 0.0
        while (km <= 3.0) { events += m.update(at(km), 100.0, t); km += 0.028; t += 1000 }
        var east = 0.0
        while (east <= 12_000) { events += m.update(LatLng(at(3.0).lat, 32.0 + east / 86_400.0), 100.0, t); east += 28; t += 1000 }
        assertEquals(1, events.count { it is AlertEvent.CorridorEntered })
        assertTrue(events.none { it is AlertEvent.CorridorExited })
        assertNull(m.currentCorridor)
    }

    @Test
    fun `rotali modda da hedef hiz ve duzelme`() {
        val engine = AlertEngine()
        engine.setHazards(listOf(RouteHazard(north, 3_000.0, 13_000.0)))
        val out = ArrayList<AlertEvent>()
        var along = 0.0
        var t = 0L
        while (along <= 14_000) {
            val v = if (along < 7_000) 140.0 else 80.0
            out += engine.update(along, v, t)
            along += v / 3.6
            t += 1000
        }
        assertTrue(out.filterIsInstance<AlertEvent.CorridorOverAverage>().first().targetKmh != null)
        assertTrue(out.any { it is AlertEvent.CorridorBackUnder })
    }

    @Test
    fun `gomulu koridor listesi okunur`() {
        val json = """{"generatedAt":"2026-11-07","corridors":[{"id":1560,"name":"Uşak – Kula","provinceName":"MANİSA",
            "districtName":"KULA","activity":3,"speedLimit":110,"length":67,"startLatY":38.674704,"startLonX":29.340367,
            "endLatY":38.547255,"endLonX":28.669982}]}"""
        val c = FreeCorridorMonitor.parseSnapshot(json).single()
        assertEquals("c:1560", c.id)
        assertEquals(110, c.speedLimit)
        assertEquals(67.0, c.lengthKm!!, 0.01)
    }

    @Test
    fun `uyari metinleri hedef hizi soyler`() {
        val h = RouteHazard(north, 0.0, 10_000.0)
        val fb = WarningText.fallback(AlertEvent.CorridorOverAverage(h, 124.0, 5_000.0, 97.0))
        assertTrue(fb, fb.contains("en fazla 95"))
        val lost = WarningText.fallback(AlertEvent.CorridorOverAverage(h, 150.0, 800.0, null))
        assertTrue(lost, !lost.contains("en fazla"))
        val prompt = WarningText.userPrompt(AlertEvent.CorridorOverAverage(h, 124.0, 5_000.0, 97.0), emptyList())
        assertTrue(prompt, prompt.contains("en fazla 95 km/s"))
        assertTrue(WarningText.fallback(AlertEvent.CorridorBackUnder(h, 108.0, 3_000.0)).contains("108"))
    }
}
