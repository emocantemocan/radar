package com.radarnav.core.nav

import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.NavRoute
import com.radarnav.core.model.RouteStep
import com.radarnav.core.routing.TurkishInstructions
import kotlin.math.max

data class NavFix(
    val location: LatLng,
    val speedKmh: Double,
    val accuracyMeters: Float = 10f,
    val timeMs: Long,
    val bearing: Float? = null,
    /** Hız arabanın kendi göstergesinden mi (Android Auto) yoksa GPS'ten mi geldi. */
    val speedFromCar: Boolean = false,
)

data class TrackState(
    val along: Double,
    val crossTrack: Double,
    val snapped: LatLng,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    /** Sıradaki (düz gitme dışındaki) manevra. */
    val nextStep: RouteStep?,
    val distanceToStep: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
)

sealed interface NavEvent {
    /** Sesli okunacak yönlendirme. */
    data class Instruction(val text: String, val step: RouteStep) : NavEvent
    data object OffRoute : NavEvent
    data object Arrived : NavEvent
}

/**
 * GPS konumlarını rotaya oturtur, sıradaki manevrayı bulur ve sesli yönlendirme
 * zamanlarını belirler.
 */
class NavigationTracker(val route: NavRoute) {

    private val geometry = route.geometry
    private val stepAlong: DoubleArray
    private val announcedPrepare = HashSet<Int>()
    private val announcedNow = HashSet<Int>()
    private var hint = 0
    private var offCount = 0
    private var offRouteReported = false
    private var arrivedReported = false
    private var lastAlong = 0.0

    init {
        stepAlong = DoubleArray(route.steps.size)
        var h = 0
        var prev = 0.0
        route.steps.forEachIndexed { i, s ->
            val pr = geometry.project(s.location, h, window = 5000)
            h = pr.segmentIndex
            val a = if (s.kind == ManeuverKind.ARRIVE) geometry.length else max(prev, pr.along)
            stepAlong[i] = a
            prev = a
        }
    }

    fun stepAlongOf(index: Int) = stepAlong[index]

    fun update(fix: NavFix): Pair<TrackState, List<NavEvent>> {
        val pr = geometry.project(fix.location, hint)
        val events = ArrayList<NavEvent>(2)
        val accurate = fix.accuracyMeters <= 40f

        // Rotadan çıkma tespiti
        val off = accurate && pr.crossTrack > 50.0 || pr.crossTrack > 150.0
        offCount = if (off) offCount + 1 else 0
        val offRoute = offCount >= 3 || (pr.crossTrack > 150.0 && offCount >= 2)
        if (offRoute && !offRouteReported) {
            offRouteReported = true
            events.add(NavEvent.OffRoute)
        } else if (!offRoute) {
            offRouteReported = false
        }

        if (!off) {
            hint = pr.segmentIndex
            // Küçük GPS geri sıçramalarını yoksay
            lastAlong = if (pr.along + 30 < lastAlong) pr.along else max(lastAlong, pr.along)
        }
        val along = lastAlong

        val remaining = (geometry.length - along).coerceAtLeast(0.0)
        val remainingSec = if (route.distanceMeters > 0) route.durationSeconds * remaining / geometry.length else 0.0

        val nextIndex = nextManeuverIndex(along)
        val nextStep = nextIndex?.let { route.steps[it] }
        val distToStep = nextIndex?.let { (stepAlong[it] - along).coerceAtLeast(0.0) } ?: remaining

        if (!offRoute && nextIndex != null) {
            announce(nextIndex, distToStep, fix.speedKmh)?.let { events.add(it) }
        }

        val arrived = remaining < 30.0
        if (arrived && !arrivedReported) {
            arrivedReported = true
            events.add(NavEvent.Arrived)
        }

        return TrackState(
            along = along,
            crossTrack = pr.crossTrack,
            snapped = if (off) fix.location else pr.point,
            remainingMeters = remaining,
            remainingSeconds = remainingSec,
            nextStep = nextStep,
            distanceToStep = distToStep,
            offRoute = offRoute,
            arrived = arrived,
        ) to events
    }

    private fun nextManeuverIndex(along: Double): Int? {
        for (i in route.steps.indices) {
            val s = route.steps[i]
            if (s.kind == ManeuverKind.DEPART || s.kind == ManeuverKind.STRAIGHT) continue
            if (stepAlong[i] > along + 3) return i
        }
        return null
    }

    private fun announce(index: Int, distance: Double, speedKmh: Double): NavEvent? {
        val step = route.steps[index]
        val nowThreshold = max(35.0, speedKmh / 3.6 * 4.0)
        val prepareThreshold = when {
            speedKmh >= 80 -> 1000.0
            speedKmh >= 50 -> 500.0
            else -> 250.0
        }
        if (step.kind == ManeuverKind.ARRIVE) {
            // Varış: hazırlık uyarısı yeterli, ulaşınca Arrived olayı okunur
            if (distance <= prepareThreshold && distance > 60 && announcedPrepare.add(index)) {
                return NavEvent.Instruction(
                    "${TurkishInstructions.spokenDistance(distance)} sonra varış noktanıza ulaşacaksınız", step,
                )
            }
            return null
        }
        if (distance <= nowThreshold && announcedNow.add(index)) {
            announcedPrepare.add(index)
            return NavEvent.Instruction(step.instruction, step)
        }
        if (distance <= prepareThreshold && distance > nowThreshold + 60 && announcedPrepare.add(index)) {
            return NavEvent.Instruction(
                "${TurkishInstructions.spokenDistance(distance)} sonra ${TurkishInstructions.lowerFirst(step.instruction)}",
                step,
            )
        }
        return null
    }
}
