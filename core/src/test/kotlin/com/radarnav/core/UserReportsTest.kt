package com.radarnav.core

import com.radarnav.core.alert.AlertEvent
import com.radarnav.core.alert.Stage
import com.radarnav.core.alert.WarningText
import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.RouteGeometry
import com.radarnav.core.ministry.DistrictDirectory
import com.radarnav.core.ministry.HazardFinder
import com.radarnav.core.ministry.InMemoryDistrictCache
import com.radarnav.core.ministry.MinistryApi
import com.radarnav.core.ministry.ProvinceLocator
import com.radarnav.core.model.HazardSource
import com.radarnav.core.model.Radar
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.osm.InMemoryTextStore
import com.radarnav.core.report.UserReport
import com.radarnav.core.report.UserReportStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sürücünün kendi seyyar radar bildirimleri: kayıt, birleştirme, unutma, rotaya yerleşme, uyarı metni. */
class UserReportsTest {

    private val day = 24L * 3600 * 1000
    private var now = 1_000 * day
    private val disk = InMemoryTextStore()
    private fun store() = UserReportStore(disk, clock = { now })

    // Kuzeye giden 10 km'lik düz rota
    private fun at(km: Double, eastMeters: Double = 0.0) =
        LatLng(39.0 + km * 1000.0 / 111_195.0, 32.0 + eastMeters / 86_400.0)
    private val geometry = RouteGeometry((0..10).map { at(it.toDouble()) })

    private val api = MinistryApi(OkHttpClient(), baseUrl = "http://127.0.0.1:1")
    private val finder = HazardFinder(api, DistrictDirectory(api, InMemoryDistrictCache(), ProvinceLocator { _, _ -> emptyList() }))

    @Test
    fun `bildirim kalici saklanir ve yakindaki tekrar birlestirilir`() {
        val s = store()
        s.report(at(2.0))
        now += day
        val again = s.report(at(2.05)) // ~50 m ötede: aynı nokta
        assertEquals(2, again.count)
        s.report(at(5.0))
        // Uygulama yeniden açıldı: kayıtlar diskten okunur
        val reopened = store()
        assertEquals(2, reopened.count())
        val merged = reopened.all().first { it.count == 2 }
        assertEquals(now, merged.at)
        assertEquals(at(2.05).lat, merged.location.lat, 1e-9)
    }

    @Test
    fun `eski kayitlar unutulur, yakindakiler silinebilir`() {
        val s = store()
        s.report(at(1.0))
        s.report(at(6.0))
        now += 200 * day
        assertEquals(0, store().count())

        val t = store()
        t.report(at(1.0))
        t.report(at(6.0))
        assertEquals(1, t.removeNear(at(1.1)))
        assertEquals(1, store().count())
        t.clear()
        assertEquals(0, store().count())
    }

    @Test
    fun `bozuk dosya uygulamayi cokertmez`() {
        disk.write(UserReportStore.KEY, "{bozuk")
        assertEquals(0, store().count())
        store().report(at(3.0))
        assertEquals(1, store().count())
    }

    @Test
    fun `rotadaki bildirim yerlesir, uzaktaki ve resmi kameranin ikizi atilir`() = runTest {
        val s = store()
        s.report(at(3.0, eastMeters = 20.0)) // yol üstünde (GPS hatası payı içinde)
        s.report(at(5.0, eastMeters = 400.0)) // paralel yol: rotada değil
        s.report(at(7.0))
        val official = Radar("osm:1", "", "", "", 70, at(7.03), source = HazardSource.OSM)
        val placed = finder.place(geometry, s.along(geometry) + official)
        assertEquals(2, placed.size)
        assertEquals(HazardSource.USER, placed[0].source)
        assertEquals(3000.0, placed[0].startAlong, 30.0)
        // 7. km'de resmî kamera da var: o tutulur
        assertEquals(HazardSource.OSM, placed[1].source)
    }

    @Test
    fun `uyari metni seyyar radari gecmis bildirim olarak anlatir`() {
        val r = UserReportStore.toRadar(UserReport(at(3.0), System.currentTimeMillis() - 3 * day, 2))
        val h = RouteHazard(r, 3000.0, 3000.0)
        val e = AlertEvent.Approach(h, Stage.FAR, 1990.0, 90.0)
        val fb = WarningText.fallback(e)
        assertTrue(fb, fb.contains("seyyar radar"))
        assertFalse(fb, fb.contains("null"))
        val prompt = WarningText.userPrompt(e, emptyList())
        assertTrue(prompt, prompt.contains("3 gün önce"))
        assertTrue(prompt, prompt.contains("2 kez"))
    }

    @Test
    fun `yas metni`() {
        assertEquals("bugün", UserReportStore.ageText(now, now))
        assertEquals("dün", UserReportStore.ageText(now - day, now))
        assertEquals("5 gün önce", UserReportStore.ageText(now - 5 * day, now))
        assertEquals("3 hafta önce", UserReportStore.ageText(now - 21 * day, now))
        assertEquals("2 ay önce", UserReportStore.ageText(now - 65 * day, now))
    }
}
