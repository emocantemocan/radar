package com.radarnav.core

import com.radarnav.core.alert.AlertEngine
import com.radarnav.core.alert.AlertEvent
import com.radarnav.core.alert.CorridorCoach
import com.radarnav.core.alert.WarningText
import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Koridor ortalama hızı: hedef hız ve düzelme bildirimi. */
class CorridorAverageTest {

    // Kuzeye düz yol: km → koordinat
    private fun at(km: Double) = LatLng(39.0 + km * 1000.0 / 111_195.0, 32.0)

    /** 1. km'den 11. km'ye, kuzey yönü. */
    private val north = SpeedCorridor("c:1", "A – B", "X", "Y", 110, 10.0, at(1.0), at(11.0), emptyList())

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

        // Kurala uyana hiç uyarı gelmez
        val calm = AlertEngine().apply { setHazards(listOf(RouteHazard(north, 3_000.0, 13_000.0))) }
        val calmOut = ArrayList<AlertEvent>()
        along = 0.0; t = 0L
        while (along <= 14_000) { calmOut += calm.update(along, 105.0, t); along += 105.0 / 3.6; t += 1000 }
        assertTrue(calmOut.none { it is AlertEvent.CorridorOverAverage || it is AlertEvent.CorridorBackUnder })
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
