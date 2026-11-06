package com.radarnav.core

import com.radarnav.core.geo.LatLng
import com.radarnav.core.ministry.DistrictDirectory
import com.radarnav.core.ministry.HazardFinder
import com.radarnav.core.ministry.InMemoryDistrictCache
import com.radarnav.core.ministry.MinistryApi
import com.radarnav.core.ministry.ProvinceLocator
import com.radarnav.core.ministry.Provinces
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.Radar
import com.radarnav.core.model.SpeedCorridor
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bakanlık sunucusunu taklit ederek uçtan uca: ilçe listesi indirme, rotayı parçalara
 * bölüp sorgulama, rotadaki radarları ayıklama.
 */
class HazardFinderTest {

    private lateinit var server: MockWebServer
    private val routeCalls = AtomicInteger()
    private val requests = AtomicInteger()
    /** CreateRoute'un dönmesi gereken HTTP kodu (hata senaryoları için). */
    @Volatile
    private var routeStatus = 200
    private val locator = ProvinceLocator { _, _ -> listOf("Kuzey", "Güney") }

    private fun api() = MinistryApi(OkHttpClient(), server.url("/").toString().trimEnd('/'), retryDelaysMs = listOf(10, 10))

    // Kuzeye giden 150 km'lik düz rota (boylam 32.0)
    private val routePoints = (0..150).map { LatLng(39.0 + it * 1000.0 / 111_195.0, 32.0) }
    private fun at(km: Double, eastMeters: Double = 0.0) =
        LatLng(39.0 + km * 1000.0 / 111_195.0, 32.0 + eastMeters / 86_400.0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/GetCities") -> json("""[{"Id":1,"Name":"Kuzey"},{"Id":2,"Name":"Güney"}]""")
                    path.contains("/GetDistricts?cityId=1") ->
                        json("""[{"Id":11,"Name":"A","Latitude":${at(0.0).lat},"Longitude":32.0},
                                 {"Id":12,"Name":"B","Latitude":${at(75.0).lat},"Longitude":32.0}]""")
                    path.contains("/GetDistricts?cityId=2") ->
                        json("""[{"Id":21,"Name":"C","Latitude":${at(150.0).lat},"Longitude":32.0}]""")
                    path.endsWith("/CreateRoute") -> {
                        routeCalls.incrementAndGet()
                        if (routeStatus != 200) return MockResponse().setResponseCode(routeStatus)
                        val body = request.body.readUtf8()
                        assertTrue(body.contains("fromDistrictId="))
                        createRoute(body)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    private fun radarJson(id: Int, p: LatLng, limit: Int) =
        """{"id":$id,"name":"R$id","provinceName":"X","districtName":"Y","activity":3,"speedLimit":$limit,"x":${p.lon},"y":${p.lat}}"""

    private fun createRoute(body: String): MockResponse {
        // İlk parça (11->12): rotada bir radar + 2 km doğuda (rotada olmayan) bir radar
        // İkinci parça (12->21): rotada bir radar ve bir koridor; ilk parçadaki radar tekrar geliyor
        val radars = if (body.contains("fromDistrictId=11")) {
            listOf(radarJson(1, at(20.0, 15.0), 82), radarJson(2, at(40.0, 2000.0), 50))
        } else {
            listOf(radarJson(1, at(20.0, 15.0), 82), radarJson(3, at(120.0, -20.0), 110))
        }
        val corridors = if (body.contains("fromDistrictId=12")) {
            val s = at(90.0)
            val e = at(100.0)
            listOf(
                """{"id":7,"name":"Koridor","provinceName":"X","districtName":"Y","activity":3,"speedLimit":110,
                   "length":10,"startLonX":${s.lon},"startLatY":${s.lat},"endLonX":${e.lon},"endLatY":${e.lat}}""",
            )
        } else emptyList()
        return json("""{"success":true,"data":{"Radars":[${radars.joinToString(",")}],"SpeedTunnels":[${corridors.joinToString(",")}]}}""")
    }

    @Test
    fun `rotadaki radar ve koridorlar bulunur, sirali ve tekil gelir`() = runTest {
        val api = api()
        val cache = InMemoryDistrictCache()
        val finder = HazardFinder(api, DistrictDirectory(api, cache, locator))
        val route = NavRoute(routePoints, 150_000.0, 6000.0, emptyList(), "test")

        val result = finder.find(route)

        assertEquals(null, result.warning)
        assertEquals(2, routeCalls.get()) // 11->12, 12->21
        val ids = result.hazards.map { it.hazard.id }
        assertEquals(listOf("r:1", "c:7", "r:3"), ids) // r:2 rotadan 2 km uzakta, atılır
        val r1 = result.hazards[0]
        assertTrue(r1.hazard is Radar)
        assertEquals(20_000.0, r1.startAlong, 50.0)
        val corridor = result.hazards[1]
        assertTrue(corridor.hazard is SpeedCorridor)
        assertEquals(90_000.0, corridor.startAlong, 50.0)
        assertEquals(100_000.0, corridor.endAlong, 50.0)
        assertNotNull("ilçe listesi önbelleğe yazılmalı", cache.content)
        // 1 il listesi + yalnızca gereken 2 ilin ilçeleri + 2 radar sorgusu
        assertEquals(5, requests.get())
    }

    @Test
    fun `sonuc onbellekten gelir, sunucu dusunce son veri kullanilir`() = runTest {
        val api = api()
        val finder = HazardFinder(api, DistrictDirectory(api, InMemoryDistrictCache(), locator))
        val route = NavRoute(routePoints, 150_000.0, 6000.0, emptyList(), "test")
        finder.find(route)
        assertEquals(2, routeCalls.get())
        finder.find(route) // taze önbellek: yeni sorgu yok
        assertEquals(2, routeCalls.get())

        routeStatus = 503
        val stale = finder.find(route, force = true)
        assertEquals(listOf("r:1", "c:7", "r:3"), stale.hazards.map { it.hazard.id })
        assertEquals(null, stale.warning)
        assertTrue("503 tekrar denenmeli", routeCalls.get() >= 2 + 2 * 3)
    }

    @Test
    fun `403 tekrar denenmez ve anlasilir hata verir`() = runTest {
        routeStatus = 403
        val api = api()
        val finder = HazardFinder(api, DistrictDirectory(api, InMemoryDistrictCache(), locator))
        val result = finder.find(NavRoute(routePoints, 150_000.0, 6000.0, emptyList(), "test"))
        assertEquals(2, routeCalls.get())
        assertTrue(result.unavailable)
        assertTrue(result.warning!!, result.warning!!.contains("HTTP 403"))
    }

    @Test
    fun `il adlari eslesir ve yakin iller bulunur`() {
        assertTrue(Provinces.matches("İSTANBUL", "İstanbul"))
        assertTrue(Provinces.matches("Afyon", "Afyonkarahisar"))
        assertTrue(Provinces.matches("K.Maraş", "Kahramanmaraş"))
        assertTrue(!Provinces.matches("Kars", "Kırşehir"))
        assertEquals(81, Provinces.capitals.size)
        assertEquals("Ankara", Provinces.candidates(LatLng(39.92, 32.85), 3).first())
        assertEquals("Bolu", Provinces.candidates(LatLng(40.74, 31.6), 1).single())
    }

    @Test
    fun `bakanlik erisilemezse uyari doner`() = runTest {
        server.shutdown()
        val api = MinistryApi(OkHttpClient(), "http://127.0.0.1:9", retryDelaysMs = listOf(10, 10))
        val finder = HazardFinder(api, DistrictDirectory(api, InMemoryDistrictCache()))
        val result = finder.find(NavRoute(routePoints, 150_000.0, 6000.0, emptyList(), "test"))
        assertTrue(result.hazards.isEmpty())
        assertNotNull(result.warning)
    }
}
