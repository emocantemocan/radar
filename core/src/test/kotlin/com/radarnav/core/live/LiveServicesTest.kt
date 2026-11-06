package com.radarnav.core.live

import com.radarnav.core.alert.AlertEngine
import com.radarnav.core.alert.AlertEvent
import com.radarnav.core.alert.Persona
import com.radarnav.core.alert.Stage
import com.radarnav.core.alert.WarningText
import com.radarnav.core.gemini.GeminiLiveSession
import com.radarnav.core.gemini.GeminiModels
import com.radarnav.core.gemini.GeminiTextClient
import com.radarnav.core.gemini.GeminiTts
import com.radarnav.core.gemini.LiveConfig
import com.radarnav.core.gemini.LiveListener
import com.radarnav.core.gemini.ToolDeclaration
import com.radarnav.core.geo.LatLng
import com.radarnav.core.ministry.DistrictDirectory
import com.radarnav.core.ministry.HazardFinder
import com.radarnav.core.ministry.InMemoryDistrictCache
import com.radarnav.core.ministry.MinistryApi
import com.radarnav.core.model.NavRoute
import com.radarnav.core.place.PlaceSearch
import com.radarnav.core.routing.OsrmRouting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Gerçek servislere karşı uçtan uca testler. Varsayılan `./gradlew :core:test` çalışmasında atlanır.
 *
 *   RADARNAV_LIVE=1 ./gradlew :core:test --tests '*LiveServicesTest*'
 *   (Gemini testleri için ayrıca GEMINI_API_KEY ortam değişkeni)
 *
 * Bakanlık sunucusunu yormamak için tüm testler aynı il/ilçe önbelleğini ve tek bir rota sorgusunu paylaşır.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveServicesTest {

    companion object {
        private val live = System.getenv("RADARNAV_LIVE") == "1"
        private val geminiKey = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { c ->
                c.proceed(c.request().newBuilder().header("User-Agent", "RadarNav/0.2 (live-test; https://github.com/emocantemocan/radar)").build())
            }
            .build()

        val ministry = MinistryApi(http)
        val directory = DistrictDirectory(ministry, InMemoryDistrictCache())
        val osm = com.radarnav.core.osm.OsmCameraSource(http, com.radarnav.core.osm.InMemoryTextStore())
        val finder = HazardFinder(ministry, directory, cameras = osm, store = com.radarnav.core.osm.InMemoryTextStore())

        // Ankara Kızılay -> Eskişehir merkez (D-200 / O-4 güzergahı, bol radar ve koridor)
        val KIZILAY = LatLng(39.9208, 32.8541)
        val ESKISEHIR = LatLng(39.7767, 30.5206)

        var route: NavRoute? = null

        @BeforeClass
        @JvmStatic
        fun gate() {
            assumeTrue("RADARNAV_LIVE=1 değil, canlı testler atlandı", live)
        }

        fun log(msg: String) = println("[canlı] $msg")
    }

    private fun needGemini() = assumeTrue("GEMINI_API_KEY yok", geminiKey != null)

    @Test
    fun a1_ministryCitiesAndDistricts() = runBlocking {
        val cities = directory.cities()
        log("il sayısı: ${cities.size}")
        assertTrue(cities.size >= 81, "81 il bekleniyordu, ${cities.size} geldi")
        val ankara = cities.first { it.name.contains("ANKARA", ignoreCase = true) }
        val d = directory.nearest(KIZILAY) ?: fail("Kızılay'a en yakın ilçe bulunamadı")
        log("Kızılay en yakın ilçe: ${d.name} (${d.cityName}) ankaraId=${ankara.id}")
        assertTrue(d.cityId == ankara.id, "Kızılay Ankara'da olmalı, ${d.cityName} bulundu")
    }

    @Test
    fun a2_placeSearch() = runBlocking {
        val r = PlaceSearch(http).search("Kızılay Ankara", near = KIZILAY)
        log("Nominatim: ${r.size} sonuç, ilk: ${r.firstOrNull()?.name} @ ${r.firstOrNull()?.location}")
        assertTrue(r.isNotEmpty(), "arama sonuç döndürmedi")
        val first = r.first().location
        assertTrue(first.lat in 39.8..40.1 && first.lon in 32.6..33.1, "ilk sonuç Ankara dışında: $first")
    }

    @Test
    fun a3_osrmRoute() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = OsrmRouting(http).route(KIZILAY, ESKISEHIR)
        route = r
        log("OSRM: ${(r.distanceMeters / 1000).toInt()} km, ${(r.durationSeconds / 60).toInt()} dk, ${r.points.size} nokta, ${r.steps.size} adım, ${System.currentTimeMillis() - t0} ms")
        r.steps.take(5).forEach { log("  adım: ${it.instruction}") }
        assertTrue(r.distanceMeters in 200_000.0..280_000.0, "Ankara-Eskişehir mesafesi beklenmedik: ${r.distanceMeters}")
        assertTrue(r.steps.size > 5)
    }

    @Test
    fun a4_hazardsOnRoute() = runBlocking {
        val r = route ?: OsrmRouting(http).route(KIZILAY, ESKISEHIR).also { route = it }
        val t0 = System.currentTimeMillis()
        val res = finder.find(r)
        log("HazardFinder: ${res.hazards.size} tehlike (OSM ${res.osmCount}, bakanlık sayısı ${res.ministryRadarCount}, konumsuz=${res.radarsWithheld}), uyarı=${res.warning}, ${System.currentTimeMillis() - t0} ms")
        res.hazards.forEach { h ->
            log("  ${if (h.isCorridor) "koridor" else "radar  "} km ${"%.1f".format(h.startAlong / 1000)}" +
                (if (h.isCorridor) "-${"%.1f".format(h.endAlong / 1000)}" else "") +
                " sınır=${h.hazard.speedLimit} aktif=${h.hazard.active} kaynak=${if (h.isOsm) "OSM" else "bakanlık"} ${h.hazard.province}/${h.hazard.district} ${h.hazard.name}")
        }
        assertTrue(!res.unavailable, "hiç radar verisi alınamadı: ${res.warning}")
        assertTrue(res.hazards.isNotEmpty(), "Ankara-Eskişehir güzergahında hiç radar/koridor bulunamadı")
        assertTrue(res.hazards.zipWithNext().all { (a, b) -> a.startAlong <= b.startAlong }, "sıralama bozuk")
    }

    @Test
    fun a5_alertSimulationAlongRoute() = runBlocking {
        val r = route ?: return@runBlocking
        val hazards = finder.find(r).hazards
        assumeTrue(hazards.isNotEmpty())
        val engine = AlertEngine().apply { setHazards(hazards) }
        val events = ArrayList<AlertEvent>()
        // 1 sn aralıkla, her radarın sınırından 15 km/s hızlı sürüş
        var along = 0.0
        var t = 0L
        while (along < r.distanceMeters) {
            val limit = engine.applicableLimit(along) ?: 110
            val speed = limit + 15.0
            events += engine.update(along, speed, t)
            along += speed / 3.6
            t += 1000
        }
        val approaches = events.filterIsInstance<AlertEvent.Approach>()
        val radars = hazards.filterNot { it.isCorridor }
        log("simülasyon: ${events.size} olay, ${approaches.size} yaklaşma uyarısı, ${radars.size} radar")
        val missing = radars.filter { h -> approaches.none { it.hazard.hazard.id == h.hazard.id && it.stage == Stage.NEAR } }
        missing.forEach { log("  500 m uyarısı gelmeyen: km ${"%.1f".format(it.startAlong / 1000)} ${it.hazard.name}") }
        assertTrue(missing.isEmpty(), "${missing.size} radar için 500 m uyarısı gelmedi")
        val finals = approaches.count { it.stage == Stage.FINAL }
        log("  200 m son uyarı sayısı: $finals")
    }

    @Test
    fun b1_geminiModelPick() = runBlocking {
        needGemini()
        val models = GeminiModels(http, geminiKey!!).list()
        val live = GeminiModels.pickLive(models, null)
        val tts = GeminiModels.pickTts(models, null)
        val text = GeminiModels.pickText(models, null)
        log("model sayısı ${models.size}; otomatik seçim -> live=$live tts=$tts text=$text")
        log("  Live adayları: ${models.filter { "bidiGenerateContent" in it.methods }.map { it.id }}")
        assertTrue(live != null && tts != null && text != null)
    }

    @Test
    fun b2_geminiWarningTextAllPersonas() = runBlocking {
        needGemini()
        // Bakanlığa bağlı kalmasın: sentetik radar
        val h = com.radarnav.core.model.RouteHazard(
            com.radarnav.core.model.Radar("r:test", "Eskişehir Yolu", "ANKARA", "ÇANKAYA", 82, KIZILAY), 1000.0, 1000.0,
        )
        val limit = h.hazard.speedLimit!!
        val client = GeminiTextClient(http, geminiKey!!)
        for (p in Persona.entries) {
            val e = AlertEvent.Approach(h, Stage.NEAR, 480.0, limit + 15.0)
            val t0 = System.currentTimeMillis()
            val text = WarningText.clean(client.generate(WarningText.systemInstruction(p), WarningText.userPrompt(e, emptyList())))
            val ms = System.currentTimeMillis() - t0
            log("${p.title} (${ms} ms): $text")
            assertTrue(text.isNotBlank())
            assertTrue(text.contains(limit.toString()), "${p.title}: cümlede hız sınırı ($limit) yok")
            assertTrue(ms < 6_000, "${p.title}: ${ms} ms, uyarı bütçesi (2,5 sn + pay) çok aşıldı")
        }
    }

    @Test
    fun b3_geminiTts() = runBlocking {
        needGemini()
        val models = GeminiModels(http, geminiKey!!).list()
        val model = GeminiModels.pickTts(models, "gemini-2.5-flash-preview-tts")!!
        val t0 = System.currentTimeMillis()
        val pcm = GeminiTts(http, geminiKey, model).synthesize(
            "Radar 500 metre ileride. Hız sınırı 82.", "Puck", WarningText.voiceStyle(Persona.SERT_ARGO, com.radarnav.core.alert.Severity.SASSY),
        )
        val secs = pcm.data.size / 2.0 / pcm.sampleRate
        log("TTS $model: ${pcm.data.size} bayt, ${pcm.sampleRate} Hz, ${"%.1f".format(secs)} sn ses, ${System.currentTimeMillis() - t0} ms")
        assertTrue(secs > 1.0, "ses çok kısa")
    }

    /** Uygulamanın kullandığı GeminiLiveSession ile gerçek bağlantı: metin sor, sesli cevap gelsin. */
    private fun liveRoundTrip(model: String, prompt: String, withTool: Boolean): String = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val done = CompletableDeferred<String>()
        val transcript = StringBuilder()
        var audioBytes = 0
        var tool: String? = null
        val listener = object : LiveListener {
            override fun onReady() { ready.complete(Unit) }
            override fun onAudio(pcm24k: ByteArray) { audioBytes += pcm24k.size }
            override fun onModelTranscript(text: String) { transcript.append(text) }
            override fun onToolCall(name: String) { tool = name }
            override fun onTurnComplete() { if (audioBytes > 0) done.complete(transcript.toString()) }
            override fun onError(message: String) { ready.completeExceptionally(IllegalStateException(message)); done.completeExceptionally(IllegalStateException(message)) }
            override fun onClosed(reason: String?) { done.completeExceptionally(IllegalStateException("kapandı: $reason")) }
        }
        val tools = if (withTool) listOf(ToolDeclaration("get_drive_status", "Anlık hız, hız sınırı, sonraki radar ve varış bilgisini döndürür.")) else emptyList()
        val s = GeminiLiveSession(
            http, geminiKey!!,
            LiveConfig(model = model, systemInstruction = "Sen Türkçe konuşan, kısa cevap veren bir sürüş asistanısın.", tools = tools),
            { _, _ -> buildJsonObject { put("speed_kmh", 78); put("speed_limit", 82); put("next_radar_m", 1800); put("eta", "14:52") } },
            listener, CoroutineScope(Dispatchers.IO),
        )
        val t0 = System.currentTimeMillis()
        try {
            s.connect()
            withTimeout(15_000) { ready.await() }
            s.sendText(prompt)
            val text = withTimeout(30_000) { done.await() }
            log("Live $model: ${System.currentTimeMillis() - t0} ms, ses ${audioBytes / 48_000.0} sn, araç=$tool, metin=\"$text\"")
            if (withTool) assertTrue(tool == "get_drive_status", "$model araç çağırmadı")
            text
        } finally {
            s.close()
        }
    }

    @Test
    fun b4_geminiLiveAutoPicked() {
        needGemini()
        val models = runBlocking { GeminiModels(http, geminiKey!!).list() }
        val model = GeminiModels.pickLive(models, LiveConfig.DEFAULT_LIVE_MODEL)!!
        liveRoundTrip(model, "Merhaba, kendini tek cümleyle tanıt.", withTool = false)
    }

    @Test
    fun b5_geminiLiveToolCall() {
        needGemini()
        val models = runBlocking { GeminiModels(http, geminiKey!!).list() }
        val model = GeminiModels.pickLive(models, LiveConfig.DEFAULT_LIVE_MODEL)!!
        liveRoundTrip(model, "Şu an kaç km hızla gidiyorum ve sonraki radar ne kadar uzakta?", withTool = true)
    }

    /** Anahtarın eriştiği tüm sohbet tipi Live modellerini dener (hangisi çalışıyor raporu). */
    @Test
    fun b6_geminiLiveAllCandidates() {
        needGemini()
        assumeTrue(System.getenv("RADARNAV_LIVE_ALL") == "1")
        val models = runBlocking { GeminiModels(http, geminiKey!!).list() }
        val ids = models.filter { "bidiGenerateContent" in it.methods }.map { it.id }
        val report = ids.map { id ->
            id to runCatching { liveRoundTrip(id, "Merhaba, tek cümleyle cevap ver: bugün nasılsın?", false) }
                .fold({ "OK: ${it.take(60)}" }, { "HATA: ${it.message?.take(120)}" })
        }
        report.forEach { (id, r) -> log("  $id -> $r") }
    }
}
