package com.radarnav.core

import com.radarnav.core.gemini.FunctionCall
import com.radarnav.core.gemini.GeminiTextClient
import com.radarnav.core.gemini.LiveConfig
import com.radarnav.core.gemini.LiveProtocol
import com.radarnav.core.gemini.LiveServerEvent
import com.radarnav.core.gemini.ToolDeclaration
import com.radarnav.core.geo.LatLng
import com.radarnav.core.geo.Polyline
import com.radarnav.core.ministry.MinistryApi
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.net.LenientJson
import com.radarnav.core.place.PlaceSearch
import com.radarnav.core.routing.GoogleRouting
import com.radarnav.core.routing.GraphHopperRouting
import com.radarnav.core.routing.OsrmRouting
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

fun resource(name: String): String =
    requireNotNull(ParsingTest::class.java.getResource("/$name")) { "kaynak yok: $name" }.readText()

class ParsingTest {

    @Test
    fun `bakanlik rota yaniti cozulur, pasif kayitlar isaretlenir`() {
        val json = LenientJson.parseToJsonElement(resource("ministry_route.json"))
        val all = MinistryApi.parseRoute(json)
        assertEquals(3, all.radars.size) // pasif kayıt da gelir, işaretli
        assertEquals(listOf(true, false, true), all.radars.map { it.active })
        assertEquals(2, all.activeRadarCount)
        val data = MinistryApi.parseRoute(json, activeOnly = true)
        assertEquals(2, data.radars.size) // activity=1 olan atıldı
        val r = data.radars.first()
        assertEquals("r:1234", r.id)
        assertEquals(82, r.speedLimit)
        assertEquals(39.885241, r.location.lat, 1e-9)
        assertEquals(32.855283, r.location.lon, 1e-9)
        assertEquals(null, data.radars[1].speedLimit) // 0 -> null
        val c = data.corridors.single()
        assertEquals(110, c.speedLimit)
        assertEquals(36.0, c.lengthKm!!, 1e-9)
        assertEquals(LatLng(38.714185, 30.608753), c.start)
        assertEquals(3, data.coordinates.size)
    }

    @Test
    fun `osrm yaniti turkce yonlendirmeye cevrilir`() {
        val route = OsrmRouting.parse(LenientJson.parseToJsonElement(resource("osrm_route.json")))
        assertEquals(3, route.points.size)
        assertEquals(1520.3, route.distanceMeters, 1e-9)
        assertEquals(listOf(ManeuverKind.DEPART, ManeuverKind.RIGHT, ManeuverKind.ROUNDABOUT, ManeuverKind.ARRIVE),
            route.steps.map { it.kind })
        assertEquals("Sağa dönün, Atatürk Bulvarı", route.steps[1].instruction)
        assertEquals("Dönel kavşaktan 2. çıkıştan çıkın, Kızılay Meydanı", route.steps[2].instruction)
    }

    @Test
    fun `graphhopper yaniti cozulur`() {
        val pts = listOf(LatLng(39.9, 32.8), LatLng(39.91, 32.81), LatLng(39.92, 32.83))
        val json = """
            {"paths":[{"distance":2500.5,"time":180000,"points_encoded":true,
              "points":"${Polyline.encode(pts, 5)}",
              "instructions":[
                {"text":"Devam edin: Eskişehir Yolu","distance":1000,"time":60000,"interval":[0,1],"sign":0,"street_name":"Eskişehir Yolu"},
                {"text":"Sağa dönün: Mevlana Bulvarı","distance":1500,"time":120000,"interval":[1,2],"sign":2,"street_name":"Mevlana Bulvarı"},
                {"text":"Varış noktası","distance":0,"time":0,"interval":[2,2],"sign":4}
              ]}]}
        """.trimIndent()
        val route = GraphHopperRouting.parse(LenientJson.parseToJsonElement(json))
        assertEquals(180.0, route.durationSeconds, 1e-9)
        assertEquals(listOf(ManeuverKind.DEPART, ManeuverKind.RIGHT, ManeuverKind.ARRIVE), route.steps.map { it.kind })
        assertEquals(pts[1].lat, route.steps[1].location.lat, 1e-5)
        assertEquals("Sağa dönün: Mevlana Bulvarı", route.steps[1].instruction)
    }

    @Test
    fun `google routes yaniti cozulur`() {
        val route = GoogleRouting.parse(LenientJson.parseToJsonElement(resource("google_route.json")))
        assertEquals(listOf(ManeuverKind.DEPART, ManeuverKind.LEFT, ManeuverKind.ARRIVE), route.steps.map { it.kind })
        assertEquals("Gazi Mustafa Kemal Blv. yoluna doğru sola dönün. Varış noktası sağda", route.steps[1].instruction)
        assertEquals(3, route.points.size) // ortak nokta tekrar edilmez
        assertEquals(900.0, route.distanceMeters, 1e-9)
        assertEquals(120.0, route.durationSeconds, 1e-9)
    }

    @Test
    fun `google hatasi kisa ve yol gosteren nota cevrilir`() {
        val raw = """HTTP 403: {"error":{"code":403,"message":"Routes API has not been used in project 123 before or it is disabled. Enable it by visiting https://console.developers.google.com/...","status":"PERMISSION_DENIED"}}"""
        val msg = GoogleRouting.errorText(403, raw)
        assertTrue(msg, msg.contains("Routes API") && msg.length < 100)
        val e = runCatching { GoogleRouting.parse(LenientJson.parseToJsonElement(raw.substringAfter(": "))) }.exceptionOrNull()
        assertEquals(msg, e?.message)
    }

    @Test
    fun `nominatim sonuclari`() {
        val json = """[{"lat":"40.7392","lon":"31.6089","name":"Bolu","display_name":"Bolu, Bolu Merkez, Bolu, Türkiye"},
            {"lat":"bozuk","lon":"1"}]"""
        val places = PlaceSearch.parse(LenientJson.parseToJsonElement(json))
        assertEquals(1, places.size)
        assertEquals("Bolu", places[0].name)
    }

    @Test
    fun `gemini metin yaniti dusunce parcalari atlanarak alinir`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"düşünüyorum","thought":true},{"text":" Yavaşla koçum, 2 km sonra radar var, sınır 82! "}]}}]}"""
        assertEquals("Yavaşla koçum, 2 km sonra radar var, sınır 82!",
            GeminiTextClient.extractText(LenientJson.parseToJsonElement(json)))
        val body = GeminiTextClient.buildBody("sys", "prompt", 1.0, withThinking = true)
        assertEquals(0, body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `live kurulum mesaji araclari ve sesi icerir`() {
        val setup = LenientJson.parseToJsonElement(
            LiveProtocol.setupMessage(
                LiveConfig(
                    model = "gemini-live-test",
                    voiceName = "Charon",
                    systemInstruction = "Türkçe konuş",
                    tools = listOf(
                        ToolDeclaration("navigate_to", "Rota oluştur", mapOf("destination" to ("STRING" to "Hedef")), listOf("destination")),
                        ToolDeclaration("get_drive_status", "Durum"),
                    ),
                ),
            ),
        ).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-live-test", setup["model"]!!.jsonPrimitive.content)
        val decls = setup["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(2, decls.size)
        assertEquals("destination", decls[0].jsonObject["parameters"]!!.jsonObject["required"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(decls[1].jsonObject["parameters"] == null)
        assertEquals("Charon", setup["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject["voiceConfig"]!!
            .jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `live sunucu mesajlari olaylara cevrilir`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val events = LiveProtocol.parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},
               "outputTranscription":{"text":"Merhaba"},"turnComplete":true}}""",
        )
        assertEquals(3, events.size)
        assertTrue(events[0] is LiveServerEvent.OutputTranscript)
        assertTrue((events[1] as LiveServerEvent.Audio).pcm.contentEquals(pcm))
        assertEquals(LiveServerEvent.TurnComplete, events[2])

        val tool = LiveProtocol.parse("""{"toolCall":{"functionCalls":[{"id":"abc","name":"navigate_to","args":{"destination":"Bolu"}}]}}""")
        val call = (tool.single() as LiveServerEvent.ToolCall).calls.single()
        assertEquals("Bolu", call.args["destination"]!!.jsonPrimitive.content)

        val resp = LenientJson.parseToJsonElement(
            LiveProtocol.toolResponse(listOf(FunctionCall("abc", "navigate_to", JsonObject(emptyMap())) to JsonObject(mapOf("ok" to JsonPrimitive(true))))),
        ).jsonObject["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray[0].jsonObject
        assertEquals("abc", resp["id"]!!.jsonPrimitive.content)
        assertEquals(LiveServerEvent.SetupComplete, LiveProtocol.parse("""{"setupComplete":{}}""").single())
    }
}
