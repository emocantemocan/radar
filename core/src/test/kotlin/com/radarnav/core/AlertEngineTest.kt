package com.radarnav.core

import com.radarnav.core.alert.AlertEngine
import com.radarnav.core.alert.AlertEvent
import com.radarnav.core.alert.Persona
import com.radarnav.core.alert.Stage
import com.radarnav.core.alert.WarningText
import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.Radar
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.SpeedCorridor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {

    private fun radar(id: String, limit: Int?) = Radar("r:$id", "Radar $id", "BOLU", "MERKEZ", limit, LatLng(0.0, 0.0))

    private val corridor = SpeedCorridor(
        "c:1", "Bolu Dağı", "BOLU", "MENGEN", 110, 10.0, LatLng(0.0, 0.0), LatLng(0.0, 0.1), emptyList(),
    )

    /** 90 km/s ile her saniye 25 m ilerleyerek sürüş simülasyonu. */
    private fun drive(engine: AlertEngine, from: Double, to: Double, speed: Double = 90.0): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        var along = from
        var t = 0L
        val step = speed / 3.6
        while (along <= to) {
            out += engine.update(along, speed, t)
            along += step
            t += 1000
        }
        return out
    }

    @Test
    fun `her radar icin 2 km ve 500 m uyarisi birer kez gelir`() {
        val engine = AlertEngine()
        engine.setHazards(
            listOf(
                RouteHazard(radar("1", 82), 5_000.0, 5_000.0),
                RouteHazard(radar("2", 50), 12_000.0, 12_000.0),
            ),
        )
        val events = drive(engine, 0.0, 15_000.0)
        val approaches = events.filterIsInstance<AlertEvent.Approach>()
        assertEquals(4, approaches.size)
        assertEquals(listOf("r:1" to Stage.FAR, "r:1" to Stage.NEAR, "r:2" to Stage.FAR, "r:2" to Stage.NEAR),
            approaches.map { it.hazard.hazard.id to it.stage })
        approaches.filter { it.stage == Stage.FAR }.forEach { assertTrue(it.distance in 1975.0..2000.0) }
        approaches.filter { it.stage == Stage.NEAR }.forEach { assertTrue(it.distance in 475.0..500.0) }

        // Ön hazırlık (prefetch) olayları uyarılardan önce gelmeli
        val firstPrefetch = events.indexOfFirst { it is AlertEvent.Prefetch && it.stage == Stage.FAR }
        val firstFar = events.indexOfFirst { it is AlertEvent.Approach && it.stage == Stage.FAR }
        assertTrue(firstPrefetch in 0 until firstFar)
    }

    @Test
    fun `radara cok yakin baslanirsa yalnizca son uyari gelir`() {
        val engine = AlertEngine()
        engine.setHazards(listOf(RouteHazard(radar("1", 82), 300.0, 300.0)))
        val approaches = drive(engine, 0.0, 1000.0).filterIsInstance<AlertEvent.Approach>()
        assertEquals(1, approaches.size)
        assertEquals(Stage.NEAR, approaches[0].stage)
    }

    @Test
    fun `yeniden rota hesaplaninca ayni radar tekrar uyarmaz`() {
        val engine = AlertEngine()
        val h = RouteHazard(radar("1", 82), 3_000.0, 3_000.0)
        engine.setHazards(listOf(h))
        drive(engine, 0.0, 1_200.0) // 2 km uyarısı geldi
        engine.setHazards(listOf(h.copy(startAlong = 1_800.0, endAlong = 1_800.0))) // yeni rota, mesafe değişti
        val events = drive(engine, 0.0, 2_000.0).filterIsInstance<AlertEvent.Approach>()
        assertEquals(listOf(Stage.NEAR), events.map { it.stage })
    }

    @Test
    fun `koridor giris, ortalama asimi ve cikis`() {
        val engine = AlertEngine()
        engine.setHazards(listOf(RouteHazard(corridor, 3_000.0, 13_000.0)))
        // 130 km/s ile koridordan geç
        val events = drive(engine, 0.0, 14_000.0, speed = 130.0)
        assertEquals(1, events.count { it is AlertEvent.CorridorEntered })
        assertTrue(events.any { it is AlertEvent.CorridorOverAverage })
        val exit = events.filterIsInstance<AlertEvent.CorridorExited>().single()
        assertEquals(130.0, exit.averageKmh, 2.0)
        assertEquals(null, engine.currentCorridor)
    }

    @Test
    fun `koridorda kurallara uyulursa ortalama uyarisi gelmez`() {
        val engine = AlertEngine()
        engine.setHazards(listOf(RouteHazard(corridor, 3_000.0, 13_000.0)))
        val events = drive(engine, 0.0, 14_000.0, speed = 100.0)
        assertTrue(events.none { it is AlertEvent.CorridorOverAverage })
    }

    @Test
    fun `gecerli hiz siniri`() {
        val engine = AlertEngine()
        engine.setHazards(listOf(RouteHazard(radar("1", 82), 5_000.0, 5_000.0)))
        assertEquals(null, engine.applicableLimit(1_000.0))
        assertEquals(82, engine.applicableLimit(3_500.0))
    }

    @Test
    fun `yedek metin mesafe ve siniri soyler`() {
        val e = AlertEvent.Approach(RouteHazard(radar("1", 82), 0.0, 0.0), Stage.FAR, 1987.0, 95.0)
        assertEquals("Radar 2 kilometre ileride. Hız sınırı 82.", WarningText.fallback(e))
        val near = AlertEvent.Approach(RouteHazard(radar("1", null), 0.0, 0.0), Stage.NEAR, 480.0, 95.0)
        assertEquals("Radar 500 metre ileride.", WarningText.fallback(near))
    }

    @Test
    fun `gemini istemi hiz asimini ve kisiligi icerir`() {
        val e = AlertEvent.Approach(RouteHazard(radar("1", 82), 0.0, 0.0), Stage.FAR, 1990.0, 104.0)
        val prompt = WarningText.userPrompt(e, listOf("Yavaşla birader"))
        assertTrue(prompt.contains("2 kilometre"))
        assertTrue(prompt.contains("82 km/s"))
        assertTrue(prompt.contains("104 km/s"))
        assertTrue(prompt.contains("sınırın üzerinde"))
        assertTrue(prompt.contains("Yavaşla birader"))
        assertTrue(WarningText.systemInstruction(Persona.SERT_ARGO).contains("küfür etme"))
    }
}
