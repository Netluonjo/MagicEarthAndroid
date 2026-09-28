package com.magicearth.navigation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.magicearth.navigation.MainActivity
import com.magicearth.navigation.R
import com.magicearth.navigation.model.Maneuver
import com.magicearth.navigation.model.TrafficCamera
import com.magicearth.navigation.navigation.NavigationStateBridge

/**
 * Smartwatch Turn-by-Turn Companion Sync Service.
 * Transmits real-time navigation telemetry, turn maneuvers, distance countdown,
 * and haptic vibrations to paired smartwatches (Wear OS, Samsung Galaxy Watch, Garmin, etc.)
 * via Android's high-priority WearableExtender navigation channel.
 */
class SmartwatchNavigationService : Service(), NavigationStateBridge.NavigationStateListener {

    companion object {
        const val CHANNEL_ID = "magic_earth_watch_nav_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "ACTION_START_WATCH_SYNC"
        const val ACTION_STOP = "ACTION_STOP_WATCH_SYNC"
        const val ACTION_STOP_NAVIGATION = "ACTION_STOP_NAV_FROM_WATCH"

        fun start(context: Context) {
            val intent = Intent(context, SmartwatchNavigationService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SmartwatchNavigationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var notificationManager: NotificationManager
    private var vibrator: Vibrator? = null
    private var lastVibratedThreshold = -1

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildWatchNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildWatchNotification())
            }
        } catch (e: Exception) {
            android.util.Log.e("SmartwatchNavService", "startForeground in onCreate notice: ${e.message}")
        }
        NavigationStateBridge.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            buildWatchNotification(),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, buildWatchNotification())
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SmartwatchNavService", "startForeground in onStartCommand notice: ${e.message}")
                }
            }
            ACTION_STOP -> {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {}
                stopSelf()
            }
            ACTION_STOP_NAVIGATION -> {
                NavigationStateBridge.onRequestStopNav?.invoke()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {}
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        NavigationStateBridge.removeListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chỉ dẫn dẫn đường trên Đồng hồ & Màn hình khóa",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Đồng bộ chỉ dẫn từng bước, khoảng cách và tốc độ lên mặt đồng hồ thông minh."
                setSound(null, null)
                enableVibration(false) // Managed programmatically via custom haptic pattern
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildWatchNotification(): android.app.Notification {
        val maneuver = NavigationStateBridge.currentManeuver
        val dest = NavigationStateBridge.destinationName
        val speed = NavigationStateBridge.currentSpeed
        val limit = NavigationStateBridge.currentSpeedLimit
        val remKm = NavigationStateBridge.remainingKm
        val eta = NavigationStateBridge.etaString
        val camera = NavigationStateBridge.activeCamera

        val titleText = if (maneuver != null) {
            "${maneuver.icon} ${maneuver.distanceText} • ${maneuver.instruction}"
        } else {
            "🧭 Đang dẫn đường tới $dest"
        }

        val telemetryText = if (camera != null) {
            "⚠️ Camera ${camera.type.titleVi} (${camera.speedLimit} km/h) • Đến lúc $eta"
        } else if (NavigationStateBridge.isOverspeed) {
            "⚠️ QUÁ TỐC ĐỘ: $speed / $limit km/h • Còn ${String.format("%.1f", remKm)} km ($eta)"
        } else {
            "⚡ $speed km/h (Tối đa $limit) • Còn ${String.format("%.1f", remKm)} km • ETA $eta"
        }

        // Tap notification to bring up MainActivity
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action directly on Smartwatch screen to cancel navigation
        val stopNavIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SmartwatchNavigationService::class.java).apply {
                action = ACTION_STOP_NAVIGATION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Dừng dẫn đường",
            stopNavIntent
        ).build()

        // Smartwatch (Wearable) Extender
        val wearableExtender = NotificationCompat.WearableExtender()
            .addAction(stopAction)
            .setContentIntentAvailableOffline(true)
            .setHintShowBackgroundOnly(false)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_magic_logo)
            .setContentTitle(titleText)
            .setContentText(telemetryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$titleText\n$telemetryText"))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .extend(wearableExtender)
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildWatchNotification())
    }

    /**
     * Gentle haptic pulse on smartwatch when approaching turn (< 50m)
     */
    private fun triggerTurnVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Pulse-pause-pulse pattern (two crisp haptic taps)
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 200), -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(250)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ========================================================
    // NavigationStateListener Callbacks
    // ========================================================
    override fun onNavStateChanged(isNavigating: Boolean, destinationName: String) {
        if (isNavigating) {
            updateNotification()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onManeuverChanged(maneuver: Maneuver) {
        updateNotification()
    }

    override fun onTelemetryChanged(speedKmh: Int, speedLimit: Int, isOverspeed: Boolean) {
        updateNotification()
    }

    override fun onRemainingChanged(distanceRemKm: Double, etaString: String, distanceMeters: Int) {
        updateNotification()

        // Trigger gentle wrist vibration at 50m threshold before turn
        if (distanceMeters in 30..60 && lastVibratedThreshold != 50) {
            lastVibratedThreshold = 50
            triggerTurnVibration()
        } else if (distanceMeters > 100) {
            lastVibratedThreshold = -1
        }
    }

    override fun onCameraAlertChanged(camera: TrafficCamera?, distanceMeters: Int) {
        updateNotification()
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        // Mute updates do not alter visual text
    }
}
