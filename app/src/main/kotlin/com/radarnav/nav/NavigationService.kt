package com.radarnav.nav

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.radarnav.R
import com.radarnav.RadarNavApp
import com.radarnav.core.routing.TurkishInstructions
import com.radarnav.graph
import com.radarnav.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Navigasyon sürerken uygulamanın arka planda (ekran kapalıyken, Android Auto'dayken)
 * konum almaya devam etmesini sağlayan ön plan servisi.
 */
class NavigationService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            graph.controller.stopNavigation()
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Navigasyon başladı"), type)

        lifecycleScope.launch {
            var last = ""
            graph.controller.state.collect { s ->
                if (s !is NavState.Navigating) return@collect
                val p = s.progress ?: return@collect
                val text = buildString {
                    p.nextStep?.let { append(TurkishInstructions.shortDistance(p.distanceToStep)).append(" · ").append(it.instruction) }
                    p.nextHazard?.let { h ->
                        val d = p.distanceToHazard ?: return@let
                        if (isNotEmpty()) append("\n")
                        append(if (h.isCorridor) "Koridor " else "Radar ")
                        append(TurkishInstructions.shortDistance(d.coerceAtLeast(0.0)))
                        h.hazard.speedLimit?.let { append(" · sınır $it") }
                    }
                }
                if (text != last) {
                    last = text
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
                }
            }
        }
        return START_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RadarNavApp.NAV_CHANNEL)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.stop_navigation), stop)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.radarnav.STOP"
    }
}
