package com.radarnav.car

import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.radarnav.RadarNavApp
import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.HazardSource
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.Place
import com.radarnav.core.model.Radar
import com.radarnav.core.model.RouteHazard
import com.radarnav.core.model.RouteStep
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.graph
import com.radarnav.nav.NavProgress
import com.radarnav.nav.NavState
import com.radarnav.nav.RoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android Auto şablonları kurallara uymazsa (ör. eylem şeridinde 4'ten fazla düğme, zorunlu alan
 * eksik) Car App Library oluşturucuları hata fırlatır ve uygulama arabada çöker. Bu testler her
 * ekranı her navigasyon durumunda kurup bunu araba olmadan yakalar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RadarNavApp::class)
class CarScreensTest {

    private lateinit var car: TestCarContext

    private val dest = Place("Eskişehir", "Odunpazarı, Eskişehir", LatLng(39.7767, 30.5206))
    private val points = (0..100).map { LatLng(39.92 - it * 0.001, 32.85 - it * 0.02) }
    private val step = RouteStep("Sağa dönün, Atatürk Bulvarı", ManeuverKind.RIGHT, points[3], "Atatürk Bulvarı", distanceMeters = 300.0)
    private val hazards = listOf(
        RouteHazard(Radar("r:1", "Polatlı girişi", "ANKARA", "POLATLI", 82, points[20]), 18_000.0, 18_000.0),
        RouteHazard(Radar("osm:2", "", "", "", null, points[40], source = HazardSource.OSM), 36_000.0, 36_000.0),
        RouteHazard(SpeedCorridor("c:3", "Sivrihisar", "ESKİŞEHİR", "SİVRİHİSAR", 110, 12.0, points[60], points[70], points.subList(60, 71)), 54_000.0, 66_000.0),
    )
    private val plan = RoutePlan(
        LatLng(39.92, 32.85), dest,
        NavRoute(points, 233_000.0, 9_700.0, listOf(step), "OSRM"),
        hazards, note = "Bakanlık bugün güzergahta 2 radar uygulaması bildiriyor ama konumlarını şu an paylaşmıyor.",
        ministryRadarCount = 2, radarsWithheld = true,
    )
    private fun progress(nextStep: RouteStep? = step) = NavProgress(
        location = points[2], bearing = 270f, speedKmh = 97.0, speedFromCar = true, along = 1_500.0,
        remainingMeters = 231_500.0, remainingSeconds = 9_600.0, nextStep = nextStep, distanceToStep = 300.0,
        currentLimit = 82, nextHazard = hazards[0], distanceToHazard = 16_500.0, corridor = null, rerouting = false,
    )

    private val states: List<NavState> get() = listOf(
        NavState.Idle,
        NavState.Planning("Eskişehir"),
        NavState.Planned(plan),
        NavState.Navigating(plan, null),
        NavState.Navigating(plan, progress()),
        NavState.Navigating(plan, progress(nextStep = null)),
        NavState.Failed("Rota servisine ulaşılamadı"),
    )

    @Before
    fun setUp() {
        car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        // Araba bağlantısındaki el sıkışmayı taklit et (Car API 6: Alert, araba mikrofonu dahil)
        car.updateHandshakeInfo(androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 6))
    }

    private fun renderer(): CarMapRenderer {
        // Başlatılmamış yaşam döngüsü: yüzey (Surface) açılmaz, yalnızca şablonlar test edilir
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        return CarMapRenderer(car, car.graph, owner.lifecycle)
    }

    @Test
    fun `ana ekran her durumda gecerli sablon uretir`() {
        val screen = CarNavScreen(car, renderer())
        for (s in states) {
            car.graph.controller.setStateForTest(s)
            val t = screen.onGetTemplate()
            assertTrue("$s için NavigationTemplate", t is NavigationTemplate)
            val nav = t as NavigationTemplate
            assertTrue("eylem şeridi en fazla 4 düğme ($s)", nav.actionStrip!!.actions.size <= 4)
            assertTrue("harita şeridi en fazla 4 düğme", nav.mapActionStrip!!.actions.size <= 4)
        }
    }

    @Test
    fun `surus sirasinda manevra ve kalan sure gosterilir`() {
        car.graph.controller.setStateForTest(NavState.Navigating(plan, progress()))
        val t = CarNavScreen(car, renderer()).onGetTemplate() as NavigationTemplate
        val info = t.navigationInfo as RoutingInfo
        assertEquals("Sağa dönün, Atatürk Bulvarı", info.currentStep!!.cue.toString())
        assertNotNull(t.destinationTravelEstimate)
        val titles = t.actionStrip!!.actions.mapNotNull { it.title?.toString() }
        assertTrue(titles.containsAll(listOf("Radarlar", "Bitir", "Radar var")))
    }

    @Test
    fun `rota onizleme ve hata ekrani`() {
        car.graph.controller.setStateForTest(NavState.Planned(plan))
        val preview = CarRoutePreviewScreen(car, dest, planned = true).onGetTemplate()
        assertTrue(preview is RoutePreviewNavigationTemplate)
        val rows = (preview as RoutePreviewNavigationTemplate).itemList!!.items
        assertEquals(1, rows.size)

        car.graph.controller.setStateForTest(NavState.Failed("Rota servisine ulaşılamadı"))
        assertTrue(CarRoutePreviewScreen(car, dest, planned = true).onGetTemplate() is MessageTemplate)

        car.graph.controller.setStateForTest(NavState.Planning("Eskişehir"))
        assertTrue((CarRoutePreviewScreen(car, dest, planned = true).onGetTemplate() as RoutePreviewNavigationTemplate).isLoading)
    }

    @Test
    fun `radar listesi kaynaklari ve koridoru gosterir`() {
        car.graph.controller.setStateForTest(NavState.Navigating(plan, progress()))
        val t = CarHazardListScreen(car).onGetTemplate() as ListTemplate
        val rows = t.singleList!!.items
        assertEquals(3, rows.size)
        val texts = rows.joinToString("\n") { r ->
            val row = r as androidx.car.app.model.Row
            row.title.toString() + " | " + row.texts.joinToString(" | ")
        }
        assertTrue(texts, texts.contains("Sabit kamera"))
        assertTrue(texts, texts.contains("OpenStreetMap"))
        assertTrue(texts, texts.contains("Hız koridoru"))

        car.graph.controller.setStateForTest(NavState.Idle)
        assertEquals(0, (CarHazardListScreen(car).onGetTemplate() as ListTemplate).singleList!!.items.size)
    }

    @Test
    fun `arama ekrani bos halde gecerli`() {
        assertTrue(CarSearchScreen(car).onGetTemplate() is SearchTemplate)
    }
}
