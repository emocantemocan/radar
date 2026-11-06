package com.radarnav.core

import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.RouteStep
import com.radarnav.core.nav.NavEvent
import com.radarnav.core.nav.NavFix
import com.radarnav.core.nav.NavigationTracker
import com.radarnav.core.routing.TurkishInstructions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTrackerTest {

    // Doğuya 3 km, sonra kuzeye 2 km (L şeklinde)
    private val corner = LatLng(40.0, 32.0 + 3000.0 / 85_180.0)
    private val points = (0..30).map { LatLng(40.0, 32.0 + it * 100.0 / 85_180.0) } +
        (1..20).map { LatLng(40.0 + it * 100.0 / 111_195.0, corner.lon) }

    private val route = NavRoute(
        points = points,
        distanceMeters = 5000.0,
        durationSeconds = 300.0,
        steps = listOf(
            RouteStep("Yola çıkın", ManeuverKind.DEPART, points.first()),
            RouteStep("Sola dönün, Atatürk Bulvarı", ManeuverKind.LEFT, corner, "Atatürk Bulvarı"),
            RouteStep("Varış noktanıza ulaştınız", ManeuverKind.ARRIVE, points.last()),
        ),
        provider = "test",
    )

    @Test
    fun `donus once hazirlik sonra simdi olarak okunur ve varis bildirilir`() {
        val tracker = NavigationTracker(route)
        val geometry = route.geometry
        val spoken = ArrayList<String>()
        var arrived = false
        var along = 0.0
        var t = 0L
        while (along <= geometry.length + 20) {
            val (state, events) = tracker.update(NavFix(geometry.pointAt(along), 60.0, 5f, t))
            events.forEach {
                when (it) {
                    is NavEvent.Instruction -> spoken += it.text
                    NavEvent.Arrived -> arrived = true
                    NavEvent.OffRoute -> error("rotadan çıkmamalı")
                }
            }
            if (along < 2900) assertEquals(ManeuverKind.LEFT, state.nextStep?.kind)
            along += 60.0 / 3.6
            t += 1000
        }
        assertEquals(
            listOf(
                "500 metre sonra sola dönün, Atatürk Bulvarı",
                "Sola dönün, Atatürk Bulvarı",
                "500 metre sonra varış noktanıza ulaşacaksınız",
            ),
            spoken,
        )
        assertTrue(arrived)
    }

    @Test
    fun `rotadan cikinca bir kez bildirilir`() {
        val tracker = NavigationTracker(route)
        val off = LatLng(40.0 + 300.0 / 111_195.0, 32.0 + 1000.0 / 85_180.0) // yolun 300 m kuzeyi
        var count = 0
        repeat(6) { i ->
            val (_, events) = tracker.update(NavFix(off, 50.0, 5f, i * 1000L))
            count += events.count { it == NavEvent.OffRoute }
        }
        assertEquals(1, count)
    }

    @Test
    fun `mesafe metinleri`() {
        assertEquals("300 metre", TurkishInstructions.spokenDistance(310.0))
        assertEquals("1,5 kilometre", TurkishInstructions.spokenDistance(1480.0))
        assertEquals("2 kilometre", TurkishInstructions.spokenDistance(2010.0))
        assertEquals("1 sa 5 dk", TurkishInstructions.duration(3900.0))
        assertEquals("sağa dönün", TurkishInstructions.lowerFirst("Sağa dönün"))
        assertEquals("ısparta", TurkishInstructions.lowerFirst("Isparta"))
    }
}
