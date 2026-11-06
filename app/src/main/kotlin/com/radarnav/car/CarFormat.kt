package com.radarnav.car

import android.graphics.Bitmap
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import com.radarnav.core.model.ManeuverKind
import com.radarnav.core.model.RouteStep
import com.radarnav.nav.NavProgress
import com.radarnav.nav.RoutePlan
import com.radarnav.ui.UiFormat
import java.util.TimeZone
import kotlin.math.roundToInt

/** Uygulama modellerini Car App Library modellerine çevirir. */
object CarFormat {

    fun icon(ctx: CarContext, res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

    fun icon(bitmap: Bitmap): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()

    fun distance(meters: Double): Distance = when {
        meters < 1000 -> Distance.create(((meters / 10).roundToInt() * 10).toDouble(), Distance.UNIT_METERS)
        meters < 10_000 -> Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create((meters / 1000.0).roundToInt().toDouble(), Distance.UNIT_KILOMETERS)
    }

    private fun maneuverType(kind: ManeuverKind, exit: Int?): Int = when (kind) {
        ManeuverKind.DEPART -> Maneuver.TYPE_DEPART
        ManeuverKind.STRAIGHT -> Maneuver.TYPE_STRAIGHT
        ManeuverKind.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        ManeuverKind.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
        ManeuverKind.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
        ManeuverKind.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        ManeuverKind.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        ManeuverKind.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
        ManeuverKind.UTURN -> Maneuver.TYPE_U_TURN_LEFT
        ManeuverKind.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
        ManeuverKind.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
        ManeuverKind.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        ManeuverKind.ON_RAMP -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
        ManeuverKind.OFF_RAMP -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        ManeuverKind.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
        ManeuverKind.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
        // Türkiye'de sağdan trafik: dönel kavşaklar saat yönünün tersine döner
        ManeuverKind.ROUNDABOUT ->
            if (exit != null && exit > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        ManeuverKind.ARRIVE -> Maneuver.TYPE_DESTINATION
    }

    fun step(ctx: CarContext, s: RouteStep): Step {
        val type = maneuverType(s.kind, s.roundaboutExit)
        val m = Maneuver.Builder(type).setIcon(icon(ctx, UiFormat.maneuverIcon(s.kind)))
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) m.setRoundaboutExitNumber(s.roundaboutExit ?: 1)
        val b = Step.Builder(s.instruction).setManeuver(m.build())
        s.roadName?.takeIf { it.isNotBlank() }?.let { b.setRoad(it) }
        return b.build()
    }

    fun travelEstimate(remainingMeters: Double, remainingSeconds: Double): TravelEstimate {
        val arrival = System.currentTimeMillis() + (remainingSeconds * 1000).toLong()
        return TravelEstimate.Builder(distance(remainingMeters), DateTimeWithZone.create(arrival, TimeZone.getDefault()))
            .setRemainingTimeSeconds(remainingSeconds.toLong().coerceAtLeast(0))
            .build()
    }

    /** Gösterge paneli / HUD gibi araç ekranları için yolculuk bilgisi. */
    fun trip(ctx: CarContext, plan: RoutePlan, p: NavProgress): Trip {
        val b = Trip.Builder()
        val destEstimate = travelEstimate(p.remainingMeters, p.remainingSeconds)
        p.nextStep?.let { s ->
            val stepSeconds = if (p.remainingMeters > 0) p.remainingSeconds * p.distanceToStep / p.remainingMeters else 0.0
            b.addStep(step(ctx, s), travelEstimate(p.distanceToStep, stepSeconds))
            s.roadName?.let { b.setCurrentRoad(it) }
        }
        b.addDestination(
            Destination.Builder().setName(plan.destination.name).setAddress(plan.destination.address).build(),
            destEstimate,
        )
        return b.build()
    }
}
