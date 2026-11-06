package com.radarnav.core

import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.RouteGeometry
import com.radarnav.core.ministry.DistrictDirectory
import com.radarnav.core.ministry.HazardFinder
import com.radarnav.core.ministry.InMemoryDistrictCache
import com.radarnav.core.ministry.MinistryApi
import com.radarnav.core.ministry.ProvinceLocator
import com.radarnav.core.model.HazardSource
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.Radar
import com.radarnav.core.net.LenientJson
import com.radarnav.core.osm.CameraSource
import com.radarnav.core.osm.InMemoryTextStore
import com.radarnav.core.osm.OsmCameraSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Bakanlık + OSM birleşimi, kalıcı önbellek, "konumsuz" yanıt ve karşı şerit kameraları. */
class HazardSourcesTest {

    private lateinit var server: MockWebServer
    private val locator = ProvinceLocator { _, _ -> listOf("Kuzey") }

    /** "ok": radarlı yanıt, "withheld": sayı var konum yok, "down": success=false. */
    @Volatile
    private var mode = "ok"
    private val routeCalls = AtomicInteger()

    // Kuzeye giden 60 km'lik düz rota (boylam 32.0): yön 0°
    private val routePoints = (0..60).map { LatLng(39.0 + it * 1000.0 / 111_195.0, 32.0) }
    private fun at(km: Double, eastMeters: Double = 0.0) =
        LatLng(39.0 + km * 1000.0 / 111_195.0, 32.0 + eastMeters / 86_400.0)

    private val route = NavRoute(routePoints, 60_000.0, 3000.0, emptyList(), "test")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/GetCities") -> json("""[{"Id":1,"Name":"Kuzey"}]""")
                    path.contains("/GetDistricts") ->
                        json("""[{"Id":11,"Name":"A","Latitude":${at(0.0).lat},"Longitude":32.0},
                                 {"Id":12,"Name":"B","Latitude":${at(60.0).lat},"Longitude":32.0}]""")
                    path.endsWith("/CreateRoute") -> {
                        routeCalls.incrementAndGet()
                        when (mode) {
                            "down" -> json("""{"success":false,"message":"Servis çağrılırken hata oluştu","data":null}""")
                            "withheld" -> json("""{"success":true,"data":{"RadarCount":2,"Cities":[{"City":"Kuzey","Radarli":2,"Radarsiz":3}],"Radars":[],"SpeedTunnels":[]}}""")
                            else -> json(
                                """{"success":true,"data":{"RadarCount":1,"Cities":[{"City":"Kuzey","Radarli":1,"Radarsiz":3}],"Radars":[
                                {"id":1,"name":"Bakanlık","provinceName":"X","districtName":"Y","activity":3,"owner":2,"speedLimit":null,"x":${at(20.0, 10.0).lon},"y":${at(20.0, 10.0).lat}}
                                ],"SpeedTunnels":[]}}""",
                            )
                        }
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

    private fun api() = MinistryApi(OkHttpClient(), server.url("/").toString().trimEnd('/'), retryDelaysMs = listOf(1))

    private fun osm(vararg r: Radar) = object : CameraSource {
        override suspend fun along(geometry: RouteGeometry) = r.toList()
    }

    private fun cam(id: Int, p: LatLng, limit: Int? = 82, facing: Double? = null) =
        Radar("osm:$id", "", "", "", limit, p, facing = facing, source = HazardSource.OSM)

    private fun finder(store: InMemoryTextStore = InMemoryTextStore(), cameras: CameraSource? = null, clock: () -> Long = { 1_000L }): HazardFinder {
        val api = api()
        return HazardFinder(api, DistrictDirectory(api, InMemoryDistrictCache(), locator), cameras = cameras, store = store, clock = clock)
    }

    @Test
    fun `osm kameralari eklenir, ayni yerdeki bakanlik kaydi tercih edilir ve sinir tamamlanir`() = runTest {
        val f = finder(
            cameras = osm(
                cam(1, at(20.05, 12.0), limit = 90), // bakanlık radarıyla aynı yer
                cam(2, at(40.0, 20.0), limit = 70), // yalnızca OSM'de
                cam(3, at(45.0, 300.0)), // rotadan 300 m uzakta: atılır
            ),
        )
        val r = f.find(route)
        assertEquals(listOf("r:1", "osm:2"), r.hazards.map { it.hazard.id })
        assertEquals("bakanlıkta sınır yoksa OSM'deki sınır kullanılır", 90, r.hazards[0].hazard.speedLimit)
        assertEquals(1, r.osmCount)
        assertEquals(1, r.ministryRadarCount)
        assertNull(r.warning)
    }

    @Test
    fun `karsi seridi denetleyen kamera atilir`() = runTest {
        // Rota kuzeye (0°) gidiyor. Kamera trafiğe bakar: güneye (180°) bakan kamera bizi, kuzeye bakan karşı şeridi denetler.
        mode = "withheld"
        val f = finder(cameras = osm(cam(5, at(30.0, 15.0), facing = 180.0), cam(6, at(35.0, -15.0), facing = 0.0), cam(7, at(38.0, 5.0), facing = null)))
        val ids = f.find(route).hazards.map { it.hazard.id }
        assertEquals(listOf("osm:5", "osm:7"), ids)
    }

    @Test
    fun `sunucu cokunce kalici onbellekteki radarlar kullanilir`() = runTest {
        val store = InMemoryTextStore()
        assertEquals(listOf("r:1"), finder(store).find(route).hazards.map { it.hazard.id })
        // Uygulama yeniden açıldı (bellek boş), bakanlık çöktü
        mode = "down"
        val r = finder(store, clock = { 1_000L + 60 * 60 * 1000 }).find(route)
        assertEquals(listOf("r:1"), r.hazards.map { it.hazard.id })
        assertFalse(r.unavailable)
    }

    @Test
    fun `konumsuz yanit onceki konumlari silmez`() = runTest {
        val store = InMemoryTextStore()
        var now = 1_000L
        val f = finder(store, clock = { now })
        f.find(route)
        mode = "withheld"
        now += 30 * 60 * 1000 // taze önbellek süresi geçti
        val r = f.find(route)
        assertEquals("önceki konum korunur", listOf("r:1"), r.hazards.map { it.hazard.id })
        assertTrue("konumsuz yanıt kalıcı önbelleğin üstüne yazılmaz", store.map.values.single().contains("\"id\":1"))
    }

    @Test
    fun `hic konum yoksa bakanlik sayisi uyari olarak gelir`() = runTest {
        mode = "withheld"
        val r = finder().find(route)
        assertTrue(r.hazards.isEmpty())
        assertEquals(2, r.ministryRadarCount)
        assertTrue(r.radarsWithheld)
        assertTrue(r.warning!!.contains("2 radar"))
    }

    @Test
    fun `bakanlik asili kalirsa rota beklemez, onbellek ve osm ile donulur`() = runTest {
        val store = InMemoryTextStore()
        finder(store).find(route) // konumlu yanıt kalıcı önbelleğe
        // Sunucu cevap vermiyor (asılı kalıyor)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        val api = MinistryApi(OkHttpClient(), server.url("/").toString().trimEnd('/'), retryDelaysMs = listOf(1))
        val directory = DistrictDirectory(api, InMemoryDistrictCache(), locator)
        // İlçe listesi önceden önbellekte (uygulamada 180 gün saklanıyor)
        val warm = finder(store)
        val f = HazardFinder(api, directory, cameras = osm(cam(2, at(40.0, 20.0))), store = store, clock = { 1_000L + 3_600_000L })
        val t0 = System.nanoTime()
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { f.find(route, budgetMs = 500) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$ms ms sürdü", ms < 2_500)
        assertTrue(warm !== f)
        assertEquals(listOf("osm:2"), r.hazards.map { it.hazard.id }.filter { it.startsWith("osm") })
    }

    @Test
    fun `bakanlik basarisiz dondugunde yeniden denenir, osm yine gelir`() = runTest {
        mode = "down"
        val r = finder(cameras = osm(cam(2, at(40.0, 20.0)))).find(route)
        assertTrue("ilk deneme + 1 tekrar", routeCalls.get() >= 2)
        assertEquals(listOf("osm:2"), r.hazards.map { it.hazard.id })
        assertFalse(r.unavailable)
        assertTrue(r.warning!!.contains("ulaşılamadı"))
    }

    @Test
    fun `osm yaniti ayrisir, sinir ve yon cozulur`() {
        val json = LenientJson.parseToJsonElement(
            """{"elements":[
              {"type":"node","id":10,"lat":39.9,"lon":32.8,"tags":{"highway":"speed_camera","maxspeed":"82","direction":"265"}},
              {"type":"node","id":11,"lat":39.8,"lon":32.7,"tags":{"highway":"speed_camera","maxspeed":"TR:urban","direction":"NE"}},
              {"type":"node","id":12,"lat":39.7,"lon":32.6,"tags":{"highway":"speed_camera","maxspeed":"50 km/h","direction":"forward"}},
              {"type":"way","id":13}
            ]}""",
        )
        val cams = OsmCameraSource.parse(json)
        assertEquals(3, cams.size)
        assertEquals(82, cams[0].speedLimit)
        assertEquals(265.0, cams[0].facing!!, 0.0)
        assertNull(cams[1].speedLimit)
        assertEquals(45.0, cams[1].facing!!, 0.0)
        assertEquals(50, cams[2].speedLimit)
        assertNull(cams[2].facing)
        assertTrue(cams.all { it.source == HazardSource.OSM && it.id.startsWith("osm:") })
    }

    @Test
    fun `osm kareleri onbellekten gelir, ag yoksa eski veri kullanilir`() = runTest {
        val overpass = MockWebServer()
        val calls = AtomicInteger()
        val failFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        overpass.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.incrementAndGet()
                if (failFlag.get()) return MockResponse().setResponseCode(504)
                return json("""{"elements":[{"type":"node","id":1,"lat":${at(10.0).lat},"lon":32.0,"tags":{"maxspeed":"90"}}]}""")
            }
        }
        overpass.start()
        try {
            val store = InMemoryTextStore()
            var now = 0L
            val src = OsmCameraSource(OkHttpClient(), store, listOf(overpass.url("/api").toString()), politeDelayMs = 0, clock = { now })
            val geom = RouteGeometry(routePoints)
            val tiles = src.tilesFor(geom).size
            assertEquals(1, src.along(geom).size)
            assertTrue(tiles > 1)
            assertEquals("eksik karelerin hepsi tek istekte sorulur", 1, calls.get())
            src.along(geom)
            assertEquals("ikinci çağrı önbellekten", 1, calls.get())
            now += 8L * 24 * 3600 * 1000 // önbellek eskidi
            failFlag.set(true)
            assertEquals("sunucu düşükken eski veri", 1, src.along(geom).size)
        } finally {
            overpass.shutdown()
        }
    }

    @Test
    fun `gomulu anlik goruntu varken ag beklenmez, guncelleme arka planda yazilir`() = runTest {
        val overpass = MockWebServer()
        overpass.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                json("""{"elements":[{"type":"node","id":9,"lat":${at(30.0).lat},"lon":32.0,"tags":{"maxspeed":"70"}}]}""")
                    .setHeadersDelay(1, java.util.concurrent.TimeUnit.SECONDS)
        }
        overpass.start()
        try {
            val store = InMemoryTextStore()
            val src = OsmCameraSource(
                OkHttpClient(), store, listOf(overpass.url("/api").toString()), politeDelayMs = 0,
                snapshot = { listOf(cam(1, at(10.0))) },
            )
            val geom = RouteGeometry(routePoints)
            val t0 = System.nanoTime()
            val first = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { src.along(geom) }
            assertTrue("ağ beklenmemeli", (System.nanoTime() - t0) / 1_000_000 < 500)
            assertEquals(listOf("osm:1"), first.map { it.id })
            // Arka plandaki güncelleme bitince yeni veri önbellekten gelir
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) { while (store.map.isEmpty()) kotlinx.coroutines.delay(50) }
                kotlinx.coroutines.delay(200)
            }
            assertEquals(listOf("osm:9"), src.along(geom).map { it.id })
        } finally {
            overpass.shutdown()
        }
    }
}
