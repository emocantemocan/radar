package com.radarnav.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.radarnav.AppGraph
import com.radarnav.R
import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.RouteHazard
import com.radarnav.nav.NavState
import com.radarnav.nav.RoutePlan
import com.radarnav.ui.UiFormat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLng as MlLatLng

/**
 * Telefon ve Android Auto haritasının ortak denetleyicisi (MapLibre).
 *
 * - Rota (kalan kısım), hız koridorları ve rota üzerindeki tüm radarlar ayrı katmanlarda.
 * - Konum oku MapLibre LocationComponent ile çizilir: konumlar yola oturtulmuş olarak verilir,
 *   ok bir sonraki konuma kayarak ilerler.
 * - Navigasyonda kamera Google Maps sürüş modu gibi: 3D eğik, gidilen yön yukarıda, araç
 *   ekranın alt kısmında, hız arttıkça biraz uzaklaşır.
 */
class NavMapController(
    private val context: Context,
    val map: MapLibreMap,
    private val style: Style,
    private val graph: AppGraph,
    /** Harita görünümünün piksel yüksekliği (kamera dolgusu için). */
    private val viewHeight: () -> Int,
) {
    /** Kamera aracı takip ediyor mu (kullanıcı haritayı kaydırınca false olur). */
    var following = true
        private set
    var onFollowChanged: ((Boolean) -> Unit)? = null

    private var shownRoute: NavRoute? = null
    private var shownHazards: List<RouteHazard>? = null
    private var passedKey = -1
    private var lastRouteTrimAt = 0L
    private var lastRouteTrimAlong = -1.0
    private var centeredOnce = false
    private var navigatingShown = false
    private var lastZoom = 0.0
    private val images = HashSet<String>()

    /** Ekranın kenarlarında harita içeriği gösterilmeyecek alanlar (sol, üst, sağ, alt). */
    private var insets = intArrayOf(0, 0, 0, 0)

    private val density = context.resources.displayMetrics.density

    init {
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isAttributionEnabled = true
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        addLayers()
        setupLocation()
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) setFollowing(false)
        }
    }

    // ---------------- Katmanlar ----------------

    private fun lineWidth(vararg stops: Pair<Float, Float>): Expression = Expression.interpolate(
        Expression.exponential(1.5f), Expression.zoom(),
        *stops.map { Expression.stop(it.first, it.second) }.toTypedArray(),
    )

    private fun addLayers() {
        style.addSource(GeoJsonSource(SRC_ROUTE, FeatureCollection.fromFeatures(emptyList())))
        style.addSource(GeoJsonSource(SRC_CORRIDORS, FeatureCollection.fromFeatures(emptyList())))
        style.addSource(GeoJsonSource(SRC_HAZARDS, FeatureCollection.fromFeatures(emptyList())))

        val below = listOf("road-label", "road-shield", "place-village").firstOrNull { style.getLayer(it) != null }
        fun add(layer: org.maplibre.android.style.layers.Layer) {
            if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        }
        add(
            LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#07101F"),
                PropertyFactory.lineWidth(lineWidth(8f to 5f, 12f to 9f, 15f to 15f, 18f to 34f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        add(
            LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#3B8BFF"),
                PropertyFactory.lineWidth(lineWidth(8f to 3f, 12f to 5.5f, 15f to 10f, 18f to 24f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        add(
            LineLayer(LYR_CORRIDOR_CASING, SRC_CORRIDORS).withProperties(
                PropertyFactory.lineColor("#07101F"),
                PropertyFactory.lineWidth(lineWidth(8f to 6f, 12f to 10f, 15f to 17f, 18f to 36f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        add(
            LineLayer(LYR_CORRIDOR, SRC_CORRIDORS).withProperties(
                PropertyFactory.lineColor("#FFB020"),
                PropertyFactory.lineWidth(lineWidth(8f to 3.5f, 12f to 6f, 15f to 11f, 18f to 26f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        // Radar levhaları ve koridor işaretleri her şeyin üstünde, ekrana dik (eğik kamerada da okunur)
        style.addLayer(
            SymbolLayer(LYR_HAZARDS, SRC_HAZARDS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(6, 0.5f), Expression.stop(12, 0.8f), Expression.stop(15, 1.0f),
                    ),
                ),
                PropertyFactory.symbolSortKey(Expression.get("order")),
            ),
        )
    }

    private fun imageFor(h: RouteHazard): String {
        val id = "hz-${if (h.isCorridor) "c" else "r"}-${h.hazard.speedLimit ?: "x"}"
        if (images.add(id)) {
            val size = (36 * density).roundToInt()
            style.addImage(id, UiFormat.hazardBitmap(context, h.hazard.speedLimit, h.isCorridor, size))
        }
        return id
    }

    private fun endImage(): String {
        if (images.add(IMG_END)) {
            val size = (30 * density).roundToInt()
            style.addImage(IMG_END, UiFormat.corridorEndBitmap(context, size))
        }
        return IMG_END
    }

    private fun point(p: LatLng) = Point.fromLngLat(p.lon, p.lat)

    // ---------------- Konum oku ----------------

    @SuppressLint("MissingPermission")
    private fun setupLocation() {
        val lc = map.locationComponent
        val options = LocationComponentOptions.builder(context)
            .foregroundTintColor(context.getColor(R.color.brand))
            .bearingTintColor(context.getColor(R.color.brand))
            .accuracyAlpha(0f)
            .trackingGesturesManagement(true)
            .compassAnimationEnabled(true)
            .build()
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false)
                .build(),
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.COMPASS
        lc.cameraMode = CameraMode.NONE
        lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() = setFollowing(false)
            override fun onCameraTrackingChanged(currentMode: Int) {}
        })
    }

    private fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        onFollowChanged?.invoke(value)
    }

    private fun pushLocation(p: LatLng, bearing: Float, speedKmh: Double) {
        val loc = Location("radarnav").apply {
            latitude = p.lat
            longitude = p.lon
            this.bearing = bearing
            speed = (speedKmh / 3.6).toFloat()
            accuracy = 5f
            time = System.currentTimeMillis()
        }
        map.locationComponent.forceLocationUpdate(loc)
    }

    // ---------------- Durum ----------------

    /** Navigasyon durumunu ve konumu haritaya yansıtır (her konum güncellemesinde çağrılır). */
    fun update(state: NavState) {
        val plan = when (state) {
            is NavState.Planned -> state.plan
            is NavState.Navigating -> state.plan
            else -> null
        }
        val navigating = state is NavState.Navigating
        val progress = (state as? NavState.Navigating)?.progress
        val along = progress?.along ?: 0.0

        if (plan == null) {
            if (shownRoute != null) clearPlan()
        } else {
            setPlan(plan, along, navigating)
        }

        val lc = map.locationComponent
        if (navigating) {
            if (!navigatingShown) {
                navigatingShown = true
                lc.renderMode = RenderMode.GPS
                setFollowing(true)
            }
            if (progress != null) {
                pushLocation(progress.location, progress.bearing, progress.speedKmh)
                if (following) track(progress.speedKmh)
            }
        } else {
            if (navigatingShown) {
                navigatingShown = false
                lc.cameraMode = CameraMode.NONE
                lc.renderMode = RenderMode.COMPASS
                map.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().tilt(0.0).bearing(0.0).build()), 600)
            }
            graph.location.fixes.value?.let { fix ->
                pushLocation(fix.location, fix.bearing ?: graph.heading.compass.value ?: 0f, fix.speedKmh)
                if (!centeredOnce && plan == null) {
                    centeredOnce = true
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(MlLatLng(fix.location.lat, fix.location.lon), 15.0))
                }
            }
        }
    }

    /** Google Maps sürüş modu kamerası: 3D eğik, yön yukarı, araç alt kısımda. */
    private fun track(speedKmh: Double) {
        val lc = map.locationComponent
        if (lc.cameraMode != CameraMode.TRACKING_GPS) lc.cameraMode = CameraMode.TRACKING_GPS
        val zoom = when {
            speedKmh >= 100 -> 15.6
            speedKmh >= 70 -> 16.1
            speedKmh >= 40 -> 16.7
            else -> 17.3
        }
        if (zoom != lastZoom) {
            lastZoom = zoom
            lc.zoomWhileTracking(zoom, 1200)
            lc.tiltWhileTracking(TILT, 1200)
            // Üst dolgu odak noktasını aşağı iter: araç ekranın ~%70'inde, önü görünür
            val h = viewHeight().toDouble()
            val top = insets[1] + (h - insets[1] - insets[3]) * 0.42
            lc.paddingWhileTracking(doubleArrayOf(insets[0].toDouble(), top, insets[2].toDouble(), insets[3].toDouble()), 600)
        }
    }

    /** Takibe geri dön (konumum düğmesi). */
    fun recenter() {
        setFollowing(true)
        lastZoom = 0.0
        if (navigatingShown) {
            val p = (graph.controller.state.value as? NavState.Navigating)?.progress
            track(p?.speedKmh ?: 0.0)
        } else {
            graph.location.current()?.let {
                map.easeCamera(CameraUpdateFactory.newLatLngZoom(MlLatLng(it.lat, it.lon), 16.0), 600)
            }
        }
    }

    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        insets = intArrayOf(left, top, right, bottom)
        lastZoom = 0.0
    }

    /** Yalnızca değiştiyse günceller (her konum güncellemesinde çağrılabilir). */
    fun setInsetsIfChanged(left: Int, top: Int, right: Int, bottom: Int) {
        if (insets[0] != left || insets[1] != top || insets[2] != right || insets[3] != bottom) {
            setInsets(left, top, right, bottom)
        }
    }

    fun zoomIn() = map.animateCamera(CameraUpdateFactory.zoomIn(), 300)
    fun zoomOut() = map.animateCamera(CameraUpdateFactory.zoomOut(), 300)

    /** Android Auto kaydırma hareketi. */
    fun pan(dx: Float, dy: Float) {
        map.locationComponent.cameraMode = CameraMode.NONE
        setFollowing(false)
        map.scrollBy(-dx, -dy, 0)
    }

    /** Android Auto yakınlaştırma hareketi. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val amount = kotlin.math.ln(factor.toDouble()) / kotlin.math.ln(2.0)
        if (navigatingShown && following) {
            lastZoom = (map.cameraPosition.zoom + amount).coerceIn(4.0, 19.0)
            map.locationComponent.zoomWhileTracking(lastZoom, 0)
        } else {
            map.moveCamera(CameraUpdateFactory.zoomBy(amount, android.graphics.Point(focusX.toInt(), focusY.toInt())))
        }
    }

    fun flyTo(p: LatLng, zoom: Double = 16.0) {
        map.locationComponent.cameraMode = CameraMode.NONE
        setFollowing(false)
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(MlLatLng(p.lat, p.lon), zoom), 700)
    }

    /** Rotanın tamamını, alttaki sayfanın üstünde kalacak şekilde gösterir. */
    fun fitRoute(route: NavRoute, bottomInset: Int, topInset: Int) {
        val pts = route.points
        if (pts.size < 2) return
        val bounds = LatLngBounds.Builder().includes(pts.map { MlLatLng(it.lat, it.lon) }).build()
        val pad = (40 * density).toInt()
        map.locationComponent.cameraMode = CameraMode.NONE
        map.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, pad, topInset + pad, pad, bottomInset + pad),
            800,
        )
    }

    // ---------------- Rota çizimi ----------------

    private fun clearPlan() {
        shownRoute = null
        shownHazards = null
        passedKey = -1
        style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        style.getSourceAs<GeoJsonSource>(SRC_CORRIDORS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        style.getSourceAs<GeoJsonSource>(SRC_HAZARDS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private fun setPlan(plan: RoutePlan, along: Double, navigating: Boolean) {
        val routeChanged = shownRoute !== plan.route
        val hazardsChanged = shownHazards !== plan.hazards
        val geometry = plan.route.geometry

        // Rota: navigasyonda yalnızca kalan kısım (en fazla 4 sn'de bir / 40 m'de bir güncellenir)
        val now = System.currentTimeMillis()
        if (routeChanged || (navigating && (now - lastRouteTrimAt > 4000) && along - lastRouteTrimAlong > 40)) {
            lastRouteTrimAt = now
            lastRouteTrimAlong = along
            val pts = if (navigating && along > 0) geometry.slice(along, geometry.length) else plan.route.points
            style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(
                Feature.fromGeometry(LineString.fromLngLats(pts.map(::point))),
            )
        }

        // Geçilen radarlar haritadan kalkar
        val passed = plan.hazards.count { (if (it.isCorridor) it.endAlong else it.startAlong) < along - 30 }
        if (!routeChanged && !hazardsChanged && passed == passedKey) return
        passedKey = passed
        shownRoute = plan.route
        shownHazards = plan.hazards

        val corridorLines = ArrayList<Feature>()
        val symbols = ArrayList<Feature>()
        plan.hazards.forEachIndexed { i, h ->
            if (h.isCorridor) {
                if (h.endAlong < along - 30) return@forEachIndexed
                val seg = geometry.slice(maxOf(h.startAlong, along), h.endAlong)
                if (seg.size >= 2) corridorLines += Feature.fromGeometry(LineString.fromLngLats(seg.map(::point)))
                symbols += Feature.fromGeometry(point(geometry.pointAt(h.endAlong))).apply {
                    addStringProperty("icon", endImage())
                    addNumberProperty("order", 1000 + i)
                }
                if (h.startAlong >= along - 30) {
                    symbols += Feature.fromGeometry(point(geometry.pointAt(h.startAlong))).apply {
                        addStringProperty("icon", imageFor(h))
                        addNumberProperty("order", 2000 + i)
                    }
                }
            } else if (h.startAlong >= along - 30) {
                symbols += Feature.fromGeometry(point(geometry.pointAt(h.startAlong))).apply {
                    addStringProperty("icon", imageFor(h))
                    addNumberProperty("order", 2000 + i)
                }
            }
        }
        style.getSourceAs<GeoJsonSource>(SRC_CORRIDORS)?.setGeoJson(FeatureCollection.fromFeatures(corridorLines))
        style.getSourceAs<GeoJsonSource>(SRC_HAZARDS)?.setGeoJson(FeatureCollection.fromFeatures(symbols))
    }

    companion object {
        const val TILT = 55.0
        private const val SRC_ROUTE = "rn-route"
        private const val SRC_CORRIDORS = "rn-corridors"
        private const val SRC_HAZARDS = "rn-hazards"
        private const val LYR_ROUTE_CASING = "rn-route-casing"
        private const val LYR_ROUTE = "rn-route"
        private const val LYR_CORRIDOR_CASING = "rn-corridor-casing"
        private const val LYR_CORRIDOR = "rn-corridor"
        private const val LYR_HAZARDS = "rn-hazards"
        private const val IMG_END = "hz-end"
    }
}
