package com.phoenix.phoenixvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phoenix.phoenixvpn.MainActivity
import com.phoenix.phoenixvpn.R

class VpnForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "phoenix_vpn_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.phoenix.phoenixvpn.vpn.START"
        const val ACTION_STOP = "com.phoenix.phoenixvpn.vpn.STOP"
        /**
         * Internal cleanup stop. Unlike [ACTION_STOP] this carries NO
         * manual-off semantics: it never touches WireGuardManager and never
         * sets the user override. Used for failure cleanup (e.g. all
         * failover attempts exhausted) where the user did NOT ask to stop.
         */
        const val ACTION_STOP_QUIET = "com.phoenix.phoenixvpn.vpn.STOP_QUIET"

        fun start(context: Context) {
            val intent = Intent(context, VpnForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VpnForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun stopQuiet(context: Context) {
            val intent = Intent(context, VpnForegroundService::class.java).apply {
                action = ACTION_STOP_QUIET
            }
            context.startService(intent)
        }

        /** Last known tunnel state, mirrored into the foreground notification. */
        @Volatile
        private var lastConnected: Boolean = true

        /** True while the service instance exists (onCreate → onDestroy). */
        @Volatile
        private var serviceRunning: Boolean = false

        /**
         * Mirror the tunnel state into the foreground notification without
         * stopping or (re)starting the service. No-op when the service isn't
         * running, so this never posts a bare notification.
         */
        fun updateTunnelState(context: Context, connected: Boolean) {
            lastConnected = connected
            if (!serviceRunning) return
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(context, connected))
            } catch (_: Exception) {
            }
        }

        private fun buildNotification(context: Context, connected: Boolean): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(context, VpnForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Honest state: "connected" vs idle. When the tunnel drops but the
            // service persists (e.g. waiting for internet), the notification
            // must not claim to be connected.
            val stateText = if (connected) "VPN ချိတ်ဆက်နေပါသည်" else "VPN ရပ်နေပါသည်"

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("ဖီးနစ် VPN")
                .setContentText(stateText)
                .setSmallIcon(R.drawable.phoenix_vpn_icon)
                .setContentIntent(pendingIntent)
                .addAction(0, "ရပ်ရန်", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * App swiped away from recents: resurrect the service ONLY if the VPN
     * should be up (auto-run on, no manual-off). Never resurrect after a
     * manual-off — the user's wish sticks.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val manager = WireGuardManager.getInstance(this)
            if (manager.isAutoRunEnabled() && !manager.isUserOverride()) {
                start(this)
            }
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
        createNotificationChannel()
    }

    override fun onDestroy() {
        serviceRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // startForegroundService() on an already-running foreground
                // service is a no-op re-delivery: this call is idempotent and
                // never throws ForegroundServiceStartNotAllowedException.
                startForeground(NOTIFICATION_ID, buildNotification(this, lastConnected))
            }
            ACTION_STOP -> {
                // "ရပ်ရန်" behaves as MANUAL-OFF: it sets the user override
                // (so auto-run never resurrects the tunnel) and brings the
                // tunnel down for real — not just killing the service.
                try {
                    WireGuardManager.getInstance(this).handleNotificationStop()
                } catch (_: Exception) {
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_STOP_QUIET -> {
                // Internal failure cleanup only: stop the service WITHOUT
                // manual-off semantics. Never touches WireGuardManager, never
                // sets the user override.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // System restarted the service after killing the process
            // (START_STICKY delivers a null intent). Re-enter the foreground
            // immediately; tunnel state is left to WireGuardManager.
            null -> {
                startForeground(NOTIFICATION_ID, buildNotification(this, lastConnected))
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ဖီးနစ် VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN ချိတ်ဆက်နေစဉ် အသိပေးချက်"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

}
